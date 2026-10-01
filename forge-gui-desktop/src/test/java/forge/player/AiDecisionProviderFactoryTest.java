package forge.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import forge.ai.ComputerUtil;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.decision.MulliganDecisionContext;
import forge.ai.decision.external.OpenAiCompatibleDecisionProvider;
import forge.ai.simulation.SimulationTest;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AiDecisionProviderFactoryTest extends SimulationTest {
    private final Map<FPref, String> savedPreferences = new EnumMap<>(FPref.class);
    private HttpServer server;

    @AfterMethod(alwaysRun = true)
    public void restorePreferences() {
        ForgePreferences preferences = FModel.getPreferences();
        savedPreferences.forEach(preferences::setPref);
        savedPreferences.clear();
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    public void disabledConfigurationCreatesNoProvider() {
        Assert.assertTrue(AiDecisionProviderFactory.create(
                false, "not a URI", "", "", "0").isEmpty());
    }

    @Test
    public void validOllamaConfigurationCreatesProviderWithoutApiKey() {
        Object provider = AiDecisionProviderFactory.create(
                true, "http://localhost:11434/v1/chat/completions", "test-model", "", "20")
                .orElseThrow();
        Assert.assertTrue(provider instanceof OpenAiCompatibleDecisionProvider);
    }

    @Test
    public void missingModelDisablesProvider() {
        Assert.assertTrue(AiDecisionProviderFactory.create(
                true, "http://localhost:11434/v1/chat/completions", " ", "", "20").isEmpty());
    }

    @Test
    public void malformedEndpointDisablesProvider() {
        Assert.assertTrue(AiDecisionProviderFactory.create(
                true, "://bad", "test-model", "", "20").isEmpty());
    }

    @Test
    public void nonPositiveTimeoutDisablesProvider() {
        Assert.assertTrue(AiDecisionProviderFactory.create(
                true, "http://localhost:11434/v1/chat/completions", "test-model", "", "0").isEmpty());
    }

    @Test
    public void configuredProviderReachesNormalAiAndOnlyRunsForMulligan() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        startServer(calls, 200);
        configureRuntimeProvider(true, endpoint(), "test-model", "", "2");
        PlayerControllerAi controller = createNormallyConfiguredController();

        controller.getAi();
        Assert.assertEquals(calls.get(), 0, "ordinary AI access must not call the provider");
        Assert.assertFalse(controller.mulliganKeepHand(null, 0));
        Assert.assertEquals(calls.get(), 1);
    }

    @Test
    public void runtimeProviderFailureUsesHeuristicWithoutRetry() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        startServer(calls, 503);
        configureRuntimeProvider(true, endpoint(), "test-model", "", "2");
        PlayerControllerAi controller = createNormallyConfiguredController();
        boolean heuristic = !ComputerUtil.wantMulligan(controller.getPlayer(), 0);

        Assert.assertEquals(controller.mulliganKeepHand(null, 0), heuristic);
        Assert.assertEquals(calls.get(), 1);
    }

    @Test
    public void defaultConfigurationKeepsNormalAiConstructionValid() {
        configureRuntimeProvider(false, "", "", "", "");
        Assert.assertNotNull(createNormallyConfiguredController());
    }

    private PlayerControllerAi createNormallyConfiguredController() {
        LobbyPlayerAi opponent = new LobbyPlayerAi("opponent", null);
        LobbyPlayerAi configured = (LobbyPlayerAi) GamePlayerUtil.createAiPlayer("configured-ai");
        Deck deck = new Deck();
        List<RegisteredPlayer> registeredPlayers = List.of(
                new RegisteredPlayer(deck).setPlayer(opponent),
                new RegisteredPlayer(deck).setPlayer(configured));
        GameRules rules = new GameRules(GameType.Constructed);
        Game game = new Game(registeredPlayers, rules, new Match(rules, registeredPlayers, "Test"));
        game.setAge(GameStage.Play);
        return (PlayerControllerAi) game.getPlayers().get(1).getController();
    }

    private void configureRuntimeProvider(
            boolean enabled, String endpoint, String model, String apiKey, String timeout) {
        ForgePreferences preferences = FModel.getPreferences();
        setPreference(preferences, FPref.AI_EXTERNAL_MULLIGAN_ENABLED, String.valueOf(enabled));
        setPreference(preferences, FPref.AI_EXTERNAL_MULLIGAN_ENDPOINT, endpoint);
        setPreference(preferences, FPref.AI_EXTERNAL_MULLIGAN_MODEL, model);
        setPreference(preferences, FPref.AI_EXTERNAL_MULLIGAN_API_KEY, apiKey);
        setPreference(preferences, FPref.AI_EXTERNAL_MULLIGAN_TIMEOUT_SECONDS, timeout);
    }

    private void setPreference(ForgePreferences preferences, FPref preference, String value) {
        savedPreferences.putIfAbsent(preference, preferences.getPref(preference));
        preferences.setPref(preference, value);
    }

    private void startServer(AtomicInteger calls, int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response = status == 200 ? mulliganResponse(body) : "unavailable";
            writeResponse(exchange, status, response);
        });
        server.start();
    }

    private String endpoint() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort()
                + "/v1/chat/completions";
    }

    private static String mulliganResponse(String requestBody) {
        JsonObject request = JsonParser.parseString(requestBody).getAsJsonObject();
        JsonArray messages = request.getAsJsonArray("messages");
        JsonObject payload = JsonParser.parseString(
                messages.get(1).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
        JsonObject decision = new JsonObject();
        decision.addProperty("decisionId", payload.get("decisionId").getAsString());
        decision.addProperty("stateFingerprint", payload.get("stateFingerprint").getAsString());
        decision.addProperty("optionId", MulliganDecisionContext.MULLIGAN_OPTION_ID);
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

    private static void writeResponse(HttpExchange exchange, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
