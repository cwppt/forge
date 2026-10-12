package forge.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import forge.ai.decision.*;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.combat.Combat;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.staticability.StaticAbilityAssignCombatDamageAsUnblocked;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Selects complete recipient permutations at Forge's legacy ordering seam only. */
final class ExternalCombatDamageOrder {
    static final int HARD_MAX_OPTIONS = 12;
    private static final Gson CANONICAL = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final List<Keyword> KEYWORDS = List.of(Keyword.TRAMPLE, Keyword.DEATHTOUCH,
            Keyword.FIRST_STRIKE, Keyword.DOUBLE_STRIKE, Keyword.INDESTRUCTIBLE, Keyword.LIFELINK);
    record Incarnation(Card card, long timestamp, Player controller) { }
    record PreparedOption(CombatDamageOrderOptionView view, List<Card> order) {
        PreparedOption { order = List.copyOf(order); }
    }
    record Prepared(CombatDamageOrderDecisionContext context, List<PreparedOption> options,
            List<Incarnation> incarnations, Map<Card, GameEntity> defenders, boolean attacking) {
        Prepared {
            options = List.copyOf(options); incarnations = List.copyOf(incarnations);
            defenders = Collections.unmodifiableMap(new LinkedHashMap<>(defenders));
        }
        String heuristicId() { return options.get(0).view().optionId(); }
    }
    private record FingerprintMaterial(String version, CombatDamageOrderDecisionContext.State state,
            List<CombatDamageOrderOptionView> options) { }

    private static List<Card> recipients(Combat combat, Card source, boolean attacking) {
        return new ArrayList<>(attacking ? combat.getBlockers(source) : combat.getAttackersBlockedBy(source));
    }
    private static boolean supported(Player player, Combat combat, Card source, List<Card> order, boolean attacking) {
        if (combat == null || player.getGame().getCombat() != combat || !player.isInGame()
                || !(player.getController() instanceof PlayerControllerAi)
                || !player.getGame().getRules().hasOrderCombatants()
                || player.getGame().getPhaseHandler().getPhase() != PhaseType.COMBAT_DECLARE_BLOCKERS
                || player.getGame().getPlayers().size() != 2 || source.getController() != player
                || source.getNetCombatDamage() != source.getNetPower()
                || source.isWitherDamage()
                || order.size() < 2 || new HashSet<>(order).size() != order.size()
                || !new HashSet<>(order).equals(new HashSet<>(recipients(combat, source, attacking)))
                || (attacking ? !combat.isAttacking(source) : combat.getAttackersBlockedBy(source).isEmpty())) return false;
        List<Card> involved = new ArrayList<>(order); involved.add(source);
        for (Card card : involved) {
            // Conservative boundary: no alternate assigners, bands, hidden characteristics or replacements.
            if (!card.isInPlay() || !card.isCreature() || card.isFaceDown()
                    || !card.getView().canBeShownTo(player.getView())
                    || card.hasKeyword(Keyword.BANDING) || card.hasKeyword(Keyword.BANDSWITH)
                    || card.getReplacementEffects().stream().anyMatch(r -> !r.isKeyword(Keyword.PROTECTION))
                    || card.getKeywords().stream().map(k -> k.getOriginal().toLowerCase(Locale.ROOT)).anyMatch(k ->
                        k.contains("assign") && k.contains("combat damage"))) return false;
            if (combat.isAttacking(card) && (combat.getBandOfAttacker(card).getAttackers().size() != 1
                    || !(combat.getDefenderByAttacker(card) instanceof Player)
                    || StaticAbilityAssignCombatDamageAsUnblocked.assignCombatDamageAsUnblocked(card, false)
                    || StaticAbilityAssignCombatDamageAsUnblocked.assignCombatDamageAsUnblocked(card))) return false;
        }
        return player.getGame().getPlayers().stream().noneMatch(p ->
                p.hasKeyword("You assign combat damage of each creature attacking you."));
    }
    private static CombatDamageOrderDecisionContext.Creature visible(Card card) {
        List<String> keywords = new ArrayList<>(KEYWORDS.stream().filter(card::hasKeyword)
                .map(k -> k.name().toLowerCase(Locale.ROOT).replace('_', ' ')).toList());
        card.getKeywords().stream().filter(k -> k.getKeyword() == Keyword.PROTECTION)
                .map(KeywordInterface::getOriginal).forEach(keywords::add);
        return new CombatDamageOrderDecisionContext.Creature(ref(card), card.getName(), card.getType().toString(),
                card.getColor().stream().map(c -> c.name().toLowerCase(Locale.ROOT)).sorted().toList(), card.getNetPower(),
                card.getNetToughness(), card.getDamage(), card.getCounters().entrySet().stream()
                    .sorted(Comparator.comparing(e -> e.getElement().getName()))
                    .map(e -> new MainPhaseCounterView(e.getElement().getName(), e.getCount())).toList(),
                keywords.stream().distinct().sorted().toList());
    }
    private static String ref(Card card) { return "PERM_" + hash(card.getId() + "|" + card.getGameTimestamp()).substring(0, 24); }
    private static CombatDamageOrderDecisionContext.State state(Player player, Combat combat, Card source,
            List<Card> order, boolean attacking) {
        return new CombatDamageOrderDecisionContext.State(player.getGame().getPhaseHandler().getTurn(),
                player.getGame().getPhaseHandler().getPhase().name(), "PRE_DAMAGE_ASSIGNMENT_ORDER", attacking,
                visible(source), order.stream().sorted(Comparator.comparing(ExternalCombatDamageOrder::ref))
                    .map(ExternalCombatDamageOrder::visible).toList(),
                attacking && source.hasKeyword(Keyword.TRAMPLE) ? combat.getDefendingPlayerRelatedTo(source).getLife() : null);
    }
    private static int lethal(Card source, Card recipient) {
        // Forge's lethal calculation handles deathtouch, marked damage and indestructible.
        return ComputerUtilCombat.getEnoughDamageToKill(recipient, Math.max(0, source.getNetCombatDamage()), source, true);
    }
    static Prepared prepare(Player player, Combat combat, Card source, List<Card> heuristic,
            boolean attacking, String decisionId, int maxOptions) {
        int cap = Math.min(HARD_MAX_OPTIONS, Math.max(1, maxOptions));
        if (cap < 2 || !supported(player, combat, source, heuristic, attacking)) return null;
        int damage = source.getNetCombatDamage();
        if (damage <= 0) return null;
        Map<Card, Integer> lethalRequirements = new HashMap<>();
        heuristic.forEach(c -> lethalRequirements.put(c, lethal(source, c)));
        if (lethalRequirements.values().stream().mapToLong(n -> Math.max(0, n)).sum() <= damage) return null;
        var state = state(player, combat, source, heuristic, attacking);
        List<List<Card>> candidates = new ArrayList<>();
        add(candidates, heuristic, cap);
        Comparator<Card> tie = Comparator.comparing(ExternalCombatDamageOrder::ref);
        add(candidates, heuristic.stream().sorted(Comparator.comparingInt(Card::getNetToughness).thenComparing(tie)).toList(), cap);
        // Public power is a safe value proxy; no hidden-state card evaluation is exposed or consulted.
        add(candidates, heuristic.stream().sorted(Comparator.comparingInt(Card::getNetPower).reversed().thenComparing(tie)).toList(), cap);
        if (source.hasKeyword(Keyword.DEATHTOUCH) || source.hasKeyword(Keyword.TRAMPLE))
            add(candidates, heuristic.stream().sorted(Comparator.comparingInt((Card c) -> lethalRequirements.get(c))
                    .thenComparing(Comparator.comparingInt(Card::getNetPower).reversed()).thenComparing(tie)).toList(), cap);
        List<Card> reverse = new ArrayList<>(heuristic); Collections.reverse(reverse); add(candidates, reverse, cap);
        // At most twelve rotations: no recursion, permutation enumeration, or N! search.
        List<Card> canonical = heuristic.stream().sorted(tie).toList();
        for (int i = 0; i < Math.min(cap, canonical.size()) && candidates.size() < cap; i++) {
            List<Card> rotated = new ArrayList<>(canonical); Collections.rotate(rotated, i); add(candidates, rotated, cap);
        }
        if (candidates.size() < 2) return null;
        List<PreparedOption> options = new ArrayList<>();
        for (List<Card> order : candidates) {
            List<String> ids = order.stream().map(ExternalCombatDamageOrder::ref).toList();
            String id = "OPT_" + hash(CANONICAL.toJson(state) + CANONICAL.toJson(ids));
            options.add(new PreparedOption(new CombatDamageOrderOptionView(id, ids,
                    order.stream().map(c -> c.getName() + " " + c.getNetPower() + "/" + c.getNetToughness()).toList()), order));
        }
        // Neutral deterministic presentation, independent of generation/heuristic rank.
        List<CombatDamageOrderOptionView> views = options.stream().map(PreparedOption::view)
                .sorted(Comparator.comparing(CombatDamageOrderOptionView::optionId)).toList();
        String fingerprint = hash(CANONICAL.toJson(new FingerprintMaterial("COMBAT_DAMAGE_ORDER_V1", state, views)));
        List<Card> all = new ArrayList<>(heuristic); all.add(source);
        Map<Card, GameEntity> defenders = new LinkedHashMap<>();
        all.stream().filter(combat::isAttacking).forEach(c -> defenders.put(c, combat.getDefenderByAttacker(c)));
        return new Prepared(new CombatDamageOrderDecisionContext(decisionId, fingerprint, state, views), options,
                all.stream().map(c -> new Incarnation(c, c.getGameTimestamp(), c.getController())).toList(),
                defenders, attacking);
    }
    private static void add(List<List<Card>> candidates, List<Card> order, int cap) {
        if (candidates.size() < cap && !candidates.contains(order)) candidates.add(List.copyOf(order));
    }
    static boolean revalidate(Player player, Combat combat, Card source, Prepared prepared, PreparedOption selected) {
        return prepared.options().contains(selected)
                && prepared.incarnations().stream().allMatch(i -> i.card().isInPlay()
                    && i.card().getGameTimestamp() == i.timestamp() && i.card().getController() == i.controller()
                    && player.getGame().getCardState(i.card()) == i.card())
                && supported(player, combat, source, selected.order(), prepared.attacking())
                && prepared.defenders().entrySet().stream().allMatch(e -> combat.isAttacking(e.getKey())
                    && combat.getDefenderByAttacker(e.getKey()) == e.getValue())
                && prepared.context().state().equals(state(player, combat, source, selected.order(), prepared.attacking()));
    }
    static CardCollection choose(Player player, Card source, CardCollection heuristic, boolean attacking,
            AiDecisionProvider provider, int maxOptions) {
        long started = System.nanoTime();
        Combat combat = player.getGame().getCombat();
        Prepared prepared;
        try {
            prepared = prepare(player, combat, source, new ArrayList<>(heuristic), attacking, UUID.randomUUID().toString(), maxOptions);
            if (prepared != null) {
                var original = prepared.context();
                String id = ExternalCombatDiagnosticIds.next(player, AiDecisionType.COMBAT_DAMAGE_ORDER, original.stateFingerprint(), original.decisionId());
                prepared = new Prepared(new CombatDamageOrderDecisionContext(id, original.stateFingerprint(), original.state(), original.options()),
                        prepared.options(), prepared.incarnations(), prepared.defenders(), prepared.attacking());
            }
        }
        catch (RuntimeException ex) { return heuristic; }
        if (prepared == null) return heuristic;
        var correlation = AiDecisionMetrics.combatCorrelation(player.getGame(), combat, AiDecisionType.COMBAT_DAMAGE_ORDER);
        AiDecisionResult result = null;
        AiDecisionFailureReason failure = null;
        PreparedOption selected = null;
        String live = "NOT_RUN";
        long liveRevalidationNanos = 0;
        long providerStarted = System.nanoTime();
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
                    if (selected == null) failure = AiDecisionFailureReason.INVALID_OPTION;
                }
            }
            if (failure == null && selected != null) {
                if (player.getGame().getCombat() != combat) { failure = AiDecisionFailureReason.LIVE_STATE_CHANGED; live = "LIVE_STATE_CHANGED"; }
                else {
                    boolean valid;
                    long liveStarted = System.nanoTime();
                    try { valid = revalidate(player, combat, source, prepared, selected); }
                    catch (RuntimeException ex) { valid = false; }
                    finally { liveRevalidationNanos = System.nanoTime() - liveStarted; }
                    if (!valid) { failure = AiDecisionFailureReason.REVALIDATION_FAILED; live = "LIVE_REVALIDATION_FAILED"; }
                    else live = "PASSED";
                }
            }
        } catch (RuntimeException ex) { failure = AiDecisionFailureReason.PROVIDER_EXCEPTION; }
        long providerElapsed = AiDecisionResult.elapsedMillis(providerStarted);
        boolean fallback = failure != null || selected == null;
        PreparedOption applied = fallback ? prepared.options().get(0) : selected;
        var run = AiDecisionMetrics.gameMetadata(player.getGame().getId());
        AiDecision returned = result == null ? null : result.decision().orElse(result.rejectedDecision());
        AiDecisionMetrics.record(new CombatDamageOrderDecisionEvent(prepared.context().decisionId(), prepared.context().stateFingerprint(),
                player.getGame().getId(), run == null ? player.getGame().getMatch().getOutcomes().size() : run.gameIndex(),
                run == null ? null : run.runSeed(), player.getName(), player.getGame().getRegisteredPlayers().indexOf(player),
                player.getRegisteredPlayer().getDeck().getName(), prepared.heuristicId(), applied.view().optionId(), prepared.options().size(),
                true, fallback ? AiDecisionSource.FORGE_HEURISTIC : AiDecisionSource.EXTERNAL_PROVIDER,
                fallback, failure, failure == AiDecisionFailureReason.LIVE_STATE_CHANGED || failure == AiDecisionFailureReason.REVALIDATION_FAILED,
                result == null ? null : result.provider(), result == null ? null : result.model(),
                result == null ? providerElapsed : result.latencyMs(), AiDecisionResult.elapsedMillis(started),
                returned == null ? null : returned.decisionId(), returned == null ? null : returned.stateFingerprint(),
                returned == null ? null : returned.optionId(), !fallback, live,
                result == null ? null : result.diagnostics(), liveRevalidationNanos,
                correlation.combatSequenceId(), correlation.turnNumber(), correlation.activePlayerSeat(),
                correlation.defendingPlayerSeat(), AiDecisionType.COMBAT_DAMAGE_ORDER), prepared.context());
        return fallback ? heuristic : new CardCollection(applied.order());
    }
    private static String hash(String material) {
        try { return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("Unable to fingerprint damage order", ex); }
    }
}
