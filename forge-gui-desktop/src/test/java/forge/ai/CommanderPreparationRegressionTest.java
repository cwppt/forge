package forge.ai;

import forge.ai.simulation.GameCopier;
import forge.game.Game;
import forge.game.ability.effects.PumpEffect;
import forge.game.card.Card;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;
import forge.game.player.PlayerOutcome;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

public class CommanderPreparationRegressionTest extends AITest {
    @Test
    public void departedCommandersKeepDamageHistoryWithoutReturningToPlay() {
        Game game = initAndCreateThreePlayerGame();
        Player survivor = game.getPlayers().get(1);
        Player departed = game.getPlayers().get(2);
        Card commander = addCard("Breena, the Demagogue", departed);
        departed.addCommander(commander);
        survivor.addCommanderDamage(commander, 7);
        departed.getStats().setOutcome(PlayerOutcome.concede());
        game.onPlayerLost(departed);
        Assert.assertNull(game.getCardState(commander, null));

        GameCopier copier = new GameCopier(game);
        Game copy = copier.makeCopy();
        Player copiedSurvivor = copier.find(survivor);
        Card historical = copiedSurvivor.getCommanderDamage().iterator().next().getKey();
        Assert.assertEquals(copiedSurvivor.getCommanderDamage(historical), 7);
        Assert.assertEquals(historical.getId(), commander.getId());
        Assert.assertSame(historical.getGame(), copy);
        Assert.assertSame(historical.getOwner(), copier.find(departed));
        Assert.assertNull(copy.getCardState(historical, null));
        Assert.assertFalse(copy.getPlayers().contains(copier.find(departed)));
        Assert.assertTrue(copier.find(departed).hasLost());
        Assert.assertEquals(copy.getLostPlayers().size(), 1);
        Assert.assertEquals(survivor.getCommanderDamage(commander), 7);
        GameCopier again = new GameCopier(copy);
        Game second = again.makeCopy();
        Player secondSurvivor = again.find(copiedSurvivor);
        Card secondHistory = secondSurvivor.getCommanderDamage().iterator().next().getKey();
        Assert.assertEquals(secondSurvivor.getCommanderDamage(secondHistory), 7);
        Assert.assertNull(second.getCardState(secondHistory, null));
    }

    @Test
    public void activeCommanderCastAndDamageKeysMapToTheZoneCard() {
        Game game = initAndCreateThreePlayerGame();
        Player owner = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card commander = addCardToZone("Lathliss, Dragon Queen", owner, ZoneType.Command);
        owner.addCommander(commander);
        owner.incCommanderCast(commander);
        opponent.addCommanderDamage(commander, 4);
        GameCopier copier = new GameCopier(game);
        Game copy = copier.makeCopy();
        Card mapped = copier.find(commander);
        Assert.assertSame(copier.find(owner).getCommanders().get(0), mapped);
        Assert.assertEquals(copier.find(owner).getCommanderCast(mapped), 1);
        Assert.assertEquals(copier.find(opponent).getCommanderDamage(mapped), 4);
        Assert.assertSame(copy.getCardState(mapped), mapped);
        Assert.assertSame(mapped.getOwner(), copier.find(owner));
    }

    @Test
    public void absentNonCommanderIsStillRejectedByStrictMapper() {
        Game game = initAndCreateGame();
        Card absent = createCard("Runeclaw Bear", game.getPlayers().get(1));
        GameCopier copier = new GameCopier(game);
        copier.makeCopy();
        Assert.expectThrows(RuntimeException.class, () -> copier.find(absent));
    }

    @Test
    public void guardianPreviewProtectionBlocksOnlyTheChosenOpponent() {
        Game game = initAndCreateThreePlayerGame();
        Player owner = game.getPlayers().get(1);
        Player chosen = game.getPlayers().get(0);
        Player other = game.getPlayers().get(2);
        Card guardian = addCard("Guardian Archon", owner);
        guardian.setChosenPlayer(chosen);
        Card attacker = addCard("Runeclaw Bear", owner);
        Card chosenBlocker = addCard("Runeclaw Bear", chosen);
        Card otherBlocker = addCard("Runeclaw Bear", other);
        SpellAbility ability = guardian.getSpellAbilities().stream().filter(sa -> sa.hasParam("KW")).findFirst().orElseThrow();
        ability.setActivatingPlayer(owner);
        List<String> raw = List.of(ability.getParam("KW"));
        Card preview = ComputerUtilCard.getPumpedCreature(owner, ability, attacker, 0, 0, raw);
        String expected = "Protection:Player.PlayerUID_" + chosen.getId() + ":" + chosen.getName();
        Assert.assertTrue(preview.hasKeyword(expected));
        Assert.assertFalse(CombatUtil.canBlock(preview, chosenBlocker));
        Assert.assertTrue(CombatUtil.canBlock(preview, otherBlocker));
        Assert.assertTrue(CombatUtil.canBlock(attacker, chosenBlocker));
        Assert.assertEquals(raw, List.of(ability.getParam("KW")));
        Assert.assertSame(guardian.getChosenPlayer(), chosen);
        Assert.assertTrue(ability.getTargets().isEmpty());
        List<String> effectKeywords = new ArrayList<>(raw);
        Assert.assertTrue(PumpEffect.resolveDefinedKeywords(ability, effectKeywords));
        Assert.assertEquals(effectKeywords, List.of(expected));
    }

    @Test
    public void missingChosenPlayerDoesNotGrantUnresolvedProtection() {
        Game game = initAndCreateGame();
        Player owner = game.getPlayers().get(1);
        Card guardian = addCard("Guardian Archon", owner);
        SpellAbility ability = guardian.getSpellAbilities().stream().filter(sa -> sa.hasParam("KW")).findFirst().orElseThrow();
        ability.setActivatingPlayer(owner);
        List<String> raw = List.of(ability.getParam("KW"));
        Assert.assertFalse(PumpEffect.resolveDefinedKeywords(ability, new ArrayList<>(raw)));
        Card preview = ComputerUtilCard.getPumpedCreature(owner, ability, guardian, 0, 0, raw);
        Assert.assertFalse(preview.hasKeyword(raw.get(0)));
    }
}
