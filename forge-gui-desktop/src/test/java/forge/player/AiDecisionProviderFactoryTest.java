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

    @Test
    public void stackOrderRandomizationDefaultsOffAndCliReachesController() throws Exception {
        ForgePreferences preferences = FModel.getPreferences();
        Assert.assertEquals(preferences.getPrefDefault(FPref.AI_EXTERNAL_STACK_RESPONSE_RANDOMIZE_ACTION_ORDER),
                "false");
        setPreference(preferences, FPref.AI_EXTERNAL_STACK_RESPONSE_RANDOMIZE_ACTION_ORDER, "false");
        var flag = forge.ai.AiController.class.getDeclaredField("externalStackResponseRandomizeActionOrder");
        flag.setAccessible(true);
        Assert.assertFalse(flag.getBoolean(createNormallyConfiguredController().getAi()));
        var configure = forge.view.SimulateMatch.class.getDeclaredMethod("configureExternalAi", Map.class);
        configure.setAccessible(true);
        configure.invoke(null, Map.of("external-ai-stack-response-randomize-action-order", List.of()));
        Assert.assertTrue(preferences.getPrefBoolean(FPref.AI_EXTERNAL_STACK_RESPONSE_RANDOMIZE_ACTION_ORDER));
        Assert.assertTrue(flag.getBoolean(createNormallyConfiguredController().getAi()));
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

    @Test public void combatActionDefaultsCliCapAndStage12Independence() throws Exception {
        var preferences=FModel.getPreferences();
        Assert.assertEquals(preferences.getPrefDefault(FPref.AI_EXTERNAL_COMBAT_ACTION_ENABLED),"false");
        Assert.assertEquals(preferences.getPrefDefault(FPref.AI_EXTERNAL_COMBAT_ACTION_MAX_ACTIONS),"4");
        configureRuntimeProvider(false,"http://localhost:11434/v1/chat/completions","test-model","","20");
        setPreference(preferences,FPref.AI_EXTERNAL_COMBAT_ACTION_ENABLED,"false");
        var configure=forge.view.SimulateMatch.class.getDeclaredMethod("configureExternalAi",Map.class);configure.setAccessible(true);
        configure.invoke(null,Map.of("external-ai-combat-integrated-enabled",List.of()));
        var brains=createNormallyConfiguredController().getAi();
        Assert.assertNull(readField(brains,"externalCombatActions"));
        configure.invoke(null,Map.of("external-ai-combat-action-enabled",List.of(),"external-ai-combat-action-max-actions",List.of("100")));
        brains=createNormallyConfiguredController().getAi();Assert.assertNotNull(readField(brains,"externalCombatActions"));
        Assert.assertEquals(readField(brains,"externalCombatActionMaxActions"),8);
        configure.invoke(null,Map.of("external-ai-combat-action-disabled",List.of()));
        Assert.assertNull(readField(createNormallyConfiguredController().getAi(),"externalCombatActions"));
    }

    @Test
    public void combatDefaultsAndCliReachNormallyConstructedController() throws Exception {
        ForgePreferences preferences = FModel.getPreferences();
        Assert.assertEquals(preferences.getPrefDefault(FPref.AI_EXTERNAL_COMBAT_ATTACKERS_ENABLED), "false");
        Assert.assertEquals(preferences.getPrefDefault(FPref.AI_EXTERNAL_COMBAT_ATTACKERS_MAX_OPTIONS), "4");
        configureRuntimeProvider(false, "http://localhost:11434/v1/chat/completions", "test-model", "", "20");
        setPreference(preferences, FPref.AI_EXTERNAL_MAIN_PHASE_ENABLED, "false");
        setPreference(preferences, FPref.AI_EXTERNAL_STACK_RESPONSE_ENABLED, "false");
        setPreference(preferences, FPref.AI_EXTERNAL_COMBAT_ATTACKERS_ENABLED, "false");
        setPreference(preferences, FPref.AI_EXTERNAL_COMBAT_ATTACKERS_MAX_OPTIONS, "4");
        Assert.assertTrue(AiDecisionProviderFactory.create(preferences).isEmpty());
        var configure = forge.view.SimulateMatch.class.getDeclaredMethod("configureExternalAi", Map.class);
        configure.setAccessible(true);
        configure.invoke(null, Map.of("external-ai-combat-attackers-enabled", List.of(),
                "external-ai-combat-attackers-max-options", List.of("3")));
        Assert.assertTrue(AiDecisionProviderFactory.create(preferences).isPresent());
        var brains = createNormallyConfiguredController().getAi();
        var flag = forge.ai.AiController.class.getDeclaredField("externalCombatAttackersEnabled"); flag.setAccessible(true);
        var cap = forge.ai.AiController.class.getDeclaredField("externalCombatAttackersMaxOptions"); cap.setAccessible(true);
        Assert.assertTrue(flag.getBoolean(brains)); Assert.assertEquals(cap.getInt(brains), 3);
    }

    @Test
    public void blockerDefaultsAndCliReachNormallyConstructedController() throws Exception {
        ForgePreferences preferences = FModel.getPreferences();
        Assert.assertEquals(preferences.getPrefDefault(FPref.AI_EXTERNAL_COMBAT_BLOCKERS_ENABLED), "false");
        Assert.assertEquals(preferences.getPrefDefault(FPref.AI_EXTERNAL_COMBAT_BLOCKERS_MAX_OPTIONS), "4");
        configureRuntimeProvider(false, "http://localhost:11434/v1/chat/completions", "test-model", "", "20");
        setPreference(preferences, FPref.AI_EXTERNAL_MAIN_PHASE_ENABLED, "false");
        setPreference(preferences, FPref.AI_EXTERNAL_STACK_RESPONSE_ENABLED, "false");
        setPreference(preferences, FPref.AI_EXTERNAL_COMBAT_ATTACKERS_ENABLED, "false");
        setPreference(preferences, FPref.AI_EXTERNAL_COMBAT_BLOCKERS_ENABLED, "false");
        setPreference(preferences, FPref.AI_EXTERNAL_COMBAT_BLOCKERS_MAX_OPTIONS, "4");
        Assert.assertTrue(AiDecisionProviderFactory.create(preferences).isEmpty());
        var configure = forge.view.SimulateMatch.class.getDeclaredMethod("configureExternalAi", Map.class);
        configure.setAccessible(true);
        configure.invoke(null, Map.of("external-ai-combat-blockers-enabled", List.of(),
                "external-ai-combat-blockers-max-options", List.of("3")));
        Assert.assertTrue(AiDecisionProviderFactory.create(preferences).isPresent());
        var ai = createNormallyConfiguredController().getAi();
        var enabled = forge.ai.AiController.class.getDeclaredField("externalCombatBlockersEnabled"); enabled.setAccessible(true);
        var cap = forge.ai.AiController.class.getDeclaredField("externalCombatBlockersMaxOptions"); cap.setAccessible(true);
        Assert.assertTrue(enabled.getBoolean(ai)); Assert.assertEquals(cap.getInt(ai), 3);
    }

    @Test
    public void damageOrderConfigCreatesProviderAndFlowsThroughCliAndLobby() throws Exception {
        ForgePreferences preferences = FModel.getPreferences();
        for (FPref flag : List.of(FPref.AI_EXTERNAL_MULLIGAN_ENABLED, FPref.AI_EXTERNAL_MAIN_PHASE_ENABLED,
                FPref.AI_EXTERNAL_STACK_RESPONSE_ENABLED, FPref.AI_EXTERNAL_COMBAT_ATTACKERS_ENABLED,
                FPref.AI_EXTERNAL_COMBAT_BLOCKERS_ENABLED, FPref.AI_EXTERNAL_COMBAT_DAMAGE_ORDER_ENABLED))
            setPreference(preferences, flag, "false");
        setPreference(preferences, FPref.AI_EXTERNAL_MULLIGAN_ENDPOINT, "http://localhost:11434/v1/chat/completions");
        setPreference(preferences, FPref.AI_EXTERNAL_MULLIGAN_MODEL, "qwen3:8b");
        setPreference(preferences, FPref.AI_EXTERNAL_MULLIGAN_TIMEOUT_SECONDS, "20");
        setPreference(preferences, FPref.AI_EXTERNAL_COMBAT_DAMAGE_ORDER_MAX_OPTIONS, "6");
        Assert.assertTrue(AiDecisionProviderFactory.create(preferences).isEmpty());
        var configure = forge.view.SimulateMatch.class.getDeclaredMethod("configureExternalAi", Map.class);
        configure.setAccessible(true);
        configure.invoke(null, Map.of("external-ai-combat-damage-order-enabled", List.of(),
                "external-ai-combat-damage-order-max-options", List.of("100")));
        Assert.assertTrue(AiDecisionProviderFactory.create(preferences).isPresent());
        var lobby = GamePlayerUtil.createAiPlayer("damage-order-test");
        var rules = new GameRules(GameType.Constructed);
        var registered = List.of(new RegisteredPlayer(new Deck()).setPlayer(lobby),
                new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("opponent", null)));
        var game = new Game(registered, rules, new Match(rules, registered, "test"));
        var controller = (PlayerControllerAi) game.getPlayers().get(0).getController();
        var enabled = PlayerControllerAi.class.getDeclaredField("externalCombatDamageOrderEnabled"); enabled.setAccessible(true);
        var cap = PlayerControllerAi.class.getDeclaredField("externalCombatDamageOrderMaxOptions"); cap.setAccessible(true);
        Assert.assertTrue(enabled.getBoolean(controller)); Assert.assertEquals(cap.getInt(controller), 12);
    }

    @Test
    public void integratedCombatEnablesOnlyCombatAndPreservesExplicitCaps() throws Exception {
        ForgePreferences preferences = FModel.getPreferences();
        configureRuntimeProvider(false, "http://localhost:11434/v1/chat/completions", "test-model", "", "20");
        for (FPref flag : List.of(FPref.AI_EXTERNAL_MAIN_PHASE_ENABLED, FPref.AI_EXTERNAL_STACK_RESPONSE_ENABLED,
                FPref.AI_EXTERNAL_COMBAT_ATTACKERS_ENABLED, FPref.AI_EXTERNAL_COMBAT_BLOCKERS_ENABLED,
                FPref.AI_EXTERNAL_COMBAT_DAMAGE_ORDER_ENABLED)) setPreference(preferences, flag, "false");
        setPreference(preferences, FPref.AI_EXTERNAL_COMBAT_ATTACKERS_MAX_OPTIONS, "4");
        setPreference(preferences, FPref.AI_EXTERNAL_COMBAT_BLOCKERS_MAX_OPTIONS, "4");
        setPreference(preferences, FPref.AI_EXTERNAL_COMBAT_DAMAGE_ORDER_MAX_OPTIONS, "6");
        Assert.assertEquals(preferences.getPrefDefault(FPref.AI_EXTERNAL_COMBAT_INTEGRATED_ENABLED), "false");
        Assert.assertTrue(AiDecisionProviderFactory.create(preferences).isEmpty());
        var configure = forge.view.SimulateMatch.class.getDeclaredMethod("configureExternalAi", Map.class);
        configure.setAccessible(true);
        configure.invoke(null, Map.of("external-ai-combat-integrated-enabled", List.of(),
                "external-ai-combat-attackers-max-options", List.of("3"),
                "external-ai-combat-blockers-max-options", List.of("5"),
                "external-ai-combat-damage-order-max-options", List.of("7")));
        assertIntegratedController();
        // The preference also works without a simulation CLI invocation.
        preferences.setPref(FPref.AI_EXTERNAL_COMBAT_INTEGRATED_ENABLED, false);
        Assert.assertTrue(AiDecisionProviderFactory.create(preferences).isEmpty());
        preferences.setPref(FPref.AI_EXTERNAL_COMBAT_INTEGRATED_ENABLED, true);
        assertIntegratedController();
    }

    private void assertIntegratedController() throws Exception {
        Assert.assertTrue(AiDecisionProviderFactory.create(FModel.getPreferences()).isPresent());
        var lobby = GamePlayerUtil.createAiPlayer("integrated-combat-test");
        var rules = new GameRules(GameType.Constructed);
        var registered = List.of(new RegisteredPlayer(new Deck()).setPlayer(lobby),
                new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("opponent", null)));
        var game = new Game(registered, rules, new Match(rules, registered, "test"));
        var controller = (PlayerControllerAi) game.getPlayers().get(0).getController();
        var brains = controller.getAi();
        for (String field : List.of("externalCombatAttackersEnabled", "externalCombatBlockersEnabled"))
            Assert.assertEquals(readField(brains, field), true);
        Assert.assertEquals(readField(controller, "externalCombatDamageOrderEnabled"), true);
        Assert.assertEquals(readField(brains, "externalCombatAttackersMaxOptions"), 3);
        Assert.assertEquals(readField(brains, "externalCombatBlockersMaxOptions"), 5);
        Assert.assertEquals(readField(controller, "externalCombatDamageOrderMaxOptions"), 7);
        Assert.assertEquals(readField(controller, "externalMulliganEnabled"), false);
        Assert.assertEquals(readField(brains, "externalMainPhaseEnabled"), false);
        Assert.assertEquals(readField(brains, "externalStackResponseEnabled"), false);
    }

    @Test
    public void simulationDisablesOverrideSavedAndEnabledNonCombatSurfaces() throws Exception {
        ForgePreferences preferences = FModel.getPreferences();
        for (FPref flag : List.of(FPref.AI_EXTERNAL_MULLIGAN_ENABLED, FPref.AI_EXTERNAL_MAIN_PHASE_ENABLED,
                FPref.AI_EXTERNAL_STACK_RESPONSE_ENABLED)) setPreference(preferences, flag, "true");
        var configure = forge.view.SimulateMatch.class.getDeclaredMethod("configureExternalAi", Map.class);
        configure.setAccessible(true);
        configure.invoke(null, Map.of("external-ai-mulligan-enabled", List.of(),
                "external-ai-mulligan-disabled", List.of(), "external-ai-main-phase-enabled", List.of(),
                "external-ai-main-phase-disabled", List.of(), "external-ai-stack-response-enabled", List.of(),
                "external-ai-stack-response-disabled", List.of()));
        for (FPref flag : List.of(FPref.AI_EXTERNAL_MULLIGAN_ENABLED, FPref.AI_EXTERNAL_MAIN_PHASE_ENABLED,
                FPref.AI_EXTERNAL_STACK_RESPONSE_ENABLED)) Assert.assertFalse(preferences.getPrefBoolean(flag));
    }

    @Test
    public void excludedSeatKeepsAllDecisionsInForgeDespiteGlobalEnableFlags() throws Exception {
        ForgePreferences preferences = FModel.getPreferences();
        configureRuntimeProvider(true, "http://localhost:11434/v1/chat/completions", "test-model", "", "20");
        for (FPref flag : List.of(FPref.AI_EXTERNAL_MAIN_PHASE_ENABLED, FPref.AI_EXTERNAL_STACK_RESPONSE_ENABLED,
                FPref.AI_EXTERNAL_COMBAT_INTEGRATED_ENABLED)) setPreference(preferences, flag, "true");
        var rules = new GameRules(GameType.Constructed);
        var registered = List.of(new RegisteredPlayer(new Deck()).setPlayer(
                        GamePlayerUtil.createAiPlayer("Forge seat", 0, 0, null, "Default", false)),
                new RegisteredPlayer(new Deck()).setPlayer(
                        GamePlayerUtil.createAiPlayer("external seat", 1, 0, null, "Default", true)));
        var game = new Game(registered, rules, new Match(rules, registered, "seat-routing"));
        for (int seat = 0; seat < 2; seat++) {
            boolean expected = seat == 1;
            var controller = (PlayerControllerAi) game.getPlayers().get(seat).getController();
            var brains = controller.getAi();
            Assert.assertEquals(readField(controller, "decisionProvider") != null, expected);
            for (String field : List.of("externalMulliganEnabled", "externalCombatDamageOrderEnabled"))
                Assert.assertEquals(readField(controller, field), expected);
            for (String field : List.of("externalMainPhaseEnabled", "externalStackResponseEnabled",
                    "externalCombatAttackersEnabled", "externalCombatBlockersEnabled"))
                Assert.assertEquals(readField(brains, field), expected);
        }
    }

    private static Object readField(Object instance, String name) throws Exception {
        var field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private void configureRuntimeProvider(
            boolean enabled, String endpoint, String model, String apiKey, String timeout) {
        ForgePreferences preferences = FModel.getPreferences();
        setPreference(preferences, FPref.AI_EXTERNAL_COMBAT_INTEGRATED_ENABLED, "false");
        setPreference(preferences, FPref.AI_EXTERNAL_COMBAT_ACTION_ENABLED, "false");
        setPreference(preferences, FPref.AI_EXTERNAL_MULLIGAN_ENABLED, String.valueOf(enabled));
        setPreference(preferences, FPref.AI_EXTERNAL_MULLIGAN_ENDPOINT, endpoint);
        setPreference(preferences, FPref.AI_EXTERNAL_MULLIGAN_MODEL, model);
        setPreference(preferences, FPref.AI_EXTERNAL_MULLIGAN_API_KEY, apiKey);
        setPreference(preferences, FPref.AI_EXTERNAL_MULLIGAN_TIMEOUT_SECONDS, timeout);
        setPreference(preferences, FPref.AI_EXTERNAL_RESPONSE_FORMAT, "AUTO");
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
