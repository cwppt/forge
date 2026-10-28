package forge.ai;
import forge.game.card.*;
import forge.game.combat.*;
import forge.game.keyword.Keyword;
import forge.game.staticability.StaticAbility;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.List;

public final class BlockingCostHoistTest extends AITest {
    private void lure(Card c,String text){c.getCurrentState().getCachedKeywords().insert(Keyword.getInstance(text));}
    private StaticAbility tax(Card c){return new StaticAbility("Mode$ CantBlockUnless | ValidCard$ Creature | Cost$ 2",c,c.getCurrentState());}
    @Test public void noRequirementDoesNotForceABlock(){
        var g=initAndCreateGame();var a=addCard("Runeclaw Bear",g.getPlayers().get(0));var b=addCard("Hill Giant",g.getPlayers().get(1));var combat=new Combat(a.getController());combat.addAttacker(a,b.getController());
        Assert.assertFalse(CombatUtil.mustBlockAnAttacker(b,combat,null));Assert.assertTrue(CombatUtil.canBlock(a,b,combat));
    }
    @Test public void lureMustStillBeBlocked(){
        var g=initAndCreateGame();var a=addCard("Prized Unicorn",g.getPlayers().get(0));var other=addCard("Runeclaw Bear",g.getPlayers().get(0));var b=addCard("Hill Giant",g.getPlayers().get(1));var combat=new Combat(a.getController());combat.addAttacker(a,b.getController());combat.addAttacker(other,b.getController());
        Assert.assertTrue(CombatUtil.mustBlockAnAttacker(b,combat,null));Assert.assertFalse(CombatUtil.canBlock(other,b,combat));Assert.assertTrue(CombatUtil.canBlock(a,b,combat));
    }
    @Test public void externalTaxAndSuppressionAreRecheckedEveryInvocation(){
        var g=initAndCreateGame();var a=addCard("Prized Unicorn",g.getPlayers().get(0));var b=addCard("Hill Giant",g.getPlayers().get(1));var host=addCard("Mountain",g.getPlayers().get(1));var combat=new Combat(a.getController());combat.addAttacker(a,b.getController());
        Assert.assertTrue(CombatUtil.mustBlockAnAttacker(b,combat,null));var t=tax(host);host.addStaticAbility(t);Assert.assertFalse(CombatUtil.mustBlockAnAttacker(b,combat,null));
        t.setSuppressed(true);Assert.assertTrue(CombatUtil.mustBlockAnAttacker(b,combat,null));t.setSuppressed(false);Assert.assertFalse(CombatUtil.mustBlockAnAttacker(b,combat,null));
        host.getCurrentState().removeStaticAbility(t);Assert.assertTrue(CombatUtil.mustBlockAnAttacker(b,combat,null));
    }
    @Test public void textAndLayerTraitsCanIntroduceCosts(){
        var g=initAndCreateGame();var a=addCard("Prized Unicorn",g.getPlayers().get(0));var b=addCard("Hill Giant",g.getPlayers().get(1));var host=addCard("Mountain",g.getPlayers().get(0));var combat=new Combat(a.getController());combat.addAttacker(a,b.getController());
        host.getChangedCardTraitsByText().put(1L,0L,new CardTraitChanges(null,null,null,List.of(tax(host)),null));Assert.assertFalse(CombatUtil.mustBlockAnAttacker(b,combat,null));host.getChangedCardTraitsByText().clear();
        host.getChangedCardTraits().put(2L,0L,new CardTraitChanges(null,null,null,List.of(tax(host)),null));Assert.assertFalse(CombatUtil.mustBlockAnAttacker(b,combat,null));host.getChangedCardTraits().clear();Assert.assertTrue(CombatUtil.mustBlockAnAttacker(b,combat,null));
    }
    @Test public void forcedSpecificBlockerRequirementIsPreserved(){
        var g=initAndCreateGame();var a=addCard("Runeclaw Bear",g.getPlayers().get(0));var b=addCard("Hill Giant",g.getPlayers().get(1));var combat=new Combat(a.getController());combat.addAttacker(a,b.getController());b.addMustBlockCard(1L,a);
        Assert.assertTrue(CombatUtil.mustBlockAnAttacker(b,combat,null));combat.addBlocker(a,b);Assert.assertFalse(CombatUtil.mustBlockAnAttacker(b,combat,null));
    }
    @Test public void satisfiedAndMinimumBlockRequirementsRemainLive(){
        var g=initAndCreateGame();var a=addCard("Runeclaw Bear",g.getPlayers().get(0));var b=addCard("Hill Giant",g.getPlayers().get(1));var c=addCard("Runeclaw Bear",g.getPlayers().get(1));var combat=new Combat(a.getController());combat.addAttacker(a,b.getController());
        lure(a,"CARDNAME must be blocked if able.");Assert.assertTrue(CombatUtil.mustBlockAnAttacker(b,combat,null));combat.addBlocker(a,c);Assert.assertFalse(CombatUtil.mustBlockAnAttacker(b,combat,null));
    }
    @Test public void flyingReachAndMenaceOptionsAreNotPruned(){
        var g=initAndCreateGame();var a=addCard("Serra Angel",g.getPlayers().get(0));var b=addCard("Runeclaw Bear",g.getPlayers().get(1));var spider=addCard("Giant Spider",g.getPlayers().get(1));var menace=addCard("Boggart Brute",g.getPlayers().get(0));var combat=new Combat(a.getController());combat.addAttacker(a,b.getController());combat.addAttacker(menace,b.getController());
        Assert.assertFalse(CombatUtil.canBlock(a,b,combat));Assert.assertTrue(CombatUtil.canBlock(a,spider,combat));Assert.assertFalse(CombatUtil.canAttackerBeBlockedWithAmount(menace,1,combat));Assert.assertTrue(CombatUtil.canAttackerBeBlockedWithAmount(menace,2,combat));
    }
    @Test public void randomizedTaxExpressionsRetainOriginalEvaluationOrder(){
        var g=initAndCreateGame();var a=addCard("Runeclaw Bear",g.getPlayers().get(0));var b=addCard("Hill Giant",g.getPlayers().get(1));var host=addCard("Mountain",g.getPlayers().get(0));var combat=new Combat(a.getController());combat.addAttacker(a,b.getController());
        var t=new StaticAbility("Mode$ CantBlockUnless | ValidCard$ Creature | Cost$ X",host,host.getCurrentState());t.setSVar("X","Count$Random.1.6");host.addStaticAbility(t);
        forge.util.MyRandom.setRandom(new java.util.Random(771230));Assert.assertFalse(CombatUtil.mustBlockAnAttacker(b,combat,null));int next=forge.util.MyRandom.getRandom().nextInt();
        var expected=new java.util.Random(771230);expected.nextInt(6);Assert.assertEquals(next,expected.nextInt());
    }
}
