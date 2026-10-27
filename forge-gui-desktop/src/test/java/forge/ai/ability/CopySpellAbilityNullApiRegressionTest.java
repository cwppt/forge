package forge.ai.ability;

import forge.ai.AITest;
import forge.ai.simulation.GameCopier;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardFactoryUtil;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

public class CopySpellAbilityNullApiRegressionTest extends AITest {
    private SpellAbility tawnos(Player ai) {
        Card source = addCard("Tawnos, Urza's Apprentice", ai);
        SpellAbility copy = source.getSpellAbilities().stream()
                .filter(sa -> sa.getApi() == ApiType.CopySpellAbility).findFirst().orElseThrow();
        copy.setActivatingPlayer(ai);
        return copy;
    }

    private void morphOnStack(Game game, Player caster) {
        Card akroma = createCard("Akroma, Angel of Fury", caster);
        game.getStackZone().add(akroma);
        SpellAbility morph = CardFactoryUtil.abilityCastFaceDown(akroma.getCurrentState(), true, "Morph");
        morph.setActivatingPlayer(caster);
        game.getStack().add(morph);
        Assert.assertNull(game.getStack().peekAbility().getApi());
    }

    @Test public void tawnosDeclinesOpponentMorphWithoutDispatchingNullApi() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        SpellAbility copy = tawnos(ai);
        morphOnStack(game, game.getPlayers().get(0));
        SpellAbility top = game.getStack().peekAbility();
        Assert.assertFalse(new CopySpellAbilityAi().checkApiLogic(ai, copy).willingToPlay());
        Assert.assertSame(game.getStack().peekAbility(), top);
        Assert.assertEquals(game.getStack().size(), 1);
        Assert.assertTrue(copy.getTargets().isEmpty());
    }

    @Test public void copiedEvaluationRetainsNonApiMorphAndDeclinesSafely() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        SpellAbility copy = tawnos(ai);
        morphOnStack(game, game.getPlayers().get(0));
        GameCopier copier = new GameCopier(game, true);
        Game simulation = copier.makeCopy();
        Player copiedAi = copier.find(ai);
        SpellAbility copiedAbility = copier.find(copy.getHostCard()).getSpellAbilities().stream()
                .filter(sa -> sa.getApi() == ApiType.CopySpellAbility).findFirst().orElseThrow();
        copiedAbility.setActivatingPlayer(copiedAi);
        Assert.assertNull(simulation.getStack().peekAbility().getApi());
        Assert.assertFalse(new CopySpellAbilityAi().checkApiLogic(copiedAi, copiedAbility).willingToPlay());
        Assert.assertEquals(game.getStack().size(), 1);
        Assert.assertTrue(copy.getTargets().isEmpty());
    }

    @Test public void tawnosStillRejectsIneligibleApiBasedSpell() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        SpellAbility copy = tawnos(ai);
        Player opponent = game.getPlayers().get(0);
        Card bolt = createCard("Lightning Bolt", opponent);
        game.getStackZone().add(bolt);
        SpellAbility spell = bolt.getFirstSpellAbility();
        spell.setActivatingPlayer(opponent);
        spell.getTargets().add(ai);
        game.getStack().add(spell);
        Assert.assertEquals(game.getStack().peekAbility().getApi(), ApiType.DealDamage);
        Assert.assertFalse(new CopySpellAbilityAi().checkApiLogic(ai, copy).willingToPlay());
        Assert.assertTrue(copy.getTargets().isEmpty());
    }

    @Test public void forkStillTargetsLegalApiBasedOpponentSpell() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        Card fork = addCardToZone("Fork", ai, ZoneType.Hand);
        SpellAbility copy = fork.getFirstSpellAbility();
        copy.setActivatingPlayer(ai);
        copy.putParam("AILogic", "Always");
        Card bolt = createCard("Lightning Bolt", opponent);
        game.getStackZone().add(bolt);
        SpellAbility spell = bolt.getFirstSpellAbility();
        spell.setActivatingPlayer(opponent);
        spell.getTargets().add(ai);
        game.getStack().add(spell);
        SpellAbility top = game.getStack().peekAbility();
        Assert.assertTrue(new CopySpellAbilityAi().checkApiLogic(ai, copy).willingToPlay());
        Assert.assertSame(copy.getTargets().getFirstTargetedSpell(), top);
        Assert.assertTrue(top.canBeTargetedBy(copy));
    }

    @Test public void emptyStackStillDeclinesOptionalTawnos() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Assert.assertFalse(new CopySpellAbilityAi().checkApiLogic(ai, tawnos(ai)).willingToPlay());
        Assert.assertTrue(game.getStack().isEmpty());
    }
}
