package forge.ai.decision;

import com.google.gson.*;
import forge.ai.AITest;
import forge.game.Game;
import forge.game.event.GameEventTurnPhase;
import forge.game.phase.PhaseType;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import java.util.*;

public class FullGameExternalAiAuditTest extends AITest {
    @BeforeMethod public void resetAudit() { AiDecisionMetrics.reset(); }
    private Game game() {
        Game game = initAndCreateGame(); AiDecisionMetrics.registerGame(game.getId(), 1, 1401L);
        FullGameExternalAiAudit.observe(game); return game;
    }
    private JsonObject event(Game game, String id, boolean fallback, long latency) {
        JsonObject e = new JsonObject(); e.addProperty("gameId", game.getId());
        e.addProperty("decisionId", id); e.addProperty("stateFingerprint", "fingerprint");
        e.addProperty("turnNumber", 3); e.addProperty("phase", "MAIN1"); e.addProperty("playerSeat", 1);
        e.addProperty("providerAttempted", true); e.addProperty("fallback", fallback);
        e.addProperty("failureReason", fallback ? "TIMEOUT" : "");
        e.addProperty("providerLatencyMs", latency); e.addProperty("heuristicOptionId", "OPT_a");
        e.addProperty("selectedOptionId", "OPT_a"); return e;
    }
    @Test public void identitiesAreDeterministicAndSeparateGamesAndTurns() {
        String id = FullGameExternalAiAudit.gameIdentity("run", 1, 1, 1401L);
        Assert.assertEquals(id, FullGameExternalAiAudit.gameIdentity("run", 1, 1, 1401L));
        Assert.assertNotEquals(id, FullGameExternalAiAudit.gameIdentity("run", 2, 1, 1401L));
        Assert.assertNotEquals(id, FullGameExternalAiAudit.gameIdentity("other", 1, 1, 1401L));
        Assert.assertEquals(FullGameExternalAiAudit.turnIdentity(id, 3), FullGameExternalAiAudit.turnIdentity(id, 3));
        Assert.assertNotEquals(FullGameExternalAiAudit.turnIdentity(id, 3), FullGameExternalAiAudit.turnIdentity(id, 4));
    }
    @Test public void allSevenSurfacesCorrelateWithoutProviderCallsOrHistory() {
        Game game = game();
        for (AiDecisionType type : AiDecisionType.values())
            FullGameExternalAiAudit.record(type, event(game, type.name(), false, 10), null);
        var rows = FullGameExternalAiAudit.completeGame(game.getId(), "p1", false, 100, 3);
        var refs = rows.stream().filter(r -> r.get("recordType").getAsString().equals("FULL_GAME_DECISION")).toList();
        Assert.assertEquals(refs.size(), 7);
        Assert.assertEquals(refs.stream().map(r -> r.get("externalAiGameId").getAsString()).distinct().count(), 1L);
        Assert.assertEquals(refs.stream().map(r -> r.get("turnSequenceId").getAsString()).distinct().count(), 1L);
        JsonObject summary = rows.get(rows.size()-1);
        Assert.assertEquals(summary.getAsJsonArray("decisionRefs").size(), 7);
        Assert.assertEquals(summary.getAsJsonObject("bySurface").size(), 7);
        Assert.assertFalse(summary.toString().contains("modelVisibleDecision"));
        Assert.assertTrue(AiDecisionMetrics.combatActionEvents().isEmpty(), "Auditing must never route a strategic decision");
    }
    @Test public void aggregateCountsLatenciesFallbacksAndDuplicateKeys() {
        Game game = game();
        FullGameExternalAiAudit.record(AiDecisionType.COMBAT_ATTACKERS, event(game, "a", false, 100), null);
        FullGameExternalAiAudit.record(AiDecisionType.COMBAT_ATTACKERS, event(game, "b", true, 300), null);
        FullGameExternalAiAudit.record(AiDecisionType.COMBAT_BLOCKERS, event(game, "c", false, 200), null);
        JsonObject changed = event(game, "d", false, 400); changed.addProperty("stateFingerprint", "different");
        FullGameExternalAiAudit.record(AiDecisionType.COMBAT_ATTACKERS, changed, null);
        var rows = FullGameExternalAiAudit.completeGame(game.getId(), "p1", false, 1000, 3);
        JsonObject m = rows.get(rows.size()-1).getAsJsonObject("metrics");
        Assert.assertEquals(m.get("externalFullGameProviderCalls").getAsInt(), 4);
        Assert.assertEquals(m.get("externalFullGameProviderLatencyMs").getAsLong(), 1000L);
        Assert.assertEquals(m.get("avgProviderLatencyMs").getAsDouble(), 250.0);
        Assert.assertEquals(m.get("p50ProviderLatencyMs").getAsLong(), 200L);
        Assert.assertEquals(m.get("p95ProviderLatencyMs").getAsLong(), 400L);
        Assert.assertEquals(m.get("accepted").getAsInt(), 3);
        Assert.assertEquals(m.get("fallback").getAsInt(), 1);
        Assert.assertEquals(m.get("timeout").getAsInt(), 1);
        Assert.assertEquals(m.get("duplicateRequest").getAsInt(), 1);
        Assert.assertFalse(game.isGameOver(), "A diagnostic fallback must not abort the game");
    }
    @Test public void observingControlProducesZeroDecisionsAndSanitizedResources() {
        Game game = game();
        addCardToZone("Doom Blade", game.getPlayers().get(0), forge.game.zone.ZoneType.Hand);
        game.fireEvent(new GameEventTurnPhase(game.getPlayers().get(1), PhaseType.MAIN1, ""));
        var rows = FullGameExternalAiAudit.completeGame(game.getId(), "p1", false, 10, 3);
        Assert.assertEquals(rows.get(rows.size()-1).getAsJsonObject("metrics").get("externalFullGameProviderCalls").getAsInt(), 0);
        Assert.assertFalse(rows.toString().contains("Doom Blade"));
        Assert.assertTrue(rows.toString().contains("handSize"));
        Assert.assertTrue(rows.toString().contains("beforeMainPhase"));
        Assert.assertTrue(FullGameExternalAiAudit.completeGame(game.getId(), "p1", false, 10, 3).isEmpty());
    }
    @Test public void safeUsageAndDecorationStayAuditOnly() {
        Game game = game(); JsonObject usage = JsonParser.parseString("{\"prompt_tokens\":12,\"completion_tokens\":4,\"total_tokens\":16,\"secret\":\"hidden\"}").getAsJsonObject();
        FullGameExternalAiAudit.recordUsage("a", usage);
        FullGameExternalAiAudit.record(AiDecisionType.COMBAT_ACTION, event(game, "a", false, 10), null);
        JsonObject decorated = FullGameExternalAiAudit.decorate(event(game, "a", false, 10));
        Assert.assertTrue(decorated.has("externalAiGameId")); Assert.assertTrue(decorated.has("turnSequenceId"));
        var rows = FullGameExternalAiAudit.completeGame(game.getId(), "p1", false, 10, 3);
        Assert.assertEquals(rows.get(0).getAsJsonObject("usage").get("total_tokens").getAsInt(), 16);
        Assert.assertFalse(rows.toString().contains("secret"));
    }
    @Test public void unobservedGamesAndResetRemainInert() {
        Game game = initAndCreateGame();
        FullGameExternalAiAudit.record(AiDecisionType.MAIN_PHASE_ACTION, event(game, "a", false, 5), null);
        Assert.assertTrue(FullGameExternalAiAudit.completeGame(game.getId(), "p1", false, 10, 3).isEmpty());
        FullGameExternalAiAudit.observe(game); FullGameExternalAiAudit.reset();
        Assert.assertTrue(FullGameExternalAiAudit.completeGame(game.getId(), "p1", false, 10, 3).isEmpty());
    }
    @Test public void eliminatedPlayerStillHasOriginalSeatAndDeckInSummary() {
        Game game = game(); game.onPlayerLost(game.getPlayers().get(0));
        Assert.assertEquals(game.getPlayers().size(),1);
        var rows = FullGameExternalAiAudit.completeGame(game.getId(),"p1",false,10,3);
        var summary = rows.get(rows.size()-1);
        Assert.assertEquals(summary.getAsJsonArray("decks").size(),2);
        Assert.assertEquals(summary.getAsJsonArray("finalResources").size(),2);
        Assert.assertEquals(summary.getAsJsonArray("finalResources").get(1).getAsJsonObject().get("seat").getAsInt(),1);
    }
}
