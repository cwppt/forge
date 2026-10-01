package forge.ai;

import forge.ai.decision.AiDecision;
import forge.ai.decision.AiDecisionFailureReason;
import forge.ai.decision.AiDecisionMetrics;
import forge.ai.decision.AiDecisionProvider;
import forge.ai.decision.AiDecisionResult;
import forge.ai.decision.MainPhaseDecisionContext;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class MainPhaseExternalDecisionTest extends AITest {
    @BeforeMethod
    public void resetMetrics() {
        AiDecisionMetrics.reset();
    }

    private PlayerControllerAi controller(Game game, AiDecisionProvider provider, boolean enabled) {
        Player player = game.getPlayers().get(1);
        PlayerControllerAi controller = new PlayerControllerAi(game, player, player.getLobbyPlayer(), provider,
                false, enabled, 3);
        player.dangerouslySetController(controller);
        return controller;
    }

    private Card[] addTwoBurnSpells(Game game) {
        Player ai = game.getPlayers().get(1);
        addCards("Mountain", 2, ai);
        Card shock = addCardToZone("Shock", ai, ZoneType.Hand);
        Card bolt = addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
        game.getPlayers().get(0).setLife(2, null);
        return new Card[] { shock, bolt };
    }

    private static AiDecision choose(MainPhaseDecisionContext context, String option) {
        return new AiDecision(context.decisionId(), context.state().fingerprint(), option);
    }

    @Test
    public void providerCanChooseActionOneWithPreparedTargetRetained() {
        Game game = initAndCreateGame();
        Card[] cards = addTwoBurnSpells(game);
        AtomicReference<MainPhaseDecisionContext> captured = new AtomicReference<>();
        PlayerControllerAi controller = controller(game, context -> {
            MainPhaseDecisionContext main = (MainPhaseDecisionContext) context;
            captured.set(main);
            return Optional.of(choose(main, "ACTION_1"));
        }, true);

        SpellAbility selected = controller.chooseSpellAbilityToPlay().get(0);

        Assert.assertEquals(selected.getHostCard(), cards[1]);
        Assert.assertEquals(selected.getTargets().getFirstTargetedPlayer(), game.getPlayers().get(0));
        Assert.assertEquals(captured.get().legalActions().size(), 2);
        Assert.assertEquals(AiDecisionMetrics.actionEvents().get(0).selectedActionId(), "ACTION_1");
    }

    @Test
    public void providerActionZeroMatchesForgeHeuristic() {
        Game game = initAndCreateGame();
        Card[] cards = addTwoBurnSpells(game);
        PlayerControllerAi controller = controller(game, context -> {
            MainPhaseDecisionContext main = (MainPhaseDecisionContext) context;
            return Optional.of(choose(main, "ACTION_0"));
        }, true);

        Assert.assertEquals(controller.chooseSpellAbilityToPlay().get(0).getHostCard(), cards[0]);
        Assert.assertEquals(AiDecisionMetrics.actionEvents().get(0).heuristicActionId(), "ACTION_0");
    }

    @Test
    public void disabledProviderUsesLegacyWithoutEnumerationOrCall() {
        Game game = initAndCreateGame();
        Card[] cards = addTwoBurnSpells(game);
        AtomicInteger calls = new AtomicInteger();
        PlayerControllerAi controller = controller(game, context -> {
            calls.incrementAndGet();
            return Optional.empty();
        }, false);

        Assert.assertEquals(controller.chooseSpellAbilityToPlay().get(0).getHostCard(), cards[0]);
        Assert.assertEquals(calls.get(), 0);
        Assert.assertTrue(AiDecisionMetrics.actionEvents().isEmpty());
    }

    @Test
    public void oneActionDoesNotCallProvider() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        addCard("Mountain", ai);
        addCardToZone("Shock", ai, ZoneType.Hand);
        game.getPlayers().get(0).setLife(2, null);
        AtomicInteger calls = new AtomicInteger();
        PlayerControllerAi controller = controller(game, context -> {
            calls.incrementAndGet();
            return Optional.empty();
        }, true);

        Assert.assertNotNull(controller.chooseSpellAbilityToPlay());
        Assert.assertEquals(calls.get(), 0);
    }

    @Test
    public void invalidActionAndMismatchedMetadataFallBackToActionZero() {
        assertFallback(context -> new AiDecision(context.decisionId(), context.state().fingerprint(), "ACTION_99"),
                AiDecisionFailureReason.ACTION_ID_MISMATCH);
        assertFallback(context -> new AiDecision("wrong", context.state().fingerprint(), "ACTION_1"),
                AiDecisionFailureReason.DECISION_ID_MISMATCH);
        assertFallback(context -> new AiDecision(context.decisionId(), "stale", "ACTION_1"),
                AiDecisionFailureReason.FINGERPRINT_MISMATCH);
    }

    private void assertFallback(java.util.function.Function<MainPhaseDecisionContext, AiDecision> response,
            AiDecisionFailureReason expectedReason) {
        AiDecisionMetrics.reset();
        Game game = initAndCreateGame();
        Card[] cards = addTwoBurnSpells(game);
        PlayerControllerAi controller = controller(game, context -> Optional.of(
                response.apply((MainPhaseDecisionContext) context)), true);

        Assert.assertEquals(controller.chooseSpellAbilityToPlay().get(0).getHostCard(), cards[0]);
        Assert.assertEquals(AiDecisionMetrics.actionEvents().get(0).fallbackReason(), expectedReason);
    }

    @Test
    public void timeoutAndTransportFailureFallBackToActionZero() {
        assertDiagnosticFailure(AiDecisionFailureReason.TIMEOUT);
        assertDiagnosticFailure(AiDecisionFailureReason.CONNECTION);
    }

    private void assertDiagnosticFailure(AiDecisionFailureReason reason) {
        AiDecisionMetrics.reset();
        Game game = initAndCreateGame();
        Card[] cards = addTwoBurnSpells(game);
        AiDecisionProvider provider = new AiDecisionProvider() {
            @Override
            public Optional<AiDecision> choose(forge.ai.decision.AiDecisionContext context) {
                return Optional.empty();
            }

            @Override
            public AiDecisionResult chooseWithDiagnostics(forge.ai.decision.AiDecisionContext context) {
                return AiDecisionResult.failed(reason, "test", "test", 4);
            }
        };
        PlayerControllerAi controller = controller(game, provider, true);

        Assert.assertEquals(controller.chooseSpellAbilityToPlay().get(0).getHostCard(), cards[0]);
        Assert.assertEquals(AiDecisionMetrics.actionEvents().get(0).fallbackReason(), reason);
    }

    @Test
    public void visibleStateChangeDuringRequestIsRejectedAsStale() {
        Game game = initAndCreateGame();
        Card[] cards = addTwoBurnSpells(game);
        PlayerControllerAi controller = controller(game, context -> {
            MainPhaseDecisionContext main = (MainPhaseDecisionContext) context;
            game.getPlayers().get(0).setLife(3, null);
            return Optional.of(choose(main, "ACTION_1"));
        }, true);

        Assert.assertNotNull(controller.chooseSpellAbilityToPlay());
        Assert.assertEquals(AiDecisionMetrics.actionEvents().get(0).fallbackReason(),
                AiDecisionFailureReason.STALE_ACTION_SET);
    }

    @Test
    public void providerIsNotCalledOutsideOwnEmptyStackMainPhase() {
        Game game = initAndCreateGame();
        addTwoBurnSpells(game);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, game.getPlayers().get(1));
        AtomicInteger calls = new AtomicInteger();
        PlayerControllerAi controller = controller(game, context -> {
            calls.incrementAndGet();
            return Optional.empty();
        }, true);

        controller.chooseSpellAbilityToPlay();

        Assert.assertEquals(calls.get(), 0);
    }

    @Test
    public void noActionsAndLandOnlyDoNotCallProvider() {
        Game emptyGame = initAndCreateGame();
        AtomicInteger calls = new AtomicInteger();
        PlayerControllerAi emptyController = controller(emptyGame, context -> {
            calls.incrementAndGet();
            return Optional.empty();
        }, true);
        Assert.assertNull(emptyController.chooseSpellAbilityToPlay());

        Game landGame = initAndCreateGame();
        Card mountain = addCardToZone("Mountain", landGame.getPlayers().get(1), ZoneType.Hand);
        PlayerControllerAi landController = controller(landGame, context -> {
            calls.incrementAndGet();
            return Optional.empty();
        }, true);
        Assert.assertEquals(landController.chooseSpellAbilityToPlay().get(0).getHostCard(), mountain);
        Assert.assertEquals(calls.get(), 0);
    }

    @Test
    public void providerSelectedXValueIsRetained() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        addCards("Forest", 4, ai);
        addCard("Mountain", ai);
        addCardToZone("Green Sun's Zenith", ai, ZoneType.Hand);
        addCardToZone("Shock", ai, ZoneType.Hand);
        addCardToZone("Birds of Paradise", ai, ZoneType.Library);
        addCardToZone("Endurance", ai, ZoneType.Library);
        game.getPlayers().get(0).setLife(2, null);
        PlayerControllerAi controller = controller(game, context -> {
            MainPhaseDecisionContext main = (MainPhaseDecisionContext) context;
            String actionId = main.legalActions().stream().filter(action -> action.xValue() != null)
                    .findFirst().orElseThrow().actionId();
            return Optional.of(choose(main, actionId));
        }, true);

        SpellAbility selected = controller.chooseSpellAbilityToPlay().get(0);

        Assert.assertEquals(selected.getXManaCostPaid(), Integer.valueOf(3));
        Assert.assertFalse(AiDecisionMetrics.actionEvents().get(0).fallback());
    }

    @Test
    public void providerSelectedModalChoiceIsRetained() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        addCards("Plains", 2, ai);
        addCards("Island", 2, ai);
        addCards("Swamp", 2, ai);
        addCard("Mountain", ai);
        addCardToZone("Dromar's Charm", ai, ZoneType.Hand);
        addCardToZone("Shock", ai, ZoneType.Hand);
        addCard("Runeclaw Bear", game.getPlayers().get(0));
        game.getPlayers().get(0).setLife(2, null);
        PlayerControllerAi controller = controller(game, context -> {
            MainPhaseDecisionContext main = (MainPhaseDecisionContext) context;
            String actionId = main.legalActions().stream().filter(action -> !action.modes().isEmpty())
                    .findFirst().orElseThrow().actionId();
            return Optional.of(choose(main, actionId));
        }, true);

        SpellAbility selected = controller.chooseSpellAbilityToPlay().get(0);

        Assert.assertNotNull(selected.getChosenList());
        Assert.assertFalse(selected.getChosenList().isEmpty());
        Assert.assertFalse(AiDecisionMetrics.actionEvents().get(0).fallback());
    }

    @Test
    public void projectionOmitsOpponentHandLibraryAndFaceDownIdentity() throws ReflectiveOperationException {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card[] cards = addTwoBurnSpells(game);
        addCardToZone("Black Lotus", opponent, ZoneType.Hand);
        addCardToZone("Ancestral Recall", ai, ZoneType.Library);
        addCardToZone("Hill Giant", opponent, ZoneType.Battlefield).turnFaceDownNoUpdate();
        PreparedAiActionSet set = controller(game, null, false).getAi().prepareCandidateActions(
                java.util.List.of(cards[0].getSpellAbilities().get(0), cards[1].getSpellAbilities().get(0)),
                false, 3);

        MainPhaseDecisionContext context = MainPhaseStateProjector.project(ai, "test", set.actions());
        String text = context.toString();
        Assert.assertFalse(text.contains("Black Lotus"));
        Assert.assertFalse(text.contains("Ancestral Recall"));
        Assert.assertFalse(text.contains("Hill Giant"));
        Assert.assertTrue(text.contains("Face-down card"));
        assertNoEngineObjects(context, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static void assertNoEngineObjects(Object value, Set<Object> visited)
            throws InvocationTargetException, IllegalAccessException {
        if (value == null || !visited.add(value)) {
            return;
        }
        Class<?> type = value.getClass();
        Assert.assertFalse(type.getName().startsWith("forge.game."),
                "engine object reachable from projection: " + type.getName());
        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                assertNoEngineObjects(element, visited);
            }
        } else if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                assertNoEngineObjects(component.getAccessor().invoke(value), visited);
            }
        }
    }
}
