package forge.ai;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import forge.ai.decision.*;
import forge.game.Game;
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

public class CombatBlockersExternalDecisionTest extends AITest {
    record Fixture(Game game, Player defender, Player enemy, Combat combat, Card attacker, Card bear, Card second) { }
    @BeforeMethod public void reset() { AiDecisionMetrics.reset(); }
    private Fixture fixture(String attackerName, String blockerName) {
        Game game = initAndCreateGame(); Player defender = game.getPlayers().get(1), enemy = game.getPlayers().get(0);
        Card attacker = addCard(attackerName, enemy), bear = addCard(blockerName, defender), second = addCard("Grizzly Bears", defender);
        attacker.setSickness(false); bear.setSickness(false); second.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, enemy);
        Combat combat = new Combat(enemy); game.getPhaseHandler().setCombat(combat); combat.addAttacker(attacker, defender);
        return new Fixture(game, defender, enemy, combat, attacker, bear, second);
    }
    private Fixture fixture() { return fixture("Hill Giant", "Runeclaw Bear"); }
    private PlayerControllerAi controller(Fixture f, AiDecisionProvider provider, boolean enabled) {
        PlayerControllerAi result = new PlayerControllerAi(f.game(), f.defender(), f.defender().getLobbyPlayer(), provider,
                false, false, 4, false, 0, false, 3, false, false, 4, enabled, 4);
        f.defender().dangerouslySetController(result); return result;
    }
    private AiDecision response(CombatBlockersDecisionContext context, String id) {
        return new AiDecision(context.decisionId(), context.stateFingerprint(), id);
    }
    private List<String> declaration(Fixture f) {
        return f.combat().getBlockers(f.attacker()).stream().map(Card::getName).sorted().toList();
    }
    private ExternalCombatBlockers.Prepared prepare(Fixture f, int cap) {
        return ExternalCombatBlockers.prepare(f.defender(), f.combat(), "request", cap);
    }

    @Test public void protectedDefenderRetainsNativeDeclarationWithoutProviderCall() {
        for (int life : List.of(0, 20)) {
            Fixture baseline = fixture("Ankle Biter", "Platinum Angel");
            baseline.defender().setLife(life, null);
            forge.util.MyRandom.withRandom(new Random(1000), () -> {
                controller(baseline, null, false).declareBlockers(baseline.defender(), baseline.combat()); return null;
            });
            var expected = declaration(baseline);
            Fixture external = fixture("Ankle Biter", "Platinum Angel");
            external.defender().setLife(life, null);
            AtomicInteger calls = new AtomicInteger();
            forge.util.MyRandom.withRandom(new Random(1000), () -> {
                controller(external, c -> { calls.incrementAndGet(); throw new AssertionError("Protected combat must bypass provider"); }, true)
                        .declareBlockers(external.defender(), external.combat()); return null;
            });
            Assert.assertEquals(declaration(external), expected);
            Assert.assertEquals(calls.get(), 0);
            Assert.assertTrue(AiDecisionMetrics.combatBlockerEvents().isEmpty());
        }
    }
    @Test public void newlyProtectedDefenderRejectsPreparedExternalOption() {
        Fixture f = fixture();
        var prepared = prepare(f, 4);
        Assert.assertNotNull(prepared);
        addCard("Platinum Angel", f.defender());
        Assert.assertFalse(ExternalCombatBlockers.revalidate(f.defender(), f.combat(), prepared, prepared.options().get(0)));
    }
    @Test public void losingNoBlockFallsBackToSurvivingNativeDeclaration() {
        Fixture f = fixture(); f.defender().setLife(3, null);
        f.combat().addBlocker(f.attacker(), f.bear());
        ExternalCombatBlockers.choose(f.defender(), f.combat(), c -> Optional.of(response((CombatBlockersDecisionContext)c, "NO_BLOCK")), 4);
        Assert.assertEquals(declaration(f), List.of("Runeclaw Bear"));
        var event = AiDecisionMetrics.combatBlockerEvents().get(0);
        Assert.assertTrue(event.fallback());
        Assert.assertEquals(event.failureReason(), AiDecisionFailureReason.UNSAFE_COMBAT_CHOICE);
        Assert.assertEquals(event.liveRevalidationResult(), "COMBAT_SAFETY_REJECTED");
    }
    @Test public void nonlethalNoBlockRemainsExternalChoice() {
        Fixture f = fixture(); f.defender().setLife(4, null);
        f.combat().addBlocker(f.attacker(), f.bear());
        ExternalCombatBlockers.choose(f.defender(), f.combat(), c -> Optional.of(response((CombatBlockersDecisionContext)c, "NO_BLOCK")), 4);
        Assert.assertTrue(declaration(f).isEmpty());
        Assert.assertFalse(AiDecisionMetrics.combatBlockerEvents().get(0).fallback());
    }
    @Test public void dangerInBothDeclarationsDoesNotCauseSafetyFallback() {
        Fixture f = fixture(); f.defender().setLife(1, null);
        f.attacker().addIntrinsicKeyword("Trample");
        f.combat().addBlocker(f.attacker(), f.bear());
        ExternalCombatBlockers.choose(f.defender(), f.combat(), c -> Optional.of(response((CombatBlockersDecisionContext)c, "NO_BLOCK")), 4);
        Assert.assertTrue(declaration(f).isEmpty());
        Assert.assertFalse(AiDecisionMetrics.combatBlockerEvents().get(0).fallback());
    }
    @Test public void experimentalMaterialGuardRespectsSurvivalTradesAndTriggers() {
        String previous = System.getProperty(ExternalCombatBlockers.MATERIAL_GUARD_PROPERTY);
        try {
            System.setProperty(ExternalCombatBlockers.MATERIAL_GUARD_PROPERTY, "true");
            for (String kind : List.of("losing", "first-strike", "lethal-defense", "trade", "profitable", "trigger")) {
                AiDecisionMetrics.reset();
                Fixture f = fixture("Hill Giant", kind.equals("trigger") ? "Striped Bears" : "Runeclaw Bear");
                f.defender().setLife(kind.equals("lethal-defense") ? 3 : 20, null);
                if (kind.equals("first-strike")) f.attacker().addIntrinsicKeyword("First strike");
                if (kind.equals("trade")) f.attacker().setBaseToughness(2);
                if (kind.equals("profitable")) {f.bear().setBasePower(4); f.bear().setBaseToughness(4);}
                ExternalCombatBlockers.choose(f.defender(), f.combat(), c -> {
                    var context = (CombatBlockersDecisionContext)c;
                    var option = context.options().stream().filter(o -> o.assignments().size() == 1
                            && o.assignments().get(0).blockerId().equals(context.state().blockers().stream()
                            .filter(b -> b.permanent().card().name().equals(f.bear().getName()))
                            .findFirst().orElseThrow().permanent().permanentId())).findFirst().orElseThrow();
                    return Optional.of(response(context, option.optionId()));
                }, 8);
                var event = AiDecisionMetrics.combatBlockerEvents().get(0);
                boolean reject = kind.equals("losing") || kind.equals("first-strike");
                Assert.assertEquals(event.fallback(), reject, kind);
                Assert.assertEquals(declaration(f).isEmpty(), reject, kind);
                if (reject) {
                    Assert.assertEquals(event.failureReason(), AiDecisionFailureReason.MATERIAL_COMBAT_CHOICE);
                    Assert.assertEquals(event.liveRevalidationResult(), "COMBAT_MATERIAL_REJECTED");
                }
            }
        } finally {
            if (previous == null) System.clearProperty(ExternalCombatBlockers.MATERIAL_GUARD_PROPERTY);
            else System.setProperty(ExternalCombatBlockers.MATERIAL_GUARD_PROPERTY, previous);
        }
    }
    @Test public void raceAwareMaterialGuardPreservesRaceAndLifeBufferBlocks() {
        String oldGuard = System.getProperty(ExternalCombatBlockers.MATERIAL_GUARD_PROPERTY);
        String oldRace = System.getProperty(ExternalCombatBlockers.RACE_AWARE_MATERIAL_GUARD_PROPERTY);
        try {
            System.setProperty(ExternalCombatBlockers.MATERIAL_GUARD_PROPERTY, "true");
            System.setProperty(ExternalCombatBlockers.RACE_AWARE_MATERIAL_GUARD_PROPERTY, "true");
            for (String kind : List.of("captured-race", "no-race", "buffer-boundary", "buffer-spare", "trade", "disabled")) {
                AiDecisionMetrics.reset();
                Fixture f = fixture(kind.startsWith("buffer") ? "Hill Giant" : "Llanowar Behemoth", "Benalish Knight");
                f.second().setTapped(true);
                f.defender().setLife(kind.startsWith("buffer") ? (kind.equals("buffer-boundary") ? 6 : 7) : 17, null);
                f.enemy().setLife(kind.equals("captured-race") ? 6 : 20, null);
                Card finisher = addCard("Benalish Knight", f.defender());
                finisher.setBasePower(6); finisher.setBaseToughness(6); finisher.setTapped(true);
                if (kind.equals("trade")) f.bear().setBasePower(4);
                if (kind.equals("disabled")) System.clearProperty(ExternalCombatBlockers.MATERIAL_GUARD_PROPERTY);
                else System.setProperty(ExternalCombatBlockers.MATERIAL_GUARD_PROPERTY, "true");
                ExternalCombatBlockers.choose(f.defender(), f.combat(), c -> {
                    var context = (CombatBlockersDecisionContext)c;
                    var option = context.options().stream().filter(o -> o.assignments().size() == 1
                            && o.assignments().get(0).blockerId().equals(context.state().blockers().stream()
                            .filter(b -> b.permanent().card().name().equals(f.bear().getName()))
                            .findFirst().orElseThrow().permanent().permanentId())).findFirst().orElseThrow();
                    return Optional.of(response(context, option.optionId()));
                }, 8);
                boolean reject = kind.equals("no-race") || kind.equals("buffer-spare");
                var event = AiDecisionMetrics.combatBlockerEvents().get(0);
                Assert.assertEquals(event.fallback(), reject, kind);
                Assert.assertEquals(declaration(f).isEmpty(), reject, kind);
                if (reject) Assert.assertEquals(event.failureReason(), AiDecisionFailureReason.MATERIAL_COMBAT_CHOICE, kind);
            }
        } finally {
            if (oldGuard == null) System.clearProperty(ExternalCombatBlockers.MATERIAL_GUARD_PROPERTY);
            else System.setProperty(ExternalCombatBlockers.MATERIAL_GUARD_PROPERTY, oldGuard);
            if (oldRace == null) System.clearProperty(ExternalCombatBlockers.RACE_AWARE_MATERIAL_GUARD_PROPERTY);
            else System.setProperty(ExternalCombatBlockers.RACE_AWARE_MATERIAL_GUARD_PROPERTY, oldRace);
        }
    }
    @Test public void seededDiagnosticIdsRepeatAcrossGamesButRemainUniqueWithinGame() {
        String previous = System.getProperty(ExternalCombatBlockers.DIAGNOSTIC_IDS_PROPERTY);
        try {
            System.setProperty(ExternalCombatBlockers.DIAGNOSTIC_IDS_PROPERTY, "true");
            Fixture first = fixture(), second = fixture(), otherSeed = fixture();
            var prepared = prepare(first, 4);
            AiDecisionMetrics.registerGame(first.game().getId(), 0, 102L);
            AiDecisionMetrics.registerGame(second.game().getId(), 0, 102L);
            AiDecisionMetrics.registerGame(otherSeed.game().getId(), 0, 103L);
            var a = ExternalCombatBlockers.diagnosticIdentity(first.defender(), prepared);
            var b = ExternalCombatBlockers.diagnosticIdentity(second.defender(), prepared);
            var repeat = ExternalCombatBlockers.diagnosticIdentity(first.defender(), prepared);
            var differentSeed = ExternalCombatBlockers.diagnosticIdentity(otherSeed.defender(), prepared);
            Assert.assertEquals(a.context().decisionId(), b.context().decisionId());
            Assert.assertNotEquals(a.context().decisionId(), repeat.context().decisionId());
            Assert.assertNotEquals(a.context().decisionId(), differentSeed.context().decisionId());
            Assert.assertEquals(a.context().stateFingerprint(), prepared.context().stateFingerprint());
            Assert.assertSame(a.context().state(), prepared.context().state());
            Assert.assertEquals(a.context().options(), prepared.context().options());
            System.clearProperty(ExternalCombatBlockers.DIAGNOSTIC_IDS_PROPERTY);
            Assert.assertSame(ExternalCombatBlockers.diagnosticIdentity(first.defender(), prepared), prepared);
            System.setProperty(ExternalCombatBlockers.DIAGNOSTIC_IDS_PROPERTY, "true");
            AiDecisionMetrics.reset();
            Assert.assertSame(ExternalCombatBlockers.diagnosticIdentity(first.defender(), prepared), prepared);
        } finally {
            if (previous == null) System.clearProperty(ExternalCombatBlockers.DIAGNOSTIC_IDS_PROPERTY);
            else System.setProperty(ExternalCombatBlockers.DIAGNOSTIC_IDS_PROPERTY, previous);
        }
    }
    @Test public void disabledPathMatchesForgeWithoutCallingProvider() {
        Fixture baseline = fixture();
        forge.util.MyRandom.withRandom(new Random(1000), () -> {
            controller(baseline, null, false).declareBlockers(baseline.defender(), baseline.combat()); return null;
        });
        var expected = declaration(baseline); Fixture disabled = fixture(); AtomicInteger calls = new AtomicInteger();
        forge.util.MyRandom.withRandom(new Random(1000), () -> {
            controller(disabled, c -> { calls.incrementAndGet(); return Optional.empty(); }, false)
                    .declareBlockers(disabled.defender(), disabled.combat()); return null;
        });
        Assert.assertEquals(declaration(disabled), expected); Assert.assertEquals(calls.get(), 0);
    }
    @Test public void outsidePhaseWrongDefenderAndNoChoicesBypassProvider() {
        Fixture f = fixture(); AtomicInteger calls = new AtomicInteger();
        AiDecisionProvider provider = c -> { calls.incrementAndGet(); return Optional.empty(); };
        f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_FIRST_STRIKE_DAMAGE, f.enemy());
        ExternalCombatBlockers.choose(f.defender(), f.combat(), provider, 4);
        f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, f.enemy());
        ExternalCombatBlockers.choose(f.enemy(), f.combat(), provider, 4);
        f.bear().setTapped(true); f.second().setTapped(true);
        ExternalCombatBlockers.choose(f.defender(), f.combat(), provider, 4);
        Assert.assertEquals(calls.get(), 0); Assert.assertNull(prepare(f, 4));
        Assert.assertNull(prepare(fixture(), 1));
    }
    @Test public void noBlockIsLiteralAndClearsCompleteForgeDeclaration() {
        Fixture f = fixture(); f.combat().addBlocker(f.attacker(), f.bear());
        ExternalCombatBlockers.choose(f.defender(), f.combat(), c -> {
            var context = (CombatBlockersDecisionContext)c;
            Assert.assertTrue(context.options().stream().anyMatch(o -> o.optionId().equals("NO_BLOCK") && o.assignments().isEmpty()));
            return Optional.of(response(context, "NO_BLOCK"));
        }, 4);
        Assert.assertTrue(declaration(f).isEmpty()); Assert.assertNull(CombatUtil.validateBlocks(f.combat(), f.defender()));
        Assert.assertTrue(AiDecisionMetrics.combatBlockerEvents().get(0).noBlockSelected());
    }
    @Test public void normalControllerRoutesAndAppliesExactNonDefaultMapping() {
        Fixture f = fixture(); AtomicReference<CombatBlockOptionView> chosen = new AtomicReference<>();
        AtomicReference<CombatBlockersDecisionContext> snapshot = new AtomicReference<>();
        controller(f, c -> {
            var context = (CombatBlockersDecisionContext)c; snapshot.set(context);
            var option = context.options().stream().skip(1).filter(o -> !o.assignments().isEmpty()).findFirst().orElseThrow();
            chosen.set(option); return Optional.of(response(context, option.optionId()));
        }, true).declareBlockers(f.defender(), f.combat());
        var idToName = new HashMap<String, String>();
        snapshot.get().state().blockers().forEach(b -> idToName.put(b.permanent().permanentId(), b.permanent().card().name()));
        Assert.assertEquals(declaration(f), chosen.get().assignments().stream().map(a -> idToName.get(a.blockerId())).sorted().toList());
        Assert.assertNull(CombatUtil.validateBlocks(f.combat(), f.defender()));
        Assert.assertFalse(AiDecisionMetrics.combatBlockerEvents().get(0).fallback());
    }
    @Test public void multiBlockAndMenaceRequirementsRemainExact() {
        Fixture f = fixture("Boggart Brute", "Runeclaw Bear");
        var prepared = prepare(f, 8); Assert.assertNotNull(prepared);
        Assert.assertTrue(prepared.options().stream().anyMatch(o -> o.view().blockerCount() == 2));
        Assert.assertTrue(prepared.options().stream().noneMatch(o -> o.view().blockerCount() == 1));
        ExternalCombatBlockers.choose(f.defender(), f.combat(), c -> {
            var context = (CombatBlockersDecisionContext)c;
            var pair = context.options().stream().filter(o -> o.blockerCount() == 2).findFirst().orElseThrow();
            Assert.assertEquals(pair.blockedAttackerCount(), 1);
            return Optional.of(response(context, pair.optionId()));
        }, 4);
        Assert.assertEquals(f.combat().getBlockers(f.attacker()).size(), 2);
        Assert.assertNull(CombatUtil.validateBlocks(f.combat(), f.defender()));
    }
    @Test public void flyingReachTappedAndProtectionUseForgeLegality() {
        Fixture ground = fixture("Wind Drake", "Runeclaw Bear"); Assert.assertNull(prepare(ground, 4));
        Fixture reach = fixture("Wind Drake", "Giant Spider");
        var prepared = prepare(reach, 4); Assert.assertNotNull(prepared);
        Assert.assertEquals(prepared.context().state().blockers().size(), 1);
        Assert.assertTrue(prepared.context().state().blockers().get(0).permanent().keywords().contains("reach"));
        reach.bear().setTapped(true); Assert.assertNull(prepare(reach, 4));
        Fixture protection = fixture("White Knight", "Black Knight"); protection.second().setTapped(true);
        Assert.assertFalse(CombatUtil.canBlock(protection.attacker(), protection.bear()));
        Assert.assertNull(prepare(protection, 4));
        protection.second().setTapped(false); var safe = prepare(protection, 4); Assert.assertNotNull(safe);
        Assert.assertTrue(safe.context().state().attackers().get(0).permanent().keywords().stream().anyMatch(k -> k.startsWith("Protection")));
        Assert.assertTrue(safe.options().stream().flatMap(o -> o.assignments().stream()).noneMatch(a -> a.blocker() == protection.bear()));
    }
    @Test public void mandatoryBlocksAndBandingBypassExternalSelection() {
        Fixture forced = fixture(); forced.bear().addMustBlockCard(1L, forced.attacker());
        forced.combat().addBlocker(forced.attacker(), forced.bear()); Assert.assertNull(prepare(forced, 4));
        Fixture banding = fixture(); banding.bear().addIntrinsicKeyword("Banding"); Assert.assertNull(prepare(banding, 4));
    }
    @Test public void multipleAttackersRequireExplicitMultiBlockPermission() {
        Fixture normal = fixture(); Card other = addCard("Hill Giant", normal.enemy());
        normal.combat().addAttacker(other, normal.defender());
        normal.combat().addBlocker(normal.attacker(), normal.bear()); normal.combat().addBlocker(other, normal.bear());
        Assert.assertNull(prepare(normal, 4));
        Fixture allowed = fixture("Hill Giant", "Palace Guard"); Card another = addCard("Hill Giant", allowed.enemy());
        allowed.game().getAction().checkStaticAbilities();
        Assert.assertTrue(allowed.bear().canBlockAny());
        allowed.combat().addAttacker(another, allowed.defender());
        allowed.combat().addBlocker(allowed.attacker(), allowed.bear()); allowed.combat().addBlocker(another, allowed.bear());
        var prepared = prepare(allowed, 4); Assert.assertNotNull(prepared);
        Assert.assertEquals(prepared.options().get(0).view().blockerCount(), 1);
        Assert.assertEquals(prepared.options().get(0).view().blockedAttackerCount(), 2);
        Assert.assertTrue(ExternalCombatBlockers.revalidate(allowed.defender(), allowed.combat(), prepared, prepared.options().get(0)));
    }
    @Test public void unsupportedCostsBypassAndNewCostsFailLiveRevalidation() {
        Fixture f = fixture();
        try (var mocked = Mockito.mockStatic(CombatUtil.class, Mockito.CALLS_REAL_METHODS)) {
            mocked.when(() -> CombatUtil.getBlockCost(Mockito.eq(f.game()), Mockito.any(Card.class), Mockito.any(Card.class)))
                    .thenReturn(new forge.game.cost.Cost("2", false));
            Assert.assertNull(prepare(f, 4));
        }
        AtomicReference<org.mockito.MockedStatic<CombatUtil>> changed = new AtomicReference<>();
        try {
            ExternalCombatBlockers.choose(f.defender(), f.combat(), c -> {
                var context = (CombatBlockersDecisionContext)c;
                var mock = Mockito.mockStatic(CombatUtil.class, Mockito.CALLS_REAL_METHODS); changed.set(mock);
                mock.when(() -> CombatUtil.getBlockCost(Mockito.eq(f.game()), Mockito.any(Card.class), Mockito.any(Card.class)))
                        .thenReturn(new forge.game.cost.Cost("2", false));
                return Optional.of(response(context, "NO_BLOCK"));
            }, 4);
        } finally { if (changed.get() != null) changed.get().close(); }
        Assert.assertEquals(AiDecisionMetrics.combatBlockerEvents().get(0).failureReason(), AiDecisionFailureReason.REVALIDATION_FAILED);
    }
    @Test public void requestAndLiveFailuresRetainForgeFallbackExactly() {
        for (String kind : List.of("OPTION", "DECISION", "FINGERPRINT", "ATTACKER", "BLOCKER", "CONTROL", "TAPPED", "TARGET", "PHASE", "REQUIREMENT", "EXCEPTION", "TIMEOUT")) {
            AiDecisionMetrics.reset(); Fixture f = fixture(); f.combat().addBlocker(f.attacker(), f.bear());
            AiDecisionProvider provider = new AiDecisionProvider() {
                @Override public Optional<AiDecision> choose(AiDecisionContext c) { return Optional.empty(); }
                @Override public AiDecisionResult chooseWithDiagnostics(AiDecisionContext c) {
                    var context = (CombatBlockersDecisionContext)c;
                    if (kind.equals("EXCEPTION")) throw new IllegalStateException("test");
                    if (kind.equals("TIMEOUT")) return AiDecisionResult.failed(AiDecisionFailureReason.TIMEOUT, "test", "test", 7);
                    if (kind.equals("ATTACKER")) f.attacker().setGameTimestamp(f.attacker().getGameTimestamp() + 1);
                    if (kind.equals("BLOCKER")) f.bear().setGameTimestamp(f.bear().getGameTimestamp() + 1);
                    if (kind.equals("CONTROL")) f.bear().setController(f.enemy(), f.game().getNextTimestamp());
                    if (kind.equals("TAPPED")) f.bear().setTapped(true);
                    if (kind.equals("TARGET")) { f.combat().clearAttackers(); f.combat().addAttacker(f.attacker(), f.enemy()); }
                    if (kind.equals("PHASE")) f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE, f.enemy());
                    if (kind.equals("REQUIREMENT")) f.second().addMustBlockCard(1L, f.attacker());
                    return AiDecisionResult.accepted(new AiDecision(kind.equals("DECISION") ? "wrong" : context.decisionId(),
                            kind.equals("FINGERPRINT") ? "wrong" : context.stateFingerprint(),
                            kind.equals("OPTION") ? "OPT_NOT_SUPPLIED" : context.options().get(0).optionId()), "test", "test", 7);
                }
            };
            ExternalCombatBlockers.choose(f.defender(), f.combat(), provider, 4);
            var event = AiDecisionMetrics.combatBlockerEvents().get(0); Assert.assertTrue(event.fallback(), kind);
            Assert.assertEquals(event.selectedOptionId(), event.heuristicOptionId());
            if (!List.of("CONTROL", "ATTACKER", "TARGET", "PHASE").contains(kind)) Assert.assertEquals(declaration(f), List.of("Runeclaw Bear"), kind);
            Assert.assertEquals(event.failureReason(), switch (kind) {
                case "OPTION" -> AiDecisionFailureReason.ACTION_ID_MISMATCH;
                case "DECISION" -> AiDecisionFailureReason.DECISION_ID_MISMATCH;
                case "FINGERPRINT" -> AiDecisionFailureReason.FINGERPRINT_MISMATCH;
                case "PHASE", "TARGET" -> AiDecisionFailureReason.LIVE_STATE_CHANGED;
                case "EXCEPTION" -> AiDecisionFailureReason.PROVIDER_EXCEPTION;
                case "TIMEOUT" -> AiDecisionFailureReason.TIMEOUT;
                default -> AiDecisionFailureReason.REVALIDATION_FAILED;
            }, kind);
        }
    }
    @Test public void immutableSnapshotIgnoresUnrelatedChangesAndIdsAreDeterministic() {
        Fixture f = fixture(); var original = prepare(f, 4); var again = prepare(f, 4);
        Assert.assertEquals(original.context(), again.context());
        Assert.assertTrue(original.context().options().stream().allMatch(o -> o.optionId().equals("NO_BLOCK") || o.optionId().matches("OPT_[A-F0-9]{24}")));
        Assert.assertEquals(original.context().options().stream().map(CombatBlockOptionView::optionId).distinct().count(), original.options().size());
        Fixture replay = fixture(); Assert.assertEquals(original.context(), prepare(replay, 4).context());
        ExternalCombatBlockers.choose(f.defender(), f.combat(), c -> {
            var context = (CombatBlockersDecisionContext)c; f.enemy().setLife(17, null);
            Assert.assertEquals(context.stateFingerprint(), original.context().stateFingerprint());
            return Optional.of(response(context, "NO_BLOCK"));
        }, 4);
        Assert.assertFalse(AiDecisionMetrics.combatBlockerEvents().get(0).fallback());
        Assert.assertNotEquals(original.context().stateFingerprint(), prepare(f, 4).context().stateFingerprint());
        Assert.assertNotEquals(original.context().stateFingerprint(), prepare(replay, 2).context().stateFingerprint());
    }
    @Test public void canonicalMappingsIgnoreEngineTraversalOrder() {
        Fixture f = fixture(); Card other = addCard("Runeclaw Bear", f.enemy());
        f.combat().addAttacker(other, f.defender()); f.combat().addBlocker(f.attacker(), f.bear()); f.combat().addBlocker(other, f.second());
        var original = prepare(f, 8);
        f.combat().clearAttackers(); f.combat().addAttacker(other, f.defender()); f.combat().addAttacker(f.attacker(), f.defender());
        f.combat().addBlocker(other, f.second()); f.combat().addBlocker(f.attacker(), f.bear());
        Assert.assertEquals(original.context(), prepare(f, 8).context());
    }
    @Test(timeOut = 5000) public void candidateGenerationHasHardCapAndFixedAttemptBudget() {
        Fixture f = fixture();
        for (int i = 0; i < 16; i++) { addCard("Runeclaw Bear", f.defender()); Card a = addCard("Hill Giant", f.enemy()); f.combat().addAttacker(a, f.defender()); }
        AtomicInteger validations = new AtomicInteger();
        try (var mocked = Mockito.mockStatic(CombatUtil.class, Mockito.CALLS_REAL_METHODS)) {
            mocked.when(() -> CombatUtil.validateBlocks(Mockito.any(Combat.class), Mockito.eq(f.defender())))
                    .thenAnswer(invocation -> { validations.incrementAndGet(); return invocation.callRealMethod(); });
            var prepared = prepare(f, 100); Assert.assertTrue(prepared.options().size() <= 8);
            Assert.assertTrue(validations.get() <= ExternalCombatBlockers.MAX_CANDIDATE_ATTEMPTS + 2);
        }
    }
    @Test public void hiddenInformationIsAbsentAndTelemetryAuditRetainOutcomes() throws Exception {
        Fixture f = fixture(); addCardToZone("Griselbrand", f.enemy(), ZoneType.Hand); addCardToZone("Black Lotus", f.defender(), ZoneType.Library);
        Card faceDown = addCard("Emrakul, the Aeons Torn", f.enemy()); faceDown.turnFaceDown(true);
        String payload = new Gson().toJson(prepare(f, 4).context());
        for (String hidden : List.of("Griselbrand", "Black Lotus", "Emrakul", "originalCandidateIndex", "heuristic", "forge.game")) Assert.assertFalse(payload.contains(hidden));
        var audit = Files.createTempFile("forge-blocks-", ".jsonl"); var csv = Files.createTempFile("forge-blocks-", ".csv");
        try {
            AiDecisionMetrics.configureDecisionAudit(audit); AiDecisionMetrics.registerGame(f.game().getId(), 2, 1000L);
            ExternalCombatBlockers.choose(f.defender(), f.combat(), c -> Optional.of(response((CombatBlockersDecisionContext)c, "NO_BLOCK")), 4);
            AiDecisionMetrics.completeGame(f.game().getId(), f.defender().getName(), false, 123, 9);
            var json = JsonParser.parseString(Files.readString(audit)).getAsJsonObject();
            Assert.assertEquals(json.get("decisionType").getAsString(), "COMBAT_BLOCKERS"); Assert.assertTrue(json.get("decidingPlayerWon").getAsBoolean());
            Assert.assertTrue(json.has("internalMapping")); Assert.assertTrue(json.has("modelVisibleDecision"));
            Assert.assertEquals(json.getAsJsonObject("telemetry").get("liveRevalidationResult").getAsString(), "PASSED");
            Assert.assertTrue(AiDecisionMetrics.summary(1).contains("combatBlockerDecisions=1"));
            Assert.assertTrue(AiDecisionMetrics.summary(1).contains("combatBlockerNoBlockSelections=1"));
            AiDecisionMetrics.writeCsv(csv); var lines = Files.readAllLines(csv);
            Assert.assertEquals(lines.get(1).split(",", -1).length, lines.get(0).split(",", -1).length);
            Assert.assertTrue(lines.get(1).contains("COMBAT_BLOCKERS"));
        } finally { AiDecisionMetrics.reset(); Files.deleteIfExists(audit); Files.deleteIfExists(csv); }
    }
}




