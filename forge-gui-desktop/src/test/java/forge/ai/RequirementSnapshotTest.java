package forge.ai;
import forge.game.card.*;
import forge.game.combat.*;
import forge.game.keyword.Keyword;
import org.testng.Assert;
import org.testng.annotations.Test;

public final class RequirementSnapshotTest extends AITest {
    private void keyword(Card c,String text){c.getCurrentState().getCachedKeywords().insert(Keyword.getInstance(text));}
    @Test public void noRequirementWithExistingBlockerDoesNotForceAnother() {
        var g=initAndCreateGame();var a=addCard("Runeclaw Bear",g.getPlayers().get(0));var b=addCard("Hill Giant",g.getPlayers().get(1));var other=addCard("Runeclaw Bear",g.getPlayers().get(1));var combat=new Combat(a.getController());combat.addAttacker(a,b.getController());combat.addBlocker(a,other);
        Assert.assertFalse(CombatUtil.mustBlockAnAttacker(b,combat,null));Assert.assertTrue(CombatUtil.canBlock(a,b,combat));
    }
    @Test public void allAbleBlockersStillRequired() {
        var g=initAndCreateGame();var a=addCard("Prized Unicorn",g.getPlayers().get(0));var b=addCard("Hill Giant",g.getPlayers().get(1));var other=addCard("Runeclaw Bear",g.getPlayers().get(1));var combat=new Combat(a.getController());combat.addAttacker(a,b.getController());combat.addBlocker(a,other);
        Assert.assertTrue(CombatUtil.mustBlockAnAttacker(b,combat,null));
    }
    @Test public void atLeastOneRequirementUsesTheLiveAssignedList() {
        var g=initAndCreateGame();var a=addCard("Runeclaw Bear",g.getPlayers().get(0));var b=addCard("Hill Giant",g.getPlayers().get(1));var other=addCard("Runeclaw Bear",g.getPlayers().get(1));var combat=new Combat(a.getController());combat.addAttacker(a,b.getController());
        Assert.assertFalse(CombatUtil.mustBlockAnAttacker(b,combat,null));keyword(a,"CARDNAME must be blocked if able.");Assert.assertTrue(CombatUtil.mustBlockAnAttacker(b,combat,null));combat.addBlocker(a,other);Assert.assertFalse(CombatUtil.mustBlockAnAttacker(b,combat,null));
    }
    @Test public void exactlyOneRequirementUsesTheLiveAssignedList() {
        var g=initAndCreateGame();var a=addCard("Runeclaw Bear",g.getPlayers().get(0));var b=addCard("Hill Giant",g.getPlayers().get(1));var other=addCard("Runeclaw Bear",g.getPlayers().get(1));var combat=new Combat(a.getController());combat.addAttacker(a,b.getController());keyword(a,"CARDNAME must be blocked by exactly one creature if able.");
        Assert.assertTrue(CombatUtil.mustBlockAnAttacker(b,combat,null));combat.addBlocker(a,other);Assert.assertFalse(CombatUtil.mustBlockAnAttacker(b,combat,null));
    }
    @Test public void twoOrMoreRequirementUsesTheLiveAssignedList() {
        var g=initAndCreateGame();var a=addCard("Runeclaw Bear",g.getPlayers().get(0));var b=addCard("Hill Giant",g.getPlayers().get(1));var one=addCard("Runeclaw Bear",g.getPlayers().get(1));var two=addCard("Runeclaw Bear",g.getPlayers().get(1));var combat=new Combat(a.getController());combat.addAttacker(a,b.getController());keyword(a,"CARDNAME must be blocked by two or more creatures if able.");
        combat.addBlocker(a,one);Assert.assertTrue(CombatUtil.mustBlockAnAttacker(b,combat,null));combat.addBlocker(a,two);Assert.assertFalse(CombatUtil.mustBlockAnAttacker(b,combat,null));
    }
    @Test public void validBlockerRequirementMustInspectWhoAlreadyBlocks() {
        var g=initAndCreateGame();var a=addCard("Runeclaw Bear",g.getPlayers().get(0));var b=addCard("Hill Giant",g.getPlayers().get(1));var one=addCard("Runeclaw Bear",g.getPlayers().get(1));var two=addCard("Hill Giant",g.getPlayers().get(1));var combat=new Combat(a.getController());combat.addAttacker(a,b.getController());keyword(a,"MustBeBlockedBy Creature.Giant");Assert.assertTrue(b.isValid("Creature.Giant",null,null,null),"giant fixture type");Assert.assertTrue(a.hasStartOfKeyword("MustBeBlockedBy"),"live keyword fixture");
        Assert.assertTrue(CombatUtil.mustBlockAnAttacker(b,combat,null));combat.addBlocker(a,one);Assert.assertTrue(CombatUtil.mustBlockAnAttacker(b,combat,null));combat.addBlocker(a,two);Assert.assertFalse(CombatUtil.mustBlockAnAttacker(b,combat,null));
    }
}
