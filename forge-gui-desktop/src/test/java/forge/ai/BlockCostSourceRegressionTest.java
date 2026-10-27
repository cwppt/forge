package forge.ai;

import forge.card.CardStateName;
import forge.game.card.*;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.staticability.StaticAbility;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.List;

public final class BlockCostSourceRegressionTest extends AITest {
    private StaticAbility tax(Card c,int amount){return new StaticAbility("Mode$ CantBlockUnless | ValidCard$ Creature | Cost$ "+amount,c,c.getCurrentState());}
    private int cost(Card attacker,Card blocker){var c=CombatUtil.getBlockCost(attacker.getGame(),blocker,attacker);return c==null?0:c.getCostMana().getMana().getCMC();}
    @Test public void ordinaryBoardsAndNewPrintedCostsRemainLive(){
        var g=initAndCreateGame();var a=addCard("Shivan Dragon",g.getPlayers().get(0));var b=addCard("Shivan Dragon",g.getPlayers().get(1));var host=addCard("Mountain",g.getPlayers().get(0));
        Assert.assertEquals(cost(a,b),0);var t=tax(host,2);host.addStaticAbility(t);Assert.assertEquals(cost(a,b),2);
        host.getCurrentState().removeStaticAbility(t);Assert.assertEquals(cost(a,b),0);
    }
    @Test public void textAndLayerSixTraitsAreNotCached(){
        var g=initAndCreateGame();var a=addCard("Runeclaw Bear",g.getPlayers().get(0));var b=addCard("Runeclaw Bear",g.getPlayers().get(1));var host=addCard("Mountain",g.getPlayers().get(0));var t=tax(host,3);
        host.getChangedCardTraitsByText().put(1L,0L,new CardTraitChanges(null,null,null,List.of(t),null));Assert.assertEquals(cost(a,b),3);
        host.getChangedCardTraitsByText().clear();Assert.assertEquals(cost(a,b),0);
        host.getChangedCardTraits().put(2L,0L,new CardTraitChanges(null,null,null,List.of(t),null));Assert.assertEquals(cost(a,b),3);
        host.getChangedCardTraits().clear();Assert.assertEquals(cost(a,b),0);
    }
    @Test public void keywordAddedCostIsSeenOnNextLookup(){
        var g=initAndCreateGame();var a=addCard("Runeclaw Bear",g.getPlayers().get(0));var b=addCard("Runeclaw Bear",g.getPlayers().get(1));
        var kw=Keyword.getInstance("Flying");b.getCurrentState().getCachedKeywords().insert(kw);Assert.assertEquals(cost(a,b),0);
        kw.addStaticAbility(tax(b,4));Assert.assertEquals(cost(a,b),4);b.getCurrentState().getCachedKeywords().removeAll(Keyword.FLYING);Assert.assertEquals(cost(a,b),0);
    }
    @Test public void removalAndSuppressionPreserveConditionalRebuild(){
        var g=initAndCreateGame();var a=addCard("Runeclaw Bear",g.getPlayers().get(0));var b=addCard("Runeclaw Bear",g.getPlayers().get(1));var host=addCard("Mountain",g.getPlayers().get(0));var t=tax(host,2);host.addStaticAbility(t);
        host.getChangedCardTraits().put(3L,0L,new CardTraitChanges(null,null,null,null,x->true));Assert.assertEquals(cost(a,b),0);
        host.getChangedCardTraits().clear();Assert.assertEquals(cost(a,b),2);t.setSuppressed(true);Assert.assertEquals(cost(a,b),0);t.setSuppressed(false);Assert.assertEquals(cost(a,b),2);
    }
    @Test public void splitStateAndMultipleTaxesPreserveCostOrder(){
        var g=initAndCreateGame();var a=addCard("Runeclaw Bear",g.getPlayers().get(0));var b=addCard("Runeclaw Bear",g.getPlayers().get(1));var split=addCard("Wear // Tear",g.getPlayers().get(0));
        split.getState(CardStateName.LeftSplit).addStaticAbility(tax(split,2));split.getState(CardStateName.RightSplit).addStaticAbility(tax(split,3));
        Assert.assertEquals(cost(a,b),5);
    }
}
