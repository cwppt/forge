package forge.ai;

import forge.ai.simulation.GameCopier;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.List;

public class CopiedReferenceStateRegressionTest extends AITest {
    @Test
    public void removedRememberedTokenRetainsStateAndRecursiveIdentity() {
        Game game = initAndCreateGame();
        Player owner = game.getPlayers().get(1);
        Card token = addToken("r_5_5_dragon_flying", owner);
        token.addPTBoost(3, 2, game.getNextTimestamp(), 0);
        Card memory = addCard("Mind Stone", owner);
        memory.addRemembered(token);
        token.addRemembered(token);
        owner.getZone(ZoneType.Battlefield).remove(token);
        token.setZone(owner.getZone(ZoneType.Battlefield)); // Historical reference still reports its old zone.
        Assert.assertNull(game.getCardState(token, null));
        GameCopier copier = new GameCopier(game);
        Game copy = copier.makeCopy();
        Card remembered = (Card) copier.find(memory).getRemembered().iterator().next();
        Assert.assertEquals(remembered.getNetPower(), 8);
        Assert.assertEquals(remembered.getNetToughness(), 7);
        Assert.assertSame(remembered.getGame(), copy);
        Assert.assertSame(remembered.getOwner(), copier.find(owner));
        Assert.assertSame(remembered.getLastKnownZone(), copier.find(owner).getZone(ZoneType.Battlefield));
        Assert.assertNull(copy.getCardState(remembered, null));
        Assert.assertSame(remembered.getRemembered().iterator().next(), remembered);
        remembered.clearRemembered();
        Assert.assertTrue(token.hasRemembered());
    }

    @Test
    public void rememberedZoneLessTokenIsPreservedInsteadOfDropped() {
        Game game = initAndCreateGame();
        Player owner = game.getPlayers().get(1);
        Card token = createToken("r_5_5_dragon_flying", owner);
        Card memory = addCard("Mind Stone", owner);
        memory.addRemembered(token);
        GameCopier copier = new GameCopier(game);
        Game copy = copier.makeCopy();
        Assert.assertEquals(com.google.common.collect.Iterables.size(copier.find(memory).getRemembered()), 1);
        Card remembered = (Card) copier.find(memory).getRemembered().iterator().next();
        Assert.assertSame(remembered.getGame(), copy);
        Assert.assertNull(copy.getCardState(remembered, null));
    }

    @Test
    public void rememberedOldVersionDoesNotAliasCurrentZoneVersion() {
        Game game = initAndCreateGame();
        Player owner = game.getPlayers().get(1);
        Card old = addCard("Runeclaw Bear", owner);
        old.addPTBoost(3, 2, game.getNextTimestamp(), 0);
        Card historical = CardCopyService.getLKICopy(old);
        Card memory = addCard("Mind Stone", owner);
        memory.addRemembered(historical);
        owner.getZone(ZoneType.Battlefield).remove(old);
        Card current = new CardCopyService(old).copyCard(false);
        current.setGameTimestamp(game.getNextTimestamp());
        owner.getZone(ZoneType.Battlefield).add(current);
        GameCopier copier = new GameCopier(game);
        Game copy = copier.makeCopy();
        Card remembered = (Card) copier.find(memory).getRemembered().iterator().next();
        Assert.assertNotSame(remembered, copier.find(current));
        Assert.assertEquals(remembered.getNetPower(), 5);
        Assert.assertEquals(remembered.getGameTimestamp(), historical.getGameTimestamp());
        Assert.assertSame(remembered.getGame(), copy);
    }

    @Test
    public void sacrificedStackSourceAndPreparedAbilityAreRetained() {
        Game game = initAndCreateGame();
        Player owner = game.getPlayers().get(1);
        Card obelisk = addCard("Unstable Obelisk", owner);
        Card target = addCard("Runeclaw Bear", game.getPlayers().get(0));
        SpellAbility ability = obelisk.getSpellAbilities().stream().filter(sa -> "Destroy".equals(String.valueOf(sa.getApi()))).findFirst().orElseThrow();
        ability.setActivatingPlayer(owner);
        ability.getTargets().add(target);
        ability.setDescription("Prepared destroy ability with paid costs");
        ability.setXManaCostPaid(7);
        owner.getZone(ZoneType.Battlefield).remove(obelisk);
        game.getStack().add(ability);
        ability = game.getStack().peekAbility(); // add() consumes and resets the reusable activation.
        GameCopier copier = new GameCopier(game, true);
        Game copy = copier.makeCopy();
        Assert.assertEquals(copy.getStack().size(), 1);
        SpellAbility copied = copy.getStack().peekAbility();
        Assert.assertEquals(copied.getDescription(), ability.getDescription());
        Assert.assertEquals(copied.getXManaCostPaid(), Integer.valueOf(7));
        Assert.assertSame(copied.getActivatingPlayer(), copier.find(owner));
        Assert.assertSame(copied.getTargets().getTargetCards().get(0), copier.find(target));
        Assert.assertSame(copied.getHostCard().getGame(), copy);
        Assert.assertNull(copy.getCardState(copied.getHostCard(), null));
        Assert.assertSame(ability.getTargets().getTargetCards().get(0), target);
    }

    @Test
    public void wrappedTriggerKeepsDetachedHostContextAndIndependentMemory() {
        Game game = initAndCreateGame();
        Player owner = game.getPlayers().get(1);
        Card kaalia = addCard("Kaalia of the Vast", owner);
        Card remembered = addCard("Runeclaw Bear", owner);
        forge.game.trigger.Trigger trigger = kaalia.getTriggers().get(0);
        trigger.addRemembered(remembered);
        SpellAbility inner = forge.game.ability.AbilityFactory.getAbility("DB$ Draw | Defined$ You | NumCards$ 1", kaalia);
        inner.setActivatingPlayer(owner);
        inner.setTriggeringObject(forge.game.ability.AbilityKey.Card, kaalia);
        forge.game.trigger.WrappedAbility wrapper = new forge.game.trigger.WrappedAbility(trigger, inner, owner);
        wrapper.setActivatingPlayer(owner);
        owner.getZone(ZoneType.Battlefield).remove(kaalia);
        game.getStack().add(wrapper);
        GameCopier copier = new GameCopier(game, true);
        Game copy = copier.makeCopy();
        Assert.assertEquals(copy.getStack().size(), 1);
        Assert.assertTrue(copy.getStack().peekAbility() instanceof forge.game.trigger.WrappedAbility);
        forge.game.trigger.WrappedAbility copied = (forge.game.trigger.WrappedAbility) copy.getStack().peekAbility();
        Assert.assertSame(copied.getWrappedAbility().getHostCard().getGame(), copy);
        Assert.assertSame(copied.getDecider(), copier.find(owner));
        Assert.assertSame(copied.getTriggeringObject(forge.game.ability.AbilityKey.Card), copied.getHostCard());
        Assert.assertSame(copied.getTrigger().getTriggerRemembered().get(0), copier.find(remembered));
        copied.getTrigger().getTriggerRemembered().clear();
        Assert.assertEquals(trigger.getTriggerRemembered().size(), 1);
        Assert.assertSame(wrapper.getWrappedAbility().getHostCard(), kaalia);
    }

    @Test
    public void preparedStackOrderAndTargetsSurviveWithoutReplayingCastEvents() {
        Game game = initAndCreateGame();
        Player owner = game.getPlayers().get(1);
        Card first = createCard("Lightning Bolt", owner);
        game.getStackZone().add(first);
        Card second = createCard("Shock", owner);
        game.getStackZone().add(second);
        for (Card card : List.of(first, second)) {
            SpellAbility ability = card.getFirstSpellAbility();
            ability.setActivatingPlayer(owner);
            ability.getTargets().add(game.getPlayers().get(0));
            game.getStack().add(ability);
        }
        GameCopier copier = new GameCopier(game, true);
        Game copy = copier.makeCopy();
        Assert.assertEquals(copy.getStack().size(), 2);
        Assert.assertSame(copy.getStack().peekAbility().getHostCard(), copier.find(second));
        Assert.assertEquals(copy.getStack().peekAbility().getTargets().size(), 1);
        Assert.assertEquals(copier.find(owner).getSpellsCastThisTurn(), owner.getSpellsCastThisTurn());
    }
}
