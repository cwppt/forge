package forge.ai;

import forge.card.CardStateName;
import forge.card.mana.ManaCost;
import forge.game.card.*;
import forge.game.cost.*;
import forge.game.keyword.Keyword;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.List;

public final class CostSourceRegressionTest extends AITest {
    private SpellAbility spell(forge.game.Game g) {
        var c=addCardToZone("Shivan Dragon",g.getPlayers().get(0),ZoneType.Hand);
        var sa=c.getFirstSpellAbility();sa.setActivatingPlayer(c.getController());return sa;
    }
    private StaticAbility modifier(Card c,String mode,int amount) {
        return new StaticAbility("Mode$ "+mode+" | ValidCard$ Card | Amount$ "+amount,c,c.getCurrentState());
    }
    private int raised(SpellAbility sa) {
        var original=new Cost(ManaCost.get(6),false);var adjusted=CostAdjustment.adjust(original,sa,false);
        Assert.assertNotSame(adjusted,original);Assert.assertEquals(original.getCostMana().getMana().getCMC(),6);
        return adjusted.getCostMana().getMana().getCMC();
    }
    private int reduced(SpellAbility sa) {
        var cost=new ManaCostBeingPaid(ManaCost.get(6));
        Assert.assertTrue(CostAdjustment.adjust(cost,sa,sa.getActivatingPlayer(),null,true,false));return cost.getConvertedManaCost();
    }
    @Test public void printedRaiseAddedRemovedAndSuppressedIsLive() {
        var g=initAndCreateGame();var sa=spell(g);var host=addCard("Mountain",g.getPlayers().get(0));
        Assert.assertEquals(raised(sa),6);var t=modifier(host,"RaiseCost",2);host.addStaticAbility(t);
        Assert.assertEquals(raised(sa),8);t.setSuppressed(true);Assert.assertEquals(raised(sa),6);
        t.setSuppressed(false);Assert.assertEquals(raised(sa),8);host.getCurrentState().removeStaticAbility(t);Assert.assertEquals(raised(sa),6);
    }
    @Test public void changedTraitsAndTextCanAddModifiers() {
        var g=initAndCreateGame();var sa=spell(g);var host=addCard("Mountain",g.getPlayers().get(0));
        host.getChangedCardTraitsByText().put(1L,0L,new CardTraitChanges(null,null,null,List.of(modifier(host,"RaiseCost",3)),null));
        Assert.assertEquals(raised(sa),9);host.getChangedCardTraitsByText().clear();Assert.assertEquals(raised(sa),6);
        host.getChangedCardTraits().put(2L,0L,new CardTraitChanges(null,null,null,List.of(modifier(host,"ReduceCost",2)),null));
        Assert.assertEquals(reduced(sa),4);host.getChangedCardTraits().clear();Assert.assertEquals(reduced(sa),6);
    }
    @Test public void keywordModifiersAreRechecked() {
        var g=initAndCreateGame();var sa=spell(g);var host=addCard("Mountain",g.getPlayers().get(0));
        var kw=Keyword.getInstance("Flying");host.getCurrentState().getCachedKeywords().insert(kw);
        Assert.assertEquals(raised(sa),6);kw.addStaticAbility(modifier(host,"RaiseCost",2));Assert.assertEquals(raised(sa),8);
        host.getCurrentState().getCachedKeywords().removeAll(Keyword.FLYING);Assert.assertEquals(raised(sa),6);
    }
    @Test public void splitAndHostOutsideScannedZonesRemainIncluded() {
        var g=initAndCreateGame();var sa=spell(g);var host=addCard("Wear // Tear",g.getPlayers().get(0));
        host.getState(CardStateName.LeftSplit).addStaticAbility(modifier(host,"RaiseCost",2));
        host.getState(CardStateName.RightSplit).addStaticAbility(modifier(host,"RaiseCost",3));Assert.assertEquals(raised(sa),11);
        var own=modifier(sa.getHostCard(),"RaiseCost",1);own.setActiveZone(java.util.EnumSet.of(ZoneType.Hand));
        sa.getHostCard().addStaticAbility(own);Assert.assertEquals(raised(sa),12);
    }
    @Test public void reductionAndMinimumCostOrderingIsPreserved() {
        var g=initAndCreateGame();var sa=spell(g);var host=addCard("Mountain",g.getPlayers().get(0));
        host.addStaticAbility(modifier(host,"ReduceCost",5));Assert.assertEquals(reduced(sa),1);
        addCard("Trinisphere",g.getPlayers().get(0));Assert.assertEquals(reduced(sa),3);
    }
    @Test public void commanderAndIntrinsicTaxAreStillApplied() {
        var g=initAndCreateGame();var sa=spell(g);sa.putParam("RaiseCost","2");Assert.assertEquals(raised(sa),8);
        sa.putParam("ReduceCost","2");Assert.assertEquals(reduced(sa),4);
        sa.getHostCard().setCommander(true);sa.getHostCard().setCastFrom(sa.getActivatingPlayer().getZone(ZoneType.Command));
        sa.getActivatingPlayer().incCommanderCast(sa.getHostCard());Assert.assertEquals(raised(sa),10);
    }
    @Test public void externalPrintedModifiersAffectAnOtherwiseUnmodifiedSpell() {
        var g=initAndCreateGame();var sa=spell(g);var other=g.getPlayers().get(1);
        addCard("Sphere of Resistance",other);Assert.assertEquals(raised(sa),7);
        addCard("Helm of Awakening",other);Assert.assertEquals(reduced(sa),5);
        var full=CostAdjustment.adjust(new Cost(ManaCost.get(6),false),sa,false);
        var paid=new ManaCostBeingPaid(full.getCostMana().getMana());
        Assert.assertTrue(CostAdjustment.adjust(paid,sa,sa.getActivatingPlayer(),null,true,false));
        Assert.assertEquals(paid.getConvertedManaCost(),6);
    }
    @Test public void externalModifierConditionsAreNotBypassed() {
        var g=initAndCreateGame();var creature=spell(g);var other=g.getPlayers().get(1);
        var thorn=addCard("Thorn of Amethyst",other);Assert.assertEquals(raised(creature),6);
        var bolt=addCardToZone("Lightning Bolt",creature.getActivatingPlayer(),ZoneType.Hand).getFirstSpellAbility();
        bolt.setActivatingPlayer(creature.getActivatingPlayer());Assert.assertEquals(raised(bolt),7);
        thorn.getCurrentState().getStaticAbilities().get(0).setSuppressed(true);Assert.assertEquals(raised(bolt),6);
    }
}
