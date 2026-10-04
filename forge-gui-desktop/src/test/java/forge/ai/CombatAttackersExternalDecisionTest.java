package forge.ai;

import com.google.gson.JsonParser;
import forge.ai.decision.*;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class CombatAttackersExternalDecisionTest extends AITest {
    record Fixture(Game game, Player ai, Player opponent, Combat combat, Card bear, Card giant) { }
    @BeforeMethod public void resetMetrics() { AiDecisionMetrics.reset(); }

    private Fixture fixture() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card bear = addCard("Runeclaw Bear", ai);
        Card giant = addCard("Hill Giant", ai);
        bear.setSickness(false); giant.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, ai);
        Combat combat = new Combat(ai);
        game.getPhaseHandler().setCombat(combat);
        return new Fixture(game, ai, opponent, combat, bear, giant);
    }

    private PlayerControllerAi controller(Fixture f, AiDecisionProvider provider, boolean enabled, int maxOptions) {
        PlayerControllerAi controller = new PlayerControllerAi(f.game(), f.ai(), f.ai().getLobbyPlayer(), provider,
                false, false, 3, false, 1, false, 3, false, enabled, maxOptions);
        f.ai().dangerouslySetController(controller);
        return controller;
    }

    private AiDecision choose(CombatAttackersDecisionContext context, String id) {
        return new AiDecision(context.decisionId(), context.stateFingerprint(), id);
    }

    private void heuristic(Fixture f) {
        f.combat().addAttacker(f.bear(), f.opponent());
        f.combat().addAttacker(f.giant(), f.opponent());
    }

    @Test public void disabledPathKeepsExistingDeclarationWithoutProvider() {
        Fixture baseline = fixture();
        controller(baseline, null, false, 4).declareAttackers(baseline.ai(), baseline.combat());
        List<String> expected = baseline.combat().getAttackers().stream().map(Card::getName).sorted().toList();
        Fixture disabled = fixture();
        AtomicInteger calls = new AtomicInteger();
        controller(disabled, context -> { calls.incrementAndGet(); return Optional.empty(); }, false, 4)
                .declareAttackers(disabled.ai(), disabled.combat());
        Assert.assertEquals(disabled.combat().getAttackers().stream().map(Card::getName).sorted().toList(), expected);
        Assert.assertEquals(calls.get(), 0);
        Assert.assertTrue(AiDecisionMetrics.combatAttackerEvents().isEmpty());
    }

    @Test public void noProviderOutsideAttackingPhaseOrWhenCombatIsNotCurrent() {
        for (PhaseType phase : List.of(PhaseType.MAIN1, PhaseType.COMBAT_DECLARE_BLOCKERS, PhaseType.COMBAT_DAMAGE)) {
            Fixture f = fixture();
            f.game().getPhaseHandler().devModeSet(phase, f.ai());
            AtomicInteger calls = new AtomicInteger();
            AiDecisionProvider provider = c -> { calls.incrementAndGet(); return Optional.empty(); };
            controller(f, provider, true, 4);
            Assert.assertFalse(ExternalCombatAttackers.eligible(f.ai(), f.combat()));
            ExternalCombatAttackers.choose(f.ai(), f.combat(), provider, 4);
            Assert.assertEquals(calls.get(), 0);
        }
        Fixture f = fixture();
        Combat anotherCombat = new Combat(f.ai());
        Assert.assertFalse(ExternalCombatAttackers.eligible(f.ai(), anotherCombat));
        f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, f.opponent());
        Assert.assertFalse(ExternalCombatAttackers.eligible(f.ai(), f.combat()));
    }

    @Test public void noChoiceAndOneOptionCapNeverCallProvider() {
        for (int maxOptions : List.of(1, 4)) {
            Fixture f = fixture();
            if (maxOptions == 4) { f.bear().setSickness(true); f.giant().setTapped(true); }
            AtomicInteger calls = new AtomicInteger();
            controller(f, c -> { calls.incrementAndGet(); return Optional.empty(); }, true, maxOptions).declareAttackers(f.ai(), f.combat());
            Assert.assertEquals(calls.get(), 0);
            Assert.assertTrue(AiDecisionMetrics.combatAttackerEvents().isEmpty());
        }
    }

    @Test public void noAttackIsACompleteSelectableOption() {
        Fixture f = fixture();
        AtomicReference<CombatAttackersDecisionContext> captured = new AtomicReference<>();
        controller(f, c -> {
            var context = (CombatAttackersDecisionContext) c;
            captured.set(context);
            var none = context.options().stream().filter(o -> CombatAttackersDecisionContext.NO_ATTACK.equals(o.optionId())).findFirst().orElseThrow();
            Assert.assertTrue(none.attackers().isEmpty());
            Assert.assertEquals(none.attackerCount(), 0); Assert.assertEquals(none.totalPower(), 0);
            return Optional.of(choose(context, none.optionId()));
        }, true, 4).declareAttackers(f.ai(), f.combat());
        Assert.assertNotNull(captured.get());
        Assert.assertTrue(f.combat().getAttackers().isEmpty());
        Assert.assertTrue(CombatUtil.validateAttackers(f.combat()));
        Assert.assertTrue(AiDecisionMetrics.combatAttackerEvents().get(0).noAttackSelected());
    }

    @Test public void nonDefaultOptionAppliesExactCreatureAndDefender() {
        Fixture f = fixture();
        AtomicReference<CombatAttackOptionView> selected = new AtomicReference<>();
        AtomicReference<CombatAttackersDecisionContext> captured = new AtomicReference<>();
        controller(f, c -> {
            var context = (CombatAttackersDecisionContext) c;
            captured.set(context);
            var option = context.options().stream().filter(o -> o.attackerCount() == 1).findFirst().orElseThrow();
            selected.set(option);
            return Optional.of(choose(context, option.optionId()));
        }, true, 4).declareAttackers(f.ai(), f.combat());
        Assert.assertEquals(f.combat().getAttackers().size(), 1);
        String attackerId = selected.get().attackers().get(0).attackerId();
        String name = captured.get().state().players().stream().filter(p -> p.self()).findFirst().orElseThrow()
                .battlefield().stream().filter(card -> card.permanentId().equals(attackerId)).findFirst().orElseThrow().card().name();
        Card original = name.equals(f.bear().getName()) ? f.bear() : f.giant();
        Assert.assertSame(f.combat().getAttackers().get(0), original);
        Assert.assertSame(f.combat().getDefenderByAttacker(original), f.opponent());
        var event = AiDecisionMetrics.combatAttackerEvents().get(0);
        Assert.assertEquals(event.selectedOptionId(), selected.get().optionId());
        Assert.assertFalse(event.fallback());
        Assert.assertTrue(CombatUtil.validateAttackers(f.combat()));
    }

    @Test public void planeswalkerAssignmentsAreRetainedExactly() {
        Fixture f = fixture();
        Card walker = addCard("Jace Beleren", f.opponent());
        f.combat().initConstraints();
        controller(f, c -> {
            var context = (CombatAttackersDecisionContext) c;
            String walkerId = context.state().legalDefenders().stream().filter(d -> d.kind().equals("PLANESWALKER")).findFirst().orElseThrow().defenderId();
            var option = context.options().stream().filter(o -> !o.attackers().isEmpty()
                    && o.attackers().stream().allMatch(a -> a.defenderId().equals(walkerId))).findFirst().orElseThrow();
            return Optional.of(choose(context, option.optionId()));
        }, true, 4).declareAttackers(f.ai(), f.combat());
        Assert.assertFalse(f.combat().getAttackers().isEmpty());
        f.combat().getAttackers().forEach(card -> Assert.assertSame(f.combat().getDefenderByAttacker(card), walker));
    }

    @Test public void invalidStaleAndProviderFailuresPreserveOriginalForgeDeclaration() {
        for (String failure : List.of("INVALID", "REQUEST", "FINGERPRINT", "STALE", "UNABLE", "EXCEPTION", "TIMEOUT")) {
            AiDecisionMetrics.reset();
            Fixture f = fixture();
            AtomicReference<Map<Card, GameEntity>> original = new AtomicReference<>();
            AiDecisionProvider provider = new AiDecisionProvider() {
                @Override public Optional<AiDecision> choose(AiDecisionContext c) { return Optional.empty(); }
                @Override public AiDecisionResult chooseWithDiagnostics(AiDecisionContext c) {
                    var context = (CombatAttackersDecisionContext) c;
                    original.set(new LinkedHashMap<>(f.combat().getAttackersAndDefenders()));
                    if (failure.equals("EXCEPTION")) throw new IllegalStateException("test");
                    if (failure.equals("TIMEOUT")) return AiDecisionResult.failed(AiDecisionFailureReason.TIMEOUT, "test", "test", 7);
                    if (failure.equals("STALE")) f.opponent().setLife(19, null);
                    if (failure.equals("UNABLE")) f.bear().setTapped(true);
                    AiDecision decision = new AiDecision(failure.equals("REQUEST") ? "wrong-request" : context.decisionId(),
                            failure.equals("FINGERPRINT") ? "stale-fingerprint" : context.stateFingerprint(),
                            failure.equals("INVALID") ? "OPT_NOT_SUPPLIED" : context.options().get(0).optionId());
                    return AiDecisionResult.accepted(decision, "test", "test", 7);
                }
            };
            controller(f, provider, true, 4).declareAttackers(f.ai(), f.combat());
            Assert.assertEquals(f.combat().getAttackersAndDefenders(), original.get());
            var event = AiDecisionMetrics.combatAttackerEvents().get(0);
            Assert.assertTrue(event.fallback()); Assert.assertEquals(event.selectedOptionId(), event.heuristicOptionId());
            Assert.assertFalse(event.responseAccepted());
            Assert.assertNotNull(event.failureReason());
            if (List.of("STALE", "UNABLE").contains(failure)) Assert.assertTrue(event.staleOrRevalidationFailure());
        }
    }

    @Test public void finalLegalityCheckStillRunsAfterFingerprintValidation() {
        Fixture f = fixture();
        AtomicReference<org.mockito.MockedStatic<CombatUtil>> mocked = new AtomicReference<>();
        AtomicReference<Map<Card, GameEntity>> original = new AtomicReference<>();
        try {
            controller(f, c -> {
                var context = (CombatAttackersDecisionContext) c;
                original.set(new LinkedHashMap<>(f.combat().getAttackersAndDefenders()));
                var mock = Mockito.mockStatic(CombatUtil.class, Mockito.CALLS_REAL_METHODS);
                mocked.set(mock);
                // Board stays byte-for-byte visible-identical; payment support changes only at final legality.
                mock.when(() -> CombatUtil.getAttackCost(Mockito.eq(f.game()), Mockito.any(Card.class), Mockito.any(GameEntity.class)))
                        .thenReturn(new forge.game.cost.Cost("2", true));
                return Optional.of(choose(context, context.options().stream().filter(o -> o.attackerCount() > 0).findFirst().orElseThrow().optionId()));
            }, true, 4).declareAttackers(f.ai(), f.combat());
            Assert.assertEquals(f.combat().getAttackersAndDefenders(), original.get());
            Assert.assertEquals(AiDecisionMetrics.combatAttackerEvents().get(0).failureReason(), AiDecisionFailureReason.REVALIDATION_FAILED);
        } finally { if (mocked.get() != null) mocked.get().close(); }
    }

    @Test public void mandatoryAndForcedAssignmentsRemainForgeOnly() {
        Fixture f = fixture();
        Card juggernaut = addCard("Juggernaut", f.ai()); juggernaut.setSickness(false);
        f.combat().initConstraints();
        AtomicInteger calls = new AtomicInteger();
        controller(f, c -> { calls.incrementAndGet(); return Optional.empty(); }, true, 4).declareAttackers(f.ai(), f.combat());
        Assert.assertEquals(calls.get(), 0);
        Assert.assertTrue(CombatUtil.validateAttackers(f.combat()));
        Fixture forced = fixture();
        forced.combat().addAttacker(forced.bear(), forced.opponent());
        controller(forced, c -> { calls.incrementAndGet(); return Optional.empty(); }, true, 4).declareAttackers(forced.ai(), forced.combat());
        Assert.assertEquals(calls.get(), 0);
    }

    @Test public void tappedAndSummoningSickCreaturesAreNeverIllegalOptions() {
        Fixture f = fixture();
        f.bear().setTapped(true); f.giant().setSickness(true);
        Card ready = addCard("Wind Drake", f.ai()); ready.setSickness(false);
        var prepared = ExternalCombatAttackers.prepare(f.ai(), f.combat(), "projection", 4);
        Assert.assertNotNull(prepared);
        prepared.options().forEach(option -> option.assignments().keySet().forEach(card -> Assert.assertSame(card, ready)));
        ready.setTapped(true);
        Assert.assertFalse(ExternalCombatAttackers.revalidate(f.ai(), f.combat(), prepared.options().stream()
                .filter(option -> option.view().attackerCount() > 0).findFirst().orElseThrow()));
    }

    @Test public void visibleKeywordsAndCombatMetadataExcludeHiddenInformation() {
        Fixture f = fixture();
        Card flying = addCard("Wind Drake", f.ai()); flying.setSickness(false);
        addCard("Giant Spider", f.opponent());
        addCardToZone("Black Lotus", f.opponent(), ZoneType.Hand);
        addCardToZone("Ancestral Recall", f.ai(), ZoneType.Library);
        addCardToZone("Time Walk", f.ai(), ZoneType.Hand);
        Card hidden = addCard("Griselbrand", f.opponent()); hidden.turnFaceDownNoUpdate();
        heuristic(f);
        var prepared = ExternalCombatAttackers.prepare(f.ai(), f.combat(), "projection", 4);
        Assert.assertNotNull(prepared);
        String text = prepared.context().toString();
        for (String secret : List.of("Black Lotus", "Ancestral Recall", "Time Walk", "Griselbrand")) Assert.assertFalse(text.contains(secret));
        var own = prepared.context().state().players().stream().filter(p -> p.self()).findFirst().orElseThrow();
        var other = prepared.context().state().players().stream().filter(p -> !p.self()).findFirst().orElseThrow();
        Assert.assertTrue(own.battlefield().stream().filter(p -> p.card().name().equals("Wind Drake")).findFirst().orElseThrow().keywords().contains("flying"));
        Assert.assertTrue(other.battlefield().stream().filter(p -> p.card().name().equals("Giant Spider")).findFirst().orElseThrow().keywords().contains("reach"));
        Assert.assertNotNull(own.mana()); Assert.assertNull(other.mana());
    }

    @Test public void idsAndFingerprintAreDeterministicAndTrackVisibleOptions() {
        Fixture f = fixture(); heuristic(f);
        var first = ExternalCombatAttackers.prepare(f.ai(), f.combat(), "first", 4);
        var repeat = ExternalCombatAttackers.prepare(f.ai(), f.combat(), "another-request", 4);
        Assert.assertEquals(first.context().stateFingerprint(), repeat.context().stateFingerprint());
        Assert.assertEquals(first.context().options(), repeat.context().options());
        first.context().options().forEach(option -> Assert.assertTrue(option.optionId().equals("NO_ATTACK") || option.optionId().matches("OPT_[0-9A-F]{24}")));
        Fixture replay = fixture(); heuristic(replay);
        Assert.assertEquals(first.context().stateFingerprint(), ExternalCombatAttackers.prepare(replay.ai(), replay.combat(), "replay", 4).context().stateFingerprint());
        Assert.assertNotEquals(first.context().stateFingerprint(), ExternalCombatAttackers.prepare(f.ai(), f.combat(), "smaller", 2).context().stateFingerprint());
        f.opponent().setLife(19, null);
        Assert.assertNotEquals(first.context().stateFingerprint(), ExternalCombatAttackers.refresh(f.ai(), f.combat(), first).stateFingerprint());
    }

    @Test(timeOut = 5000) public void largeBoardUsesBoundedVariantsWithoutSubsetSearch() {
        Fixture f = fixture();
        for (Card card : addCards("Runeclaw Bear", 30, f.ai())) { card.setSickness(false); f.combat().addAttacker(card, f.opponent()); }
        heuristic(f);
        var constraints = Mockito.spy(f.combat().getAttackConstraints());
        var combat = Mockito.spy(f.combat());
        Mockito.doReturn(constraints).when(combat).getAttackConstraints();
        f.game().getPhaseHandler().setCombat(combat);
        var prepared = ExternalCombatAttackers.prepare(f.ai(), combat, "large", 1000);
        Assert.assertNotNull(prepared); Assert.assertTrue(prepared.options().size() <= ExternalCombatAttackers.HARD_MAX_OPTIONS);
        Assert.assertEquals(ExternalCombatAttackers.prepare(f.ai(), combat, "bounded", 4).options().size(), 4);
        Mockito.verify(constraints, Mockito.never()).getLegalAttackers();
    }

    @Test public void incarnationAndControllerChangesFailExactIdentityRevalidation() {
        Fixture f = fixture(); heuristic(f);
        var prepared = ExternalCombatAttackers.prepare(f.ai(), f.combat(), "identity", 4);
        var attacking = prepared.options().stream().filter(option -> option.view().attackerCount() > 0).findFirst().orElseThrow();
        String fingerprint = prepared.context().stateFingerprint();
        f.bear().setGameTimestamp(f.game().getNextTimestamp());
        Assert.assertEquals(ExternalCombatAttackers.refresh(f.ai(), f.combat(), prepared).stateFingerprint(), fingerprint);
        Assert.assertFalse(ExternalCombatAttackers.revalidate(f.ai(), f.combat(), attacking));
        Fixture changed = fixture(); heuristic(changed);
        var before = ExternalCombatAttackers.prepare(changed.ai(), changed.combat(), "controller", 4);
        var option = before.options().stream().filter(o -> o.view().attackerCount() > 0).findFirst().orElseThrow();
        changed.bear().setController(changed.opponent(), changed.game().getNextTimestamp());
        Assert.assertFalse(ExternalCombatAttackers.revalidate(changed.ai(), changed.combat(), option));
    }

    @Test public void battleTargetsRetainTheirProtectingPlayer() {
        Fixture f = fixture();
        Card battle = addCard("Invasion of Zendikar", f.ai()); battle.setProtectingPlayer(f.opponent());
        f.combat().initConstraints();
        var prepared = ExternalCombatAttackers.prepare(f.ai(), f.combat(), "battle", 4);
        var target = prepared.context().state().legalDefenders().stream().filter(d -> d.kind().equals("BATTLE")).findFirst().orElseThrow();
        Assert.assertEquals(target.controllerId(), "PLAYER_1"); Assert.assertEquals(target.defendingPlayerId(), "PLAYER_0");
        var option = prepared.options().stream().filter(o -> o.assignments().containsValue(battle)).findFirst().orElseThrow();
        Assert.assertTrue(ExternalCombatAttackers.revalidate(f.ai(), f.combat(), option));
        battle.setProtectingPlayer(f.ai());
        Assert.assertFalse(ExternalCombatAttackers.revalidate(f.ai(), f.combat(), option));
    }

    @Test public void multiplayerAndAttackCostsDoNotRoute() {
        Game multiplayer = initAndCreateThreePlayerGame();
        Player ai = multiplayer.getPlayers().get(1);
        multiplayer.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, ai);
        Combat combat = new Combat(ai); multiplayer.getPhaseHandler().setCombat(combat);
        Assert.assertNull(ExternalCombatAttackers.prepare(ai, combat, "multiplayer", 4));
        Fixture f = fixture();
        addCard("Propaganda", f.opponent());
        f.game().getAction().checkStaticAbilities();
        f.combat().initConstraints();
        Assert.assertNull(ExternalCombatAttackers.prepare(f.ai(), f.combat(), "costs", 4));
    }

    @Test public void combatMetricsCsvAndAuditRetainOutcomeAndInternalMapping() throws Exception {
        var audit = Files.createTempFile("forge-combat-", ".jsonl");
        var csv = Files.createTempFile("forge-combat-", ".csv");
        try {
            AiDecisionMetrics.configureDecisionAudit(audit);
            Fixture f = fixture(); AiDecisionMetrics.registerGame(f.game().getId(), 7, 1000L);
            controller(f, c -> {
                var context = (CombatAttackersDecisionContext) c;
                return Optional.of(choose(context, "NO_ATTACK"));
            }, true, 4).declareAttackers(f.ai(), f.combat());
            AiDecisionMetrics.completeGame(f.game().getId(), f.ai().getName(), false, 123, 9);
            var json = JsonParser.parseString(Files.readString(audit)).getAsJsonObject();
            Assert.assertEquals(json.get("decisionType").getAsString(), "COMBAT_ATTACKERS");
            Assert.assertTrue(json.has("modelVisibleDecision")); Assert.assertTrue(json.has("internalMapping"));
            Assert.assertTrue(json.get("decidingPlayerWon").getAsBoolean());
            Assert.assertEquals(json.getAsJsonObject("telemetry").get("selectedOptionId").getAsString(), "NO_ATTACK");
            Assert.assertEquals(json.getAsJsonObject("telemetry").get("runSeed").getAsLong(), 1000L);
            Assert.assertTrue(AiDecisionMetrics.summary(1).contains("combatAttackerDecisions=1"));
            AiDecisionMetrics.writeCsv(csv);
            Assert.assertTrue(Files.readString(csv).contains("COMBAT_ATTACKERS"));
            List<String> csvLines = Files.readAllLines(csv);
            Assert.assertEquals(csvLines.get(1).split(",", -1).length, csvLines.get(0).split(",", -1).length);
        } finally { AiDecisionMetrics.reset(); Files.deleteIfExists(audit); Files.deleteIfExists(csv); }
    }
}
