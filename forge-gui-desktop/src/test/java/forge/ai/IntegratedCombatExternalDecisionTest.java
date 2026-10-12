package forge.ai;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import forge.ai.decision.*;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.event.*;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.util.*;

public class IntegratedCombatExternalDecisionTest extends AITest {
    record Fixture(Game game, Player attacker, Player defender, Combat combat, Card giant, Card second,
            List<Card> bears) { }
    @BeforeMethod public void resetMetrics() { AiDecisionMetrics.reset(); }
    private Fixture fixture(boolean twoPairs) {
        Game game = initAndCreateGame(); Player attacker = game.getPlayers().get(0), defender = game.getPlayers().get(1);
        game.getRules().setOrderCombatants(true);
        Card giant = addCard("Hill Giant", attacker), second = addCard("Canyon Minotaur", attacker);
        giant.setSickness(false); second.setSickness(false);
        List<Card> bears = new ArrayList<>();
        for (String name : twoPairs ? List.of("Runeclaw Bear", "Grizzly Bears", "Bear Cub", "Forest Bear")
                : List.of("Runeclaw Bear", "Grizzly Bears")) bears.add(addCard(name, defender));
        AiDecisionMetrics.registerGame(game.getId(), 7, 1234L);
        AiDecisionMetrics.observeCombatSequences(game);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN, attacker, false, 4);
        Combat combat = new Combat(attacker); game.getPhaseHandler().setCombat(combat);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, attacker, false, 4);
        combat.addAttacker(giant, defender); combat.addAttacker(second, defender);
        return new Fixture(game, attacker, defender, combat, giant, second, bears);
    }
    private void phase(Fixture f, PhaseType phase) {
        f.game().getPhaseHandler().devModeSet(phase, f.attacker(), false, 4);
    }
    private void controllers(Fixture f, AiDecisionProvider provider) {
        for (Player player : List.of(f.attacker(), f.defender())) {
            var controller = new PlayerControllerAi(f.game(), player, player.getLobbyPlayer(), provider,
                    false, false, 3, false, 0, false, 3, false, true, 4, true, 8, true, 6);
            player.dangerouslySetController(controller);
        }
    }
    private void fireAttackers(Fixture f) {
        Multimap<GameEntity, Card> map = HashMultimap.create();
        f.combat().getAttackersAndDefenders().forEach((card, defender) -> map.put(defender, card));
        f.game().fireEvent(new GameEventAttackersDeclared(f.attacker(), map));
    }
    private void fireBlockers(Fixture f) {
        Multimap<Card, Card> map = HashMultimap.create();
        for (Card attacker : f.combat().getAttackers()) {
            if (f.combat().getBlockers(attacker).isEmpty()) map.put(attacker, attacker);
            else for (Card blocker : f.combat().getBlockers(attacker)) map.put(attacker, blocker);
        }
        f.game().fireEvent(new GameEventBlockersDeclared(f.defender(), Map.of(f.defender(), map)));
    }
    private final class Provider implements AiDecisionProvider {
        final Fixture f;
        final AiDecisionType failing;
        final boolean twoPairs;
        final List<AiDecisionContext> requests = new ArrayList<>();
        final Map<Card, List<Card>> selectedOrders = new HashMap<>();
        Provider(Fixture f, AiDecisionType failing, boolean twoPairs) { this.f = f; this.failing = failing; this.twoPairs = twoPairs; }
        @Override public Optional<AiDecision> choose(AiDecisionContext context) { return chooseWithDiagnostics(context).decision(); }
        @Override public AiDecisionResult chooseWithDiagnostics(AiDecisionContext context) {
            requests.add(context);
            String json = new Gson().toJson(context);
            for (String hidden : List.of("Griselbrand", "Black Lotus", "combatSequenceId", "beforeState", "afterState"))
                Assert.assertFalse(json.contains(hidden), hidden);
            String option, fingerprint;
            if (context instanceof CombatAttackersDecisionContext c) {
                // Choose a supplied single attacker, or retain both for the two-pair scenario.
                option = c.options().stream().filter(o -> o.attackers().size() == (twoPairs ? 2 : 1))
                        .findFirst().orElseThrow().optionId(); fingerprint = c.stateFingerprint();
            } else if (context instanceof CombatBlockersDecisionContext c) {
                Assert.assertEquals(c.state().attackers().stream().map(a -> a.permanent().card().name()).sorted().toList(),
                        f.combat().getAttackers().stream().map(Card::getName).sorted().toList());
                option = c.options().stream().filter(o -> o.assignments().size() == (twoPairs ? 4 : 2))
                        .findFirst().orElseThrow().optionId(); fingerprint = c.stateFingerprint();
            } else {
                var c = (CombatDamageOrderDecisionContext) context;
                Card source = f.combat().getAttackers().stream().filter(a -> a.getName().equals(c.state().source().name())).findFirst().orElseThrow();
                Assert.assertEquals(c.state().recipients().stream().map(CombatDamageOrderDecisionContext.Creature::name).sorted().toList(),
                        f.combat().getBlockers(source).stream().map(Card::getName).sorted().toList());
                List<Card> order = new ArrayList<>(AiBlockController.orderBlockers(source, new CardCollection(f.combat().getBlockers(source))));
                Collections.reverse(order); selectedOrders.put(source, List.copyOf(order));
                List<String> summaries = order.stream().map(card -> card.getName() + " " + card.getNetPower() + "/" + card.getNetToughness()).toList();
                option = c.options().stream().filter(o -> o.orderedRecipientSummaries().equals(summaries)).findFirst().orElseThrow().optionId();
                fingerprint = c.stateFingerprint();
            }
            return context.type() == failing ? AiDecisionResult.failed(AiDecisionFailureReason.TIMEOUT, "fake", "test", 10)
                    : AiDecisionResult.accepted(new AiDecision(context.decisionId(), fingerprint, option), "fake", "test", 10);
        }
    }
    private Provider run(Fixture f, AiDecisionType failing, boolean twoPairs) throws Exception {
        addCardToZone("Griselbrand", f.defender(), ZoneType.Hand); addCardToZone("Black Lotus", f.attacker(), ZoneType.Library);
        Provider provider = new Provider(f, failing, twoPairs); controllers(f, provider);
        ExternalCombatAttackers.choose(f.attacker(), f.combat(), provider, 4);
        Assert.assertTrue(f.combat().getAttackConstraints().countViolations(f.combat().getAttackersAndDefenders()) == 0);
        var appliedAttackers = new ArrayList<>(f.combat().getAttackers()); fireAttackers(f);
        phase(f, PhaseType.COMBAT_DECLARE_BLOCKERS);
        // The original Forge declaration is a legal double block; fallback must preserve it.
        Card blocked = appliedAttackers.get(0);
        f.combat().addBlocker(blocked, f.bears().get(0)); f.combat().addBlocker(blocked, f.bears().get(1));
        if (twoPairs) {
            f.combat().addBlocker(appliedAttackers.get(1), f.bears().get(2));
            f.combat().addBlocker(appliedAttackers.get(1), f.bears().get(3));
        }
        ExternalCombatBlockers.choose(f.defender(), f.combat(), provider, 8);
        Assert.assertEquals(new ArrayList<>(f.combat().getAttackers()), appliedAttackers);
        Assert.assertNull(CombatUtil.validateBlocks(f.combat(), f.defender())); fireBlockers(f);
        f.combat().orderBlockersForDamageAssignment();
        var field = Combat.class.getDeclaredField("blockersOrderedForDamageAssignment"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var orders = (com.google.common.base.Supplier<Map<Card, CardCollection>>) field.get(f.combat());
        if (failing != AiDecisionType.COMBAT_DAMAGE_ORDER) provider.selectedOrders.forEach((source, expected) ->
                Assert.assertEquals(orders.get().get(source), new CardCollection(expected)));
        Assert.assertEquals(provider.requests.size(), twoPairs ? 4 : 3);
        Assert.assertEquals(provider.requests.stream().map(AiDecisionContext::decisionId).distinct().count(), (long) provider.requests.size());
        return provider;
    }
    @Test public void independentChoicesSeeAppliedDeclarationsAndShareOneSequence() throws Exception {
        Fixture f = fixture(false); Provider provider = run(f, null, false);
        phase(f, PhaseType.COMBAT_END);
        String id = AiDecisionMetrics.combatAttackerEvents().get(0).combatSequenceId();
        Assert.assertEquals(AiDecisionMetrics.combatBlockerEvents().get(0).combatSequenceId(), id);
        Assert.assertEquals(AiDecisionMetrics.combatDamageOrderEvents().get(0).combatSequenceId(), id);
        var sequence = AiDecisionMetrics.combatSequences().get(0);
        Assert.assertEquals(sequence.combatSequenceId(), id); Assert.assertEquals(sequence.providerCallCount(), 3);
        Assert.assertEquals(sequence.declaredAttackerCount(), 1); Assert.assertEquals(sequence.declaredBlockerCount(), 2);
        Assert.assertEquals(sequence.attackersBlocked(), 1); Assert.assertEquals(sequence.unblockedAttackers(), 0);
        Assert.assertEquals(sequence.beforeState().attackerBattlefieldCreatures(), 2);
        Assert.assertEquals(sequence.beforeState().defenderBattlefieldCreatures(), 2);
        Assert.assertEquals(sequence.decisions().size(), provider.requests.size()); Assert.assertTrue(sequence.coherenceIssues().isEmpty());
        Assert.assertEquals(sequence.turnNumber(), 4); Assert.assertEquals(sequence.activePlayerSeat(), 0); Assert.assertEquals(sequence.defendingPlayerSeat(), 1);
    }
    @Test public void partialFallbacksDoNotRollBackOtherSurfacesAndAggregateExactly() throws Exception {
        for (AiDecisionType failing : List.of(AiDecisionType.COMBAT_ATTACKERS, AiDecisionType.COMBAT_BLOCKERS, AiDecisionType.COMBAT_DAMAGE_ORDER)) {
            AiDecisionMetrics.reset(); Fixture f = fixture(false); run(f, failing, false); phase(f, PhaseType.COMBAT_END);
            var sequence = AiDecisionMetrics.combatSequences().get(0);
            Assert.assertEquals(sequence.fallbackCount(), 1); Assert.assertEquals(sequence.providerCallCount(), 3);
            Assert.assertEquals(sequence.totalProviderLatencyMs(), 30L);
            Assert.assertEquals(sequence.decisions().stream().filter(d -> d.accepted()).count(), 2L);
            Assert.assertEquals(sequence.declaredAttackerCount(), failing == AiDecisionType.COMBAT_ATTACKERS ? 2 : 1);
            var metrics = AiDecisionMetrics.integratedCombatMetrics();
            Assert.assertEquals(metrics.integratedCombatSequences(), 1L); Assert.assertEquals(metrics.combatsWithAllThreeSurfaces(), 1L);
            Assert.assertEquals(metrics.totalCombatProviderCalls(), 3L); Assert.assertEquals(metrics.combatSequencesWithFallback(), 1L);
            Assert.assertEquals(metrics.combatSequenceFallbackCount(), 1L); Assert.assertEquals(metrics.avgProviderCallsPerCombat(), 3.0);
            Assert.assertEquals(metrics.avgProviderLatencyPerCombat(), 30.0);
            Assert.assertEquals(metrics.p50ProviderLatencyPerCombat(), 30L); Assert.assertEquals(metrics.p95ProviderLatencyPerCombat(), 30L);
            Assert.assertEquals(AiDecisionMetrics.combatAttackerEvents().size(), 1); Assert.assertEquals(AiDecisionMetrics.combatBlockerEvents().size(), 1);
            Assert.assertEquals(AiDecisionMetrics.combatDamageOrderEvents().size(), 1);
        }
    }
    @Test public void multipleDamageOrdersAndNextCombatHaveCorrectIdentity() throws Exception {
        Fixture f = fixture(true); run(f, null, true); phase(f, PhaseType.COMBAT_END);
        var orders = AiDecisionMetrics.combatDamageOrderEvents(); Assert.assertEquals(orders.size(), 2);
        Assert.assertEquals(orders.get(0).combatSequenceId(), orders.get(1).combatSequenceId());
        phase(f, PhaseType.COMBAT_BEGIN);
        Combat next = new Combat(f.attacker()); f.game().getPhaseHandler().setCombat(next);
        var nextId = AiDecisionMetrics.combatCorrelation(f.game(), next, AiDecisionType.COMBAT_ATTACKERS).combatSequenceId();
        Assert.assertNotEquals(nextId, orders.get(0).combatSequenceId()); phase(f, PhaseType.COMBAT_END);
        var metrics = AiDecisionMetrics.integratedCombatMetrics();
        Assert.assertEquals(metrics.integratedCombatSequences(), 2L); Assert.assertEquals(metrics.totalCombatProviderCalls(), 4L);
        Assert.assertEquals(metrics.avgProviderCallsPerCombat(), 2.0); Assert.assertEquals(metrics.p50ProviderLatencyPerCombat(), 0L);
        Assert.assertEquals(metrics.p95ProviderLatencyPerCombat(), 40L);
    }
    @Test public void compactAuditLinksDecisionRecordsAndCapturesDamageDeathsAndLifeLoss() throws Exception {
        var path = Files.createTempFile("integrated-combat", ".jsonl");
        try {
            AiDecisionMetrics.configureDecisionAudit(path);
            Fixture f = fixture(false); run(f, AiDecisionType.COMBAT_ATTACKERS, false);
            phase(f, PhaseType.COMBAT_DAMAGE);
            f.defender().setLife(17, null);
            f.game().fireEvent(new GameEventPlayerDamaged(f.defender().getView(), f.second().getView(), 3, true, false));
            f.attacker().setLife(18, null); f.attacker().setLife(20, null);
            f.game().getAction().moveToGraveyard(f.bears().get(0), null); f.game().getAction().moveToGraveyard(f.giant(), null);
            phase(f, PhaseType.COMBAT_END);
            var sequence = AiDecisionMetrics.combatSequences().get(0); var after = sequence.afterState();
            Assert.assertEquals(after.defenderLifeLost(), 3); Assert.assertEquals(after.activePlayerLifeLost(), 2);
            Assert.assertEquals(after.combatDamageToDefender(), 3); Assert.assertEquals(after.creaturesDied().size(), 2);
            Assert.assertEquals(after.attackersSurviving(), 1); Assert.assertEquals(after.blockersSurviving(), 1); Assert.assertFalse(after.lethal());
            AiDecisionMetrics.completeGame(f.game().getId(), f.attacker().getName(), false, 100, 4);
            var rows = Files.readAllLines(path).stream().map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
            Assert.assertEquals(rows.size(), 4);
            var summary = rows.stream().filter(j -> j.get("decisionType").getAsString().equals("COMBAT_SEQUENCE")).findFirst().orElseThrow();
            Assert.assertFalse(summary.has("modelVisibleDecision")); Assert.assertEquals(summary.getAsJsonArray("decisions").size(), 3);
            for (var row : rows) if (row.has("telemetry"))
                Assert.assertEquals(row.getAsJsonObject("telemetry").get("combatSequenceId").getAsString(), sequence.combatSequenceId());
            for (String hidden : List.of("Griselbrand", "Black Lotus")) Assert.assertFalse(Files.readString(path).contains(hidden));
        } finally { AiDecisionMetrics.configureDecisionAudit(null); Files.deleteIfExists(path); }
    }
    @Test public void interruptedCombatWithEarlierDamageRemainsIncomplete() throws Exception {
        Fixture f = fixture(false); run(f, null, false); phase(f, PhaseType.COMBAT_FIRST_STRIKE_DAMAGE);
        f.game().fireEvent(new GameEventPlayerDamaged(f.defender().getView(), f.giant().getView(), 1, true, false));
        f.game().fireEvent(new GameEventGameOutcome(4, List.of(), "Draw", "test"));
        Assert.assertFalse(AiDecisionMetrics.combatSequences().get(0).damageCompleted());
        Assert.assertFalse(AiDecisionMetrics.combatSequences().get(0).afterState().lethal());
    }
    @Test public void skippingToMainPhaseClosesCombatBeforeUnrelatedOutcomes() throws Exception {
        Fixture f = fixture(false); run(f, null, false);
        phase(f, PhaseType.MAIN2);
        var sequence = AiDecisionMetrics.combatSequences().get(0);
        Assert.assertFalse(sequence.damageCompleted());
        Assert.assertEquals(sequence.completionReason(), "COMBAT_PHASES_INTERRUPTED");
        f.defender().setLife(12, null);
        f.game().getAction().moveToGraveyard(f.bears().get(0), null);
        phase(f, PhaseType.COMBAT_BEGIN);
        Assert.assertEquals(AiDecisionMetrics.combatSequences().size(), 1);
        Assert.assertEquals(sequence.afterState().defenderLifeLost(), 0);
        Assert.assertTrue(sequence.afterState().creaturesDied().isEmpty());
    }

    @Test public void replacedCombatGetsNewIdentityWithoutReusingRequests() throws Exception {
        Fixture f = fixture(false); run(f, null, false);
        String firstId = AiDecisionMetrics.combatAttackerEvents().get(0).combatSequenceId();
        Combat replacement = new Combat(f.attacker()); f.game().getPhaseHandler().setCombat(replacement);
        var correlation = AiDecisionMetrics.combatCorrelation(f.game(), replacement, AiDecisionType.COMBAT_ATTACKERS);
        Assert.assertNotEquals(correlation.combatSequenceId(), firstId);
        Assert.assertEquals(AiDecisionMetrics.combatSequences().get(0).completionReason(), "COMBAT_REPLACED");
        Assert.assertFalse(AiDecisionMetrics.combatSequences().get(0).damageCompleted());
        phase(f, PhaseType.COMBAT_END);
        Assert.assertEquals(AiDecisionMetrics.combatSequences().size(), 2);
    }

    @Test public void completedGameObserverIgnoresLaterEvents() throws Exception {
        Fixture f = fixture(false); run(f, null, false); phase(f, PhaseType.COMBAT_END);
        AiDecisionMetrics.completeGame(f.game().getId(), f.attacker().getName(), false, 100, 4);
        phase(f, PhaseType.COMBAT_BEGIN); phase(f, PhaseType.COMBAT_END);
        Assert.assertEquals(AiDecisionMetrics.combatSequences().size(), 1);
    }

    @Test public void lethalCombatIsRecordedEvenWithoutEndCombatPhase() throws Exception {
        Fixture f = fixture(false); run(f, null, false); phase(f, PhaseType.COMBAT_DAMAGE);
        f.defender().setLife(0, null);
        f.game().fireEvent(new GameEventPlayerDamaged(f.defender().getView(), f.giant().getView(), 20, true, false));
        f.game().fireEvent(new GameEventGameOutcome(4, List.of(), f.attacker().getName(), "test"));
        Assert.assertTrue(AiDecisionMetrics.combatSequences().get(0).afterState().lethal());
        Assert.assertEquals(AiDecisionMetrics.integratedCombatMetrics().combatSequencesEndingLethal(), 1L);
        AiDecisionMetrics.completeGame(f.game().getId(), f.attacker().getName(), false, 100, 4);
        Assert.assertEquals(AiDecisionMetrics.combatSequences().size(), 1);
    }
}
