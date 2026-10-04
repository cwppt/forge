package forge.ai;

import forge.ai.decision.AiDecision;
import forge.ai.decision.AiDecisionFailureReason;
import forge.ai.decision.AiDecisionMetrics;
import forge.ai.decision.AiDecisionProvider;
import forge.ai.decision.AiDecisionResult;
import forge.ai.decision.MainPhaseDecisionContext;
import forge.ai.decision.MainPhaseCardView;
import forge.ai.decision.MainPhasePlayerState;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
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
        return controller(game, provider, enabled, 3);
    }

    private PlayerControllerAi controller(Game game, AiDecisionProvider provider, boolean enabled, int maxActions) {
        Player player = game.getPlayers().get(1);
        PlayerControllerAi controller = new PlayerControllerAi(game, player, player.getLobbyPlayer(), provider,
                false, enabled, maxActions);
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
        Assert.assertTrue(captured.get().legalActions().stream()
                .allMatch(action -> action.cost() != null && !action.cost().isBlank()));
        Assert.assertTrue(captured.get().legalActions().stream()
                .allMatch(action -> action.cost().contains("{R}")));
        Assert.assertEquals(AiDecisionMetrics.actionEvents().get(0).selectedActionId(), "ACTION_1");
        AiDecisionMetrics.MainPhaseRoutingDiagnostics routing = AiDecisionMetrics.mainPhaseRoutingDiagnostics();
        Assert.assertEquals(routing.windowsConsidered(), 1L);
        Assert.assertEquals(routing.twoOrMoreSafeActions(), 1L);
        Assert.assertEquals(routing.exposedCandidates(), 2L);
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
        Assert.assertEquals(AiDecisionMetrics.mainPhaseRoutingDiagnostics().windowsConsidered(), 0L);
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
        AiDecisionMetrics.MainPhaseRoutingDiagnostics routing = AiDecisionMetrics.mainPhaseRoutingDiagnostics();
        Assert.assertEquals(routing.oneSafeAction(), 1L);
        Assert.assertEquals(routing.exposedCandidates(), 0L);
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
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0));
        AtomicInteger calls = new AtomicInteger();
        PlayerControllerAi controller = controller(game, context -> {
            calls.incrementAndGet();
            return Optional.empty();
        }, true);

        controller.chooseSpellAbilityToPlay();

        Assert.assertEquals(calls.get(), 0);
        Assert.assertEquals(AiDecisionMetrics.mainPhaseRoutingDiagnostics().notActivePlayer(), 1L);
    }

    @Test
    public void routingDiagnosticsCountNonemptyStackAndZeroSafeActions() {
        Game stackGame = initAndCreateGame();
        addTwoBurnSpells(stackGame);
        Player opponent = stackGame.getPlayers().get(0);
        Card pendingCard = addCardToZone("Runeclaw Bear", opponent, ZoneType.Hand);
        SpellAbility pendingSpell = pendingCard.getFirstSpellAbility();
        pendingSpell.setActivatingPlayer(opponent);
        stackGame.getStackZone().add(pendingCard);
        stackGame.getStack().add(pendingSpell);
        AtomicInteger calls = new AtomicInteger();
        PlayerControllerAi stackController = controller(stackGame, context -> {
            calls.incrementAndGet();
            return Optional.empty();
        }, true);
        stackController.chooseSpellAbilityToPlay();
        Assert.assertEquals(calls.get(), 0);
        Assert.assertEquals(AiDecisionMetrics.mainPhaseRoutingDiagnostics().stackNonempty(), 1L);

        AiDecisionMetrics.reset();
        Game zeroGame = initAndCreateGame();
        addCardToZone("Shock", zeroGame.getPlayers().get(1), ZoneType.Hand);
        PlayerControllerAi zeroController = controller(zeroGame, context -> {
            calls.incrementAndGet();
            return Optional.empty();
        }, true);
        zeroController.chooseSpellAbilityToPlay();
        AiDecisionMetrics.MainPhaseRoutingDiagnostics routing = AiDecisionMetrics.mainPhaseRoutingDiagnostics();
        Assert.assertEquals(routing.zeroSafeActions(), 1L);
        Assert.assertTrue(routing.rawCandidates() >= 1L);
        Assert.assertEquals(routing.exposedCandidates(), 0L);
    }

    @Test
    public void routingDiagnosticsResetAndSummarizeEnumerationFailure() {
        AiDecisionMetrics.recordMainPhaseWindowConsidered();
        AiDecisionMetrics.recordMainPhaseEnumerationFailure();
        Assert.assertTrue(AiDecisionMetrics.summary(0).contains("mainPhaseEnumerationFailures=1"));

        AiDecisionMetrics.reset();
        Assert.assertEquals(AiDecisionMetrics.mainPhaseRoutingDiagnostics().windowsConsidered(), 0L);
        Assert.assertEquals(AiDecisionMetrics.mainPhaseRoutingDiagnostics().enumerationFailures(), 0L);
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
        AtomicReference<String> projectedCost = new AtomicReference<>();
        PlayerControllerAi controller = controller(game, context -> {
            MainPhaseDecisionContext main = (MainPhaseDecisionContext) context;
            var xAction = main.legalActions().stream().filter(action -> action.xValue() != null)
                    .findFirst().orElseThrow();
            projectedCost.set(xAction.cost());
            String actionId = xAction.actionId();
            return Optional.of(choose(main, actionId));
        }, true);

        SpellAbility selected = controller.chooseSpellAbilityToPlay().get(0);

        Assert.assertEquals(selected.getXManaCostPaid(), Integer.valueOf(3));
        Assert.assertTrue(projectedCost.get().contains("X"));
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
        MainPhaseCardView hidden = context.state().players().stream().filter(state -> !state.self())
                .flatMap(state -> state.battlefield().stream())
                .filter(card -> "Face-down card".equals(card.name())).findFirst().orElseThrow();
        Assert.assertNull(hidden.power());
        Assert.assertNull(hidden.toughness());
        Assert.assertNull(hidden.markedDamage());
        Assert.assertTrue(hidden.counters().isEmpty());
        assertNoEngineObjects(context, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    @Test
    public void projectionIncludesExplicitManaPreparedCostsAndVisibleCombatState() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card[] spells = addTwoBurnSpells(game);
        Card greenSource = addCard("Forest", ai);
        Card colorlessSource = addCard("Wastes", ai);
        ai.getManaPool().addMana(new Mana((byte) ManaAtom.GREEN, greenSource, null, ai));
        ai.getManaPool().addMana(new Mana((byte) ManaAtom.GREEN, greenSource, null, ai));
        ai.getManaPool().addMana(new Mana((byte) ManaAtom.COLORLESS, colorlessSource, null, ai));
        Card creature = addCard("Runeclaw Bear", opponent);
        creature.setDamage(1);
        creature.addCounterInternal(CounterEnumType.P1P1, 2, opponent, false, null, null);
        creature.addCounterInternal(CounterEnumType.STUN, 1, opponent, false, null, null);
        PreparedAiActionSet set = controller(game, null, false).getAi().prepareCandidateActions(
                List.of(spells[0].getSpellAbilities().get(0), spells[1].getSpellAbilities().get(0)), false, 3);

        MainPhaseDecisionContext context = MainPhaseStateProjector.project(ai, "projection", set.actions());
        MainPhasePlayerState self = context.state().players().stream()
                .filter(MainPhasePlayerState::self).findFirst().orElseThrow();
        Assert.assertEquals(self.mana().green(), 2);
        Assert.assertEquals(self.mana().colorless(), 1);
        Assert.assertEquals(self.mana().total(), 3);
        Assert.assertEquals(self.mana().white(), 0);
        Assert.assertFalse(self.mana().toString().contains("forge.game."));
        Assert.assertTrue(context.legalActions().stream().allMatch(action -> "{R}".equals(action.cost())));

        MainPhaseCardView projectedCreature = context.state().players().stream()
                .filter(state -> !state.self()).flatMap(state -> state.battlefield().stream())
                .filter(card -> "Runeclaw Bear".equals(card.name())).findFirst().orElseThrow();
        Assert.assertEquals(projectedCreature.power(), Integer.valueOf(4));
        Assert.assertEquals(projectedCreature.toughness(), Integer.valueOf(4));
        Assert.assertEquals(projectedCreature.markedDamage(), Integer.valueOf(1));
        Assert.assertEquals(projectedCreature.counters().stream().map(counter -> counter.name()).toList()
                .stream().sorted().toList(), projectedCreature.counters().stream()
                .map(counter -> counter.name()).toList());
        Assert.assertTrue(projectedCreature.counters().stream().anyMatch(counter -> counter.amount() == 2));

        String structural = self.mana() + " " + context.legalActions().stream()
                .map(action -> action.cost()).toList() + " " + projectedCreature.counters();
        Assert.assertFalse(structural.contains("forge.game."));
        Assert.assertFalse(structural.contains("forge.ai."));
    }

    @Test
    public void strategicFingerprintIsDeterministicAndTracksManaAndCombatState() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card creature = addCard("Runeclaw Bear", opponent);

        String initial = MainPhaseStateProjector.project(ai, "one", List.of()).state().fingerprint();
        String equivalent = MainPhaseStateProjector.project(ai, "two", List.of()).state().fingerprint();
        Assert.assertEquals(equivalent, initial);

        Card source = addCard("Forest", ai);
        ai.getManaPool().addMana(new Mana(MagicColor.GREEN, source, null, ai));
        String withMana = MainPhaseStateProjector.project(ai, "three", List.of()).state().fingerprint();
        Assert.assertNotEquals(withMana, initial);

        creature.setDamage(1);
        String withDamage = MainPhaseStateProjector.project(ai, "four", List.of()).state().fingerprint();
        Assert.assertNotEquals(withDamage, withMana);
    }

    @Test
    public void auditJsonlContainsSanitizedRequestResponseAndOutcome() throws Exception {
        Path audit = Files.createTempFile("forge-main-phase-audit", ".jsonl");
        AiDecisionMetrics.configureDecisionAudit(audit);
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        addTwoBurnSpells(game);
        addCardToZone("Black Lotus", opponent, ZoneType.Hand);
        addCardToZone("Ancestral Recall", ai, ZoneType.Library);
        addCardToZone("Hill Giant", opponent, ZoneType.Battlefield).turnFaceDownNoUpdate();
        AiDecisionMetrics.registerGame(game.getId(), 7, 1234L);
        PlayerControllerAi controller = controller(game, context -> {
            MainPhaseDecisionContext main = (MainPhaseDecisionContext) context;
            return Optional.of(choose(main, "ACTION_1"));
        }, true);

        controller.chooseSpellAbilityToPlay();
        AiDecisionMetrics.completeGame(game.getId(), ai.getName(), false, 9876, 12);
        String json = Files.readString(audit);

        Assert.assertTrue(json.contains("\"decisionType\":\"MAIN_PHASE_ACTION\""));
        Assert.assertTrue(json.contains("\"gameIndex\":7"));
        Assert.assertTrue(json.contains("\"runSeed\":1234"));
        Assert.assertTrue(json.contains("\"state\":"));
        Assert.assertTrue(json.contains("\"legalActions\":"));
        Assert.assertTrue(json.contains("\"heuristicActionId\":\"ACTION_0\""));
        Assert.assertTrue(json.contains("\"selectedActionId\":\"ACTION_1\""));
        Assert.assertTrue(json.contains("\"returnedOptionId\":\"ACTION_1\""));
        Assert.assertTrue(json.contains("\"responseAccepted\":true"));
        Assert.assertTrue(json.contains("\"decidingPlayerWon\":true"));
        Assert.assertTrue(json.contains("\"gameDurationMs\":9876"));
        Assert.assertFalse(json.contains("Black Lotus"));
        Assert.assertFalse(json.contains("Ancestral Recall"));
        Assert.assertFalse(json.contains("Hill Giant"));
        Assert.assertFalse(json.contains("apiKey"));
    }

    @Test
    public void disabledAuditCreatesNoOutputAndSummaryTracksPositionsAndTruncation() throws Exception {
        Path audit = Files.createTempDirectory("forge-disabled-audit").resolve("disabled.jsonl");
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        addCards("Mountain", 5, ai);
        addCardToZone("Shock", ai, ZoneType.Hand);
        addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
        addCardToZone("Lava Spike", ai, ZoneType.Hand);
        game.getPlayers().get(0).setLife(2, null);
        PlayerControllerAi controller = controller(game, context -> {
            MainPhaseDecisionContext main = (MainPhaseDecisionContext) context;
            return Optional.of(choose(main, "ACTION_1"));
        }, true, 2);

        controller.chooseSpellAbilityToPlay();
        AiDecisionMetrics.completeGame(game.getId(), ai.getName(), false, 10, 2);

        Assert.assertFalse(Files.exists(audit));
        Assert.assertTrue(AiDecisionMetrics.actionEvents().get(0).candidateSetTruncated());
        String summary = AiDecisionMetrics.summary(1);
        Assert.assertTrue(summary.contains("mainPhaseTwoCandidates=1"));
        Assert.assertTrue(summary.contains("mainPhaseTruncated=1"));
        Assert.assertTrue(summary.contains("mainPhaseDisagreements=1"));
        Assert.assertTrue(summary.contains("selectedPositions={1=1}"));
    }

    @Test
    public void auditKeepsDistinctMetadataAcrossGames() throws Exception {
        Path audit = Files.createTempFile("forge-multi-game-audit", ".jsonl");
        AiDecisionMetrics.configureDecisionAudit(audit);
        for (int index = 0; index < 2; index++) {
            Game game = initAndCreateGame();
            Player ai = game.getPlayers().get(1);
            addTwoBurnSpells(game);
            AiDecisionMetrics.registerGame(game.getId(), index, 5000L + index);
            PlayerControllerAi controller = controller(game, context -> {
                MainPhaseDecisionContext main = (MainPhaseDecisionContext) context;
                return Optional.of(choose(main, "ACTION_0"));
            }, true);
            controller.chooseSpellAbilityToPlay();
            AiDecisionMetrics.completeGame(game.getId(), ai.getName(), false, 100 + index, 3 + index);
        }

        String json = Files.readString(audit);
        Assert.assertEquals(json.lines().count(), 2);
        Assert.assertTrue(json.contains("\"gameIndex\":0"));
        Assert.assertTrue(json.contains("\"gameIndex\":1"));
        Assert.assertTrue(json.contains("\"runSeed\":5000"));
        Assert.assertTrue(json.contains("\"runSeed\":5001"));
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
