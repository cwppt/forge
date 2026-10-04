package forge.ai;

import forge.ai.decision.AiDecision;
import forge.ai.decision.AiDecisionFailureReason;
import forge.ai.decision.AiDecisionMetrics;
import forge.ai.decision.StackResponseDecisionContext;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class StackResponseExternalDecisionTest extends AITest {
    @BeforeMethod
    public void resetMetrics() {
        AiDecisionMetrics.reset();
    }

    private PlayerControllerAi controller(Game game, forge.ai.decision.AiDecisionProvider provider,
            boolean enabled, int maxActions) {
        Player ai = game.getPlayers().get(1);
        PlayerControllerAi controller = new PlayerControllerAi(game, ai, ai.getLobbyPlayer(), provider,
                false, false, 3, false, 1, enabled, maxActions);
        ai.dangerouslySetController(controller);
        return controller;
    }

    private SpellAbility addOpponentSpellToStack(Game game) {
        Player opponent = game.getPlayers().get(0);
        Card card = addCardToZone("Runeclaw Bear", opponent, ZoneType.Hand);
        SpellAbility spell = card.getFirstSpellAbility();
        spell.setActivatingPlayer(opponent);
        game.getStackZone().add(card);
        game.getStack().add(spell);
        return spell;
    }

    private Card addCounterspell(Game game) {
        Player ai = game.getPlayers().get(1);
        addCards("Island", 2, ai);
        return addCardToZone("Counterspell", ai, ZoneType.Hand);
    }

    private static AiDecision choose(StackResponseDecisionContext context, String optionId) {
        if ("FIRST".equals(optionId)) optionId = context.legalActions().get(0).actionId();
        if ("SECOND".equals(optionId)) optionId = context.legalActions().get(1).actionId();
        return new AiDecision(context.decisionId(), context.stateFingerprint(), optionId);
    }

    @Test
    public void disabledPathUsesLegacyAndDoesNotCallProvider() {
        Game game = initAndCreateGame();
        Card counter = addCounterspell(game);
        addOpponentSpellToStack(game);
        AtomicInteger calls = new AtomicInteger();
        PlayerControllerAi controller = controller(game, context -> {
            calls.incrementAndGet();
            return Optional.empty();
        }, false, 3);

        controller.chooseSpellAbilityToPlay();
        Assert.assertEquals(calls.get(), 0);
        Assert.assertTrue(AiDecisionMetrics.stackResponseEvents().isEmpty());
    }

    @Test
    public void providerCanSelectPreparedCounterAndRetainsTarget() {
        Game game = initAndCreateGame();
        Card counter = addCounterspell(game);
        SpellAbility pending = addOpponentSpellToStack(game);
        AtomicReference<StackResponseDecisionContext> captured = new AtomicReference<>();
        PlayerControllerAi controller = controller(game, context -> {
            StackResponseDecisionContext stack = (StackResponseDecisionContext) context;
            captured.set(stack);
            return Optional.of(choose(stack, "FIRST"));
        }, true, 3);

        SpellAbility selected = controller.chooseSpellAbilityToPlay().get(0);
        Assert.assertEquals(selected.getHostCard(), counter);
        Assert.assertTrue(selected.getTargets().contains(pending));
        Assert.assertEquals(captured.get().type().name(), "STACK_RESPONSE");
        Assert.assertFalse(captured.get().stackItems().isEmpty());
        Assert.assertFalse(AiDecisionMetrics.stackResponseEvents().get(0).fallback());
    }

    @Test
    public void providerCanPassPriority() {
        Game game = initAndCreateGame();
        addCounterspell(game);
        addOpponentSpellToStack(game);
        PlayerControllerAi controller = controller(game, context -> {
            StackResponseDecisionContext stack = (StackResponseDecisionContext) context;
            return Optional.of(choose(stack, StackResponseDecisionContext.PASS_OPTION_ID));
        }, true, 3);

        Assert.assertNull(controller.chooseSpellAbilityToPlay());
        Assert.assertTrue(AiDecisionMetrics.stackResponseEvents().get(0).passSelected());
    }

    @Test
    public void providerCanSelectSecondPreparedCounter() {
        Game game = initAndCreateGame();
        addCounterspell(game);
        addCards("Island", 2, game.getPlayers().get(1));
        Card second = addCardToZone("Essence Scatter", game.getPlayers().get(1), ZoneType.Hand);
        SpellAbility pending = addOpponentSpellToStack(game);
        AtomicReference<StackResponseDecisionContext> captured = new AtomicReference<>();
        PlayerControllerAi controller = controller(game, context -> {
            StackResponseDecisionContext stack = (StackResponseDecisionContext) context;
            captured.set(stack);
            return Optional.of(choose(stack, "SECOND"));
        }, true, 3);

        SpellAbility selected = controller.chooseSpellAbilityToPlay().get(0);
        Assert.assertEquals(captured.get().legalActions().size(), 2);
        Assert.assertEquals(selected.getHostCard(), second);
        Assert.assertTrue(selected.getTargets().contains(pending));
        Assert.assertEquals(AiDecisionMetrics.stackResponseEvents().get(0).selectedResponseId(),
                captured.get().legalActions().get(1).actionId());
    }

    @Test
    public void providerFailureFallsBackAndIsRecorded() {
        Game game = initAndCreateGame();
        addCounterspell(game);
        addOpponentSpellToStack(game);
        forge.ai.decision.AiDecisionProvider provider = new forge.ai.decision.AiDecisionProvider() {
            @Override
            public Optional<AiDecision> choose(forge.ai.decision.AiDecisionContext context) {
                return Optional.empty();
            }

            @Override
            public forge.ai.decision.AiDecisionResult chooseWithDiagnostics(
                    forge.ai.decision.AiDecisionContext context) {
                return forge.ai.decision.AiDecisionResult.failed(AiDecisionFailureReason.TIMEOUT,
                        "test", "test", 7);
            }
        };

        controller(game, provider, true, 3).chooseSpellAbilityToPlay();

        Assert.assertEquals(AiDecisionMetrics.stackResponseEvents().get(0).fallbackReason(),
                AiDecisionFailureReason.TIMEOUT);
        Assert.assertTrue(AiDecisionMetrics.stackResponseEvents().get(0).fallback());
        Assert.assertEquals(AiDecisionMetrics.stackResponseEvents().get(0).selectedResponseId(),
                AiDecisionMetrics.stackResponseEvents().get(0).heuristicResponseId());
    }

    @Test
    public void stackFingerprintIgnoresInternalRecommendationsAndTracksVisibleData() {
        Game game = initAndCreateGame();
        Card counter = addCounterspell(game);
        SpellAbility pending = addOpponentSpellToStack(game);
        Player ai = game.getPlayers().get(1);
        SpellAbility ability = counter.getFirstSpellAbility();
        ability.setActivatingPlayer(ai);
        ability.getTargets().add(pending);
        PreparedAiAction action = new PreparedAiAction("ACTION_0", ability, ability, counter,
                PreparedAiAction.ActionCategory.SPELL, 0, "Counter creature spell", true);
        PreparedAiAction internalChange = new PreparedAiAction("ACTION_0", ability, ability, counter,
                PreparedAiAction.ActionCategory.SPELL, 42, action.description(), false);
        StackResponseDecisionContext first = MainPhaseStateProjector.projectStackResponse(ai, "first", List.of(action));
        Assert.assertNull(first.legalActions().get(0).forgeRecommendation());
        Assert.assertEquals(MainPhaseStateProjector.projectStackResponse(ai, "another-id", List.of(action))
                .stateFingerprint(), first.stateFingerprint());
        Assert.assertEquals(MainPhaseStateProjector.projectStackResponse(ai, "internal", List.of(internalChange))
                .stateFingerprint(), first.stateFingerprint());
        Assert.assertNotEquals(MainPhaseStateProjector.project(ai, "main", List.of(action)).state().fingerprint(),
                MainPhaseStateProjector.project(ai, "main", List.of(internalChange)).state().fingerprint());
        addCardToZone("Black Lotus", game.getPlayers().get(0), ZoneType.Hand);
        Assert.assertEquals(MainPhaseStateProjector.projectStackResponse(ai, "hidden", List.of(action))
                .stateFingerprint(), first.stateFingerprint());
        PreparedAiAction visibleChange = new PreparedAiAction("ACTION_0", ability, ability, counter,
                action.category(), 0, "Different visible description", true);
        Assert.assertNotEquals(MainPhaseStateProjector.projectStackResponse(ai, "visible", List.of(visibleChange))
                .stateFingerprint(), first.stateFingerprint());
        PreparedAiAction second = new PreparedAiAction("ACTION_1", ability, ability, counter,
                action.category(), 1, "Second response", true);
        Assert.assertNotEquals(MainPhaseStateProjector.projectStackResponse(ai, "order", List.of(action, second))
                .stateFingerprint(), MainPhaseStateProjector.projectStackResponse(ai, "order", List.of(second, action))
                .stateFingerprint());
        game.getPlayers().get(0).setLife(19, null);
        Assert.assertNotEquals(MainPhaseStateProjector.projectStackResponse(ai, "life", List.of(action))
                .stateFingerprint(), first.stateFingerprint());
        game.getPlayers().get(0).setLife(20, null);
        pending.getTargets().add(ai);
        Assert.assertNotEquals(MainPhaseStateProjector.projectStackResponse(ai, "stack-target", List.of(action))
                .stateFingerprint(), first.stateFingerprint());
    }

    @Test
    public void secondHeuristicResponseRemainsInternalForAgreementAndFallback() throws Exception {
        for (String selection : List.of("FIRST", "SECOND", "FAIL")) {
            AiDecisionMetrics.reset();
            Path audit = Files.createTempFile("forge-stack-heuristic-", ".jsonl");
            try {
                AiDecisionMetrics.configureDecisionAudit(audit);
                Game game = initAndCreateGame();
                Card first = addCounterspell(game);
                addCards("Island", 2, game.getPlayers().get(1));
                Card second = addCardToZone("Essence Scatter", game.getPlayers().get(1), ZoneType.Hand);
                // Avoid Forge's probabilistic choice to conserve counters against low-CMC spells.
                Player opponent = game.getPlayers().get(0);
                Card threat = addCardToZone("Colossal Dreadmaw", opponent, ZoneType.Hand);
                SpellAbility pending = threat.getFirstSpellAbility();
                pending.setActivatingPlayer(opponent);
                game.getStackZone().add(threat);
                game.getStack().add(pending);
                first.getFirstSpellAbility().setActivatingPlayer(game.getPlayers().get(1));
                second.getFirstSpellAbility().setActivatingPlayer(game.getPlayers().get(1));
                AtomicReference<StackResponseDecisionContext> captured = new AtomicReference<>();
                PlayerControllerAi controller = controller(game, context -> {
                    StackResponseDecisionContext stack = (StackResponseDecisionContext) context;
                    captured.set(stack);
                    return "FAIL".equals(selection) ? Optional.empty() : Optional.of(choose(stack, selection));
                }, true, 3);
                SpellAbility selected = controller.chooseSpellAbilityToPlay().get(0);
                Assert.assertNotNull(captured.get());
                Assert.assertEquals(captured.get().legalActions().stream().map(action -> action.sourceName()).toList(),
                        List.of("Counterspell", "Essence Scatter"));
                captured.get().legalActions().forEach(action -> Assert.assertNull(action.forgeRecommendation()));
                var event = AiDecisionMetrics.stackResponseEvents().get(0);
                Assert.assertEquals(selected.getHostCard(), "FIRST".equals(selection) ? first : second,
                        event.toString());
                String heuristicId = captured.get().legalActions().get(1).actionId();
                Assert.assertEquals(event.heuristicResponseId(), heuristicId);
                Assert.assertEquals(event.selectedResponseId(), "FIRST".equals(selection)
                        ? captured.get().legalActions().get(0).actionId() : heuristicId);
                Assert.assertEquals(event.fallback(), "FAIL".equals(selection));
                AiDecisionMetrics.completeGame(game.getId(), game.getPlayers().get(1).getName(), false, 10, 1);
                var json = com.google.gson.JsonParser.parseString(Files.readString(audit)).getAsJsonObject();
                Assert.assertEquals(json.get("heuristicResponseId").getAsString(), heuristicId);
                Assert.assertEquals(json.get("agreement").getAsBoolean(), !"FIRST".equals(selection));
                Assert.assertTrue(json.has("internalActionDetails"));
                Assert.assertFalse(json.has("legalActions"));
                Assert.assertFalse(json.toString().contains("PREFERRED"));
            } finally {
                AiDecisionMetrics.reset();
                Files.deleteIfExists(audit);
            }
        }
    }

    @Test
    public void instantRemovalIsPreparedWithItsTargetAndHiddenZonesAreAbsent() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        addCounterspell(game);
        addCard("Mountain", ai);
        Card shock = addCardToZone("Shock", ai, ZoneType.Hand);
        Card creature = addCard("Runeclaw Bear", opponent);
        addCardToZone("Black Lotus", opponent, ZoneType.Hand);
        addCardToZone("Ancestral Recall", opponent, ZoneType.Library);
        addOpponentSpellToStack(game);
        AtomicReference<StackResponseDecisionContext> captured = new AtomicReference<>();
        PlayerControllerAi controller = controller(game, context -> {
            StackResponseDecisionContext stack = (StackResponseDecisionContext) context;
            captured.set(stack);
            String option = stack.legalActions().stream()
                    .filter(action -> shock.getName().equals(action.sourceName()))
                    .findFirst().orElseThrow().actionId();
            return Optional.of(choose(stack, option));
        }, true, 3);

        SpellAbility selected = controller.chooseSpellAbilityToPlay().get(0);
        Assert.assertEquals(selected.getHostCard(), shock);
        Assert.assertTrue(selected.getTargets().contains(creature));
        Assert.assertFalse(captured.get().toString().contains("Black Lotus"));
        Assert.assertFalse(captured.get().toString().contains("Ancestral Recall"));
    }

    @Test
    public void ownStackMechanicalPathDoesNotCallProvider() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card card = addCardToZone("Runeclaw Bear", ai, ZoneType.Hand);
        SpellAbility spell = card.getFirstSpellAbility();
        spell.setActivatingPlayer(ai);
        game.getStackZone().add(card);
        game.getStack().add(spell);
        AtomicInteger calls = new AtomicInteger();

        controller(game, context -> { calls.incrementAndGet(); return Optional.empty(); }, true, 3)
                .chooseSpellAbilityToPlay();

        Assert.assertEquals(calls.get(), 0);
    }

    @Test
    public void auditJsonlContainsSanitizedStackAndPassOption() throws Exception {
        Path audit = Files.createTempFile("forge-stack-response-", ".jsonl");
        try {
            AiDecisionMetrics.configureDecisionAudit(audit);
            Game game = initAndCreateGame();
            addCounterspell(game);
            addOpponentSpellToStack(game);
            Player ai = game.getPlayers().get(1);
            PlayerControllerAi controller = controller(game, context -> {
                StackResponseDecisionContext stack = (StackResponseDecisionContext) context;
                return Optional.of(choose(stack, StackResponseDecisionContext.PASS_OPTION_ID));
            }, true, 3);
            controller.chooseSpellAbilityToPlay();
            AiDecisionMetrics.completeGame(game.getId(), ai.getName(), false, 10, 1);

            String json = Files.readString(audit);
            Assert.assertTrue(json.contains("\"decisionType\":\"STACK_RESPONSE\""));
            Assert.assertTrue(json.contains("\"passAvailable\":true"));
            Assert.assertTrue(json.contains("\"stackItems\""));
            Assert.assertFalse(json.contains("forge.game"));
        } finally {
            AiDecisionMetrics.reset();
            Files.deleteIfExists(audit);
        }
    }

    @Test
    public void invalidAndStaleResponsesFallBackToForge() {
        assertFallback("ACTION_99", false, AiDecisionFailureReason.ACTION_ID_MISMATCH);
        assertFallback("FIRST", true, AiDecisionFailureReason.STALE_ACTION_SET);
        assertFallback("ACTION_0", false, AiDecisionFailureReason.ACTION_ID_MISMATCH);
    }

    private void assertFallback(String optionId, boolean mutate, AiDecisionFailureReason reason) {
        AiDecisionMetrics.reset();
        Game game = initAndCreateGame();
        Card counter = addCounterspell(game);
        addOpponentSpellToStack(game);
        PlayerControllerAi controller = controller(game, context -> {
            StackResponseDecisionContext stack = (StackResponseDecisionContext) context;
            if (mutate) {
                game.getPlayers().get(0).setLife(19, null);
            }
            return Optional.of(choose(stack, optionId));
        }, true, 3);

        java.util.List<SpellAbility> result = controller.chooseSpellAbilityToPlay();
        if (result != null) {
            Assert.assertEquals(result.get(0).getHostCard(), counter);
        }
        Assert.assertEquals(AiDecisionMetrics.stackResponseEvents().get(0).fallbackReason(), reason);
    }

    @Test
    public void maxBoundAndEmptyStackRoutingAreEnforced() {
        Game game = initAndCreateGame();
        addCounterspell(game);
        addCardToZone("Essence Scatter", game.getPlayers().get(1), ZoneType.Hand);
        addOpponentSpellToStack(game);
        AtomicReference<StackResponseDecisionContext> captured = new AtomicReference<>();
        PlayerControllerAi controller = controller(game, context -> {
            StackResponseDecisionContext stack = (StackResponseDecisionContext) context;
            captured.set(stack);
            return Optional.of(choose(stack, StackResponseDecisionContext.PASS_OPTION_ID));
        }, true, 1);
        controller.chooseSpellAbilityToPlay();
        Assert.assertEquals(captured.get().legalActions().size(), 1);

        Game empty = initAndCreateGame();
        addCounterspell(empty);
        AtomicInteger calls = new AtomicInteger();
        controller(empty, context -> { calls.incrementAndGet(); return Optional.empty(); }, true, 3)
                .chooseSpellAbilityToPlay();
        Assert.assertEquals(calls.get(), 0);
    }
}
