package forge.view;
import forge.ai.AITest;
import forge.game.event.GameEventTurnBegan;
import java.nio.file.*;
import com.google.gson.JsonParser;
import org.testng.Assert;
import org.testng.annotations.Test;

public class SimulationTurnMetricsTest extends AITest {
    @Test public void compactTurnBoundariesObserveLiveBoardAndCloseOnce() throws Exception {
        var game=initAndCreateGame();var p=game.getPlayers().get(0);addCard("Shivan Dragon",p);
        addToken("c_a_treasure_sac",p);Path dir=Files.createTempDirectory("turn-metrics-");Path file=dir.resolve("turns.jsonl");
        try {
            var metrics=SimulationTurnMetrics.begin(game,file);
            game.fireEvent(new GameEventTurnBegan(p.getView(),1));
            game.fireEvent(new GameEventTurnBegan(p.getView(),2));metrics.finish();metrics.finish();
            var rows=Files.readAllLines(file);Assert.assertEquals(rows.size(),2);
            var row=JsonParser.parseString(rows.get(0)).getAsJsonObject();
            Assert.assertEquals(row.get("turn").getAsInt(),1);Assert.assertTrue(row.get("elapsedMs").getAsDouble()>=0);
            Assert.assertEquals(row.get("permanents").getAsInt(),2);Assert.assertEquals(row.get("treasure").getAsInt(),1);
            Assert.assertEquals(row.get("creatures").getAsInt(),1);Assert.assertEquals(row.get("tokens").getAsInt(),1);
            Assert.assertEquals(game.getCardsIn(forge.game.zone.ZoneType.Battlefield).size(),2);
        } finally {Files.deleteIfExists(file);Files.deleteIfExists(dir);}
    }
}
