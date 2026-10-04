package forge.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import forge.ai.decision.*;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Bounded, cost-free attacker alternatives layered after the unchanged Forge declaration. */
final class ExternalCombatAttackers {
    static final int HARD_MAX_OPTIONS = 8;
    private static final int MAX_DEFENDERS = 8;
    private static final Gson CANONICAL = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final List<Keyword> KEYWORDS = List.of(Keyword.FLYING, Keyword.REACH, Keyword.TRAMPLE,
            Keyword.MENACE, Keyword.VIGILANCE, Keyword.DEATHTOUCH, Keyword.FIRST_STRIKE, Keyword.DOUBLE_STRIKE,
            Keyword.LIFELINK, Keyword.INDESTRUCTIBLE, Keyword.DEFENDER, Keyword.HASTE);

    record PreparedOption(CombatAttackOptionView view, Map<Card, GameEntity> assignments,
            Map<Card, Long> timestamps, Map<Card, Player> controllers) {
        PreparedOption {
            assignments = Collections.unmodifiableMap(new LinkedHashMap<>(assignments));
            timestamps = Map.copyOf(timestamps); controllers = Map.copyOf(controllers);
        }
        PreparedOption(CombatAttackOptionView view, Map<Card, GameEntity> assignments) {
            this(view, assignments, timestamps(assignments), controllers(assignments));
        }
        private static Set<Card> referencedCards(Map<Card, GameEntity> assignments) {
            Set<Card> cards = new HashSet<>(assignments.keySet());
            assignments.values().forEach(defender -> { if (defender instanceof Card card) cards.add(card); });
            return cards;
        }
        private static Map<Card, Long> timestamps(Map<Card, GameEntity> assignments) {
            Map<Card, Long> result = new HashMap<>(); referencedCards(assignments).forEach(card -> result.put(card, card.getGameTimestamp())); return result;
        }
        private static Map<Card, Player> controllers(Map<Card, GameEntity> assignments) {
            Map<Card, Player> result = new HashMap<>(); referencedCards(assignments).forEach(card -> result.put(card, card.getController())); return result;
        }
    }
    record Prepared(List<PreparedOption> options, CombatAttackersDecisionContext context, String heuristicId) {
        Prepared { options = List.copyOf(options); }
    }
    private record Projection(CombatAttackersDecisionContext.State state, Map<Card, String> cardIds,
            Map<GameEntity, String> defenderIds) { }
    private record FingerprintMaterial(String version, CombatAttackersDecisionContext.State state,
            List<CombatAttackOptionView> options) { }

    static boolean eligible(Player player, Combat combat) {
        return combat != null && player.getGame().getCombat() == combat && combat.getAttackingPlayer() == player
                && player.getGame().getPhaseHandler().getPhase() == PhaseType.COMBAT_DECLARE_ATTACKERS
                && player.getGame().getPhaseHandler().getPlayerTurn() == player && !player.isSkippingCombat()
                && player.getGame().getPlayers().size() == 2 && player.getController() instanceof PlayerControllerAi;
    }

    private static boolean supported(Player player, Combat combat) {
        if (!eligible(player, combat) || combat.getDefenders().size() > MAX_DEFENDERS) return false;
        if (combat.getAttackConstraints().getRequirements().values().stream().anyMatch(r -> r.hasRequirement())
                || combat.getAttackConstraints().countViolations(Map.of()) != 0) return false;
        for (GameEntity defender : combat.getDefenders()) {
            if (combat.getAttackingBandsOf(defender).stream().anyMatch(band -> band.getAttackers().size() > 1)) return false;
        }
        for (Card card : player.getCreaturesInPlay()) {
            if (card.isGoaded() || card.hasKeyword(Keyword.BANDING) || card.hasKeyword(Keyword.BANDSWITH)) return false;
            for (GameEntity defender : combat.getDefenders()) {
                if (defender instanceof Card target && (target.isFaceDown()
                        || !target.getView().canBeShownTo(player.getView()))) return false;
                if (CombatUtil.canAttack(card, defender)
                        && CombatUtil.getAttackCost(player.getGame(), card, defender) != null) return false;
            }
        }
        return combat.getAttackConstraints().countViolations(combat.getAttackersAndDefenders()) == 0;
    }

    static Prepared prepare(Player player, Combat combat, String decisionId, int maxOptions) {
        if (!supported(player, combat)) return null;
        int limit = Math.min(HARD_MAX_OPTIONS, Math.max(1, maxOptions));
        if (limit < 2) return null;
        Projection projection = project(player, combat);
        List<Map<Card, GameEntity>> candidates = new ArrayList<>();
        addCandidate(player, combat, candidates, combat.getAttackersAndDefenders(), limit);
        if (candidates.isEmpty()) return null;
        Map<Card, GameEntity> heuristic = candidates.get(0);
        addCandidate(player, combat, candidates, Map.of(), limit);
        // One all-in declaration per visible defender, then single-creature alternatives.
        // Stop at the cap. No recursive subset generation or combat simulation occurs here.
        for (GameEntity defender : combat.getDefenders()) {
            if (candidates.size() >= limit) break;
            Map<Card, GameEntity> all = new LinkedHashMap<>();
            for (Card card : player.getCreaturesInPlay()) if (CombatUtil.canAttack(card, defender)) all.put(card, defender);
            addCandidate(player, combat, candidates, all, limit);
        }
        for (Card card : player.getCreaturesInPlay()) {
            if (candidates.size() >= limit) break;
            for (GameEntity defender : combat.getDefenders()) {
                if (candidates.size() >= limit) break;
                if (CombatUtil.canAttack(card, defender)) addCandidate(player, combat, candidates, Map.of(card, defender), limit);
            }
        }
        if (candidates.size() < 2) return null;
        List<PreparedOption> prepared = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Map<Card, GameEntity> assignments : candidates) {
            List<CombatAttackOptionView.Assignment> visible = assignmentViews(assignments, projection);
            String material = CANONICAL.toJson(projection.state()) + CANONICAL.toJson(visible);
            String id = assignments.isEmpty() ? CombatAttackersDecisionContext.NO_ATTACK : "OPT_" + hash(material).substring(0, 24);
            int retry = 0;
            while (!used.add(id)) id = "OPT_" + hash(material + "|collision|" + ++retry).substring(0, 24);
            int power = assignments.keySet().stream().mapToInt(Card::getNetPower).sum();
            prepared.add(new PreparedOption(new CombatAttackOptionView(id, visible, power, assignments.size()), assignments));
        }
        List<CombatAttackOptionView> options = prepared.stream().map(PreparedOption::view).toList();
        return new Prepared(prepared, context(decisionId, projection.state(), options),
                prepared.stream().filter(option -> option.assignments().equals(heuristic)).findFirst().orElseThrow().view().optionId());
    }

    private static void addCandidate(Player player, Combat combat, List<Map<Card, GameEntity>> candidates,
            Map<Card, GameEntity> assignments, int limit) {
        if (candidates.size() < limit && legal(player, combat, assignments)
                && candidates.stream().noneMatch(assignments::equals)) candidates.add(new LinkedHashMap<>(assignments));
    }

    private static boolean legal(Player player, Combat combat, Map<Card, GameEntity> assignments) {
        for (Map.Entry<Card, GameEntity> entry : assignments.entrySet()) {
            Card card = entry.getKey();
            if (card.getController() != player || !card.isInPlay() || !player.getCreaturesInPlay().contains(card)
                    || !combat.getDefenders().contains(entry.getValue()) || !CombatUtil.canAttack(card, entry.getValue())
                    || CombatUtil.getAttackCost(player.getGame(), card, entry.getValue()) != null) return false;
        }
        // Zero violations is the absolute legal minimum: no call to an attacker subset search.
        return combat.getAttackConstraints().countViolations(assignments) == 0;
    }

    static boolean revalidate(Player player, Combat combat, PreparedOption option) {
        if (!eligible(player, combat)) return false;
        for (Card card : option.timestamps().keySet()) {
            if (!card.isInPlay() || card.getGameTimestamp() != option.timestamps().get(card)
                    || card.getController() != option.controllers().get(card)) return false;
        }
        Combat currentConstraints = new Combat(player);
        return supported(player, combat)
                && legal(player, currentConstraints, option.assignments());
    }

    private static CombatAttackersDecisionContext context(String decisionId, CombatAttackersDecisionContext.State state,
            List<CombatAttackOptionView> options) {
        String fingerprint = hash(CANONICAL.toJson(new FingerprintMaterial("COMBAT_ATTACKERS_V1", state, options)));
        return new CombatAttackersDecisionContext(decisionId, fingerprint, state, options);
    }

    static CombatAttackersDecisionContext refresh(Player player, Combat combat, Prepared prepared) {
        Projection projection = project(player, combat);
        List<CombatAttackOptionView> options = prepared.options().stream().map(option -> new CombatAttackOptionView(
                option.view().optionId(), assignmentViews(option.assignments(), projection),
                option.assignments().keySet().stream().mapToInt(Card::getNetPower).sum(), option.assignments().size())).toList();
        return context(prepared.context().decisionId(), projection.state(), options);
    }

    private static List<CombatAttackOptionView.Assignment> assignmentViews(Map<Card, GameEntity> assignments, Projection projection) {
        return assignments.entrySet().stream().map(entry -> new CombatAttackOptionView.Assignment(
                projection.cardIds().get(entry.getKey()), projection.defenderIds().get(entry.getValue())))
                .sorted(Comparator.comparing(CombatAttackOptionView.Assignment::attackerId, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(CombatAttackOptionView.Assignment::defenderId, Comparator.nullsFirst(String::compareTo))).toList();
    }

    private static Projection project(Player self, Combat combat) {
        Map<Card, String> cardIds = new IdentityHashMap<>();
        List<CombatAttackersDecisionContext.PlayerState> players = new ArrayList<>();
        int seat = 0;
        for (Player player : self.getGame().getPlayers()) {
            List<Card> cards = new ArrayList<>();
            player.getCardsIn(ZoneType.Battlefield).forEach(cards::add);
            cards.sort(Comparator.comparing((Card card) -> CANONICAL.toJson(visibleCard(self, card))).thenComparingInt(Card::getId));
            List<CombatAttackersDecisionContext.Permanent> battlefield = new ArrayList<>();
            int index = 0;
            for (Card card : cards) {
                MainPhaseCardView view = visibleCard(self, card);
                String id = "PERM_" + hash(seat + "|" + index++ + "|" + CANONICAL.toJson(view)).substring(0, 24);
                cardIds.put(card, id);
                List<String> keywords = card.isFaceDown() || !card.getView().canBeShownTo(self.getView()) ? List.of()
                        : KEYWORDS.stream().filter(card::hasKeyword).map(keyword -> keyword.name().toLowerCase(Locale.ROOT).replace('_', ' ')).toList();
                battlefield.add(new CombatAttackersDecisionContext.Permanent(id, view, card.isCreature() && card.isSick(),
                        player == self && card.isCreature() && CombatUtil.canAttack(card), keywords));
            }
            players.add(new CombatAttackersDecisionContext.PlayerState("PLAYER_" + seat++, player.getName(), player.getLife(),
                    player == self, player == self ? MainPhaseStateProjector.mana(player.getManaPool()) : null, battlefield));
        }
        Map<GameEntity, String> defenderIds = new IdentityHashMap<>();
        List<CombatAttackersDecisionContext.Defender> defenders = new ArrayList<>();
        for (GameEntity defender : CombatUtil.getAllPossibleDefenders(self)) {
            String id;
            String kind;
            Player controller;
            if (defender instanceof Player player) {
                id = "PLAYER_" + self.getGame().getPlayers().indexOf(player); kind = "PLAYER"; controller = player;
            } else if (defender instanceof Card card) {
                id = cardIds.get(card); kind = card.isBattle() ? "BATTLE" : "PLANESWALKER"; controller = card.getController();
            } else continue;
            defenderIds.put(defender, id);
            defenders.add(new CombatAttackersDecisionContext.Defender(id, defender.getName(), kind,
                    "PLAYER_" + self.getGame().getPlayers().indexOf(controller),
                    "PLAYER_" + self.getGame().getPlayers().indexOf(defender instanceof Card card && card.isBattle()
                            ? card.getProtectingPlayer() : controller)));
        }
        defenders.sort(Comparator.comparing(CombatAttackersDecisionContext.Defender::defenderId));
        return new Projection(new CombatAttackersDecisionContext.State(self.getGame().getPhaseHandler().getTurn(),
                self.getGame().getPhaseHandler().getPhase().name(), "PLAYER_" + self.getGame().getPlayers().indexOf(self),
                players, defenders, List.of("No additional attack payments or mandatory attack assignments apply")),
                cardIds, defenderIds);
    }

    private static MainPhaseCardView visibleCard(Player self, Card card) {
        if (!card.isFaceDown()) return MainPhaseStateProjector.cardView(self, card);
        return new MainPhaseCardView("Face-down card", null, null, null, card.isTapped(), card.getController().getName(),
                card.isCreature() ? card.getNetPower() : null, card.isCreature() ? card.getNetToughness() : null,
                card.isCreature() ? card.getDamage() : null, card.getCounters().entrySet().stream()
                        .sorted(Comparator.comparing(entry -> entry.getElement().getName()))
                        .map(entry -> new MainPhaseCounterView(entry.getElement().getName(), entry.getCount())).toList());
    }

    private static String hash(String material) {
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("Unable to fingerprint combat", e); }
    }

    static void choose(Player player, Combat combat, AiDecisionProvider provider, int maxOptions) {
        long started = System.nanoTime();
        Prepared prepared;
        try { prepared = prepare(player, combat, UUID.randomUUID().toString(), maxOptions); }
        catch (RuntimeException ex) { return; }
        if (prepared == null) return;
        AiDecisionResult result = null;
        AiDecisionFailureReason failure = null;
        PreparedOption selected = null;
        try {
            result = provider.chooseWithDiagnostics(prepared.context());
            if (result == null || result.decision().isEmpty()) failure = result == null
                    ? AiDecisionFailureReason.PROVIDER_EXCEPTION : result.failureReason() == null
                    ? AiDecisionFailureReason.EMPTY_RESPONSE : result.failureReason();
            else {
                AiDecision decision = result.decision().orElseThrow();
                if (!prepared.context().decisionId().equals(decision.decisionId())) failure = AiDecisionFailureReason.DECISION_ID_MISMATCH;
                else if (!prepared.context().stateFingerprint().equals(decision.stateFingerprint())) failure = AiDecisionFailureReason.FINGERPRINT_MISMATCH;
                else {
                    selected = prepared.options().stream().filter(option -> option.view().optionId().equals(decision.optionId())).findFirst().orElse(null);
                    if (selected == null) failure = AiDecisionFailureReason.ACTION_ID_MISMATCH;
                }
            }
            if (!eligible(player, combat) || !prepared.context().stateFingerprint().equals(refresh(player, combat, prepared).stateFingerprint()))
                failure = AiDecisionFailureReason.STALE_ACTION_SET;
            else if (selected != null && !revalidate(player, combat, selected)) failure = AiDecisionFailureReason.REVALIDATION_FAILED;
        } catch (RuntimeException ex) { failure = AiDecisionFailureReason.PROVIDER_EXCEPTION; }
        boolean fallback = failure != null || selected == null;
        String selectedId = fallback ? prepared.heuristicId() : selected.view().optionId();
        Map<Card, GameEntity> declaration = fallback ? prepared.options().stream()
                .filter(option -> option.view().optionId().equals(prepared.heuristicId())).findFirst().orElseThrow().assignments()
                : selected.assignments();
        if (!combat.getAttackersAndDefenders().equals(declaration)) {
            combat.clearAttackers();
            declaration.forEach(combat::addAttacker);
        }
        AiDecisionMetrics.GameRunMetadata run = AiDecisionMetrics.gameMetadata(player.getGame().getId());
        AiDecision returned = result == null ? null : result.decision().orElse(null);
        AiDecisionMetrics.record(new CombatAttackersDecisionEvent(prepared.context().decisionId(), prepared.context().stateFingerprint(),
                player.getGame().getId(), run == null ? player.getGame().getMatch().getOutcomes().size() : run.gameIndex(),
                run == null ? null : run.runSeed(), player.getName(), player.getGame().getRegisteredPlayers().indexOf(player),
                player.getRegisteredPlayer().getDeck().getName(), prepared.heuristicId(), selectedId, prepared.options().size(),
                !fallback && CombatAttackersDecisionContext.NO_ATTACK.equals(selectedId), true,
                fallback ? AiDecisionSource.FORGE_HEURISTIC : AiDecisionSource.EXTERNAL_PROVIDER, fallback, failure,
                failure == AiDecisionFailureReason.STALE_ACTION_SET || failure == AiDecisionFailureReason.REVALIDATION_FAILED,
                result == null ? null : result.provider(), result == null ? null : result.model(),
                result == null ? 0 : result.latencyMs(), AiDecisionResult.elapsedMillis(started), returned == null ? null : returned.decisionId(),
                returned == null ? null : returned.stateFingerprint(), returned == null ? null : returned.optionId(), !fallback), prepared.context());
    }
}
