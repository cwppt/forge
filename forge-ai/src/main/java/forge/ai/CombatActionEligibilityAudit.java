package forge.ai;

import com.google.gson.Gson;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.ai.simulation.GameCopier;
import forge.util.MyRandom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/** Opt-in local diagnostics, separate from decision metrics and model-visible state. */
final class CombatActionEligibilityAudit {
    static final String PROPERTY = "forge.ai.combatAction.eligibilityAudit";
    private static final Gson JSON = new Gson();
    private CombatActionEligibilityAudit() { }

    /** Support evidence stays in the opt-in internal audit, never the provider DTO. */
    static void recordSupport(Player player, List<SpellAbility> candidates) {
        String file = System.getProperty(PROPERTY);
        if (file == null || file.isBlank()) return;
        try {
            List<Map<String, Object>> abilities = new ArrayList<>();
            for (SpellAbility sa : candidates) {
                if (sa.getHostCard() == null || !ComputerUtilCard.isCardRemAIDeck(sa.getHostCard())) continue;
                abilities.add(Map.of("sourceName", sa.getHostCard().getName(), "hostWarning", true,
                        "api", sa.getApi() == null ? "none" : sa.getApi().name(), "spell", sa.isSpell(),
                        "support", ExternalAiAbilitySupport.status(sa, forge.ai.decision.AiDecisionType.COMBAT_ACTION).name(),
                        "description", sa.getDescription()));
            }
            if (abilities.isEmpty()) return;
            var event = Map.of("kind", "ABILITY_SUPPORT", "surface", "COMBAT_ACTION",
                    "window", player.getGame().getPhaseHandler().getPhase().name(),
                    "gameId", player.getGame().getId(), "abilities", abilities);
            synchronized (CombatActionEligibilityAudit.class) {
                Files.writeString(Path.of(file), JSON.toJson(event) + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception ex) { org.tinylog.Logger.debug(ex, "Ability support audit unavailable"); }
    }

    static void record(Player player, List<SpellAbility> candidates, String outcome,
            List<PreparedAiAction> actions, ExternalCombatActions.Prepared exposed) {
        String file = System.getProperty(PROPERTY);
        if (file == null || file.isBlank() || !player.getGame().getPhaseHandler().inCombat()) return;
        // Diagnostics may never change the native decision or consume the live random stream.
        try {
            Map<String, Object> event = MyRandom.withRandom(new Random(131L), () ->
                    inspect(player, candidates, outcome, actions, exposed));
            synchronized (CombatActionEligibilityAudit.class) {
                Files.writeString(Path.of(file), JSON.toJson(event) + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception ex) {
            org.tinylog.Logger.debug(ex, "Combat action eligibility audit unavailable");
        }
    }

    static Map<String, Object> inspect(Player player, List<SpellAbility> candidates, String outcome,
            List<PreparedAiAction> actions, ExternalCombatActions.Prepared exposed) {
        var game = player.getGame();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("gameId", game.getId()); event.put("turn", game.getPhaseHandler().getTurn());
        event.put("window", game.getPhaseHandler().getPhase().name());
        event.put("playerSeat", game.getPlayers().indexOf(player)); event.put("outcome", outcome);
        event.put("stackEmpty", game.getStack().isEmpty()); event.put("candidateCount", candidates.size());
        event.put("probeLimit", 24);
        List<SpellAbility> tactical = candidates.stream().filter(sa -> !sa.isManaAbility()
                && !sa.isTrigger() && !sa.isReplacementAbility() && !sa.isLandAbility()).toList();
        event.put("mechanicalExcluded", candidates.size() - tactical.size());
        event.put("unprobedCandidates", Math.max(0, tactical.size() - 24));
        if (game.getCombat() != null) {
            var combatants = ExternalCombatActions.combatants(game.getCombat());
            event.put("noncombatReplacementBlockers", game.getCardsIn(forge.game.zone.ZoneType.Battlefield).stream()
                    .filter(c -> !combatants.contains(c) && c.getReplacementEffects().stream()
                        .anyMatch(r -> !ExternalCombatActions.irrelevantReplacement(c, r)))
                    .map(Card::getName).sorted().toList());
        }
        var copier = new GameCopier(game, true);
        var copiedGame = copier.makeCopy();
        var copiedPlayer = copier.find(player);
        var copiedCombat = copiedGame.getCombat();
        var copiedAi = ((PlayerControllerAi) copiedPlayer.getController()).getAi();
        List<Map<String, Object>> probes = new ArrayList<>();
        for (SpellAbility original : tactical.stream().limit(24).toList()) {
            Map<String, Object> probe = new LinkedHashMap<>();
            probe.put("source", original.getHostCard().getName());
            probe.put("sourceRef", ExternalCombatActions.ref(original.getHostCard()));
            probe.put("api", Objects.toString(original.getApi()));
            probe.put("supportedTemplate", ExternalCombatActions.safeTemplate(original));
            try {
                SpellAbility sa = copier.findSpellAbility(original);
                if (sa == null) { probe.put("engineStatus", "UNMAPPABLE"); probes.add(probe); continue; }
                sa = sa.copy(copiedPlayer); sa.setActivatingPlayer(copiedPlayer);
                boolean playable = sa.checkRestrictions(sa.getHostCard(), copiedPlayer) && sa.canPlay();
                boolean payable = playable && ComputerUtilCost.canPayCost(sa, copiedPlayer, sa.isTrigger());
                List<GameEntity> targets = sa.usesTargeting() ? sa.getTargetRestrictions().getAllCandidates(sa) : List.of();
                int minimum = sa.usesTargeting() ? sa.getTargetRestrictions().getMinTargets(sa.getHostCard(), sa) : 0;
                probe.put("engineStatus", !playable ? "TIMING_OR_RESTRICTIONS" : !payable ? "COST_UNPAYABLE"
                        : targets.size() < minimum ? "NO_LEGAL_TARGET" : "TIMING_COST_AND_TARGETS_AVAILABLE");
                probe.put("legalTargetCount", targets.size());
                probe.put("combatTargetCount", copiedCombat == null ? 0 : targets.stream()
                        .filter(t -> t instanceof Card c && ExternalCombatActions.combatants(copiedCombat).contains(c)).count());
                // This does not claim arbitrary modes/X/target combinations are fully legal.
                // A successfully revalidated prepared action is the conclusive legality evidence.
                List<PreparedAiAction> matching = actions.stream().filter(a -> a.sourceCard() == original.getHostCard()).toList();
                if (matching.isEmpty() && ExternalCombatActions.safeTemplate(sa) && playable && payable
                        && copiedCombat != null && (outcome.equals("WINDOW_EXCLUDED") || outcome.equals("UNSUPPORTED_COMBAT"))) {
                    matching = copiedAi.prepareCombatActionCandidates(List.of(sa), 1).actions();
                    probe.put("diagnosticPreparation", true);
                }
                probe.put("preparedCount", matching.size());
                var combat = !matching.isEmpty() && matching.get(0).sourceCard().getGame() == copiedGame
                        ? copiedCombat : game.getCombat();
                probe.put("preparedRelevantCount", combat == null ? 0 : matching.stream()
                        .filter(a -> ExternalCombatActions.relevant(a, combat)).count());
                probe.put("exposedCount", exposed == null ? 0 : exposed.mapping().values().stream()
                        .filter(a -> a.sourceCard() == original.getHostCard()).count());
            } catch (RuntimeException ex) { probe.put("engineStatus", "PROBE_UNRESOLVED"); }
            probes.add(probe);
        }
        event.put("candidates", probes);
        event.put("engineAvailability", tactical.isEmpty() ? "NO_INSTANT_SPEED_CANDIDATES"
                : tactical.size() <= 24 && probes.stream().allMatch(p -> Set.of("TIMING_OR_RESTRICTIONS",
                    "COST_UNPAYABLE", "NO_LEGAL_TARGET").contains(p.get("engineStatus")))
                    ? "NO_ENGINE_PLAYABLE_ACTION" : "SEE_CANDIDATE_PROBES");
        event.put("preparedCount", actions.size());
        if (game.getCombat() != null) event.put("selection", ExternalCombatActions.selectionCounts(player, game.getCombat(), actions, exposed));
        return event;
    }
}
