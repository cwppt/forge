package forge.ai;

import com.google.gson.JsonParser;
import forge.ai.decision.CommanderRoleTelemetry;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventTurnPhase;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.nio.file.Files;
import java.util.Random;

public class CommanderRoleTelemetryTest extends AITest {
    @Test public void retainsZoneEventWhenResolvingStackIsEmpty() throws Exception {
        var game=initAndCreateGame(); var player=game.getPlayers().get(1);
        var card=addCardToZone("Goblin Engineer",player,ZoneType.Hand);
        var path=Files.createTempFile("role-empty-stack-",".jsonl");
        Assert.assertTrue(game.getStack().isEmpty());
        game.getStack().setResolving(true);
        try(var observer=CommanderRoleTelemetry.begin(game,0,2556L,path)) {
            observer.zone(new GameEventCardChangeZone(card,player.getZone(ZoneType.Library),player.getZone(ZoneType.Hand)));
            Assert.assertTrue(game.getStack().isEmpty());
            Assert.assertTrue(player.getCardsIn(ZoneType.Hand).contains(card));
            observer.finish(false,null);
        } finally {
            game.getStack().setResolving(false);
        }
        var rows=Files.readAllLines(path); Assert.assertEquals(rows.size(),3);
        var event=JsonParser.parseString(rows.get(1)).getAsJsonObject();
        Assert.assertEquals(event.get("type").getAsString(),"ZONE");
        Assert.assertEquals(event.getAsJsonObject("card").get("id").getAsInt(),card.getId());
        Assert.assertTrue(event.get("resolving").isJsonNull());
        Files.deleteIfExists(path);
    }
    @Test public void snapshotsDoNotChangeCardsAbilitiesOrRandomState() throws Exception {
        var game=initAndCreateGame(); var player=game.getPlayers().get(1);
        var land=addCardToZone("Mountain",player,ZoneType.Battlefield);
        var dragon=addCardToZone("Steel Hellkite",player,ZoneType.Hand);
        var ability=land.getManaAbilities().get(0);
        var activating=ability.getActivatingPlayer(); var path=Files.createTempFile("role-snapshot-",".jsonl");
        MyRandom.setRandom(new Random(1782));
        try(var observer=CommanderRoleTelemetry.begin(game,2,2500L,path)) {
            observer.phase(new GameEventTurnPhase(player,PhaseType.MAIN1,""));
            Assert.assertFalse(land.isTapped()); Assert.assertEquals(ability.getActivatingPlayer(),activating);
            Assert.assertTrue(player.getCardsIn(ZoneType.Hand).contains(dragon));
            Assert.assertEquals(MyRandom.getRandom().nextLong(),new Random(1782).nextLong());
            observer.finish(false,null);
        }
        var rows=Files.readAllLines(path); Assert.assertEquals(rows.size(),3);
        var snapshot=JsonParser.parseString(rows.get(1)).getAsJsonObject();
        Assert.assertEquals(snapshot.get("type").getAsString(),"SNAPSHOT");
        var hand=snapshot.getAsJsonArray("players").get(0).getAsJsonObject().getAsJsonArray("Hand");
        Assert.assertEquals(hand.get(0).getAsJsonObject().get("name").getAsString(),"Steel Hellkite");
        Assert.assertEquals(hand.get(0).getAsJsonObject().get("mv").getAsInt(),6);
        Files.deleteIfExists(path);
    }
    @Test public void exportsZoneIdentityAndExcludesCopiedCandidatesAndLateWorkers() throws Exception {
        var game=initAndCreateGame(); var player=game.getPlayers().get(1);
        var card=addCardToZone("Goblin Engineer",player,ZoneType.Hand);
        var ability=card.getSpellAbilities().get(0); var path=Files.createTempFile("role-events-",".jsonl");
        var other=initAndCreateGame(); var otherPlayer=other.getPlayers().get(1);
        var otherCard=addCardToZone("Goblin Engineer",otherPlayer,ZoneType.Hand);
        try(var observer=CommanderRoleTelemetry.begin(game,0,2500L,path)) {
            observer.zone(new GameEventCardChangeZone(card,player.getZone(ZoneType.Library),player.getZone(ZoneType.Hand)));
            CommanderRoleTelemetry.observeCandidate(player,ability,"CantAfford");
            CommanderRoleTelemetry.observeCandidate(otherPlayer,otherCard.getSpellAbilities().get(0),"WillPlay");
            Thread late=new Thread(()->CommanderRoleTelemetry.observeCandidate(player,ability,"WillPlay"));
            observer.finish(false,null); late.start(); late.join();
        }
        var rows=Files.readAllLines(path); Assert.assertEquals(rows.size(),4);
        var zone=JsonParser.parseString(rows.get(1)).getAsJsonObject();
        Assert.assertEquals(zone.get("from").getAsString(),"Library");
        Assert.assertEquals(zone.get("to").getAsString(),"Hand");
        Assert.assertEquals(zone.getAsJsonObject("card").get("id").getAsInt(),card.getId());
        Assert.assertEquals(JsonParser.parseString(rows.get(2)).getAsJsonObject().get("opinion").getAsString(),"CantAfford");
        Files.deleteIfExists(path);
    }
}
