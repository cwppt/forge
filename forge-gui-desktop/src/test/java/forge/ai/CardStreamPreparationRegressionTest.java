package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class CardStreamPreparationRegressionTest extends AITest {
    @Test
    public void preservesFilterEvaluationOrderAndDuplicateHandling() {
        Game game = initAndCreateGame();
        Card a = addCardToZone("Runeclaw Bear", game.getPlayers().get(1), ZoneType.Library);
        Card b = addCardToZone("Runeclaw Bear", game.getPlayers().get(1), ZoneType.Library);
        Card c = addCardToZone("Runeclaw Bear", game.getPlayers().get(1), ZoneType.Library);
        List<Card> input = Arrays.asList(a, b, a, c, b);
        List<Card> evaluated = new ArrayList<>();
        CardCollection result = new CardCollection(input.stream().filter(card -> {
            evaluated.add(card);
            return card != b;
        }));
        Assert.assertEquals(evaluated, input);
        Assert.assertEquals(new ArrayList<>(result), Arrays.asList(a, c));
        result.clear();
        Assert.assertEquals(input.size(), 5);
    }

    @Test
    public void preservesParallelEncounterOrderAndEmptyStreams() {
        Game game = initAndCreateGame();
        List<Card> input = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            input.add(addCardToZone("Runeclaw Bear", game.getPlayers().get(1), ZoneType.Library));
        }
        List<Card> duplicated = new ArrayList<>(input);
        duplicated.addAll(input);
        List<Card> evaluated = new ArrayList<>();
        CardCollection result = new CardCollection(duplicated.parallelStream().filter(card -> {
            evaluated.add(card);
            return true;
        }));
        Assert.assertEquals(new ArrayList<>(result), input);
        Assert.assertEquals(evaluated, duplicated);
        Assert.assertTrue(new CardCollection(Stream.<Card>empty()).isEmpty());
    }

    @Test
    public void consumesPredicatesOnceAndPropagatesFailures() {
        Game game = initAndCreateGame();
        Card a = addCardToZone("Runeclaw Bear", game.getPlayers().get(1), ZoneType.Library);
        int[] calls = {0};
        RuntimeException failure = new RuntimeException("sentinel");
        try {
            new CardCollection(Stream.of(a, a, a).filter(card -> {
                if (++calls[0] == 2) throw failure;
                return true;
            }));
            Assert.fail("Expected predicate failure");
        } catch (RuntimeException actual) {
            Assert.assertSame(actual, failure);
        }
        Assert.assertEquals(calls[0], 2);
    }
}
