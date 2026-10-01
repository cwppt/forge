package forge.ai.decision.external;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import forge.ai.decision.AiDecision;
import forge.ai.decision.AiOptionView;
import forge.ai.decision.AiPlayerView;
import forge.ai.decision.MulliganAiState;
import forge.ai.decision.MulliganCardView;
import forge.ai.decision.MulliganDecisionContext;
import forge.ai.decision.LegalActionView;
import forge.ai.decision.MainPhaseAiState;
import forge.ai.decision.MainPhaseDecisionContext;
import forge.ai.decision.MainPhasePlayerState;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class OpenAiCompatibleDecisionProviderTest {
    private HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeMethod
    public void resetCapturedRequest() {
        requestCount.set(0);
        requestBody.set(null);
        authorization.set(null);
    }

    @AfterMethod(alwaysRun = true)
    public void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    public void acceptsKeepAndSendsWhitelistedRequestWithApiKey() throws IOException {
        MulliganDecisionContext context = context();
        startServer(200, completion(context, MulliganDecisionContext.KEEP_OPTION_ID), 0);

        Optional<AiDecision> result = provider(true, "secret-test-key", Duration.ofSeconds(2)).choose(context);

        Assert.assertTrue(result.isPresent());
        Assert.assertEquals(result.get().optionId(), MulliganDecisionContext.KEEP_OPTION_ID);
        Assert.assertEquals(authorization.get(), "Bearer secret-test-key");
        Assert.assertEquals(requestCount.get(), 1);
        JsonObject request = JsonParser.parseString(requestBody.get()).getAsJsonObject();
        Assert.assertEquals(request.get("model").getAsString(), "test-model");
        JsonArray messages = request.getAsJsonArray("messages");
        JsonObject payload = JsonParser.parseString(
                messages.get(1).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
        Assert.assertEquals(payload.get("decisionId").getAsString(), context.decisionId());
        Assert.assertEquals(payload.get("stateFingerprint").getAsString(), context.state().fingerprint());
        Assert.assertEquals(payload.getAsJsonObject("state").getAsJsonArray("openingHand")
                .get(0).getAsJsonObject().get("name").getAsString(), "Mountain");
        Assert.assertFalse(requestBody.get().contains("forge.game"));
    }

    @Test
    public void acceptsMulliganAndOmitsApiKey() throws IOException {
        MulliganDecisionContext context = context();
        startServer(200, completion(context, MulliganDecisionContext.MULLIGAN_OPTION_ID), 0);

        Optional<AiDecision> result = provider(true, null, Duration.ofSeconds(2)).choose(context);

        Assert.assertTrue(result.isPresent());
        Assert.assertEquals(result.get().optionId(), MulliganDecisionContext.MULLIGAN_OPTION_ID);
        Assert.assertNull(authorization.get());
        Assert.assertEquals(requestCount.get(), 1);
    }

    @Test
    public void timeoutReturnsEmptyWithoutRetry() throws IOException {
        MulliganDecisionContext context = context();
        startServer(200, completion(context, MulliganDecisionContext.KEEP_OPTION_ID), 500);

        Assert.assertTrue(provider(true, null, Duration.ofMillis(50)).choose(context).isEmpty());
        Assert.assertEquals(requestCount.get(), 1);
    }

    @Test
    public void malformedJsonReturnsEmpty() throws IOException {
        startServer(200, "not-json", 0);
        Assert.assertTrue(provider(true, null, Duration.ofSeconds(2)).choose(context()).isEmpty());
        Assert.assertEquals(requestCount.get(), 1);
    }

    @Test
    public void emptyResponseReturnsEmpty() throws IOException {
        startServer(200, "", 0);
        Assert.assertTrue(provider(true, null, Duration.ofSeconds(2)).choose(context()).isEmpty());
        Assert.assertEquals(requestCount.get(), 1);
    }

    @Test
    public void httpErrorReturnsEmptyWithoutRetry() throws IOException {
        startServer(503, "unavailable", 0);
        Assert.assertTrue(provider(true, null, Duration.ofSeconds(2)).choose(context()).isEmpty());
        Assert.assertEquals(requestCount.get(), 1);
    }

    @Test
    public void mismatchedDecisionIdReturnsEmpty() throws IOException {
        MulliganDecisionContext context = context();
        startServer(200, completion("wrong-id", context.state().fingerprint(),
                MulliganDecisionContext.KEEP_OPTION_ID), 0);
        Assert.assertTrue(provider(true, null, Duration.ofSeconds(2)).choose(context).isEmpty());
    }

    @Test
    public void invalidOptionReturnsEmpty() throws IOException {
        MulliganDecisionContext context = context();
        startServer(200, completion(context.decisionId(), context.state().fingerprint(), "DRAW_CARD"), 0);
        Assert.assertTrue(provider(true, null, Duration.ofSeconds(2)).choose(context).isEmpty());
    }

    @Test
    public void mismatchedFingerprintReturnsEmpty() throws IOException {
        MulliganDecisionContext context = context();
        startServer(200, completion(context.decisionId(), "stale-fingerprint",
                MulliganDecisionContext.KEEP_OPTION_ID), 0);
        Assert.assertTrue(provider(true, null, Duration.ofSeconds(2)).choose(context).isEmpty());
    }

    @Test
    public void connectionFailureReturnsEmpty() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        URI stoppedEndpoint = endpoint();
        server.stop(0);
        server = null;

        OpenAiCompatibleProviderConfig config = new OpenAiCompatibleProviderConfig(
                true, stoppedEndpoint, "test-model", null, Duration.ofMillis(250));
        Assert.assertTrue(new OpenAiCompatibleDecisionProvider(config).choose(context()).isEmpty());
    }

    @Test
    public void disabledProviderMakesNoRequest() throws IOException {
        startServer(200, completion(context(), MulliganDecisionContext.KEEP_OPTION_ID), 0);
        Assert.assertTrue(provider(false, null, Duration.ofSeconds(2)).choose(context()).isEmpty());
        Assert.assertEquals(requestCount.get(), 0);
    }

    @Test
    public void configurationDoesNotExposeApiKeyInToString() {
        OpenAiCompatibleProviderConfig config = new OpenAiCompatibleProviderConfig(
                true, URI.create("http://localhost/v1/chat/completions"),
                "test-model", "top-secret", Duration.ofSeconds(2));

        Assert.assertFalse(config.toString().contains("top-secret"));
        Assert.assertTrue(config.toString().contains("<redacted>"));
    }

    @Test
    public void acceptsMainPhaseActionAndSendsOnlyPreparedActions() throws IOException {
        MainPhaseDecisionContext context = mainPhaseContext();
        startServer(200, completion(context.decisionId(), context.state().fingerprint(), "ACTION_1"), 0);

        Optional<AiDecision> result = provider(true, null, Duration.ofSeconds(2)).choose(context);

        Assert.assertTrue(result.isPresent());
        Assert.assertEquals(result.get().optionId(), "ACTION_1");
        JsonObject request = JsonParser.parseString(requestBody.get()).getAsJsonObject();
        JsonObject payload = JsonParser.parseString(request.getAsJsonArray("messages").get(1)
                .getAsJsonObject().get("content").getAsString()).getAsJsonObject();
        Assert.assertEquals(payload.get("decisionType").getAsString(), "MAIN_PHASE_ACTION");
        Assert.assertEquals(payload.getAsJsonArray("legalActions").size(), 2);
        Assert.assertFalse(requestBody.get().contains("forge.game"));
        Assert.assertFalse(requestBody.get().contains("Opponent secret"));
    }

    private OpenAiCompatibleDecisionProvider provider(boolean enabled, String apiKey, Duration timeout) {
        OpenAiCompatibleProviderConfig config = new OpenAiCompatibleProviderConfig(
                enabled, endpoint(), "test-model", apiKey, timeout);
        return new OpenAiCompatibleDecisionProvider(config);
    }

    private void startServer(int status, String response, long delayMillis) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            writeResponse(exchange, status, response);
        });
        server.start();
    }

    private static void writeResponse(HttpExchange exchange, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private URI endpoint() {
        return URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort()
                + "/v1/chat/completions");
    }

    private static MulliganDecisionContext context() {
        MulliganAiState state = new MulliganAiState(
                "fingerprint-123",
                null,
                new AiPlayerView(1, "Opponent"),
                new AiPlayerView(2, "AI"),
                40,
                List.of(new MulliganCardView("Mountain", "", "Basic Land — Mountain", "({T}: Add {R}.)")),
                7,
                0,
                false);
        return new MulliganDecisionContext(
                "decision-123",
                state,
                List.of(
                        new AiOptionView(MulliganDecisionContext.KEEP_OPTION_ID, "Keep hand"),
                        new AiOptionView(MulliganDecisionContext.MULLIGAN_OPTION_ID, "Take mulligan")));
    }

    private static MainPhaseDecisionContext mainPhaseContext() {
        AiPlayerView ai = new AiPlayerView(2, "AI");
        AiPlayerView opponent = new AiPlayerView(1, "Opponent");
        MainPhaseAiState state = new MainPhaseAiState("main-fingerprint", 3, "MAIN1", ai, ai,
                List.of(
                        new MainPhasePlayerState(opponent, 20, false, null, List.of(), List.of(),
                                List.of(), List.of(), List.of()),
                        new MainPhasePlayerState(ai, 20, true, "{R}{R}", List.of(), List.of(),
                                List.of(), List.of(), List.of())), 0);
        return new MainPhaseDecisionContext("main-decision", state, List.of(
                new LegalActionView("ACTION_0", "SPELL", "Shock", "Instant", "DealDamage",
                        "Shock deals 2 damage", "{R}", List.of("Player: Opponent"), List.of(), null, 0),
                new LegalActionView("ACTION_1", "SPELL", "Lightning Bolt", "Instant", "DealDamage",
                        "Lightning Bolt deals 3 damage", "{R}", List.of("Player: Opponent"), List.of(), null, 1)));
    }

    private static String completion(MulliganDecisionContext context, String optionId) {
        return completion(context.decisionId(), context.state().fingerprint(), optionId);
    }

    private static String completion(String decisionId, String fingerprint, String optionId) {
        JsonObject decision = new JsonObject();
        decision.addProperty("decisionId", decisionId);
        decision.addProperty("stateFingerprint", fingerprint);
        decision.addProperty("optionId", optionId);
        JsonObject message = new JsonObject();
        message.addProperty("content", decision.toString());
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject response = new JsonObject();
        response.add("choices", choices);
        return response.toString();
    }
}
