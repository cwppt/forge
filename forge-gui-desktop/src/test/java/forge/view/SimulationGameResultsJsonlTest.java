package forge.view;

import com.google.gson.JsonParser;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class SimulationGameResultsJsonlTest {
    @Test
    public void exportsInterruptedRunsSeparatelyFromRecordedOutcomes() throws Exception {
        Path output = Files.createTempFile("forge-results-status-", ".jsonl");
        try {
            SimulateMatch.writeGameResultsJsonl(output, List.of(
                    new SimulateMatch.GameSimulationResult(1, 0, 2100L, 10, 5, false, "Normal winner"),
                    new SimulateMatch.GameSimulationResult(2, 1, 2100L, 120000, 6, false,
                            "Recorded winner", true, null),
                    new SimulateMatch.GameSimulationResult(3, 2, 2100L, 15, 7, true,
                            null, false, "java.lang.IllegalStateException")));
            var rows = Files.readAllLines(output).stream().map(line ->
                    JsonParser.parseString(line).getAsJsonObject()).toList();
            Assert.assertEquals(rows.size(), 3);
            Assert.assertFalse(rows.get(0).get("timedOut").getAsBoolean());
            Assert.assertTrue(rows.get(0).get("simulationError").isJsonNull());
            Assert.assertTrue(rows.get(1).get("timedOut").getAsBoolean());
            Assert.assertEquals(rows.get(1).get("winner").getAsString(), "Recorded winner");
            Assert.assertEquals(rows.get(1).get("gameIndex").getAsInt(), 1);
            Assert.assertEquals(rows.get(1).get("runSeed").getAsLong(), 2100L);
            Assert.assertTrue(rows.get(2).get("winner").isJsonNull());
            Assert.assertTrue(rows.get(2).get("draw").getAsBoolean());
            Assert.assertEquals(rows.get(2).get("simulationError").getAsString(),
                    "java.lang.IllegalStateException");
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
