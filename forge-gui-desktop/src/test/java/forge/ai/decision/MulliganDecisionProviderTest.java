package forge.ai.decision;

import forge.ai.ComputerUtil;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.mulligan.MulliganService;
import forge.game.player.Player;
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

public class MulliganDecisionProviderTest extends SimulationTest {
    @BeforeMethod
    public void resetDecisionMetrics() {
        AiDecisionMetrics.reset();
    }

    private PlayerControllerAi controllerWith(AiDecisionProvider provider) {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        PlayerControllerAi controller = new PlayerControllerAi(
                game, player, player.getLobbyPlayer(), provider);
        player.dangerouslySetController(controller);
        return controller;
    }

    @Test
    public void noProviderUsesExistingHeuristic() {
        PlayerControllerAi controller = controllerWith(null);
        boolean heuristic = !ComputerUtil.wantMulligan(controller.getPlayer(), 0);

        Assert.assertEquals(controller.mulliganKeepHand(null, 0), heuristic);
    }

    @Test
    public void providerCanKeep() {
        PlayerControllerAi controller = controllerWith(context -> Optional.of(
                new AiDecision(context.decisionId(), MulliganDecisionContext.KEEP_OPTION_ID)));
        for (int i = 0; i < 20; i++) {
            addCardToZone("Mountain", controller.getPlayer(), ZoneType.Library);
        }
        for (int i = 0; i < 7; i++) {
            addCardToZone("Hill Giant", controller.getPlayer(), ZoneType.Hand);
        }

        Assert.assertTrue(ComputerUtil.wantMulligan(controller.getPlayer(), 0),
                "the fixture must be a hand the Forge heuristic rejects");

        Assert.assertTrue(controller.mulliganKeepHand(null, 0));
        MulliganDecisionEvent event = AiDecisionMetrics.events().get(0);
        Assert.assertEquals(event.source(), AiDecisionSource.EXTERNAL_PROVIDER);
        Assert.assertFalse(event.fallback());
        Assert.assertEquals(event.option(), MulliganDecisionContext.KEEP_OPTION_ID);
        Assert.assertEquals(event.heuristicOption(), MulliganDecisionContext.MULLIGAN_OPTION_ID);
        Assert.assertEquals(event.finalOption(), MulliganDecisionContext.KEEP_OPTION_ID);
    }

    @Test
    public void providerCanMulligan() {
        PlayerControllerAi controller = controllerWith(context -> Optional.of(
                new AiDecision(context.decisionId(), MulliganDecisionContext.MULLIGAN_OPTION_ID)));

        Assert.assertFalse(controller.mulliganKeepHand(null, 0));
    }

    @Test
    public void invalidOptionUsesHeuristic() {
        PlayerControllerAi controller = controllerWith(context -> Optional.of(
                new AiDecision(context.decisionId(), "DRAW_EXTRA_CARD")));
        boolean heuristic = !ComputerUtil.wantMulligan(controller.getPlayer(), 0);

        Assert.assertEquals(controller.mulliganKeepHand(null, 0), heuristic);
    }

    @Test
    public void providerExceptionUsesHeuristic() {
        PlayerControllerAi controller = controllerWith(context -> {
            throw new IllegalStateException("provider unavailable");
        });
        boolean heuristic = !ComputerUtil.wantMulligan(controller.getPlayer(), 0);

        Assert.assertEquals(controller.mulliganKeepHand(null, 0), heuristic);
        Assert.assertEquals(AiDecisionMetrics.events().get(0).fallbackReason(),
                AiDecisionFailureReason.PROVIDER_EXCEPTION);
    }

    @Test
    public void timeoutTelemetryUsesHeuristicAndRecordsLatency() {
        AiDecisionProvider provider = new AiDecisionProvider() {
            @Override
            public Optional<AiDecision> choose(AiDecisionContext context) {
                return Optional.empty();
            }

            @Override
            public AiDecisionResult chooseWithDiagnostics(AiDecisionContext context) {
                return AiDecisionResult.failed(AiDecisionFailureReason.TIMEOUT,
                        "scripted", "test-model", 42);
            }
        };
        PlayerControllerAi controller = controllerWith(provider);
        boolean heuristic = !ComputerUtil.wantMulligan(controller.getPlayer(), 0);

        Assert.assertEquals(controller.mulliganKeepHand(null, 0), heuristic);
        MulliganDecisionEvent event = AiDecisionMetrics.events().get(0);
        Assert.assertEquals(event.source(), AiDecisionSource.FORGE_HEURISTIC);
        Assert.assertTrue(event.fallback());
        Assert.assertEquals(event.fallbackReason(), AiDecisionFailureReason.TIMEOUT);
        Assert.assertEquals(event.latencyMs(), 42);
        Assert.assertEquals(event.finalOption(), event.heuristicOption());
        Assert.assertEquals(AiDecisionMetrics.snapshot(1).timeouts(), 1);
    }

    @Test
    public void externalAgreementAndDisagreementAreCounted() {
        PlayerControllerAi agreeing = controllerWith(context -> Optional.of(
                new AiDecision(context.decisionId(), MulliganDecisionContext.KEEP_OPTION_ID)));
        Assert.assertFalse(ComputerUtil.wantMulligan(agreeing.getPlayer(), 0));
        Assert.assertTrue(agreeing.mulliganKeepHand(null, 0));

        PlayerControllerAi disagreeing = controllerWith(context -> Optional.of(
                new AiDecision(context.decisionId(), MulliganDecisionContext.MULLIGAN_OPTION_ID)));
        Assert.assertFalse(ComputerUtil.wantMulligan(disagreeing.getPlayer(), 0));
        Assert.assertFalse(disagreeing.mulliganKeepHand(null, 0));

        AiDecisionMetricsSnapshot snapshot = AiDecisionMetrics.snapshot(2);
        Assert.assertEquals(snapshot.acceptedExternalDecisions(), 2);
        Assert.assertEquals(snapshot.externalAgreements(), 1);
        Assert.assertEquals(snapshot.externalDisagreements(), 1);
        Assert.assertEquals(snapshot.externalDisagreementRate(), 0.5);
    }

    @Test
    public void gameAndPlayerMetadataAreAttachedToDecision() {
        PlayerControllerAi controller = controllerWith(context -> Optional.of(
                new AiDecision(context.decisionId(), MulliganDecisionContext.KEEP_OPTION_ID)));
        Game game = controller.getGame();
        AiDecisionMetrics.registerGame(game.getId(), 7, 12345L);

        controller.mulliganKeepHand(null, 0);

        MulliganDecisionEvent event = AiDecisionMetrics.events().get(0);
        Assert.assertEquals(event.gameId(), game.getId());
        Assert.assertEquals(event.gameIndex(), Integer.valueOf(7));
        Assert.assertEquals(event.runSeed(), Long.valueOf(12345L));
        Assert.assertEquals(event.playerIdentity(), controller.getPlayer().getName());
        Assert.assertEquals(event.playerSeat(), game.getRegisteredPlayers().indexOf(controller.getPlayer()));
        Assert.assertEquals(event.deckIdentifier(), controller.getPlayer().getRegisteredPlayer().getDeck().getName());
    }

    @Test
    public void londonBottomingDoesNotConsultProvider() {
        AtomicInteger calls = new AtomicInteger();
        PlayerControllerAi controller = controllerWith(context -> {
            calls.incrementAndGet();
            return Optional.of(new AiDecision(context.decisionId(), MulliganDecisionContext.KEEP_OPTION_ID));
        });

        controller.tuckCardsViaMulligan(controller.getPlayer().getCardsIn(ZoneType.Hand), 0);

        Assert.assertEquals(calls.get(), 0);
        Assert.assertTrue(AiDecisionMetrics.events().isEmpty());
    }

    @Test
    public void scriptedProviderDecisionFlowsThroughMulliganService() {
        AtomicInteger calls = new AtomicInteger();
        PlayerControllerAi controller = controllerWith(context -> {
            int call = calls.incrementAndGet();
            String option = call == 1
                    ? MulliganDecisionContext.MULLIGAN_OPTION_ID
                    : MulliganDecisionContext.KEEP_OPTION_ID;
            return Optional.of(new AiDecision(context.decisionId(), option));
        });

        new MulliganService(controller.getGame().getPlayers().getFirst()).perform();

        Assert.assertEquals(calls.get(), 2, "scripted MULLIGAN followed by KEEP must traverse the service loop");
        Assert.assertFalse(controller.getGame().isGameOver());
        Assert.assertTrue(AiDecisionMetrics.events().size() >= 3,
                "the other AI and both scripted callbacks must be recorded");
    }

    @Test
    public void mismatchedDecisionIdUsesHeuristic() {
        PlayerControllerAi controller = controllerWith(context -> Optional.of(
                new AiDecision("another-decision", MulliganDecisionContext.MULLIGAN_OPTION_ID)));
        boolean heuristic = !ComputerUtil.wantMulligan(controller.getPlayer(), 0);

        Assert.assertEquals(controller.mulliganKeepHand(null, 0), heuristic);
    }

    @Test
    public void projectionExcludesHiddenOpponentAndLibraryCards() {
        PlayerControllerAi controller = controllerWith(null);
        Player self = controller.getPlayer();
        Player opponent = self.getOpponents().getFirst();
        addCardToZone("Black Lotus", opponent, ZoneType.Hand);
        addCardToZone("Ancestral Recall", self, ZoneType.Library);
        addCardToZone("Hill Giant", opponent, ZoneType.Battlefield).turnFaceDownNoUpdate();
        addCardToZone("Mountain", self, ZoneType.Hand);

        MulliganAiState state = MulliganStateProjector.project(self, opponent, 0);
        String projected = state.toString();

        Assert.assertFalse(projected.contains("Black Lotus"), "opponent hand identity leaked");
        Assert.assertFalse(projected.contains("Ancestral Recall"), "library identity leaked");
        Assert.assertFalse(projected.contains("Hill Giant"), "face-down identity leaked");
        Assert.assertTrue(projected.contains("Mountain"), "self hand should remain visible");
    }

    @Test
    public void projectionContainsNoEngineObjects() throws ReflectiveOperationException {
        PlayerControllerAi controller = controllerWith(null);
        MulliganAiState state = MulliganStateProjector.project(
                controller.getPlayer(), controller.getPlayer().getOpponents().getFirst(), 0);

        assertNoEngineObjects(state, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    @Test
    public void equivalentVisibleStatesHaveSameFingerprint() {
        PlayerControllerAi firstController = controllerWith(null);
        Player firstSelf = firstController.getPlayer();
        addCardToZone("Mountain", firstSelf, ZoneType.Hand);
        addCardToZone("Hill Giant", firstSelf, ZoneType.Hand);
        MulliganAiState first = MulliganStateProjector.project(
                firstSelf, firstSelf.getOpponents().getFirst(), 0);

        PlayerControllerAi secondController = controllerWith(null);
        Player secondSelf = secondController.getPlayer();
        addCardToZone("Hill Giant", secondSelf, ZoneType.Hand);
        addCardToZone("Mountain", secondSelf, ZoneType.Hand);
        MulliganAiState second = MulliganStateProjector.project(
                secondSelf, secondSelf.getOpponents().getFirst(), 0);

        Assert.assertEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    public void changedHandChangesFingerprint() {
        PlayerControllerAi controller = controllerWith(null);
        Player self = controller.getPlayer();
        Player startingPlayer = self.getOpponents().getFirst();
        MulliganAiState before = MulliganStateProjector.project(self, startingPlayer, 0);
        addCardToZone("Mountain", self, ZoneType.Hand);
        MulliganAiState after = MulliganStateProjector.project(self, startingPlayer, 0);

        Assert.assertNotEquals(before.fingerprint(), after.fingerprint());
    }

    @Test
    public void changedMulliganCountChangesFingerprint() {
        PlayerControllerAi controller = controllerWith(null);
        Player self = controller.getPlayer();
        Player startingPlayer = self.getOpponents().getFirst();
        MulliganAiState first = MulliganStateProjector.project(self, startingPlayer, 0);
        MulliganAiState second = MulliganStateProjector.project(self, startingPlayer, 1);

        Assert.assertNotEquals(first.fingerprint(), second.fingerprint());
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
