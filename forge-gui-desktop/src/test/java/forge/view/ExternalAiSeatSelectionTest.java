package forge.view;

import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ExternalAiSeatSelectionTest {
    @Test public void missingFlagPreservesAllSeatsAndEmptyFlagSelectsNone() {
        Assert.assertNull(SimulateMatch.externalSeats(Map.of(), 2));
        Assert.assertEquals(SimulateMatch.externalSeats(Map.of("external-ai-seats", List.of()), 2), Set.of());
    }
    @Test public void selectedSeatsAreValidatedAndDeduplicated() {
        Assert.assertEquals(SimulateMatch.externalSeats(Map.of("external-ai-seats", List.of("1")), 2), Set.of(1));
        Assert.assertEquals(SimulateMatch.externalSeats(Map.of("external-ai-seats", List.of("0", "1", "0")), 2), Set.of(0, 1));
        for (String invalid : List.of("-1", "2", "abc")) {
            Assert.expectThrows(IllegalArgumentException.class,
                    () -> SimulateMatch.externalSeats(Map.of("external-ai-seats", List.of(invalid)), 2));
        }
        Assert.expectThrows(IllegalArgumentException.class,
                () -> SimulateMatch.externalSeats(Map.of("external-ai-seats", List.of(), "t", List.of("Swiss")), 2));
    }
}
