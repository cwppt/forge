package forge.ai;

import forge.card.CardStateName;
import forge.game.card.*;
import forge.game.keyword.Keyword;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.List;

public final class EmptyTriggerRegressionTest extends AITest {
    private Trigger trigger(Card host) {return TriggerHandler.parseTrigger("Mode$ SpellCast | ValidCard$ Card | TriggerZones$ Battlefield | Static$ True",host,true);}
    @Test public void ordinaryAndKeywordEmptyStatesStayEmpty() {
        var g=initAndCreateGame();var p=g.getPlayers().get(1);
        for(String name:List.of("Mountain","Runeclaw Bear","Shivan Dragon")) {
            var c=addCard(name,p);for(int i=0;i<5;i++)Assert.assertTrue(c.getTriggers().isEmpty(),name);
        }
        Assert.assertTrue(addToken("c_a_treasure_sac",p).getTriggers().isEmpty());
    }
    @Test public void newlyAddedPrintedAndKeywordTriggersRemainVisible() {
        var g=initAndCreateGame();var c=addCard("Runeclaw Bear",g.getPlayers().get(1));
        var prior=c.getTriggers();Assert.assertTrue(prior.isEmpty());var t=trigger(c);c.addTrigger(t);
        Assert.assertTrue(c.getTriggers().contains(t));Assert.assertTrue(prior.isEmpty());
        var other=addCard("Runeclaw Bear",g.getPlayers().get(1));var keyword=Keyword.getInstance("Flying");
        other.getCurrentState().getCachedKeywords().insert(keyword);Assert.assertTrue(other.getTriggers().isEmpty());
        var added=trigger(other);keyword.addTrigger(added);Assert.assertTrue(other.getTriggers().contains(added));
        other.getCurrentState().getCachedKeywords().removeAll(Keyword.FLYING);Assert.assertTrue(other.getTriggers().isEmpty());
    }
    @Test public void liveChangedTextTraitsAndRemovalUseOriginalRebuild() {
        var g=initAndCreateGame();var c=addCard("Runeclaw Bear",g.getPlayers().get(1));var t=trigger(c);
        Assert.assertTrue(c.getTriggers().isEmpty());
        c.getChangedCardTraits().put(1L,0L,new CardTraitChanges(null,List.of(t),null,null,null));
        Assert.assertTrue(c.getTriggers().contains(t));c.getChangedCardTraits().clear();Assert.assertTrue(c.getTriggers().isEmpty());
        c.getChangedCardTraitsByText().put(2L,0L,new CardTraitChanges(null,List.of(t),null,null,null));
        Assert.assertTrue(c.getTriggers().contains(t));c.getChangedCardTraitsByText().clear();Assert.assertTrue(c.getTriggers().isEmpty());
        c.addTrigger(t);c.getChangedCardTraits().put(3L,0L,new CardTraitChanges(null,null,null,null,x->true));
        Assert.assertTrue(c.getTriggers().isEmpty());c.getChangedCardTraits().clear();Assert.assertTrue(c.getTriggers().contains(t));
    }
    @Test public void splitAndCopiedStateRetainTriggersAndOrdering() {
        var g=initAndCreateGame();var p=g.getPlayers().get(1);var split=createCard("Wear // Tear",p);
        var first=trigger(split);var second=trigger(split);
        split.getState(CardStateName.LeftSplit).addTrigger(first);split.getState(CardStateName.RightSplit).addTrigger(second);
        Assert.assertEquals(List.copyOf(split.getState(CardStateName.Original).getTriggers()),List.of(first,second));
        var lathliss=addCard("Lathliss, Dragon Queen",p);var copy=CardCopyService.getLKICopy(lathliss);
        Assert.assertEquals(copy.getTriggers().size(),lathliss.getTriggers().size());Assert.assertFalse(copy.getTriggers().isEmpty());
    }
}
