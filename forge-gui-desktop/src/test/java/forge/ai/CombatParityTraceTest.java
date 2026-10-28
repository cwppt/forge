package forge.ai;
import forge.game.*;
import forge.game.card.*;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.nio.file.*;
import com.google.gson.*;

public final class CombatParityTraceTest extends AITest {
    @Test public void nativeSimultaneousDamageHasBoundariesRecipientsAmountsAndFinalState() throws Exception {
        var g=initAndCreateGame();var a=addCard("Shivan Dragon",g.getPlayers().get(0));var b=addCard("Shivan Dragon",g.getPlayers().get(1));
        Path out=Path.of(System.getProperty("forge.parity.testdir")).resolve("native-batch.jsonl");
        System.setProperty("forge.parity.trace",out.toString());
        try {
            CombatParityTrace.begin(g);g.copyLastState();var table=new CardDamageTable();table.put(a,b,2);table.put(a,g.getPlayers().get(1),3);
            int life=g.getPlayers().get(1).getLife();g.getAction().dealDamage(true,table,new CardDamageTable(),new GameEntityCounterTable(),null);
            Assert.assertEquals(b.getDamage(),2);Assert.assertEquals(g.getPlayers().get(1).getLife(),life-3);CombatParityTrace.finish(g);
            var rows=Files.readAllLines(out).stream().map(x->JsonParser.parseString(x).getAsJsonObject()).toList();
            Assert.assertEquals(rows.get(0).get("type").getAsString(),"BEGIN");Assert.assertEquals(rows.get(rows.size()-1).get("type").getAsString(),"END");
            Assert.assertEquals(rows.stream().filter(r->r.get("type").getAsString().equals("BATCH_BEGIN")).count(),1L);
            Assert.assertEquals(rows.stream().filter(r->r.get("type").getAsString().equals("BATCH_END")).count(),1L);
            var damage=rows.stream().filter(r->r.get("type").getAsString().equals("DAMAGE")).toList();Assert.assertEquals(damage.size(),2);
            Assert.assertTrue(damage.stream().anyMatch(r->r.getAsJsonObject("data").get("recipient").getAsString().equals("card:"+b.getId()) && r.getAsJsonObject("data").get("amount").getAsInt()==2));
            Assert.assertTrue(damage.stream().anyMatch(r->r.getAsJsonObject("data").get("recipient").getAsString().equals("player:"+g.getPlayers().get(1).getId()) && r.getAsJsonObject("data").get("amount").getAsInt()==3));
        } finally {CombatParityTrace.finish(g);System.clearProperty("forge.parity.trace");}
    }
}
