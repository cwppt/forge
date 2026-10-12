package forge.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import forge.ai.decision.*;
import forge.game.GameObject;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Proactive priority only. Never participates in declaration or damage-assignment callbacks. */
final class ExternalCombatActions {
    static final int HARD_CAP = 8;
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static final Set<ApiType> SAFE = EnumSet.of(ApiType.Pump, ApiType.Protection,
            ApiType.DealDamage, ApiType.Destroy, ApiType.ChangeZone, ApiType.PermanentCreature,
            ApiType.PreventDamage, ApiType.Regenerate);
    private final Map<String, Boolean> seen = new LinkedHashMap<>();
    private Combat previousCombat;
    record Incarnation(Card card, long timestamp, Player controller, ZoneType zone) { }
    record Prepared(CombatActionDecisionContext context, Map<String, PreparedAiAction> mapping,
            List<Incarnation> incarnations, Combat combat, Map<String,String> signatures) {
        Prepared { mapping = Collections.unmodifiableMap(new LinkedHashMap<>(mapping)); incarnations = List.copyOf(incarnations); signatures = Map.copyOf(signatures); }
    }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String ref(Card card) { return "PERM_" + hash(Integer.toString(card.getId())).substring(0, 24); }
    static boolean window(Player player) {
        PhaseType phase = player.getGame().getPhaseHandler().getPhase();
        return player.isInGame() && player.getGame().getPhaseHandler().getPriorityPlayer() == player
                && player.getGame().getStack().isEmpty()
                && (phase == PhaseType.COMBAT_DECLARE_ATTACKERS || phase == PhaseType.COMBAT_DECLARE_BLOCKERS
                    || phase == PhaseType.COMBAT_FIRST_STRIKE_DAMAGE);
    }
    static List<Card> combatants(Combat combat) {
        Set<Card> cards = new LinkedHashSet<>(combat.getAttackers());
        combat.getAttackers().forEach(a -> cards.addAll(combat.getBlockers(a)));
        return cards.stream().sorted(Comparator.comparing(ExternalCombatActions::ref)).toList();
    }
    static boolean supported(Player player, Combat combat) {
        if (!window(player) || combat == null || player.getGame().getCombat() != combat
                || player.getGame().getPlayers().size() != 2 || player.getGame().getRegisteredPlayers().size() != 2
                || combat.getAttackers().isEmpty()) return false;
        for (Card c : combatants(combat)) {
            if (!c.isInPlay() || c.isFaceDown() || !c.getView().canBeShownTo(player.getView())
                    || c.hasKeyword(Keyword.BANDING) || c.hasKeyword(Keyword.BANDSWITH)
                    || c.getNetCombatDamage() != c.getNetPower() || c.isWitherDamage()
                    || c.getReplacementEffects().stream().anyMatch(r -> !irrelevantReplacement(c, r))
                    || c.getKeywords().stream().anyMatch(k -> k.getOriginal().toLowerCase(Locale.ROOT).contains("assign"))) return false;
            if (combat.isAttacking(c) && (!(combat.getDefenderByAttacker(c) instanceof Player)
                    || combat.getBandOfAttacker(c).getAttackers().size() != 1
                    || forge.game.staticability.StaticAbilityAssignCombatDamageAsUnblocked.assignCombatDamageAsUnblocked(c)
                    || forge.game.staticability.StaticAbilityAssignCombatDamageAsUnblocked.assignCombatDamageAsUnblocked(c, false))) return false;
        }
        // Global replacement effects and alternate assignment are outside this small tactical surface.
        return player.getGame().getPlayers().stream().noneMatch(p -> p.hasKeyword("You assign combat damage of each creature attacking you."))
                && player.getGame().getCardsIn(ZoneType.Battlefield).stream().noneMatch(c ->
                    !combatants(combat).contains(c) && c.getReplacementEffects().stream()
                        .anyMatch(r -> !irrelevantReplacement(c, r)));
    }
    static boolean irrelevantReplacement(Card card, forge.game.replacement.ReplacementEffect replacement) {
        // Protection is local to its host and is already supported on combatants.
        // Self-entry replacements on a permanent already in play do not participate
        // in this combat. Newly cast flash creatures with replacements remain excluded.
        return replacement.isKeyword(Keyword.PROTECTION) || card.isInPlay()
                && replacement.getMode() == forge.game.replacement.ReplacementType.Moved
                && "Card.Self".equals(replacement.getParam("ValidCard"))
                && "Battlefield".equals(replacement.getParam("Destination"));
    }
    static boolean safeTemplate(SpellAbility sa) {
        if (!ExternalAiAbilitySupport.allowed(sa, AiDecisionType.COMBAT_ACTION)) return false;
        if (sa == null || sa.getHostCard() == null || sa.isManaAbility() || sa.isTrigger()
                || sa.isReplacementAbility() || sa.isLandAbility() || sa.getRestrictions().isSorcerySpeed()
                || (sa.getSplicedCards() != null && !sa.getSplicedCards().isEmpty())) return false;
        if (!sa.isSpell() && !sa.isActivatedAbility()) return false;
        if (sa.getApi() == ApiType.PermanentCreature && (!sa.isSpell() || !sa.getHostCard().isCreature()
                || !sa.getHostCard().hasKeyword(Keyword.FLASH) || !sa.getHostCard().getTriggers().isEmpty()
                || !sa.getHostCard().getReplacementEffects().isEmpty() || sa.getSubAbility() != null)) return false;
        if (sa.getPayCosts() == null || sa.getPayCosts().getCostParts().stream().anyMatch(c ->
                !(c instanceof forge.game.cost.CostPartMana) && !(c instanceof forge.game.cost.CostTap)
                && !(c instanceof forge.game.cost.CostUntap))) return false;
        for (SpellAbility part = sa; part != null; part = part.getSubAbility()) {
            if (part.getApi() == ApiType.Charm) {
                if (part != sa || part.hasParam("Chooser") || part.hasParam("Random") || part.hasParam("Optional") || part.hasParam("TargetingPlayer")) return false;
                continue; // Only fully chosen, whitelisted chained modes survive relevant().
            }
            if (!SAFE.contains(part.getApi()) || part.getMapParams().keySet().stream().anyMatch(k ->
                    k.contains("Choice") || k.contains("Choose") || k.contains("Optional") || k.contains("Unless")
                    || k.contains("Random") || k.equals("Radiance") || k.equals("TargetingPlayer"))
                    || part.getMapParams().values().stream().anyMatch(v -> v.contains("Chosen") || v.contains("Remembered") || v.contains("Imprinted"))
                    || part.getSVars().values().stream().anyMatch(v -> v.contains("Library") || v.contains("Hand"))) return false;
            if (part.getApi() == ApiType.ChangeZone && !"Battlefield".equals(part.getParam("Origin"))) return false;
            if (part.getApi() == ApiType.Protection && part.hasParam("Gains")
                    && part.getParam("Gains").contains("Choice")) return false;
        }
        return true;
    }
    static boolean relevant(PreparedAiAction action, Combat combat) {
        if (!safeTemplate(action.spellAbility())) return false;
        List<Card> involved = combatants(combat);
        if (action.spellAbility().getApi() == ApiType.PermanentCreature) {
            Card creature = action.sourceCard();
            // A simple flash creature can become a blocker only before blockers are declared.
            return creature.getGame().getPhaseHandler().getPhase() == PhaseType.COMBAT_DECLARE_ATTACKERS
                    && creature.getController() != combat.getAttackingPlayer()
                    && combat.getAttackers().stream().anyMatch(a -> !a.hasKeyword(Keyword.FLYING)
                        || creature.hasKeyword(Keyword.FLYING) || creature.hasKeyword(Keyword.REACH));
        }
        if (action.spellAbility().getApi() == ApiType.Charm && (action.spellAbility().getChosenList() == null
                || action.spellAbility().getChosenList().isEmpty() || action.spellAbility().getSubAbility() == null)) return false;
        boolean affects = false;
        for (SpellAbility part = action.spellAbility(); part != null; part = part.getSubAbility()) {
            if (part.getApi() == ApiType.Charm) continue;
            if (part.usesTargeting()) {
                if (!part.isTargetNumberValid()) return false;
                for (GameObject target : part.getTargets()) {
                    if (!(target instanceof Card c) || !involved.contains(c)) return false;
                    affects = true;
                }
            } else {
                // Self-only activated tricks are safe; arbitrary Defined expressions are not.
                if (!"Self".equals(part.getParam("Defined")) || !involved.contains(action.sourceCard())) return false;
                affects = true;
            }
        }
        return affects;
    }
    static CombatActionOptionView view(Player player, PreparedAiAction action, String id) {
        LegalActionView safe = MainPhaseStateProjector.actionView(player, action, false);
        List<String> targets = new ArrayList<>();
        for (SpellAbility sa = action.spellAbility(); sa != null; sa = sa.getSubAbility())
            for (GameObject t : sa.getTargets()) targets.add(t instanceof Card c ? ref(c) + " " + c.getName() : "Player");
        targets.sort(String::compareTo);
        List<String> optional = new ArrayList<>();
        action.spellAbility().getOptionalCosts().forEach(c -> optional.add(c.name()));
        action.sourceCard().getKeywords().stream().filter(action.spellAbility()::hasOptionalKeywordAmount)
                .forEach(k -> optional.add(k.getOriginal() + "=" + action.spellAbility().getOptionalKeywordAmount(k)));
        optional.sort(String::compareTo);
        // Description strings can contain engine target IDs. Use whitelisted effect parameters instead.
        List<String> effects = new ArrayList<>();
        for (SpellAbility sa = action.spellAbility(); sa != null; sa = sa.getSubAbility()) {
            StringBuilder effect = new StringBuilder(sa.getApi().name());
            if (sa.getApi() == ApiType.PermanentCreature) effect.append(" flash creature ")
                    .append(action.sourceCard().getNetPower()).append('/').append(action.sourceCard().getNetToughness())
                    .append(' ').append(action.sourceCard().getKeywords().stream().map(k -> k.getOriginal()).sorted().toList());
            for (String key : List.of("NumAtt", "NumDef", "KW", "NumDmg", "Destination", "Gains", "CounterType", "CounterNum", "Amount", "Duration")) {
                if (!sa.hasParam(key)) continue;
                String value = sa.getParam(key);
                if (List.of("NumAtt", "NumDef", "NumDmg", "CounterNum", "Amount").contains(key)) {
                    try { value = Integer.toString(forge.game.ability.AbilityUtils.calculateAmount(action.sourceCard(), value, sa)); }
                    catch (RuntimeException ignored) { /* Preserve the safe expression if Forge cannot summarize it. */ }
                }
                effect.append(' ').append(key).append('=').append(value);
            }
            effects.add(effect.toString());
        }
        return new CombatActionOptionView(id, ref(action.sourceCard()), safe.sourceName(), safe.category(), safe.cost(),
                targets, String.join("; ", effects), safe.modes(), safe.xValue(), optional);
    }
    private static CombatActionDecisionContext.Permanent permanent(Player perspective, Card c, Combat combat) {
        return new CombatActionDecisionContext.Permanent(ref(c), c.getName(),
                perspective.getGame().getPlayers().indexOf(c.getController()), c.isCreature() ? c.getNetPower() : null,
                c.isCreature() ? c.getNetToughness() : null, c.getDamage(), c.isTapped(),
                c.getCounters().entrySet().stream().sorted(Comparator.comparing(e -> e.getElement().getName()))
                    .map(e -> new MainPhaseCounterView(e.getElement().getName(), e.getCount())).toList(),
                c.getKeywords().stream().map(k -> k.getOriginal()).distinct().sorted().toList(),
                combat.isAttacking(c) ? combat.getDefenderByAttacker(c).getName() : null,
                combat.getAttackersBlockedBy(c).stream().map(ExternalCombatActions::ref).sorted().toList(),
                c.getStaticAbilities().stream().filter(ExternalCombatActions::relevantStatic)
                    .map(a -> a.getParamOrDefault("Description", "Visible static effect").replace("CARDNAME", c.getName())).sorted().toList());
    }
    private static boolean relevantStatic(forge.game.staticability.StaticAbility ability) {
        var mode = ability.getMode();
        return mode.contains(forge.game.staticability.StaticAbilityMode.RaiseCost)
                || mode.contains(forge.game.staticability.StaticAbilityMode.ReduceCost)
                || mode.contains(forge.game.staticability.StaticAbilityMode.CantBeCast)
                || mode.contains(forge.game.staticability.StaticAbilityMode.CantBeActivated)
                || mode.contains(forge.game.staticability.StaticAbilityMode.CantTarget)
                || (mode.contains(forge.game.staticability.StaticAbilityMode.Continuous)
                    && List.of("AddPower", "AddToughness", "SetPower", "SetToughness", "AddKeyword", "RemoveKeyword")
                        .stream().anyMatch(ability::hasParam));
    }
    private static boolean playableMana(Card source, Player controller) {
        return source.getManaAbilities().stream().anyMatch(a -> {
            SpellAbility copy = a.copy(controller); copy.setActivatingPlayer(controller); return copy.canPlay();
        });
    }
    static CombatActionDecisionContext.State state(Player player, Combat combat, Collection<PreparedAiAction> actions) {
        List<Card> involved = combatants(combat);
        Set<Card> relevant = new LinkedHashSet<>();
        actions.forEach(a -> { if (a.sourceCard().isInPlay() && !involved.contains(a.sourceCard())) relevant.add(a.sourceCard()); });
        player.getGame().getCardsIn(ZoneType.Battlefield).stream().filter(c -> !involved.contains(c)
                && !c.isFaceDown() && c.getView().canBeShownTo(player.getView())
                && c.getStaticAbilities().stream().anyMatch(ExternalCombatActions::relevantStatic)).forEach(relevant::add);
        var players = player.getGame().getPlayers();
        return new CombatActionDecisionContext.State(player.getGame().getPhaseHandler().getTurn(),
                player.getGame().getPhaseHandler().getPhase().name(), players.indexOf(player),
                players.indexOf(combat.getAttackingPlayer()), players.indexOf(combat.getAttackingPlayer().getOpponents().get(0)),
                players.stream().map(Player::getLife).toList(), involved.stream().map(c -> permanent(player, c, combat)).toList(),
                relevant.stream().sorted(Comparator.comparing(ExternalCombatActions::ref)).map(c -> permanent(player, c, combat)).toList(),
                players.stream().map(p -> new CombatActionDecisionContext.Mana(players.indexOf(p), MainPhaseStateProjector.mana(p.getManaPool()),
                    p.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getView().canBeShownTo(player.getView()) && playableMana(c, p))
                        .map(c -> ref(c) + " " + c.getName()).sorted().toList())).toList());
    }
    static String fingerprint(CombatActionDecisionContext.State state, List<CombatActionOptionView> options) {
        return hash("COMBAT_ACTION_V1|PASS|" + JSON.toJson(state) + JSON.toJson(options));
    }
    private static String signature(PreparedAiAction action) {
        List<Map<String,String>> parts = new ArrayList<>();
        for (SpellAbility sa = action.spellAbility(); sa != null; sa = sa.getSubAbility()) parts.add(new TreeMap<>(sa.getMapParams()));
        return hash(JSON.toJson(parts));
    }
    private static String outcome(Player player, PreparedAiAction action) {
        var v = view(player, action, "");
        return JSON.toJson(List.of(v.sourceName(), v.category(), v.cost(), v.targets(), v.effect(),
                v.modes(), Objects.toString(v.xValue(), "none"), v.optionalCosts(), signature(action)));
    }
    static Map<String, Integer> selectionCounts(Player player, Combat combat, List<PreparedAiAction> actions, Prepared exposed) {
        Set<String> distinct = new HashSet<>();
        int relevant = 0;
        for (var action : actions) if (relevant(action, combat)) {
            relevant++; distinct.add(outcome(player, action));
        }
        int selected = exposed == null ? 0 : exposed.mapping().size();
        return Map.of("relevantPrepared", relevant, "duplicates", relevant - distinct.size(),
                "distinctPrepared", distinct.size(), "exposed", selected,
                "boundedOut", exposed == null ? 0 : Math.max(0, distinct.size() - selected));
    }
    static Prepared snapshot(Player player, Combat combat, List<PreparedAiAction> actions, int max) {
        Map<String, PreparedAiAction> mapping = new TreeMap<>();
        Set<String> outcomes = new HashSet<>();
        // Bound after deduplication; the preferred Forge action is inserted by the caller first.
        for (PreparedAiAction a : actions) {
            if (!relevant(a, combat)) continue;
            var v = view(player, a, "");
            if (!outcomes.add(outcome(player, a))) continue;
            String id = "OPT_" + hash(JSON.toJson(v) + signature(a));
            mapping.putIfAbsent(id, a);
            if (mapping.size() >= Math.min(HARD_CAP, Math.max(1, max))) break;
        }
        if (mapping.isEmpty()) return null;
        List<CombatActionOptionView> options = mapping.entrySet().stream().map(e -> view(player, e.getValue(), e.getKey())).toList();
        var state = state(player, combat, mapping.values());
        var memory=ExternalAiStrategicMemory.snapshot(player,AiDecisionType.COMBAT_ACTION,options.stream().flatMap(a->java.util.stream.Stream.concat(java.util.stream.Stream.of(a.sourceName()),a.targets().stream())).toList());
        var context = new CombatActionDecisionContext(UUID.randomUUID().toString(), ExternalAiStrategicMemory.fingerprint(fingerprint(state, options),memory), state, options, true,memory);
        Set<Card> cards = new LinkedHashSet<>(combatants(combat));
        mapping.values().forEach(a -> cards.add(a.sourceCard()));
        Map<String,String> signatures = new TreeMap<>(); mapping.forEach((id,a) -> signatures.put(id, signature(a)));
        return new Prepared(context, mapping, cards.stream().map(c -> new Incarnation(c, c.getGameTimestamp(), c.getController(), c.getZone().getZoneType())).toList(), combat, signatures);
    }
    static boolean revalidate(AiController ai, Player player, Prepared prepared, PreparedAiAction selected, boolean pass) {
        if (player.getGame().getCombat() != prepared.combat() || !window(player)
                || !prepared.context().state().window().equals(player.getGame().getPhaseHandler().getPhase().name())) return false;
        if (pass) return true;
        if (!prepared.mapping().containsValue(selected) || !supported(player, prepared.combat())
                || prepared.mapping().entrySet().stream().anyMatch(e -> !signature(e.getValue()).equals(prepared.signatures().get(e.getKey())))) return false;
        if (prepared.incarnations().stream().anyMatch(i -> player.getGame().getCardState(i.card()) != i.card()
                || i.card().getGameTimestamp() != i.timestamp() || i.card().getController() != i.controller()
                || i.card().getZone() == null || i.card().getZone().getZoneType() != i.zone())) return false;
        List<CombatActionOptionView> current = prepared.mapping().entrySet().stream().map(e -> view(player, e.getValue(), e.getKey())).toList();
        var memory=ExternalAiStrategicMemory.snapshot(player,AiDecisionType.COMBAT_ACTION,current.stream().flatMap(a->java.util.stream.Stream.concat(java.util.stream.Stream.of(a.sourceName()),a.targets().stream())).toList());
        return prepared.context().stateFingerprint().equals(ExternalAiStrategicMemory.fingerprint(fingerprint(state(player, prepared.combat(), prepared.mapping().values()), current),memory))
                && selected.sourceCard().getController() == player && ai.revalidatePreparedAction(selected);
    }
    SpellAbility choose(AiController ai, Player player, AiDecisionProvider provider, List<SpellAbility> candidates,
            PreparedAiAction heuristic, SpellAbility legacy, int cap) {
        long started = System.nanoTime();
        Combat combat = player.getGame().getCombat();
        if (Boolean.getBoolean("forge.ai.combatAction.diagnostics") && window(player))
            org.tinylog.Logger.info("combatActionWindow player={} supported={} combatants={} candidates={}", player.getName(),
                    supported(player, combat), combat == null ? 0 : combatants(combat).size(), candidates.size());
        if (!supported(player, combat)) {
            CombatActionEligibilityAudit.record(player, candidates, window(player) ? "UNSUPPORTED_COMBAT" : "WINDOW_EXCLUDED", List.of(), null);
            return legacy;
        }
        if (previousCombat != combat) { seen.clear(); previousCombat = combat; }
        String key = hash(JSON.toJson(state(player, combat, List.of())) + candidates.stream()
                .filter(ExternalCombatActions::safeTemplate).map(sa -> ref(sa.getHostCard()) + sa.getDescription()).sorted().toList());
        if (seen.containsKey(key)) {
            CombatActionEligibilityAudit.record(player, candidates, "UNCHANGED_PRIORITY", List.of(), null);
            return seen.get(key) ? null : legacy;
        }
        Prepared prepared;
        try {
            List<SpellAbility> safe = candidates.stream().filter(ExternalCombatActions::safeTemplate).limit(24).toList();
            if (Boolean.getBoolean("forge.ai.combatAction.diagnostics")) org.tinylog.Logger.info("combatActionSafeTemplates count={}",safe.size());
            if (safe.isEmpty()) {
                CombatActionEligibilityAudit.record(player, candidates, "NO_SUPPORTED_TEMPLATE", List.of(), null);
                return legacy;
            }
            List<PreparedAiAction> actions = new ArrayList<>();
            if (heuristic != null && relevant(heuristic, combat)) actions.add(heuristic);
            actions.addAll(ai.prepareCombatActionCandidates(safe, Math.min(HARD_CAP, Math.max(1, cap))).actions());
            prepared = snapshot(player, combat, actions, cap);
            CombatActionEligibilityAudit.record(player, candidates, prepared == null ? "NO_PREPARED_COMBAT_ACTION" : "PROVIDER_CALLED", actions, prepared);
            if (Boolean.getBoolean("forge.ai.combatAction.diagnostics")) org.tinylog.Logger.info("combatActionPreparation actions={} relevant={}",actions.size(),prepared == null ? 0 : prepared.mapping().size());
        } catch (RuntimeException ex) {
            CombatActionEligibilityAudit.record(player, candidates, "PREPARATION_EXCEPTION", List.of(), null);
            if (Boolean.getBoolean("forge.ai.combatAction.diagnostics")) org.tinylog.Logger.warn(ex,"combatActionPreparation failed");
            return legacy;
        }
        if (prepared == null) return legacy;
        seen.put(key, false);
        var correlation = AiDecisionMetrics.combatCorrelation(player.getGame(), combat, AiDecisionType.COMBAT_ACTION);
        String heuristicId = legacy == null ? "PASS" : prepared.mapping().entrySet().stream()
                .filter(e -> e.getValue() == heuristic || e.getValue().spellAbility() == legacy)
                .map(Map.Entry::getKey).findFirst().orElse("FORGE_UNEXPOSED_ACTION");
        AiDecisionResult result = null;
        AiDecisionFailureReason failure = null;
        String returned = null;
        PreparedAiAction selected = null;
        boolean pass = false, stale = false;
        long providerStarted = System.nanoTime();
        try {
            result = provider.chooseWithDiagnostics(prepared.context());
            AiDecision decision = result == null ? null : result.decision().orElse(null);
            if (result != null && result.rejectedDecision() != null) returned = result.rejectedDecision().optionId();
            if (decision == null) failure = result == null || result.failureReason() == null
                    ? AiDecisionFailureReason.PROVIDER_EXCEPTION : result.failureReason();
            else {
                returned = decision.optionId();
                if (!prepared.context().decisionId().equals(decision.decisionId())) failure = AiDecisionFailureReason.DECISION_ID_MISMATCH;
                else if (!prepared.context().stateFingerprint().equals(decision.stateFingerprint())) failure = AiDecisionFailureReason.FINGERPRINT_MISMATCH;
                else if ("PASS".equals(returned)) pass = true;
                else if ((selected = prepared.mapping().get(returned)) == null) failure = AiDecisionFailureReason.INVALID_OPTION;
            }
            // Identity is completely checked against the immutable snapshot before any live inspection.
            if (failure == null) {
                boolean valid;
                try { valid = revalidate(ai, player, prepared, selected, pass); }
                catch (RuntimeException ex) { valid = false; }
                if (!valid) { stale = true; failure = AiDecisionFailureReason.REVALIDATION_FAILED; }
            }
        } catch (RuntimeException ex) { failure = AiDecisionFailureReason.PROVIDER_EXCEPTION; }
        long providerMs = result == null ? AiDecisionResult.elapsedMillis(providerStarted) : result.latencyMs();
        boolean fallback = failure != null;
        String selectedId = fallback ? heuristicId : returned;
        if (!fallback && pass) seen.put(key, true);
        AiDecisionMetrics.record(new CombatActionDecisionEvent(prepared.context().decisionId(), prepared.context().stateFingerprint(),
                correlation.combatSequenceId(), player.getGame().getId(), prepared.context().state().turn(),
                prepared.context().state().window(), prepared.context().state().decidingSeat(),
                result == null ? null : result.provider(), result == null ? null : result.model(), prepared.mapping().size(),
                heuristicId, returned, selectedId, !fallback && pass, fallback, failure, stale, providerMs,
                AiDecisionResult.elapsedMillis(started), stale ? "FAILED" : fallback ? "NOT_RUN"
                    : pass ? "PASS_PRIORITY_VALID" : "ACTION_VALID", null), prepared.context());
        if (!fallback && !pass) ai.retainPreparedCombatAction(selected.spellAbility());
        return fallback ? legacy : pass ? null : selected.spellAbility();
    }
}
