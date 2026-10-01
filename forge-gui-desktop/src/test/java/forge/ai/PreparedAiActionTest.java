package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;
import forge.util.MyRandom;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class PreparedAiActionTest extends AITest {
    private AiController aiFor(Game game) {
        return ((PlayerControllerAi) game.getPlayers().get(1).getController()).getAi();
    }

    private Player prepareMain2(Game game) {
        Player ai = game.getPlayers().get(1);
        ai.setTeam(0);
        game.getPlayers().get(0).setTeam(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        game.getAction().checkStateEffects(true);
        return ai;
    }

    @Test
    public void noPlayableCandidateProducesNoPreparedAction() {
        Game game = initAndCreateGame();
        Player ai = prepareMain2(game);
        Card counterspell = addCardToZone("Counterspell", ai, ZoneType.Hand);

        List<PreparedAiAction> prepared = aiFor(game).prepareSpellAbilityActions(
                List.of(counterspell.getSpellAbilities().get(0)), false);

        Assert.assertTrue(prepared.isEmpty());
    }

    @Test
    public void preparedTargetSurvivesWithoutMutatingOriginalAbility() {
        Game game = initAndCreateGame();
        Player ai = prepareMain2(game);
        addCard("Mountain", ai);
        Card shock = addCardToZone("Shock", ai, ZoneType.Hand);
        Player target = game.getPlayers().get(0);
        target.setLife(2, null);
        SpellAbility original = shock.getSpellAbilities().get(0);

        List<PreparedAiAction> prepared = aiFor(game).prepareSpellAbilityActions(List.of(original), false);

        Assert.assertEquals(prepared.size(), 1);
        PreparedAiAction action = prepared.get(0);
        Assert.assertEquals(action.actionId(), "ACTION_0");
        Assert.assertEquals(action.sourceCard(), shock);
        Assert.assertEquals(action.originalCandidateIndex(), 0);
        Assert.assertNotSame(action.spellAbility(), original);
        Assert.assertEquals(action.spellAbility().getTargets().getFirstTargetedPlayer(), target);
        Assert.assertTrue(original.getTargets().isEmpty(), "target preparation leaked into the source ability");
    }

    @Test
    public void rejectedCandidateDoesNotPreventLaterCandidate() {
        Game game = initAndCreateGame();
        Player ai = prepareMain2(game);
        addCard("Mountain", ai);
        Card hillGiant = addCardToZone("Hill Giant", ai, ZoneType.Hand);
        Card shock = addCardToZone("Shock", ai, ZoneType.Hand);
        game.getPlayers().get(0).setLife(2, null);

        List<PreparedAiAction> prepared = aiFor(game).prepareSpellAbilityActions(List.of(
                hillGiant.getSpellAbilities().get(0), shock.getSpellAbilities().get(0)), false);

        Assert.assertEquals(prepared.size(), 1);
        Assert.assertEquals(prepared.get(0).sourceCard(), shock);
        Assert.assertEquals(prepared.get(0).originalCandidateIndex(), 1);
    }

    @Test
    public void firstAcceptableCandidatePreservesOrdering() {
        Game game = initAndCreateGame();
        Player ai = prepareMain2(game);
        addCard("Mountain", ai);
        Card shock = addCardToZone("Shock", ai, ZoneType.Hand);
        Card bolt = addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
        game.getPlayers().get(0).setLife(2, null);

        List<PreparedAiAction> prepared = aiFor(game).prepareSpellAbilityActions(List.of(
                shock.getSpellAbilities().get(0), bolt.getSpellAbilities().get(0)), false);

        Assert.assertEquals(prepared.size(), 1);
        Assert.assertEquals(prepared.get(0).sourceCard(), shock);
        Assert.assertEquals(prepared.get(0).originalCandidateIndex(), 0);
    }

    @Test
    public void normalLandSelectionRemainsOutsidePreparedSpellPipeline() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card mountain = addCardToZone("Mountain", ai, ZoneType.Hand);

        List<SpellAbility> selected = aiFor(game).chooseSpellAbilityToPlay();

        Assert.assertNotNull(selected);
        Assert.assertEquals(selected.get(0).getHostCard(), mountain);
        Assert.assertTrue(selected.get(0).isLandAbility());
    }

    @Test
    public void preparedXValueSurvivesWithoutMutatingOriginalAbility() {
        Game game = initAndCreateGame();
        Player ai = prepareMain2(game);
        addCards("Forest", 4, ai);
        Card zenith = addCardToZone("Green Sun's Zenith", ai, ZoneType.Hand);
        addCardToZone("Birds of Paradise", ai, ZoneType.Library);
        addCardToZone("Endurance", ai, ZoneType.Library);
        SpellAbility original = zenith.getSpellAbilities().get(0);

        List<PreparedAiAction> prepared = aiFor(game).prepareSpellAbilityActions(List.of(original), false);

        Assert.assertEquals(prepared.size(), 1);
        Assert.assertEquals(prepared.get(0).spellAbility().getXManaCostPaid(), Integer.valueOf(3));
        Assert.assertNotEquals(original.getXManaCostPaid(), Integer.valueOf(3));
    }

    @Test
    public void preparedModalChoiceSurvivesWithoutMutatingOriginalAbility() {
        Game game = initAndCreateGame();
        Player ai = prepareMain2(game);
        addCard("Plains", ai);
        addCard("Island", ai);
        addCard("Swamp", ai);
        Card charm = addCardToZone("Dromar's Charm", ai, ZoneType.Hand);
        addCard("Runeclaw Bear", game.getPlayers().get(0));
        SpellAbility original = charm.getSpellAbilities().get(0);

        List<PreparedAiAction> prepared = aiFor(game).prepareSpellAbilityActions(List.of(original), false);

        Assert.assertEquals(prepared.size(), 1);
        Assert.assertNotNull(prepared.get(0).spellAbility().getChosenList());
        Assert.assertFalse(prepared.get(0).spellAbility().getChosenList().isEmpty());
        Assert.assertNull(original.getChosenList(), "mode preparation leaked into the source ability");
    }

    @Test
    public void explicitEnumerationReturnsMultipleIndependentPlayableSpellsInOrder() {
        Game game = initAndCreateGame();
        Player ai = prepareMain2(game);
        addCards("Mountain", 2, ai);
        Card shock = addCardToZone("Shock", ai, ZoneType.Hand);
        Card bolt = addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
        game.getPlayers().get(0).setLife(2, null);

        PreparedAiActionSet set = aiFor(game).prepareCandidateActions(List.of(
                shock.getSpellAbilities().get(0), bolt.getSpellAbilities().get(0)), false, 10);

        Assert.assertEquals(set.actions().size(), 2);
        Assert.assertEquals(set.actions().get(0).actionId(), "ACTION_0");
        Assert.assertEquals(set.actions().get(0).sourceCard(), shock);
        Assert.assertEquals(set.actions().get(1).actionId(), "ACTION_1");
        Assert.assertEquals(set.actions().get(1).sourceCard(), bolt);
        Assert.assertNotSame(set.actions().get(0).spellAbility(), set.actions().get(1).spellAbility());
    }

    @Test
    public void enumerationRejectsUnplayableCandidatesAndHonorsBound() {
        Game game = initAndCreateGame();
        Player ai = prepareMain2(game);
        addCards("Mountain", 2, ai);
        Card giant = addCardToZone("Hill Giant", ai, ZoneType.Hand);
        Card shock = addCardToZone("Shock", ai, ZoneType.Hand);
        Card bolt = addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
        game.getPlayers().get(0).setLife(2, null);

        PreparedAiActionSet set = aiFor(game).prepareCandidateActions(List.of(
                giant.getSpellAbilities().get(0), shock.getSpellAbilities().get(0),
                bolt.getSpellAbilities().get(0)), false, 1);

        Assert.assertEquals(set.actions().size(), 1);
        Assert.assertEquals(set.actions().get(0).sourceCard(), shock);
        Assert.assertEquals(set.actions().get(0).originalCandidateIndex(), 1);
        Assert.assertEquals(set.evaluatedCandidateCount(), 2);
    }

    @Test
    public void legacyFirstActionMatchesEnumeratedActionZeroForDeterministicState() {
        Game game = initAndCreateGame();
        Player ai = prepareMain2(game);
        addCards("Mountain", 2, ai);
        Card shock = addCardToZone("Shock", ai, ZoneType.Hand);
        Card bolt = addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
        game.getPlayers().get(0).setLife(2, null);
        List<SpellAbility> abilities = List.of(shock.getSpellAbilities().get(0), bolt.getSpellAbilities().get(0));

        PreparedAiAction legacy = aiFor(game).prepareSpellAbilityActions(new ArrayList<>(abilities), false).get(0);
        PreparedAiAction enumerated = aiFor(game).prepareCandidateActions(abilities, false, 10).actions().get(0);

        Assert.assertEquals(enumerated.sourceCard(), legacy.sourceCard());
        Assert.assertEquals(enumerated.originalCandidateIndex(), legacy.originalCandidateIndex());
        Assert.assertEquals(enumerated.spellAbility().getTargets().getFirstTargetedPlayer(),
                legacy.spellAbility().getTargets().getFirstTargetedPlayer());
    }

    @Test
    public void enumerationDoesNotAdvanceLiveRandomStream() {
        Game game = initAndCreateGame();
        Player ai = prepareMain2(game);
        addCards("Mountain", 2, ai);
        Card shock = addCardToZone("Shock", ai, ZoneType.Hand);
        game.getPlayers().get(0).setLife(2, null);
        Random previous = MyRandom.getRandom();
        try {
            MyRandom.setRandom(new Random(1234567L));
            long expected = new Random(1234567L).nextLong();

            aiFor(game).prepareCandidateActions(List.of(shock.getSpellAbilities().get(0)), false, 10);

            Assert.assertEquals(MyRandom.getRandom().nextLong(), expected);
        } finally {
            MyRandom.setRandom(previous);
        }
    }

    @Test
    public void enumerationDoesNotChangeLiveStateOrAiMemory() {
        Game game = initAndCreateGame();
        Player ai = prepareMain2(game);
        addCards("Mountain", 2, ai);
        Card shock = addCardToZone("Shock", ai, ZoneType.Hand);
        Card marker = addCard("Runeclaw Bear", ai);
        game.getPlayers().get(0).setLife(2, null);
        AiCardMemory.rememberCard(ai, marker, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_MAIN2);
        int handSize = ai.getCardsIn(ZoneType.Hand).size();
        int battlefieldSize = ai.getCardsIn(ZoneType.Battlefield).size();

        aiFor(game).prepareCandidateActions(List.of(shock.getSpellAbilities().get(0)), false, 10);

        Assert.assertEquals(ai.getCardsIn(ZoneType.Hand).size(), handSize);
        Assert.assertEquals(ai.getCardsIn(ZoneType.Battlefield).size(), battlefieldSize);
        Assert.assertTrue(AiCardMemory.isRememberedCard(ai, marker,
                AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_MAIN2));
        Assert.assertTrue(AiCardMemory.isMemorySetEmpty(ai, AiCardMemory.MemorySet.PAYS_TAP_COST));
        Assert.assertTrue(AiCardMemory.isMemorySetEmpty(ai, AiCardMemory.MemorySet.PAYS_SAC_COST));
    }

    @Test
    public void enumeratedTargetsAreIsolatedFromEachOtherAndLiveAbilities() {
        Game game = initAndCreateGame();
        Player ai = prepareMain2(game);
        addCards("Mountain", 2, ai);
        Card first = addCardToZone("Shock", ai, ZoneType.Hand);
        Card second = addCardToZone("Shock", ai, ZoneType.Hand);
        game.getPlayers().get(0).setLife(2, null);
        SpellAbility firstOriginal = first.getSpellAbilities().get(0);
        SpellAbility secondOriginal = second.getSpellAbilities().get(0);

        List<PreparedAiAction> actions = aiFor(game).prepareCandidateActions(
                List.of(firstOriginal, secondOriginal), false, 10).actions();

        Assert.assertEquals(actions.size(), 2);
        Assert.assertNotSame(actions.get(0).spellAbility().getTargets(), actions.get(1).spellAbility().getTargets());
        Assert.assertTrue(firstOriginal.getTargets().isEmpty());
        Assert.assertTrue(secondOriginal.getTargets().isEmpty());
    }

    @Test
    public void emptyOrZeroBoundEnumerationReturnsEmptySet() {
        Game game = initAndCreateGame();
        AiController controller = aiFor(game);

        Assert.assertTrue(controller.prepareCandidateActions(List.of(), false, 3).actions().isEmpty());
        Assert.assertTrue(controller.prepareCandidateActions(List.of(), false, 0).actions().isEmpty());
    }

    @Test
    public void threeCandidatesPreserveEvaluationOrderAndExposeDiagnostics() {
        Game game = initAndCreateGame();
        Player ai = prepareMain2(game);
        addCards("Mountain", 3, ai);
        Card first = addCardToZone("Shock", ai, ZoneType.Hand);
        Card second = addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
        Card third = addCardToZone("Shock", ai, ZoneType.Hand);
        game.getPlayers().get(0).setLife(2, null);

        PreparedAiActionSet set = aiFor(game).prepareCandidateActions(List.of(
                first.getSpellAbilities().get(0), second.getSpellAbilities().get(0),
                third.getSpellAbilities().get(0)), false, 3);

        Assert.assertEquals(set.actions().stream().map(PreparedAiAction::sourceCard).toList(),
                List.of(first, second, third));
        Assert.assertEquals(set.rawCandidateCount(), 3);
        Assert.assertEquals(set.evaluatedCandidateCount(), 3);
        Assert.assertEquals(set.copiedGameCount(), 3);
        Assert.assertTrue(set.preparationNanos() > 0);
    }

    @Test
    public void enumeratedXChoicesRemainIndependent() {
        Game game = initAndCreateGame();
        Player ai = prepareMain2(game);
        addCards("Forest", 4, ai);
        Card first = addCardToZone("Green Sun's Zenith", ai, ZoneType.Hand);
        Card second = addCardToZone("Green Sun's Zenith", ai, ZoneType.Hand);
        addCardToZone("Birds of Paradise", ai, ZoneType.Library);
        addCardToZone("Endurance", ai, ZoneType.Library);
        SpellAbility firstOriginal = first.getSpellAbilities().get(0);
        SpellAbility secondOriginal = second.getSpellAbilities().get(0);

        List<PreparedAiAction> actions = aiFor(game).prepareCandidateActions(
                List.of(firstOriginal, secondOriginal), false, 2).actions();

        Assert.assertEquals(actions.size(), 2);
        Assert.assertEquals(actions.get(0).spellAbility().getXManaCostPaid(), Integer.valueOf(3));
        Assert.assertEquals(actions.get(1).spellAbility().getXManaCostPaid(), Integer.valueOf(3));
        Assert.assertNotSame(actions.get(0).spellAbility(), actions.get(1).spellAbility());
        Assert.assertNotEquals(firstOriginal.getXManaCostPaid(), Integer.valueOf(3));
        Assert.assertNotEquals(secondOriginal.getXManaCostPaid(), Integer.valueOf(3));
    }

    @Test
    public void enumeratedModalChoicesRemainIndependent() {
        Game game = initAndCreateGame();
        Player ai = prepareMain2(game);
        addCards("Plains", 2, ai);
        addCards("Island", 2, ai);
        addCards("Swamp", 2, ai);
        Card first = addCardToZone("Dromar's Charm", ai, ZoneType.Hand);
        Card second = addCardToZone("Dromar's Charm", ai, ZoneType.Hand);
        addCard("Runeclaw Bear", game.getPlayers().get(0));

        List<PreparedAiAction> actions = aiFor(game).prepareCandidateActions(List.of(
                first.getSpellAbilities().get(0), second.getSpellAbilities().get(0)), false, 2).actions();

        Assert.assertEquals(actions.size(), 2);
        Assert.assertNotNull(actions.get(0).spellAbility().getChosenList());
        Assert.assertNotNull(actions.get(1).spellAbility().getChosenList());
        Assert.assertNotSame(actions.get(0).spellAbility().getChosenList(),
                actions.get(1).spellAbility().getChosenList());
        Assert.assertNull(first.getSpellAbilities().get(0).getChosenList());
        Assert.assertNull(second.getSpellAbilities().get(0).getChosenList());
    }
}
