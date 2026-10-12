package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.game.phase.PhaseType;
import forge.ai.decision.AiDecisionType;
import forge.ai.simulation.GameCopier;
import forge.ai.simulation.SpellAbilityChoiceCopier;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import java.util.Arrays;
import java.util.List;

public class DeadRingersPreparationProbeTest extends AITest {
    @DataProvider
    public Object[][] targetScenarios() {
        return new Object[][] {
            {"Matching green nonblack creatures", new String[]{"Runeclaw Bear", "Grizzly Bears"}, true},
            {"Different nonblack colors (blue vs green)", new String[]{"Merfolk of the Pearl Trident", "Runeclaw Bear"}, false},
            {"Two colorless creatures", new String[]{"Ornithopter", "Ornithopter"}, true},
            {"Matching multicolor creatures (green/white)", new String[]{"Watchwolf", "Watchwolf"}, true},
            {"Green vs green/white (different colors)", new String[]{"Runeclaw Bear", "Watchwolf"}, false}
        };
    }

    @Test(dataProvider = "targetScenarios")
    public void testDeadRingers(String description, String[] targetNames, boolean expectedMetConditions) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player enemy = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getPhaseHandler().setPriority(ai);

        addCards("Swamp", 5, ai);
        Card deadRingers = addCardToZone("Dead Ringers", ai, ZoneType.Hand);
        SpellAbility deadRingersSA = deadRingers.getFirstSpellAbility();
        deadRingersSA.setActivatingPlayer(ai);
        deadRingersSA.resetTargets();

        Card target1 = addCard(targetNames[0], enemy);
        Card target2 = null;
        if (targetNames.length > 1) {
            target2 = addCard(targetNames[1], enemy);
        }
        deadRingersSA.getTargets().add(target1);
        if (target2 != null) {
            deadRingersSA.getTargets().add(target2);
        }

        for (Card target : Arrays.asList(target1, target2)) {
            if (target == null) continue;
            Assert.assertTrue(deadRingersSA.canTarget(target, true),
                    "Ability should target " + target.getName() + " for " + description);
        }
        Assert.assertEquals(deadRingersSA.getTargets().size(), targetNames.length,
                "Target count mismatch for " + description);
        Assert.assertEquals(deadRingersSA.getConditions().areMet(deadRingersSA), expectedMetConditions,
                "metConditions should be " + expectedMetConditions + " for " + description);

        boolean played = ComputerUtil.playStack(deadRingersSA, ai, game);
        Assert.assertTrue(played, "Should be able to play stack for " + description);
        game.getStack().resolveStack();

        for (Card target : Arrays.asList(target1, target2)) {
            if (target == null) continue;
            if (expectedMetConditions) {
                Assert.assertEquals(target.getZone().getZoneType(), ZoneType.Graveyard,
                        "Target " + target.getName() + " should be in graveyard");
            } else {
                Assert.assertEquals(target.getZone().getZoneType(), ZoneType.Battlefield,
                        "Target " + target.getName() + " should be on battlefield");
            }
        }
    }

    @Test
    public void testUnsupportedPolicy() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player enemy = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getPhaseHandler().setPriority(ai);

        addCards("Swamp", 5, ai);
        addCard("Runeclaw Bear", enemy);
        addCard("Grizzly Bears", enemy);
        Card deadRingers = addCardToZone("Dead Ringers", ai, ZoneType.Hand);
        SpellAbility deadRingersSA = deadRingers.getFirstSpellAbility();

        Assert.assertEquals(ExternalAiAbilitySupport.status(deadRingersSA, AiDecisionType.MAIN_PHASE_ACTION),
                ExternalAiAbilitySupport.Status.VERIFIED_SAFE,
                "Dead Ringers should be VERIFIED_SAFE for MAIN_PHASE_ACTION");

        var controller = (PlayerControllerAi) ai.getController();
        var prepared = controller.getAi().prepareCandidateActions(List.of(deadRingersSA), true, 4);
        Assert.assertEquals(prepared.actions().size(),1);
        var action=prepared.actions().get(0);
        Assert.assertEquals(action.spellAbility().getTargets().size(),2);
        Assert.assertTrue(action.spellAbility().metConditions());
        Assert.assertTrue(controller.getAi().revalidatePreparedAction(action));
        Assert.assertNull(controller.chooseSpellAbilityToPlay(), "Normal routing must still exclude Dead Ringers");
        // Direct helper is a preparation probe, not proof of external exposure
    }

    @Test
    public void testMapping() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player enemy = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getPhaseHandler().setPriority(ai);

        addCards("Swamp", 5, ai);
        Card deadRingers = addCardToZone("Dead Ringers", ai, ZoneType.Hand);
        SpellAbility deadRingersSA = deadRingers.getFirstSpellAbility();
        deadRingersSA.setActivatingPlayer(ai);

        Card target1 = addCard("Runeclaw Bear", enemy);
        Card target2 = addCard("Grizzly Bears", enemy);
        deadRingersSA.resetTargets();
        deadRingersSA.getTargets().add(target1);
        deadRingersSA.getTargets().add(target2);

        GameCopier copier = new GameCopier(game);
        Game copiedGame = copier.makeCopy();
        SpellAbility copiedAbility = copier.findSpellAbility(deadRingersSA);
        copiedAbility.setActivatingPlayer(copier.find(ai));
        copiedAbility.resetTargets();
        copiedAbility.getTargets().add(copier.find(target1));
        copiedAbility.getTargets().add(copier.find(target2));
        deadRingersSA.resetTargets();
        SpellAbility mappedAbility = SpellAbilityChoiceCopier.copyCastChoices(copiedAbility, deadRingersSA, ai);
        mappedAbility.setActivatingPlayer(ai);
        SpellAbilityChoiceCopier.copyTargets(copiedAbility, mappedAbility, copier::reverseFind);

        List<Card> mappedCards = List.copyOf(mappedAbility.getTargets().getTargetCards());
        Assert.assertEquals(mappedCards.size(), 2);
        Assert.assertSame(mappedCards.get(0), target1);
        Assert.assertSame(mappedCards.get(1), target2);

        PreparedAiAction action = new PreparedAiAction(
            "PROBE",
            mappedAbility,
            deadRingersSA,
            deadRingers,
            PreparedAiAction.ActionCategory.SPELL,
            0,
            "Dead Ringers test",
            true
        );

        var controller = (PlayerControllerAi) ai.getController();
        boolean revalidated = controller.getAi().revalidatePreparedAction(action);
        Assert.assertTrue(revalidated, "Mapped matching pair should revalidate");
    }

    @Test
    public void testGenericValidationAndConditions() {
        // Green + Green
        Game game = setupGameForDeadRingers("Runeclaw Bear", "Grizzly Bears");
        Player ai = game.getPlayers().get(1);
        SpellAbility deadRingersSA = game.getPlayers().get(1).getCardsIn(ZoneType.Hand).get(0).getFirstSpellAbility();

        Assert.assertTrue(deadRingersSA.isTargetNumberValid());
        Assert.assertTrue(deadRingersSA.getConditions().areMet(deadRingersSA));

        PreparedAiAction action = new PreparedAiAction(
            "PROBE",
            deadRingersSA,
            deadRingersSA,
            game.getPlayers().get(1).getCardsIn(ZoneType.Hand).get(0),
            PreparedAiAction.ActionCategory.SPELL,
            0,
            "Dead Ringers test",
            true
        );

        var controller = (PlayerControllerAi) ai.getController();
        boolean revalidated = controller.getAi().revalidatePreparedAction(action);
        Assert.assertTrue(revalidated);

        // Green + Blue
        game = setupGameForDeadRingers("Runeclaw Bear", "Merfolk of the Pearl Trident");
        ai = game.getPlayers().get(1);
        deadRingersSA = game.getPlayers().get(1).getCardsIn(ZoneType.Hand).get(0).getFirstSpellAbility();

        Assert.assertTrue(deadRingersSA.isTargetNumberValid());
        Assert.assertFalse(deadRingersSA.getConditions().areMet(deadRingersSA));

        action = new PreparedAiAction(
            "PROBE",
            deadRingersSA,
            deadRingersSA,
            game.getPlayers().get(1).getCardsIn(ZoneType.Hand).get(0),
            PreparedAiAction.ActionCategory.SPELL,
            0,
            "Dead Ringers test",
            true
        );

        controller = (PlayerControllerAi) ai.getController();
        revalidated = controller.getAi().revalidatePreparedAction(action);
        Assert.assertFalse(revalidated);
    }

    @Test
    public void testWrongCountRejected() {
        Game game = setupGameForDeadRingers("Runeclaw Bear", "Grizzly Bears");
        Player ai = game.getPlayers().get(1);
        SpellAbility deadRingersSA = game.getPlayers().get(1).getCardsIn(ZoneType.Hand).get(0).getFirstSpellAbility();

        deadRingersSA.resetTargets();
        deadRingersSA.getTargets().add(game.getPlayers().get(0).getCardsIn(ZoneType.Battlefield).get(0));

        PreparedAiAction action = new PreparedAiAction(
            "PROBE",
            deadRingersSA,
            deadRingersSA,
            game.getPlayers().get(1).getCardsIn(ZoneType.Hand).get(0),
            PreparedAiAction.ActionCategory.SPELL,
            0,
            "Dead Ringers test",
            true
        );

        var controller = (PlayerControllerAi) ai.getController();
        boolean revalidated = controller.getAi().revalidatePreparedAction(action);
        Assert.assertFalse(revalidated);
    }

    @Test
    public void testNoManaRejected() {
        Game game = setupGameForDeadRingers("Runeclaw Bear", "Grizzly Bears");
        Player ai = game.getPlayers().get(1);

        for (Card land : ai.getLandsInPlay()) {
            land.setTapped(true);
        }

        SpellAbility deadRingersSA = game.getPlayers().get(1).getCardsIn(ZoneType.Hand).get(0).getFirstSpellAbility();

        PreparedAiAction action = new PreparedAiAction(
            "PROBE",
            deadRingersSA,
            deadRingersSA,
            game.getPlayers().get(1).getCardsIn(ZoneType.Hand).get(0),
            PreparedAiAction.ActionCategory.SPELL,
            0,
            "Dead Ringers test",
            true
        );

        var controller = (PlayerControllerAi) ai.getController();
        boolean revalidated = controller.getAi().revalidatePreparedAction(action);
        Assert.assertFalse(revalidated);
    }

    @Test
    public void testMissingTargetRejected() {
        Game game = setupGameForDeadRingers("Runeclaw Bear", "Grizzly Bears");
        Player enemy = game.getPlayers().get(0);
        Card target1 = enemy.getCardsIn(ZoneType.Battlefield).get(0);

        game.getAction().moveToGraveyard(target1, null);

        SpellAbility deadRingersSA = game.getPlayers().get(1).getCardsIn(ZoneType.Hand).get(0).getFirstSpellAbility();

        PreparedAiAction action = new PreparedAiAction(
            "PROBE",
            deadRingersSA,
            deadRingersSA,
            game.getPlayers().get(1).getCardsIn(ZoneType.Hand).get(0),
            PreparedAiAction.ActionCategory.SPELL,
            0,
            "Dead Ringers test",
            true
        );

        var controller = (PlayerControllerAi) game.getPlayers().get(1).getController();
        boolean revalidated = controller.getAi().revalidatePreparedAction(action);
        Assert.assertFalse(revalidated);
    }

    private Game setupGameForDeadRingers(String target1, String target2) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player enemy = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getPhaseHandler().setPriority(ai);

        addCards("Swamp", 5, ai);
        Card deadRingers = addCardToZone("Dead Ringers", ai, ZoneType.Hand);
        SpellAbility deadRingersSA = deadRingers.getFirstSpellAbility();
        deadRingersSA.setActivatingPlayer(ai);
        deadRingersSA.resetTargets();

        Card target1Card = addCard(target1, enemy);
        Card target2Card = addCard(target2, enemy);
        deadRingersSA.getTargets().add(target1Card);
        deadRingersSA.getTargets().add(target2Card);

        return game;
    }
}
