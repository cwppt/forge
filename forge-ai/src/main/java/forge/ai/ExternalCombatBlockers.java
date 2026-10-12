package forge.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import forge.ai.decision.*;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.phase.PhaseType;
import forge.game.player.Player;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Bounded declarations layered after Forge's unchanged blocker heuristic. */
final class ExternalCombatBlockers {
    static final String MATERIAL_GUARD_PROPERTY = "forge.ai.external.combat.materialGuard";
    static final String RACE_AWARE_MATERIAL_GUARD_PROPERTY = "forge.ai.external.combat.materialGuardRaceAware";
    static final String DIAGNOSTIC_IDS_PROPERTY = "forge.ai.external.combat.seededBlockerIds";
    static final int HARD_MAX_OPTIONS = 8;
    static final int MAX_CANDIDATE_ATTEMPTS = 32;
    private static final Gson CANONICAL = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    record Assignment(Card blocker, Card attacker) { }
    record Incarnation(Card card, long timestamp, Player controller) { }
    record PreparedOption(CombatBlockOptionView view, List<Assignment> assignments,
            List<Incarnation> incarnations) {
        PreparedOption { assignments = List.copyOf(assignments); incarnations = List.copyOf(incarnations); }
    }
    record Prepared(List<PreparedOption> options, CombatBlockersDecisionContext context, String heuristicId,
            Map<Card, GameEntity> attacks, List<Incarnation> attackers) {
        Prepared { options = List.copyOf(options); attacks = Collections.unmodifiableMap(new LinkedHashMap<>(attacks));
            attackers = List.copyOf(attackers); }
    }
    private record FingerprintMaterial(String version, CombatBlockersDecisionContext.State state,
            List<CombatBlockOptionView> options) { }

    static boolean eligible(Player defender, Combat combat) {
        return combat != null && defender.getGame().getCombat() == combat
                && defender.getGame().getPhaseHandler().getPhase() == PhaseType.COMBAT_DECLARE_BLOCKERS
                && combat.getAttackingPlayer() != defender && defender.isInGame()
                && defender.getController() instanceof PlayerControllerAi
                && defender.getGame().getPlayers().size() == 2 && !combat.getAttackers().isEmpty()
                && combat.getAttackers().stream().allMatch(a -> combat.getDefendingPlayerRelatedTo(a) == defender);
    }

    private static boolean supported(Player defender, Combat combat) {
        if (!eligible(defender, combat)) return false;
        for (Card attacker : combat.getAttackers()) {
            var band = combat.getBandOfAttacker(attacker);
            if (band == null || band.getAttackers().size() != 1 || banding(attacker)) return false;
            for (Card blocker : defender.getCreaturesInPlay()) {
                if (banding(blocker)) return false;
                if (CombatUtil.canBlock(attacker, blocker)
                        && CombatUtil.getBlockCost(defender.getGame(), blocker, attacker) != null) return false;
            }
        }
        return true;
    }
    private static boolean banding(Card card) {
        return card.hasKeyword(Keyword.BANDING) || card.hasKeyword(Keyword.BANDSWITH);
    }
    private static List<Assignment> declaration(Player defender, Combat combat) {
        List<Assignment> result = new ArrayList<>();
        for (Card attacker : combat.getAttackers()) for (Card blocker : combat.getBlockers(attacker))
            if (blocker.getController() == defender) result.add(new Assignment(blocker, attacker));
        return result;
    }
    private static Combat scratch(Combat combat) {
        Combat result = new Combat(combat.getAttackingPlayer());
        combat.getAttackersAndDefenders().forEach(result::addAttacker);
        return result;
    }
    private static boolean legal(Player defender, Combat original, List<Assignment> assignments) {
        Combat candidate = scratch(original);
        Set<Assignment> unique = new HashSet<>();
        for (Assignment assignment : assignments) {
            Card blocker = assignment.blocker(), attacker = assignment.attacker();
            if (!unique.add(assignment) || !blocker.isInPlay() || blocker.getController() != defender
                    || blocker.isTapped() || !defender.getCreaturesInPlay().contains(blocker)
                    || !candidate.isAttacking(attacker) || !CombatUtil.canBlock(attacker, blocker, candidate)
                    || CombatUtil.getBlockCost(defender.getGame(), blocker, attacker) != null) return false;
            candidate.addBlocker(attacker, blocker);
        }
        return CombatUtil.validateBlocks(candidate, defender) == null;
    }
    private static List<Incarnation> capture(Collection<Card> cards) {
        return cards.stream().distinct().map(c -> new Incarnation(c, c.getGameTimestamp(), c.getController())).toList();
    }
    private static boolean unchanged(List<Incarnation> cards) {
        return cards.stream().allMatch(i -> i.card().isInPlay() && i.card().getGameTimestamp() == i.timestamp()
                && i.card().getController() == i.controller());
    }
    private static CombatAttackersDecisionContext.Permanent visiblePermanent(
            CombatAttackersDecisionContext.Permanent permanent, Card card, Player self) {
        List<String> keywords = new ArrayList<>(permanent.keywords());
        if (!card.isFaceDown() && card.getView().canBeShownTo(self.getView())) {
            card.getKeywords().stream().filter(k -> k.getKeyword() == Keyword.PROTECTION)
                    .map(KeywordInterface::getOriginal).forEach(keywords::add);
        }
        keywords = keywords.stream().distinct().sorted().toList();
        return new CombatAttackersDecisionContext.Permanent(permanent.permanentId(), permanent.card(),
                permanent.summoningSick(), permanent.canAttack(), keywords);
    }

    private static String targetId(Player self, GameEntity target, ExternalCombatAttackers.Projection projection) {
        return target instanceof Player player ? "PLAYER_" + self.getGame().getPlayers().indexOf(player)
                : projection.cardIds().get((Card) target);
    }

    static Prepared prepare(Player defender, Combat combat, String decisionId, int maxOptions) {
        if (!supported(defender, combat)) return null;
        int cap = Math.min(HARD_MAX_OPTIONS, Math.max(1, maxOptions));
        if (cap < 2) return null;
        var projection = ExternalCombatAttackers.project(defender, combat);
        Map<String, CombatAttackersDecisionContext.Permanent> permanents = new HashMap<>();
        projection.state().players().forEach(p -> p.battlefield().forEach(c -> permanents.put(c.permanentId(), c)));
        List<Card> attackers = new ArrayList<>(combat.getAttackers());
        attackers.sort(Comparator.comparing(projection.cardIds()::get));
        List<Card> blockers = defender.getCreaturesInPlay().stream().filter(c -> !c.isTapped()
                && CombatUtil.canBlock(c) && attackers.stream().anyMatch(a -> CombatUtil.canBlock(a, c)))
                .sorted(Comparator.comparing(projection.cardIds()::get)).toList();
        if (blockers.isEmpty()) return null;
        var state = new CombatBlockersDecisionContext.State(projection.state().turn(), projection.state().phase(),
                "PLAYER_" + defender.getGame().getPlayers().indexOf(defender), projection.state().players(),
                attackers.stream().map(c -> new CombatBlockersDecisionContext.Attacker(
                        visiblePermanent(permanents.get(projection.cardIds().get(c)), c, defender),
                        targetId(defender, combat.getDefenderByAttacker(c), projection))).toList(),
                blockers.stream().map(c -> new CombatBlockersDecisionContext.Blocker(
                        visiblePermanent(permanents.get(projection.cardIds().get(c)), c, defender), true)).toList());
        List<List<Assignment>> candidates = new ArrayList<>();
        List<Assignment> heuristic = declaration(defender, combat);
        if (!legal(defender, combat, heuristic)) return null;
        candidates.add(heuristic);
        // Conservative scope: mandatory declarations bypass external selection.
        if (!legal(defender, combat, List.of())) return null;
        addCandidate(defender, combat, candidates, List.of(), cap);
        int attempts = 0;
        outer: for (Card attacker : attackers) {
            List<Card> available = blockers.stream().filter(b -> CombatUtil.canBlock(attacker, b)).toList();
            int minimum = Math.max(2, CombatUtil.getMinNumBlockersForAttacker(attacker, defender));
            if (available.size() >= minimum) {
                if (++attempts > MAX_CANDIDATE_ATTEMPTS || candidates.size() >= cap) break;
                addCandidate(defender, combat, candidates, available.subList(0, minimum).stream()
                        .map(b -> new Assignment(b, attacker)).toList(), cap);
            }
            for (Card blocker : available) {
                if (++attempts > MAX_CANDIDATE_ATTEMPTS || candidates.size() >= cap) break outer;
                addCandidate(defender, combat, candidates, List.of(new Assignment(blocker, attacker)), cap);
            }
        }
        if (candidates.size() < 2) return null;
        List<PreparedOption> options = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (List<Assignment> candidate : candidates) {
            List<CombatBlockOptionView.Assignment> visible = candidate.stream().map(a -> new CombatBlockOptionView.Assignment(
                    projection.cardIds().get(a.blocker()), projection.cardIds().get(a.attacker())))
                    .sorted(Comparator.comparing(CombatBlockOptionView.Assignment::blockerId)
                            .thenComparing(CombatBlockOptionView.Assignment::attackerId)).toList();
            String material = CANONICAL.toJson(state) + CANONICAL.toJson(visible);
            String id = candidate.isEmpty() ? CombatBlockersDecisionContext.NO_BLOCK : "OPT_" + hash(material).substring(0, 24);
            int retry = 0;
            while (!used.add(id)) id = "OPT_" + hash(material + "|collision|" + ++retry).substring(0, 24);
            var view = new CombatBlockOptionView(id, visible, (int)candidate.stream().map(Assignment::blocker).distinct().count(),
                    (int)candidate.stream().map(Assignment::attacker).distinct().count(),
                    BlockerTacticalProjection.project(defender, combat, candidate));
            options.add(new PreparedOption(view, candidate, capture(candidate.stream().map(Assignment::blocker).toList())));
        }
        List<CombatBlockOptionView> views = options.stream().map(PreparedOption::view).toList();
        String fingerprint = fingerprint(state, views);
        var memory=ExternalAiStrategicMemory.snapshot(defender,AiDecisionType.COMBAT_BLOCKERS,
                java.util.stream.Stream.concat(state.blockers().stream().map(b->b.permanent().card().name()),
                        state.attackers().stream().map(a->a.permanent().card().name())).toList());
        List<Card> attackCards = new ArrayList<>(attackers);
        combat.getAttackersAndDefenders().values().forEach(t -> { if (t instanceof Card card) attackCards.add(card); });
        return new Prepared(options, new CombatBlockersDecisionContext(decisionId, ExternalAiStrategicMemory.fingerprint(fingerprint,memory), state, views,memory),
                options.get(0).view().optionId(), combat.getAttackersAndDefenders(), capture(attackCards));
    }
    private static void addCandidate(Player defender, Combat combat, List<List<Assignment>> candidates,
            List<Assignment> assignments, int cap) {
        Set<Assignment> identity = new HashSet<>(assignments);
        if (candidates.size() < cap && candidates.stream().noneMatch(c -> new HashSet<>(c).equals(identity))
                && legal(defender, combat, assignments)) candidates.add(List.copyOf(assignments));
    }
    static Prepared diagnosticIdentity(Player defender, Prepared prepared) {
        if (prepared == null) return null;
        var original = prepared.context();
        String id = ExternalCombatDiagnosticIds.next(defender, AiDecisionType.COMBAT_BLOCKERS, original.stateFingerprint(), original.decisionId());
        if (id.equals(original.decisionId())) return prepared;
        var context = new CombatBlockersDecisionContext(id, original.stateFingerprint(), original.state(), original.options(),original.memorySnapshot());
        return new Prepared(prepared.options(), context, prepared.heuristicId(), prepared.attacks(), prepared.attackers());
    }
    private static boolean predictsLoss(Player defender, Combat original, PreparedOption option) {
        Combat candidate = scratch(original);
        option.assignments().forEach(a -> candidate.addBlocker(a.attacker(), a.blocker()));
        return ComputerUtilCombat.lifeInSeriousDanger(defender, candidate);
    }
    static String fingerprint(CombatBlockersDecisionContext.State state, List<CombatBlockOptionView> views) {
        return hash(CANONICAL.toJson(new FingerprintMaterial("COMBAT_BLOCKERS_V2", state, views)));
    }
    private static boolean materialGuardRejects(Player defender, Combat combat, Prepared prepared, PreparedOption selected) {
        if (!Boolean.getBoolean(MATERIAL_GUARD_PROPERTY) || !prepared.options().get(0).assignments().isEmpty()
                || selected.assignments().size() != 1 || predictsLoss(defender, combat, prepared.options().get(0))) return false;
        Assignment assignment = selected.assignments().get(0);
        Card attacker = assignment.attacker(), blocker = assignment.blocker();
        if (Boolean.getBoolean(RACE_AWARE_MATERIAL_GUARD_PROPERTY)
                && raceOrLifeBufferUncertain(defender, combat, blocker)) return false;
        // Triggered benefits and gang blocks need a broader evaluation than this experiment.
        if (!attacker.getTriggers().isEmpty() || !blocker.getTriggers().isEmpty()) return false;
        Combat candidate = scratch(combat);
        candidate.addBlocker(attacker, blocker);
        return ComputerUtilCombat.canDestroyBlocker(defender, blocker, attacker, candidate, true)
                && !ComputerUtilCombat.canDestroyAttacker(defender, attacker, blocker, candidate, true);
    }
    private static boolean raceOrLifeBufferUncertain(Player defender, Combat combat, Card sacrificedBlocker) {
        // Decline a material override near a race. This is a public-board uncertainty bound,
        // not a prediction of future blocks, draws, untaps or a guaranteed lethal attack.
        int remaining = ComputerUtilCombat.lifeThatWouldRemain(defender, scratch(combat));
        int incoming = Math.max(0, defender.getLife() - remaining);
        if (remaining <= incoming) return true;
        Player opponent = combat.getAttackingPlayer();
        int potential = 0;
        for (Card creature : defender.getCreaturesInPlay()) {
            if (creature != sacrificedBlocker && !creature.isPhasedOut()
                    && CombatUtil.canAttackNextTurn(creature, opponent)) {
                potential += Math.max(0, ComputerUtilCombat.damageIfUnblocked(creature, opponent, null, true));
            }
        }
        return potential >= opponent.getLife();
    }
    private static boolean lossProtectionActive(Player defender) {
        return defender.cantLose() || defender.cantLoseForZeroOrLessLife();
    }
    static boolean revalidate(Player defender, Combat combat, Prepared prepared, PreparedOption option) {
        return !lossProtectionActive(defender) && supported(defender, combat) && prepared.attacks().equals(combat.getAttackersAndDefenders())
                && unchanged(prepared.attackers()) && unchanged(option.incarnations())
                && legal(defender, combat, option.assignments());
    }
    private static void apply(Player defender, Combat combat, List<Assignment> declaration) {
        if (defender.getGame().getCombat() != combat
                || defender.getGame().getPhaseHandler().getPhase() != PhaseType.COMBAT_DECLARE_BLOCKERS) return;
        // A vanished attacker cannot receive any declaration. Keep the engine's current
        // Forge state intact rather than partially restoring or repairing assignments.
        if (declaration.stream().anyMatch(a -> combat.getBandOfAttacker(a.attacker()) == null)) return;
        if (new HashSet<>(declaration(defender, combat)).equals(new HashSet<>(declaration))) return;
        for (Assignment old : declaration(defender, combat)) combat.removeBlockAssignment(old.attacker(), old.blocker());
        declaration.forEach(a -> combat.addBlocker(a.attacker(), a.blocker()));
    }
    static void choose(Player defender, Combat combat, AiDecisionProvider provider, int maxOptions) {
        long started = System.nanoTime();
        Prepared prepared;
        // Preserving a loss-prevention effect can matter more than blocking damage.
        // Until these counterfactual effects are supported, retain Forge's declaration.
        try {
            if (lossProtectionActive(defender)) return;
            prepared = diagnosticIdentity(defender, prepare(defender, combat, UUID.randomUUID().toString(), maxOptions));
        }
        catch (RuntimeException ex) { return; }
        if (prepared == null) return;
        var correlation = AiDecisionMetrics.combatCorrelation(defender.getGame(), combat, AiDecisionType.COMBAT_BLOCKERS);
        AiDecisionResult result = null;
        AiDecisionFailureReason failure = null;
        PreparedOption selected = null;
        String live = "NOT_RUN";
        try {
            result = provider.chooseWithDiagnostics(prepared.context());
            if (result == null || result.decision().isEmpty()) failure = result == null
                    ? AiDecisionFailureReason.PROVIDER_EXCEPTION : result.failureReason() == null
                    ? AiDecisionFailureReason.EMPTY_RESPONSE : result.failureReason();
            else {
                AiDecision response = result.decision().orElseThrow();
                if (!prepared.context().decisionId().equals(response.decisionId())) failure = AiDecisionFailureReason.DECISION_ID_MISMATCH;
                else if (!prepared.context().stateFingerprint().equals(response.stateFingerprint())) failure = AiDecisionFailureReason.FINGERPRINT_MISMATCH;
                else {
                    selected = prepared.options().stream().filter(o -> o.view().optionId().equals(response.optionId())).findFirst().orElse(null);
                    if (selected == null) failure = AiDecisionFailureReason.ACTION_ID_MISMATCH;
                }
            }
            if (failure == null && selected != null) {
                if (!eligible(defender, combat)) { failure = AiDecisionFailureReason.LIVE_STATE_CHANGED; live = "LIVE_STATE_CHANGED"; }
                else if (!revalidate(defender, combat, prepared, selected)) { failure = AiDecisionFailureReason.REVALIDATION_FAILED; live = "LIVE_REVALIDATION_FAILED"; }
                else if (predictsLoss(defender, combat, selected)
                        && !predictsLoss(defender, combat, prepared.options().get(0))) {
                    failure = AiDecisionFailureReason.UNSAFE_COMBAT_CHOICE;
                    live = "COMBAT_SAFETY_REJECTED";
                } else if (materialGuardRejects(defender, combat, prepared, selected)) {
                    failure = AiDecisionFailureReason.MATERIAL_COMBAT_CHOICE;
                    live = "COMBAT_MATERIAL_REJECTED";
                } else live = "PASSED";
            }
        } catch (RuntimeException ex) { failure = AiDecisionFailureReason.PROVIDER_EXCEPTION; }
        boolean fallback = failure != null || selected == null;
        PreparedOption applied = fallback ? prepared.options().get(0) : selected;
        apply(defender, combat, applied.assignments());
        var run = AiDecisionMetrics.gameMetadata(defender.getGame().getId());
        AiDecision returned = result == null ? null : result.decision().orElse(result.rejectedDecision());
        AiDecisionMetrics.record(new CombatBlockersDecisionEvent(prepared.context().decisionId(), prepared.context().stateFingerprint(),
                defender.getGame().getId(), run == null ? defender.getGame().getMatch().getOutcomes().size() : run.gameIndex(),
                run == null ? null : run.runSeed(), defender.getName(), defender.getGame().getRegisteredPlayers().indexOf(defender),
                defender.getRegisteredPlayer().getDeck().getName(), prepared.heuristicId(), applied.view().optionId(), prepared.options().size(),
                !fallback && applied.assignments().isEmpty(), true, fallback ? AiDecisionSource.FORGE_HEURISTIC : AiDecisionSource.EXTERNAL_PROVIDER,
                fallback, failure, failure == AiDecisionFailureReason.LIVE_STATE_CHANGED || failure == AiDecisionFailureReason.REVALIDATION_FAILED,
                result == null ? null : result.provider(), result == null ? null : result.model(), result == null ? 0 : result.latencyMs(),
                AiDecisionResult.elapsedMillis(started), returned == null ? null : returned.decisionId(),
                returned == null ? null : returned.stateFingerprint(), returned == null ? null : returned.optionId(), !fallback, live,
                correlation.combatSequenceId(), correlation.turnNumber(), correlation.activePlayerSeat(),
                correlation.defendingPlayerSeat(), AiDecisionType.COMBAT_BLOCKERS), prepared.context());
    }
    private static String hash(String material) {
        try { return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("Unable to fingerprint blockers", ex); }
    }
}





