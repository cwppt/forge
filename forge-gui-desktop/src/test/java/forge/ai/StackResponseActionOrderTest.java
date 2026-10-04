package forge.ai;

import com.google.gson.JsonParser;
import forge.ai.decision.AiDecision;
import forge.ai.decision.AiDecisionFailureReason;
import forge.ai.decision.AiDecisionMetrics;
import forge.ai.decision.StackResponseDecisionContext;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

public class StackResponseActionOrderTest extends AITest {
    private List<PreparedAiAction> dummyActions() {
        List<PreparedAiAction> actions = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            actions.add(new PreparedAiAction("ACTION_" + i, null, null, null,
                    PreparedAiAction.ActionCategory.SPELL, i, "Response " + i, true));
        }
        return actions;
    }

    @Test
    public void disabledPresentationKeepsOriginalObjectsAndOrder() {
        List<PreparedAiAction> actions = dummyActions();
        List<String> ids = List.of("OPT_AAA", "OPT_BBB", "OPT_CCC", "OPT_DDD", "OPT_EEE");
        var presentation = StackResponseActionPresentation.create(actions, false, 1000, 0, 1, 0, "state").withOptionIds(ids);
        Assert.assertEquals(presentation.originalPositions(), List.of(0, 1, 2, 3, 4));
        for (int i = 0; i < actions.size(); i++) {
            Assert.assertSame(presentation.resolve(ids.get(i)), actions.get(i));
            Assert.assertEquals(presentation.presentedId(actions.get(i).actionId()), ids.get(i));
        }
        Assert.assertNull(presentation.resolve("PASS"));
        Assert.assertEquals(presentation.presentedId("PASS"), "PASS");
        Assert.assertNull(presentation.resolve("ACTION_99"));
    }

    @Test
    public void isolatedShuffleIsDeterministicAndDecisionSensitive() {
        List<PreparedAiAction> actions = dummyActions();
        List<String> ids = List.of("OPT_AAA", "OPT_BBB", "OPT_CCC", "OPT_DDD", "OPT_EEE");
        var first = StackResponseActionPresentation.create(actions, true, 1000, 0, 1, 0, "state").withOptionIds(ids);
        Assert.assertEquals(first.originalPositions(),
                StackResponseActionPresentation.create(actions, true, 1000, 0, 1, 0, "state").originalPositions());
        var orders = new HashSet<List<Integer>>();
        var runOrders = new HashSet<List<Integer>>();
        var gameOrders = new HashSet<List<Integer>>();
        var stateOrders = new HashSet<List<Integer>>();
        for (int i = 0; i < 32; i++) {
            orders.add(StackResponseActionPresentation.create(actions, true, 1000, 0, 1, i, "state").originalPositions());
            runOrders.add(StackResponseActionPresentation.create(actions, true, i, 0, 1, 0, "state").originalPositions());
            gameOrders.add(StackResponseActionPresentation.create(actions, true, 1000, i, 1, 0, "state").originalPositions());
            stateOrders.add(StackResponseActionPresentation.create(actions, true, 1000, 0, 1, 0, "state" + i).originalPositions());
        }
        Assert.assertTrue(orders.size() > 1);
        Assert.assertTrue(runOrders.size() > 1);
        Assert.assertTrue(gameOrders.size() > 1);
        Assert.assertTrue(stateOrders.size() > 1);
        for (int i = 0; i < actions.size(); i++) {
            PreparedAiAction original = first.resolve(ids.get(i));
            Assert.assertSame(original, actions.get(first.originalPositions().get(i)));
            Assert.assertEquals(first.presentedId(original.actionId()), ids.get(i));
            Assert.assertEquals(original.actionId(), "ACTION_" + first.originalPositions().get(i));
        }
        Random forgeRandom = new Random(1234);
        long next = MyRandom.withRandom(forgeRandom, () -> {
            StackResponseActionPresentation.create(actions, true, 1000, 0, 1, 0, "state");
            Assert.assertSame(MyRandom.getRandom(), forgeRandom);
            return forgeRandom.nextLong();
        });
        Assert.assertEquals(next, new Random(1234).nextLong());
    }

    @Test
    public void fingerprintTracksOnlyPresentedOrderAndMainPhaseIsUnaffected() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card counter = addCardToZone("Counterspell", ai, ZoneType.Hand);
        SpellAbility ability = counter.getFirstSpellAbility();
        ability.setActivatingPlayer(ai);
        List<PreparedAiAction> actions = List.of(
                new PreparedAiAction("ACTION_0", ability, ability, counter,
                        PreparedAiAction.ActionCategory.SPELL, 0, "First response", true),
                new PreparedAiAction("ACTION_1", ability, ability, counter,
                        PreparedAiAction.ActionCategory.SPELL, 1, "Second response", true));
        String mainBefore = MainPhaseStateProjector.project(ai, "main", actions).state().fingerprint();
        var original = MainPhaseStateProjector.projectStackResponse(ai, "stack", actions);
        var unchanged = MainPhaseStateProjector.projectStackResponse(ai, "stack", actions, List.of(0, 1), true);
        var swapped = MainPhaseStateProjector.projectStackResponse(ai, "stack", actions, List.of(1, 0), true);
        Assert.assertEquals(original.stateFingerprint(), unchanged.stateFingerprint());
        Assert.assertNotEquals(original.stateFingerprint(), swapped.stateFingerprint());
        Assert.assertEquals(swapped.stateFingerprint(), MainPhaseStateProjector.projectStackResponse(
                ai, "different-request-id", actions, List.of(1, 0), true).stateFingerprint());
        Assert.assertTrue(swapped.legalActions().get(0).actionId().matches("OPT_[0-9A-F]{24}"));
        Assert.assertEquals(swapped.legalActions().get(0).description(), "Second response");
        Assert.assertEquals(swapped.internalActionMapping().get(0).originalActionId(), "ACTION_1");
        Assert.assertEquals(swapped.internalActionMapping().get(0).originalPosition(), 1);
        Assert.assertEquals(MainPhaseStateProjector.project(ai, "main", actions).state().fingerprint(), mainBefore);
        Assert.assertEquals(MainPhaseStateProjector.project(ai, "main", actions).legalActions().get(0)
                .forgeRecommendation(), "PREFERRED");
        List<PreparedAiAction> identicalVisibleActions = List.of(actions.get(0),
                new PreparedAiAction("ACTION_1", ability, ability, counter,
                        PreparedAiAction.ActionCategory.SPELL, 1, "First response", true));
        var twins = MainPhaseStateProjector.projectStackResponse(ai, "twins", identicalVisibleActions, List.of(0, 1), true);
        var swappedTwins = MainPhaseStateProjector.projectStackResponse(ai, "twins", identicalVisibleActions, List.of(1, 0), true);
        Assert.assertNotEquals(twins.legalActions().get(0).actionId(), twins.legalActions().get(1).actionId());
        Assert.assertEquals(twins.legalActions().get(0).actionId(), swappedTwins.legalActions().get(1).actionId());
        Assert.assertNotEquals(twins.stateFingerprint(), swappedTwins.stateFingerprint());
    }

    @Test
    public void randomizedSelectionRemapsHeuristicFallbackPassAndAudit() throws Exception {
        var observedOrders = new HashSet<List<String>>();
        var ordersBySeed = new HashMap<Long, List<String>>();
        var fingerprintsBySeed = new HashMap<Long, String>();
        for (long seed = 1000; seed < 1008; seed++) {
            for (String selection : List.of("FIRST", "HEURISTIC", "PASS", "FAIL", "STALE", "REVALIDATE")) {
                AiDecisionMetrics.reset();
                Path audit = Files.createTempFile("forge-stack-order-", ".jsonl");
                AtomicReference<MockedStatic<ComputerUtilCost>> costMock = new AtomicReference<>();
                try {
                    AiDecisionMetrics.configureDecisionAudit(audit);
                    Game game = initAndCreateGame();
                    AiDecisionMetrics.registerGame(game.getId(), 0, seed);
                    Player ai = game.getPlayers().get(1);
                    addCards("Island", 4, ai);
                    Card first = addCardToZone("Counterspell", ai, ZoneType.Hand);
                    Card heuristic = addCardToZone("Essence Scatter", ai, ZoneType.Hand);
                    Player opponent = game.getPlayers().get(0);
                    Card threat = addCardToZone("Colossal Dreadmaw", opponent, ZoneType.Hand);
                    SpellAbility pending = threat.getFirstSpellAbility();
                    pending.setActivatingPlayer(opponent);
                    game.getStackZone().add(threat);
                    game.getStack().add(pending);
                    AtomicReference<StackResponseDecisionContext> captured = new AtomicReference<>();
                    AtomicReference<String> requested = new AtomicReference<>();
                    PlayerControllerAi controller = new PlayerControllerAi(game, ai, ai.getLobbyPlayer(), context -> {
                        StackResponseDecisionContext stack = (StackResponseDecisionContext) context;
                        captured.set(stack);
                        String id = "PASS".equals(selection) ? "PASS" : "HEURISTIC".equals(selection)
                                ? stack.legalActions().stream().filter(action -> "Essence Scatter".equals(action.sourceName()))
                                .findFirst().orElseThrow().actionId() : stack.legalActions().get(0).actionId();
                        requested.set(id);
                        if ("FAIL".equals(selection)) return Optional.empty();
                        if ("STALE".equals(selection)) opponent.setLife(19, null);
                        if ("REVALIDATE".equals(selection)) {
                            // Fail final payability without changing any fingerprinted data.
                            MockedStatic<ComputerUtilCost> mock = Mockito.mockStatic(ComputerUtilCost.class,
                                    Mockito.CALLS_REAL_METHODS);
                            costMock.set(mock);
                            mock.when(() -> ComputerUtilCost.canPayCost(Mockito.any(SpellAbility.class),
                                    Mockito.eq(ai), Mockito.eq(false))).thenReturn(false);
                        }
                        return Optional.of(new AiDecision(stack.decisionId(), stack.stateFingerprint(), id));
                    }, false, false, 3, false, 1, true, 3, true);
                    ai.dangerouslySetController(controller);
                    List<SpellAbility> result = controller.chooseSpellAbilityToPlay();
                    StackResponseDecisionContext stack = captured.get();
                    Assert.assertTrue(stack.randomizedOrder());
                    List<String> order = stack.legalActions().stream().map(action -> action.sourceName()).toList();
                    observedOrders.add(order);
                    Assert.assertEquals(order, ordersBySeed.computeIfAbsent(seed, ignored -> order));
                    Assert.assertEquals(stack.stateFingerprint(), fingerprintsBySeed.computeIfAbsent(seed,
                            ignored -> stack.stateFingerprint()));
                    stack.legalActions().forEach(action -> Assert.assertTrue(action.actionId().matches("OPT_[0-9A-F]{24}")));
                    String heuristicId = stack.legalActions().stream()
                            .filter(action -> "Essence Scatter".equals(action.sourceName())).findFirst().orElseThrow().actionId();
                    var event = AiDecisionMetrics.stackResponseEvents().get(0);
                    boolean fallback = List.of("FAIL", "STALE", "REVALIDATE").contains(selection);
                    Assert.assertEquals(event.heuristicResponseId(), heuristicId);
                    Assert.assertEquals(event.selectedResponseId(), fallback ? heuristicId : requested.get());
                    Assert.assertEquals(event.fallback(), fallback);
                    if ("PASS".equals(selection)) {
                        Assert.assertNull(result);
                        Assert.assertTrue(event.passSelected());
                    } else {
                        String name = fallback ? heuristic.getName() : stack.legalActions().stream()
                                .filter(action -> requested.get().equals(action.actionId())).findFirst().orElseThrow().sourceName();
                        Assert.assertSame(result.get(0).getHostCard(), first.getName().equals(name) ? first : heuristic);
                        if (!fallback) Assert.assertTrue(result.get(0).getTargets().contains(pending));
                    }
                    if ("STALE".equals(selection)) Assert.assertEquals(event.fallbackReason(), AiDecisionFailureReason.STALE_ACTION_SET);
                    if ("REVALIDATE".equals(selection)) Assert.assertEquals(event.fallbackReason(), AiDecisionFailureReason.REVALIDATION_FAILED);
                    AiDecisionMetrics.completeGame(game.getId(), ai.getName(), false, 10, 1);
                    var json = JsonParser.parseString(Files.readString(audit)).getAsJsonObject();
                    Assert.assertTrue(json.get("randomizedOrder").getAsBoolean());
                    Assert.assertEquals(json.get("heuristicResponseId").getAsString(), heuristicId);
                    Assert.assertEquals(json.get("agreement").getAsBoolean(), event.selectedResponseId().equals(heuristicId));
                    var mapping = json.getAsJsonArray("internalActionMapping");
                    Assert.assertEquals(mapping.size(), 2);
                    for (int i = 0; i < 2; i++) {
                        var entry = mapping.get(i).getAsJsonObject();
                        Assert.assertEquals(entry.get("modelVisibleOptionId").getAsString(), stack.legalActions().get(i).actionId());
                        Assert.assertEquals(entry.get("matchesForgeHeuristic").getAsBoolean(),
                                stack.legalActions().get(i).actionId().equals(heuristicId));
                        Assert.assertEquals(entry.get("presentedPosition").getAsInt(), i);
                        Assert.assertEquals(entry.get("originalActionId").getAsString(),
                                "ACTION_" + entry.get("originalPosition").getAsInt());
                        Assert.assertEquals(entry.get("sourceName").getAsString(), stack.legalActions().get(i).sourceName());
                    }
                } finally {
                    if (costMock.get() != null) costMock.get().close();
                    AiDecisionMetrics.reset();
                    Files.deleteIfExists(audit);
                }
            }
        }
        Assert.assertEquals(observedOrders.size(), 2, "Genuine shuffle must permit both permutations");
    }
}
