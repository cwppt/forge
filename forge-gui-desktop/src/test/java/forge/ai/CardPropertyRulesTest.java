package forge.ai;

import forge.card.CardType;
import forge.game.card.Card;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Test;

/** Run against the same frozen bytecode under both JVM compilation settings. */
public final class CardPropertyRulesTest extends AITest {
    @Test public void liveTapStateAndNegation() {
        var g=initAndCreateGame();var p=g.getPlayers().get(0);var c=addCard("Runeclaw Bear",p);
        Assert.assertFalse(c.hasProperty("tapped",p,c,null));c.setTapped(true);
        Assert.assertTrue(c.hasProperty("tapped",p,c,null));Assert.assertFalse(c.hasProperty("!tapped",p,c,null));
    }
    @Test public void liveControllerAndOpponentRestrictions() {
        var g=initAndCreateGame();var p=g.getPlayers().get(0);var q=g.getPlayers().get(1);var c=addCard("Runeclaw Bear",p);
        Assert.assertTrue(c.hasProperty("YouCtrl",p,c,null));c.setController(q,g.getNextTimestamp());
        Assert.assertFalse(c.hasProperty("YouCtrl",p,c,null));Assert.assertTrue(c.hasProperty("YouDontCtrl",p,c,null));
    }
    @Test public void phasedOutPrefixPreservesRules() {
        var g=initAndCreateGame();var p=g.getPlayers().get(0);var c=addCard("Runeclaw Bear",p);
        c.setPhasedOut(p);Assert.assertFalse(c.hasProperty("YouCtrl",p,c,null));
        Assert.assertTrue(c.hasProperty("phasedOutYouCtrl",p,c,null));c.setPhasedOut(null);Assert.assertTrue(c.hasProperty("YouCtrl",p,c,null));
    }
    @Test public void characteristicAndChosenColorChangesRemainLive() {
        var g=initAndCreateGame();var p=g.getPlayers().get(0);var c=addCard("Runeclaw Bear",p);
        Assert.assertTrue(c.isValid("Creature.Green",p,c,null));c.setType(CardType.parse("Artifact"));Assert.assertFalse(c.isValid("Creature.Green",p,c,null));
        c.setChosenColors(List.of("Green"));Assert.assertTrue(c.hasProperty("ChosenColor",p,c,null));
        c.setChosenColors(List.of("Blue"));Assert.assertFalse(c.hasProperty("ChosenColor",p,c,null));
    }
    @Test public void externalStaticEffectAndItsRemovalChangePower() {
        var g=initAndCreateGame();var p=g.getPlayers().get(0);var c=addCard("Runeclaw Bear",p);
        Assert.assertFalse(c.hasProperty("powerGE3",p,c,null));var anthem=addCard("Glorious Anthem",p);g.getAction().checkStaticAbilities();
        Assert.assertTrue(c.hasProperty("powerGE3",p,c,null));g.getAction().moveToGraveyard(anthem,null);g.getAction().checkStaticAbilities();
        Assert.assertFalse(c.hasProperty("powerGE3",p,c,null));
    }
    @Test public void zoneChangeAndIdentityRemainLive() {
        var g=initAndCreateGame();var p=g.getPlayers().get(0);var c=addCard("Runeclaw Bear",p);var source=addCard("Hill Giant",p);
        Assert.assertTrue(c.hasProperty("Other",p,source,null));Assert.assertFalse(c.hasProperty("Self",p,source,null));
        Card moved=g.getAction().moveToGraveyard(c,null);
        Assert.assertTrue(moved.hasProperty("inZoneGraveyard",p,source,null));Assert.assertFalse(moved.hasProperty("inZoneBattlefield",p,source,null));
    }
}
