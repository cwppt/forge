package forge.ai;

import forge.card.CardStateName;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardState;
import forge.game.card.CardTraitChanges;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;
import forge.game.staticability.StaticAbility;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

public class StaticTraitPreparationRegressionTest extends AITest {
    private StaticAbility restriction(Card host, CardState state) {
        return new StaticAbility("Mode$ CantAttack | ValidCard$ Card.Self", host, state);
    }

    @Test
    public void provenEmptyStateSkipsRebuildButNewPrintedAbilityIsSeen() {
        Game game = initAndCreateGame();
        Player owner = game.getPlayers().get(1);
        int[] rebuilds = {0};
        Card sentinel = new Card(game.nextCardId(), game) {
            @Override
            public void updateStaticAbilities(List<StaticAbility> list, CardState state) {
                rebuilds[0]++;
                super.updateStaticAbilities(list, state);
            }
        };
        sentinel.setName("Runeclaw Bear");
        sentinel.setOwner(owner);
        rebuilds[0] = 0;
        Assert.assertTrue(sentinel.getStaticAbilities().isEmpty());
        Assert.assertEquals(rebuilds[0], 0);
        StaticAbility added = restriction(sentinel, sentinel.getCurrentState());
        sentinel.addStaticAbility(added);
        Assert.assertTrue(sentinel.getStaticAbilities().contains(added));
        Assert.assertEquals(rebuilds[0], 1);
        sentinel.getCurrentState().removeStaticAbility(added);
        Assert.assertTrue(sentinel.getStaticAbilities().isEmpty());
        Assert.assertEquals(rebuilds[0], 1);
    }

    @Test
    public void changedTraitsTextAndRemovalKeepTheFullLiveRebuild() {
        Game game = initAndCreateGame();
        Card bear = addCard("Runeclaw Bear", game.getPlayers().get(1));
        StaticAbility added = restriction(bear, bear.getCurrentState());
        Assert.assertTrue(bear.getStaticAbilities().isEmpty());
        bear.getChangedCardTraits().put(1L, 0L, new CardTraitChanges(null, null, null, List.of(added), null));
        Assert.assertTrue(bear.getStaticAbilities().contains(added));
        bear.getChangedCardTraits().clear();
        Assert.assertTrue(bear.getStaticAbilities().isEmpty());
        bear.getChangedCardTraitsByText().put(2L, 0L, new CardTraitChanges(null, null, null, List.of(added), null));
        Assert.assertTrue(bear.getStaticAbilities().contains(added));
        bear.getChangedCardTraitsByText().clear();
        bear.addStaticAbility(added);
        bear.getChangedCardTraits().put(3L, 0L, new CardTraitChanges(null, null, null, null, trait -> true));
        Assert.assertTrue(bear.getStaticAbilities().isEmpty());
        bear.getChangedCardTraits().clear();
        Assert.assertTrue(bear.getStaticAbilities().contains(added));
    }

    @Test
    public void keywordTraitsRemainLiveWithoutCachingResults() {
        Game game = initAndCreateGame();
        Card bear = addCard("Runeclaw Bear", game.getPlayers().get(1));
        KeywordInterface keyword = Keyword.getInstance("Flying");
        bear.getCurrentState().getCachedKeywords().insert(keyword);
        Assert.assertTrue(bear.getStaticAbilities().isEmpty());
        StaticAbility added = restriction(bear, bear.getCurrentState());
        keyword.addStaticAbility(added);
        Assert.assertTrue(bear.getStaticAbilities().contains(added));
        bear.getCurrentState().getCachedKeywords().removeAll(Keyword.FLYING);
        Assert.assertTrue(bear.getStaticAbilities().isEmpty());
    }

    @Test
    public void splitStateAbilitiesRetainOrderingAndAreNotSkipped() {
        Game game = initAndCreateGame();
        Card split = createCard("Wear // Tear", game.getPlayers().get(1));
        CardState left = split.getState(CardStateName.LeftSplit);
        CardState right = split.getState(CardStateName.RightSplit);
        StaticAbility first = restriction(split, left);
        StaticAbility second = restriction(split, right);
        left.addStaticAbility(first);
        right.addStaticAbility(second);
        Assert.assertEquals(List.copyOf(split.getState(CardStateName.Original).getStaticAbilities()), List.of(first, second));
    }
}
