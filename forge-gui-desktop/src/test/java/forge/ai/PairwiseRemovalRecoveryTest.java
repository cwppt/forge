package forge.ai;

import forge.ai.decision.AiDecision;
import forge.ai.decision.AiDecisionProvider;
import forge.ai.decision.AiDecisionMetrics;
import forge.ai.decision.MainPhaseDecisionContext;
import forge.ai.decision.LegalActionView;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

public class PairwiseRemovalRecoveryTest extends AITest {
    @BeforeMethod
    public void resetMetrics() {
        AiDecisionMetrics.reset();
    }

    private record Fixture(Game game, Player ai, Player enemy, Card source, Card nativeCard, Card first, Card second) {}

    private Fixture fixture() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player enemy = game.getPlayers().get(0);
        enemy.setLife(2, null);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getPhaseHandler().setPriority(ai);

        addCards("Swamp", 5, ai);
        addCards("Mountain", 2, ai);

        Card deadRingers = addCardToZone("Dead Ringers", ai, ZoneType.Hand);
        Card nativeCard = addCardToZone("Shock", ai, ZoneType.Hand);
        Card runeclawBear = addCardToZone("Runeclaw Bear", enemy, ZoneType.Battlefield);
        Card grizzlyBearsEnemy = addCardToZone("Grizzly Bears", enemy, ZoneType.Battlefield);

        return new Fixture(game, ai, enemy, deadRingers, nativeCard, runeclawBear, grizzlyBearsEnemy);
    }

    private PlayerControllerAi controller(Fixture f, AiDecisionProvider provider, boolean enabled) {
        PlayerControllerAi controller = new PlayerControllerAi(f.game(), f.ai(), f.ai().getLobbyPlayer(), provider, false, enabled, 4);
        f.ai().dangerouslySetController(controller);
        return controller;
    }

    @Test
    public void testPairwiseRemovalEnabled() {
        Fixture f = fixture();
        AtomicReference<MainPhaseDecisionContext> captured = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();

        PlayerControllerAi controller = controller(f, context -> {
            MainPhaseDecisionContext main = (MainPhaseDecisionContext) context;
            captured.set(main);
            calls.incrementAndGet();

            List<LegalActionView> legalActions = main.legalActions();
            LegalActionView selectedAction = legalActions.stream()
                .filter(action -> action.sourceName().equals("Dead Ringers"))
                .findFirst()
                .orElseThrow();

            return Optional.of(new AiDecision(main.decisionId(), main.state().fingerprint(), selectedAction.actionId()));
        }, true);

        List<SpellAbility> selected = controller.chooseSpellAbilityToPlay();

        Assert.assertNotNull(selected);
        Assert.assertEquals(selected.size(), 1);
        Assert.assertSame(selected.get(0).getHostCard(), f.source);

        Assert.assertEquals(selected.get(0).getTargets().size(), 2);
        Assert.assertTrue(selected.get(0).getTargets().stream().anyMatch(t -> t == f.first()));
        Assert.assertTrue(selected.get(0).getTargets().stream().anyMatch(t -> t == f.second()));

        Assert.assertEquals(captured.get().legalActions().size(), 2);
        Assert.assertEquals(captured.get().legalActions().stream().map(LegalActionView::actionId).distinct().count(), 2);
        Assert.assertTrue(captured.get().legalActions().stream()
            .anyMatch(option -> option.targets().stream().anyMatch(target -> target.contains("colors="))));

        Assert.assertTrue(ComputerUtil.playStack(selected.get(0), f.ai(), f.game()));
        f.game().getStack().resolveStack();

        Assert.assertEquals(f.game().getCardState(f.source(), null).getZone().getZoneType(), ZoneType.Graveyard);
        Assert.assertEquals(f.first().getZone().getZoneType(), ZoneType.Graveyard);
        Assert.assertEquals(f.second().getZone().getZoneType(), ZoneType.Graveyard);

        Assert.assertEquals(f.ai().getCardsIn(ZoneType.Battlefield).stream().filter(Card::isTapped).count(), 5);

        Assert.assertFalse(AiDecisionMetrics.actionEvents().get(0).fallback());
        Assert.assertEquals(calls.get(), 1);
    }

    @Test
    public void testPairwiseRemovalDisabled() {
        Fixture f = fixture();
        AtomicInteger calls = new AtomicInteger();

        PlayerControllerAi controller = controller(f, context -> {
            calls.incrementAndGet();
            throw new AssertionError("Provider should not be invoked when disabled");
        }, false);

        List<SpellAbility> selected = controller.chooseSpellAbilityToPlay();

        Assert.assertNotNull(selected);
        Assert.assertEquals(selected.size(), 1);
        Assert.assertSame(selected.get(0).getHostCard(), f.nativeCard);

        Assert.assertEquals(calls.get(), 0);
        Assert.assertTrue(AiDecisionMetrics.actionEvents().isEmpty());
    }

    @Test
    public void wrongDecisionIdentityFallsBack() {
        Fixture f = fixture();
        AtomicReference<MainPhaseDecisionContext> captured = new AtomicReference<>();

        PlayerControllerAi controller = controller(f, context -> {
            MainPhaseDecisionContext main = (MainPhaseDecisionContext) context;
            captured.set(main);
            return Optional.of(new AiDecision("wrong", main.state().fingerprint(), main.legalActions().stream()
                .filter(a -> a.sourceName().equals("Dead Ringers"))
                .findFirst()
                .orElseThrow()
                .actionId()));
        }, true);

        List<SpellAbility> selected = controller.chooseSpellAbilityToPlay();

        Assert.assertNotNull(selected);
        Assert.assertEquals(selected.size(), 1);
        Assert.assertSame(selected.get(0).getHostCard(), f.nativeCard);

        Assert.assertTrue(AiDecisionMetrics.actionEvents().get(0).fallback());
        Assert.assertEquals(AiDecisionMetrics.actionEvents().get(0).fallbackReason(), forge.ai.decision.AiDecisionFailureReason.DECISION_ID_MISMATCH);
    }

    @Test
    public void targetColorChangesAfterRequestFallBack() {
        Fixture f = fixture();
        AtomicInteger calls = new AtomicInteger();

        PlayerControllerAi controller = controller(f, context -> {
            MainPhaseDecisionContext main = (MainPhaseDecisionContext) context;
            calls.incrementAndGet();
            String id = main.legalActions().stream()
                .filter(a -> a.sourceName().equals("Dead Ringers"))
                .findFirst()
                .orElseThrow()
                .actionId();
            f.first().setColor("Blue");
            return Optional.of(new AiDecision(main.decisionId(), main.state().fingerprint(), id));
        }, true);

        List<SpellAbility> selected = controller.chooseSpellAbilityToPlay();

        Assert.assertNotNull(selected);
        Assert.assertEquals(selected.size(), 1);
        Assert.assertSame(selected.get(0).getHostCard(), f.nativeCard);

        Assert.assertTrue(AiDecisionMetrics.actionEvents().get(0).fallback());
        Assert.assertTrue(AiDecisionMetrics.actionEvents().get(0).staleOrRevalidationFailure());
        Assert.assertEquals(calls.get(), 1);
    }

    @Test
    public void sameColorChangeIsStaleEvenWhenPairStillMatches() {
        Fixture f = fixture();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicReference<MainPhaseDecisionContext> captured = new AtomicReference<>();

        PlayerControllerAi controller = controller(f, context -> {
            MainPhaseDecisionContext main = (MainPhaseDecisionContext) context;
            captured.set(main);
            providerCalls.incrementAndGet();
            String id = main.legalActions().stream()
                .filter(a -> a.sourceName().equals("Dead Ringers"))
                .findFirst()
                .orElseThrow()
                .actionId();
            f.first().setColor("Blue");
            f.second().setColor("Blue");
            return Optional.of(new AiDecision(main.decisionId(), main.state().fingerprint(), id));
        }, true);

        List<SpellAbility> selected = controller.chooseSpellAbilityToPlay();

        Assert.assertNotNull(selected);
        Assert.assertEquals(selected.size(), 1);
        Assert.assertSame(selected.get(0).getHostCard(), f.nativeCard);

        Assert.assertEquals(providerCalls.get(), 1);
        Assert.assertTrue(AiDecisionMetrics.actionEvents().get(0).staleOrRevalidationFailure());
    }

    @Test
    public void cannotPayRecoveredCostAfterProviderFallsBack() {
        Fixture f = fixture();
        AtomicInteger providerCalls = new AtomicInteger();

        PlayerControllerAi controller = controller(f, context -> {
            MainPhaseDecisionContext main = (MainPhaseDecisionContext) context;
            providerCalls.incrementAndGet();
            String id = main.legalActions().stream()
                .filter(a -> a.sourceName().equals("Dead Ringers"))
                .findFirst()
                .orElseThrow()
                .actionId();
            f.ai().getCardsIn(ZoneType.Battlefield).stream()
                .filter(c -> c.getName().equals("Swamp"))
                .forEach(c -> c.setTapped(true));
            return Optional.of(new AiDecision(main.decisionId(), main.state().fingerprint(), id));
        }, true);

        List<SpellAbility> selected = controller.chooseSpellAbilityToPlay();

        Assert.assertNotNull(selected);
        Assert.assertEquals(selected.size(), 1);
        Assert.assertSame(selected.get(0).getHostCard(), f.nativeCard);

        Assert.assertEquals(providerCalls.get(), 1);
        Assert.assertTrue(AiDecisionMetrics.actionEvents().get(0).fallback());
        Assert.assertEquals(f.ai().getCardsIn(ZoneType.Battlefield).stream()
            .filter(c -> c.getName().equals("Mountain")).count(), 2);
    }

    @Test
    public void mismatchingColorsExcluded() {
        Fixture f = fixture();
        AtomicInteger providerCalls = new AtomicInteger();

        f.first().setColor("Blue");

        PlayerControllerAi controller = controller(f, context -> {
            MainPhaseDecisionContext main = (MainPhaseDecisionContext) context;
            providerCalls.incrementAndGet();
            return Optional.empty();
        }, true);

        List<SpellAbility> selected = controller.chooseSpellAbilityToPlay();

        Assert.assertNotNull(selected);
        Assert.assertEquals(selected.size(), 1);
        Assert.assertSame(selected.get(0).getHostCard(), f.nativeCard);

        Assert.assertEquals(providerCalls.get(), 0);
        Assert.assertEquals(AiDecisionMetrics.actionEvents().size(), 0);
    }

    @Test
    public void recoveredOnlyCannotReplaceNativeFallback() {
        Fixture f = fixture();
        AtomicInteger providerCalls = new AtomicInteger();

        f.game().getAction().moveTo(ZoneType.Graveyard, f.nativeCard(), null, null);

        PlayerControllerAi controller = controller(f, context -> {
            MainPhaseDecisionContext main = (MainPhaseDecisionContext) context;
            providerCalls.incrementAndGet();
            return Optional.empty();
        }, true);

        List<SpellAbility> selected = controller.chooseSpellAbilityToPlay();

        Assert.assertNull(selected);
        Assert.assertEquals(providerCalls.get(), 0);
        Assert.assertEquals(AiDecisionMetrics.actionEvents().size(), 0);
    }

    @Test
    public void fullNativeBoundDoesNotDisplaceNativeOptions() {
        Fixture f = fixture();
        AtomicInteger providerCalls = new AtomicInteger();
        addCardToZone("Lightning Bolt", f.ai(), ZoneType.Hand);

        PlayerControllerAi controller = new PlayerControllerAi(f.game(), f.ai(), f.ai().getLobbyPlayer(), context -> {
            providerCalls.incrementAndGet();
            MainPhaseDecisionContext main = (MainPhaseDecisionContext) context;
            Assert.assertEquals(main.legalActions().size(), 2);
            Assert.assertFalse(main.legalActions().stream().anyMatch(a -> a.sourceName().equals("Dead Ringers")));
            return Optional.of(new AiDecision(main.decisionId(), main.state().fingerprint(), main.legalActions().stream()
                .findFirst()
                .orElseThrow()
                .actionId()));
        }, false, true, 2);
        f.ai().dangerouslySetController(controller);

        List<SpellAbility> selected = controller.chooseSpellAbilityToPlay();

        Assert.assertNotNull(selected);
        Assert.assertEquals(selected.size(), 1);
        Assert.assertNotSame(selected.get(0).getHostCard(), f.source);

        Assert.assertEquals(providerCalls.get(), 1);
    }

    @Test
    public void staleSourceTargetOrPriorityFallsBack() {
        for (String kind : List.of("source", "target", "priority")) {
            Fixture f = fixture();
            AiDecisionMetrics.reset();
            AtomicInteger calls = new AtomicInteger();
            PlayerControllerAi controller = controller(f, context -> {
                MainPhaseDecisionContext main = (MainPhaseDecisionContext) context;
                calls.incrementAndGet();
                String id = main.legalActions().stream()
                        .filter(a -> a.sourceName().equals("Dead Ringers"))
                        .findFirst()
                        .orElseThrow()
                        .actionId();
                if (kind.equals("source")) {
                    f.game().getAction().moveTo(ZoneType.Graveyard, f.source(), null, null);
                } else if (kind.equals("target")) {
                    f.game().getAction().moveTo(ZoneType.Graveyard, f.first(), null, null);
                } else {
                    f.game().getPhaseHandler().setPriority(f.enemy());
                }
                return Optional.of(new AiDecision(main.decisionId(), main.state().fingerprint(), id));
            }, true);
            Assert.assertSame(controller.chooseSpellAbilityToPlay().get(0).getHostCard(), f.nativeCard());
            Assert.assertEquals(1, calls.get());
            Assert.assertTrue(AiDecisionMetrics.actionEvents().get(0).fallback());
        }
    }

    @Test
    public void exactPairwiseTemplateRejectsModifiedScriptsAndOtherSurfaces() {
        Fixture f = fixture();
        SpellAbility sa = f.source().getFirstSpellAbility();
        Assert.assertTrue(ExternalAiAbilitySupport.verifiedPairwiseTemplate(sa));
        sa.getMapParams().put("NoRegen", "False");
        Assert.assertFalse(ExternalAiAbilitySupport.verifiedPairwiseTemplate(sa));

        Fixture f2 = fixture();
        Assert.assertEquals(ExternalAiAbilitySupport.Status.UNSUPPORTED,
            ExternalAiAbilitySupport.status(f2.source().getFirstSpellAbility(),
            forge.ai.decision.AiDecisionType.COMBAT_ACTION));
        Assert.assertEquals(ExternalAiAbilitySupport.Status.UNSUPPORTED,
            ExternalAiAbilitySupport.status(f2.source().getFirstSpellAbility(),
            forge.ai.decision.AiDecisionType.STACK_RESPONSE));

        Assert.assertTrue(ComputerUtilCard.isCardRemAIDeck(f.source()));
    }


    @Test
    public void mainTwoCanRecover() {
        Fixture f = fixture();
        f.game().getPhaseHandler().devModeSet(PhaseType.MAIN2, f.ai());
        f.game().getPhaseHandler().setPriority(f.ai());

        PlayerControllerAi controller = controller(f, context -> {
            MainPhaseDecisionContext main = (MainPhaseDecisionContext) context;
            return Optional.of(new AiDecision(main.decisionId(), main.state().fingerprint(), main.legalActions().stream()
                .filter(a -> a.sourceName().equals("Dead Ringers"))
                .findFirst()
                .orElseThrow()
                .actionId()));
        }, true);

        List<SpellAbility> selected = controller.chooseSpellAbilityToPlay();

        Assert.assertNotNull(selected);
        Assert.assertEquals(selected.size(), 1);
        Assert.assertSame(selected.get(0).getHostCard(), f.source);

        Assert.assertEquals(selected.get(0).getTargets().size(), 2);
        Assert.assertTrue(selected.get(0).getTargets().stream().anyMatch(t -> t == f.first));
        Assert.assertTrue(selected.get(0).getTargets().stream().anyMatch(t -> t == f.second));

        Assert.assertFalse(AiDecisionMetrics.actionEvents().get(0).fallback());
    }
}
