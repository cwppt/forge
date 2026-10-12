package forge.ai.decision.external;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import forge.ai.decision.AiDecision;
import forge.ai.decision.AiDecisionFailureReason;
import forge.ai.decision.AiOptionView;
import forge.ai.decision.AiPlayerView;
import forge.ai.decision.MulliganAiState;
import forge.ai.decision.MulliganCardView;
import forge.ai.decision.MulliganDecisionContext;
import forge.ai.decision.LegalActionView;
import forge.ai.decision.MainPhaseAiState;
import forge.ai.decision.MainPhaseDecisionContext;
import forge.ai.decision.MainPhaseManaView;
import forge.ai.decision.MainPhasePlayerState;
import forge.ai.decision.StackItemView;
import forge.ai.decision.StackResponseDecisionContext;
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
import java.util.Set;
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

    private static forge.ai.decision.CombatActionDecisionContext combatActionContext() {
        var state = new forge.ai.decision.CombatActionDecisionContext.State(3,"COMBAT_DECLARE_BLOCKERS",1,0,1,
                List.of(20,20),List.of(),List.of(),List.of());
        var option = new forge.ai.decision.CombatActionOptionView("OPT_" + "a".repeat(64),"PERM_public","Giant Growth",
                "SPELL","{G}",List.of("PERM_bear"),"Pump NumAtt=+3 NumDef=+3",List.of(),null,List.of());
        return new forge.ai.decision.CombatActionDecisionContext("combat-action","combat-fingerprint",state,List.of(option),true);
    }
    @Test public void combatActionSchemaPassAndTransportControls() throws Exception {
        var context = combatActionContext();
        startServer(200,completion(context.decisionId(),context.stateFingerprint(),"PASS"),0);
        Assert.assertTrue(provider(true,null,Duration.ofSeconds(2)).choose(context).isPresent());
        JsonObject request=JsonParser.parseString(requestBody.get()).getAsJsonObject();
        assertDecisionSchema(request,List.of("PASS",context.options().get(0).optionId()));
        Assert.assertTrue(request.get("max_tokens").getAsInt() <= 512);
        Assert.assertFalse(requestBody.get().contains("heuristic"));
        var ollama = new OpenAiCompatibleDecisionProvider(new OpenAiCompatibleProviderConfig(true,
                URI.create("http://localhost:11434/v1/chat/completions"),"qwen3:8b",null,Duration.ofSeconds(2)));
        Assert.assertEquals(renderedRequest(ollama,context).get("reasoning_effort").getAsString(),"none");
    }
    @Test public void combatActionPreparedOptionAccepted() throws Exception {
        var context=combatActionContext();startServer(200,completion(context.decisionId(),context.stateFingerprint(),context.options().get(0).optionId()),0);
        Assert.assertTrue(provider(true,null,Duration.ofSeconds(2)).choose(context).isPresent());
    }
    @Test public void combatActionMalformedResponseRejected() throws Exception {
        startServer(200,"{broken",0);
        Assert.assertEquals(provider(true,null,Duration.ofSeconds(2)).chooseWithDiagnostics(combatActionContext()).failureReason(),AiDecisionFailureReason.MALFORMED_RESPONSE);
    }
    @Test public void combatActionTimeoutRejected() throws Exception {
        var c=combatActionContext(); startServer(200,completion(c.decisionId(),c.stateFingerprint(),"PASS"),250);
        Assert.assertEquals(provider(true,null,Duration.ofMillis(30)).chooseWithDiagnostics(c).failureReason(),AiDecisionFailureReason.TIMEOUT);
    }

    @Test
    public void optionalCombatCaptureMatchesTransmittedBytesAndIgnoresNoncombat() throws Exception {
        var file = java.nio.file.Files.createTempFile("forge-combat-wire-", ".jsonl");
        String previous = System.getProperty(OpenAiCompatibleDecisionProvider.COMBAT_REQUEST_CAPTURE_PROPERTY);
        try {
            System.setProperty(OpenAiCompatibleDecisionProvider.COMBAT_REQUEST_CAPTURE_PROPERTY, file.toString());
            var state = new forge.ai.decision.CombatBlockersDecisionContext.State(4, "COMBAT_DECLARE_BLOCKERS", "PLAYER_1", List.of(), List.of(), List.of());
            var context = new forge.ai.decision.CombatBlockersDecisionContext("capture", "fingerprint", state,
                    List.of(new forge.ai.decision.CombatBlockOptionView("NO_BLOCK", List.of(), 0, 0)));
            startServer(200, completion("capture", "fingerprint", "NO_BLOCK"), 0);
            Assert.assertTrue(provider(true, "secret-test-key", Duration.ofSeconds(2)).choose(context).isPresent());
            var captured = JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
            byte[] bytes = java.util.Base64.getDecoder().decode(captured.get("RequestUtf8Base64").getAsString());
            Assert.assertEquals(new String(bytes, StandardCharsets.UTF_8), requestBody.get());
            Assert.assertEquals(captured.get("RequestSha256").getAsString(), java.util.HexFormat.of().withUpperCase().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
            Assert.assertFalse(java.nio.file.Files.readString(file).contains("secret-test-key"));
            stopServer();
            long size = java.nio.file.Files.size(file);
            startServer(200, completion(context(), MulliganDecisionContext.KEEP_OPTION_ID), 0);
            Assert.assertTrue(provider(true, null, Duration.ofSeconds(2)).choose(context()).isPresent());
            Assert.assertEquals(java.nio.file.Files.size(file), size);
            stopServer();
            // An unavailable destination must not turn a valid provider response into a fallback.
            System.setProperty(OpenAiCompatibleDecisionProvider.COMBAT_REQUEST_CAPTURE_PROPERTY, file.resolve("missing.jsonl").toString());
            startServer(200, completion("capture", "fingerprint", "NO_BLOCK"), 0);
            Assert.assertTrue(provider(true, null, Duration.ofSeconds(2)).choose(context).isPresent());
        } finally {
            if (previous == null) System.clearProperty(OpenAiCompatibleDecisionProvider.COMBAT_REQUEST_CAPTURE_PROPERTY);
            else System.setProperty(OpenAiCompatibleDecisionProvider.COMBAT_REQUEST_CAPTURE_PROPERTY, previous);
            java.nio.file.Files.deleteIfExists(file);
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
        assertDecisionSchema(request, List.of("KEEP", "MULLIGAN"));
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
        Assert.assertEquals(payload.getAsJsonArray("legalActions").get(0).getAsJsonObject()
                .get("forgeRecommendation").getAsString(), "PREFERRED");
        Assert.assertFalse(requestBody.get().contains("forge.game"));
        Assert.assertFalse(requestBody.get().contains("Opponent secret"));
        assertDecisionSchema(request, List.of("ACTION_0", "ACTION_1"));
    }

    @Test
    public void acceptsStackPassAndProjectsOnlyWhitelistedState() throws IOException {
        StackResponseDecisionContext context = stackContext();
        startServer(200, completion(context.decisionId(), context.stateFingerprint(), "PASS"), 0);

        Optional<AiDecision> result = provider(true, null, Duration.ofSeconds(2)).choose(context);

        Assert.assertEquals(result.orElseThrow().optionId(), "PASS");
        assertStackPayload(context);
    }

    private static StackResponseDecisionContext stackContext() {
        List<LegalActionView> opaqueActions = mainPhaseContext().legalActions().stream().map(action ->
                new LegalActionView("Shock".equals(action.sourceName()) ? "OPT_7F3AC19D89A6B942251CEEAB"
                        : "OPT_C291D84E5A6F192ABBEE089F", action.category(), action.sourceName(), action.sourceType(),
                        action.apiType(), action.description(), action.cost(), action.targets(), action.modes(),
                        action.xValue(), action.heuristicPosition(), null)).toList();
        StackResponseDecisionContext context = new StackResponseDecisionContext("stack-decision",
                "stack-fingerprint", mainPhaseContext().state(),
                List.of(new StackItemView(0, "Runeclaw Bear", "Opponent",
                "SPELL", "Creature spell", List.of())), opaqueActions, true,
                List.of(new forge.ai.decision.StackResponseActionMapping(opaqueActions.get(0).actionId(), "ACTION_1", 1, 0,
                        "Internal mapping", "Audit only")));
        return context;
    }

    @Test
    public void selectsSecondPresentedStackActionUsingOpaqueId() throws IOException {
        StackResponseDecisionContext context = stackContext();
        String secondId = context.legalActions().get(1).actionId();
        startServer(200, completion(context.decisionId(), context.stateFingerprint(), secondId), 0);
        Assert.assertEquals(provider(true, null, Duration.ofSeconds(2)).choose(context).orElseThrow().optionId(), secondId);
        assertStackPayload(context);
    }

    @Test
    public void combatUsesActualOpaqueEnumAndWhitelistWithNeutralPrompt() throws IOException {
        var state = new forge.ai.decision.CombatAttackersDecisionContext.State(4, "COMBAT_DECLARE_ATTACKERS", "PLAYER_1",
                List.of(), List.of(new forge.ai.decision.CombatAttackersDecisionContext.Defender("PLAYER_0", "Opponent", "PLAYER", "PLAYER_0")),
                List.of());
        String opaque = "OPT_A83F9C10829AB82291EE417D";
        var option = new forge.ai.decision.CombatAttackOptionView(opaque,
                List.of(new forge.ai.decision.CombatAttackOptionView.Assignment("PERM_A1", "PLAYER_0")), 3, 1);
        var context = new forge.ai.decision.CombatAttackersDecisionContext("combat-decision", "combat-fingerprint", state,
                List.of(new forge.ai.decision.CombatAttackOptionView("NO_ATTACK", List.of(), 0, 0), option));
        startServer(200, completion(context.decisionId(), context.stateFingerprint(), opaque), 0);
        Assert.assertEquals(provider(true, null, Duration.ofSeconds(2)).choose(context).orElseThrow().optionId(), opaque);
        JsonObject request = JsonParser.parseString(requestBody.get()).getAsJsonObject();
        assertDecisionSchema(request, List.of("NO_ATTACK", opaque));
        JsonObject properties = request.getAsJsonObject("response_format").getAsJsonObject("json_schema")
                .getAsJsonObject("schema").getAsJsonObject("properties");
        Assert.assertEquals(properties.getAsJsonObject("decisionId").getAsJsonArray("enum").get(0).getAsString(), context.decisionId());
        Assert.assertEquals(properties.getAsJsonObject("stateFingerprint").getAsJsonArray("enum").get(0).getAsString(), context.stateFingerprint());
        JsonObject payload = JsonParser.parseString(request.getAsJsonArray("messages").get(1).getAsJsonObject()
                .get("content").getAsString()).getAsJsonObject();
        Assert.assertEquals(payload.get("decisionType").getAsString(), "COMBAT_ATTACKERS");
        Assert.assertEquals(payload.getAsJsonArray("options").get(1).getAsJsonObject().getAsJsonArray("attackers")
                .get(0).getAsJsonObject().get("defenderId").getAsString(), "PLAYER_0");
        for (String internal : List.of("heuristic", "originalCandidateIndex", "internalMapping", "ACTION_", "forge.game", "hand", "library"))
            Assert.assertFalse(payload.toString().contains(internal));
        String prompt = request.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
        Assert.assertTrue(prompt.contains("choosing attackers"));
        Assert.assertFalse(prompt.contains("preferred"));
    }

    @Test
    public void blockersUseOpaqueEnumImmutableIdentityAndNeutralWhitelist() throws IOException {
        var state = new forge.ai.decision.CombatBlockersDecisionContext.State(4, "COMBAT_DECLARE_BLOCKERS", "PLAYER_1", List.of(), List.of(), List.of());
        String opaque = "OPT_A83F9C10829AB82291EE417D";
        var tactics = new forge.ai.decision.CombatBlockOptionView.TacticalConsequences(
                "Current visible combat only; assumes no further interaction.",1,0,3,17,false,false,true,true,false,true);
        var context = new forge.ai.decision.CombatBlockersDecisionContext("block-decision", "block-fingerprint", state,
                List.of(new forge.ai.decision.CombatBlockOptionView("NO_BLOCK", List.of(), 0, 0),
                        new forge.ai.decision.CombatBlockOptionView(opaque, List.of(new forge.ai.decision.CombatBlockOptionView.Assignment("PERM_B", "PERM_A")), 1, 1,tactics)));
        startServer(200, completion(context.decisionId(), context.stateFingerprint(), opaque), 0);
        Assert.assertEquals(provider(true, null, Duration.ofSeconds(2)).choose(context).orElseThrow().optionId(), opaque);
        JsonObject request = JsonParser.parseString(requestBody.get()).getAsJsonObject();
        assertDecisionSchema(request, List.of("NO_BLOCK", opaque));
        JsonObject properties = request.getAsJsonObject("response_format").getAsJsonObject("json_schema").getAsJsonObject("schema").getAsJsonObject("properties");
        Assert.assertEquals(properties.getAsJsonObject("decisionId").getAsJsonArray("enum").get(0).getAsString(), context.decisionId());
        String prompt = request.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
        Assert.assertTrue(prompt.contains("choosing blockers")); Assert.assertFalse(prompt.contains("preferred"));
        Assert.assertTrue(prompt.contains("unless the block creates a meaningful trade or other tactical benefit"));
        JsonObject payload = JsonParser.parseString(request.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
        Assert.assertEquals(payload.get("decisionType").getAsString(), "COMBAT_BLOCKERS");
        Assert.assertEquals(payload.getAsJsonArray("options").get(1).getAsJsonObject().getAsJsonArray("assignments").get(0).getAsJsonObject().get("blockerId").getAsString(), "PERM_B");
        Assert.assertEquals(payload.getAsJsonArray("options").get(1).getAsJsonObject().getAsJsonObject("tacticalConsequences")
                .get("projectedCreatureLossesSelf").getAsInt(),1);
        Assert.assertTrue(payload.getAsJsonArray("options").get(1).getAsJsonObject().getAsJsonObject("tacticalConsequences")
                .get("unnecessaryLosingBlock").getAsBoolean());
        for (String internal : List.of("heuristic", "originalCandidateIndex", "internalMapping", "forge.game", "hand", "library")) Assert.assertFalse(payload.toString().contains(internal));
    }

    @Test
    public void blockersRejectWrongIdentityFingerprintAndOptionWithoutLosingReturnedIdentity() throws IOException {
        var state = new forge.ai.decision.CombatBlockersDecisionContext.State(4, "COMBAT_DECLARE_BLOCKERS", "PLAYER_1", List.of(), List.of(), List.of());
        var context = new forge.ai.decision.CombatBlockersDecisionContext("blocks", "fingerprint", state,
                List.of(new forge.ai.decision.CombatBlockOptionView("NO_BLOCK", List.of(), 0, 0)));
        for (String kind : List.of("ID", "FINGERPRINT", "OPTION")) {
            startServer(200, completion(kind.equals("ID") ? "wrong" : context.decisionId(), kind.equals("FINGERPRINT") ? "wrong" : context.stateFingerprint(), kind.equals("OPTION") ? "unknown" : "NO_BLOCK"), 0);
            var result = provider(true, null, Duration.ofSeconds(2)).chooseWithDiagnostics(context);
            Assert.assertTrue(result.decision().isEmpty()); Assert.assertNotNull(result.rejectedDecision());
            Assert.assertEquals(result.failureReason(), kind.equals("ID") ? AiDecisionFailureReason.DECISION_ID_MISMATCH
                    : kind.equals("FINGERPRINT") ? AiDecisionFailureReason.FINGERPRINT_MISMATCH : AiDecisionFailureReason.INVALID_OPTION);
            stopServer();
        }
    }

    @Test
    public void damageOrdersUseOpaqueEnumImmutableIdentityAndNeutralWhitelist() throws IOException {
        var state = damageOrderState();
        String opaque = "OPT_A83F9C10829AB82291EE417D";
        var context = new forge.ai.decision.CombatDamageOrderDecisionContext("block-decision", "block-fingerprint", state,
                List.of(new forge.ai.decision.CombatDamageOrderOptionView("OPT_A", List.of("PERM_A", "PERM_B"), List.of("Bear", "Giant")),
                        new forge.ai.decision.CombatDamageOrderOptionView(opaque, List.of("PERM_B", "PERM_A"), List.of("Giant", "Bear"))));
        startServer(200, completion(context.decisionId(), context.stateFingerprint(), opaque), 0);
        Assert.assertEquals(provider(true, null, Duration.ofSeconds(2)).choose(context).orElseThrow().optionId(), opaque);
        JsonObject request = JsonParser.parseString(requestBody.get()).getAsJsonObject();
        assertDecisionSchema(request, List.of("OPT_A", opaque));
        JsonObject properties = request.getAsJsonObject("response_format").getAsJsonObject("json_schema").getAsJsonObject("schema").getAsJsonObject("properties");
        Assert.assertEquals(properties.getAsJsonObject("decisionId").getAsJsonArray("enum").get(0).getAsString(), context.decisionId());
        String prompt = request.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
        Assert.assertTrue(prompt.contains("choosing combat damage assignment order")); Assert.assertFalse(prompt.contains("preferred"));
        JsonObject payload = JsonParser.parseString(request.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
        Assert.assertEquals(payload.get("decisionType").getAsString(), "COMBAT_DAMAGE_ORDER");
        Assert.assertEquals(payload.getAsJsonArray("options").get(1).getAsJsonObject().getAsJsonArray("order").get(0).getAsString(), "C2");
        for (String internal : List.of("heuristic", "originalCandidateIndex", "internalMapping", "forge.game", "hand", "library")) Assert.assertFalse(payload.toString().contains(internal));
    }

    @Test
    public void damageOrdersRejectWrongIdentityFingerprintAndOptionWithoutLosingReturnedIdentity() throws IOException {
        var state = damageOrderState();
        var context = new forge.ai.decision.CombatDamageOrderDecisionContext("blocks", "fingerprint", state,
                List.of(new forge.ai.decision.CombatDamageOrderOptionView("OPT_A", List.of("PERM_A", "PERM_B"), List.of("Bear", "Giant"))));
        for (String kind : List.of("ID", "FINGERPRINT", "OPTION")) {
            startServer(200, completion(kind.equals("ID") ? "wrong" : context.decisionId(), kind.equals("FINGERPRINT") ? "wrong" : context.stateFingerprint(), kind.equals("OPTION") ? "unknown" : "OPT_A"), 0);
            var result = provider(true, null, Duration.ofSeconds(2)).chooseWithDiagnostics(context);
            Assert.assertTrue(result.decision().isEmpty()); Assert.assertNotNull(result.rejectedDecision());
            Assert.assertEquals(result.failureReason(), kind.equals("ID") ? AiDecisionFailureReason.DECISION_ID_MISMATCH
                    : kind.equals("FINGERPRINT") ? AiDecisionFailureReason.FINGERPRINT_MISMATCH : AiDecisionFailureReason.INVALID_OPTION);
            stopServer();
        }
    }

    @Test
    public void combatRejectsStaleFingerprint() throws IOException {
        var state = new forge.ai.decision.CombatAttackersDecisionContext.State(4, "COMBAT_DECLARE_ATTACKERS", "PLAYER_1", List.of(), List.of(), List.of());
        var context = new forge.ai.decision.CombatAttackersDecisionContext("combat", "fingerprint", state,
                List.of(new forge.ai.decision.CombatAttackOptionView("NO_ATTACK", List.of(), 0, 0)));
        startServer(200, completion(context.decisionId(), "wrong-fingerprint", "NO_ATTACK"), 0);
        var result = provider(true, null, Duration.ofSeconds(2)).chooseWithDiagnostics(context);
        Assert.assertTrue(result.decision().isEmpty());
        Assert.assertEquals(result.failureReason(), AiDecisionFailureReason.FINGERPRINT_MISMATCH);
        Assert.assertEquals(result.rejectedDecision().stateFingerprint(), "wrong-fingerprint");
    }

    @Test
    public void combatRetainsWrongReturnedDecisionIdentity() throws IOException {
        var state = new forge.ai.decision.CombatAttackersDecisionContext.State(4, "COMBAT_DECLARE_ATTACKERS", "PLAYER_1", List.of(), List.of(), List.of());
        var context = new forge.ai.decision.CombatAttackersDecisionContext("original", "fingerprint", state,
                List.of(new forge.ai.decision.CombatAttackOptionView("NO_ATTACK", List.of(), 0, 0)));
        startServer(200, completion("another-decision", context.stateFingerprint(), "NO_ATTACK"), 0);
        var result = provider(true, null, Duration.ofSeconds(2)).chooseWithDiagnostics(context);
        Assert.assertTrue(result.decision().isEmpty());
        Assert.assertEquals(result.failureReason(), AiDecisionFailureReason.DECISION_ID_MISMATCH);
        Assert.assertEquals(result.rejectedDecision().decisionId(), "another-decision");
        Assert.assertEquals(context.decisionId(), "original");
    }

    @Test
    public void combatRejectsMissingFingerprintAndUnsuppliedOption() throws IOException {
        var state = new forge.ai.decision.CombatAttackersDecisionContext.State(4, "COMBAT_DECLARE_ATTACKERS", "PLAYER_1", List.of(), List.of(), List.of());
        var context = new forge.ai.decision.CombatAttackersDecisionContext("combat", "fingerprint", state,
                List.of(new forge.ai.decision.CombatAttackOptionView("NO_ATTACK", List.of(), 0, 0)));
        for (String option : List.of("NO_ATTACK", "OPT_NOT_SUPPLIED")) {
            String response = completion(context.decisionId(), context.stateFingerprint(), option);
            if (option.equals("NO_ATTACK")) {
                JsonObject envelope = JsonParser.parseString(response).getAsJsonObject();
                JsonObject message = envelope.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
                JsonObject decision = JsonParser.parseString(message.get("content").getAsString()).getAsJsonObject();
                decision.remove("stateFingerprint"); message.addProperty("content", decision.toString());
                response = envelope.toString();
            }
            startServer(200, response, 0);
            Assert.assertTrue(provider(true, null, Duration.ofSeconds(2)).choose(context).isEmpty());
            stopServer();
        }
    }

    private void assertStackPayload(StackResponseDecisionContext context) {
        JsonObject request = JsonParser.parseString(requestBody.get()).getAsJsonObject();
        JsonObject payload = JsonParser.parseString(request.getAsJsonArray("messages").get(1)
                .getAsJsonObject().get("content").getAsString()).getAsJsonObject();
        Assert.assertEquals(payload.get("decisionType").getAsString(), "STACK_RESPONSE");
        Assert.assertTrue(payload.get("passAvailable").getAsBoolean());
        Assert.assertEquals(payload.getAsJsonArray("stackItems").get(0).getAsJsonObject()
                .get("sourceName").getAsString(), "Runeclaw Bear");
        JsonArray actions = payload.getAsJsonArray("legalActions");
        Assert.assertEquals(actions.size(), 2);
        for (int i = 0; i < actions.size(); i++) {
            JsonObject action = actions.get(i).getAsJsonObject();
            Assert.assertEquals(action.get("actionId").getAsString(), context.legalActions().get(i).actionId());
            Assert.assertFalse(action.has("forgeRecommendation"));
            Assert.assertFalse(action.has("heuristicPosition"));
        }
        String prompt = request.getAsJsonArray("messages").get(0).getAsJsonObject()
                .get("content").getAsString();
        Assert.assertTrue(prompt.contains("Choose exactly one supplied option ID or PASS"));
        Assert.assertFalse(prompt.contains("ACTION_"));
        Assert.assertFalse(prompt.contains("recommendation"));
        Assert.assertFalse(requestBody.get().contains("PREFERRED"));
        Assert.assertFalse(requestBody.get().contains("ALTERNATIVE"));
        Assert.assertFalse(requestBody.get().contains("heuristicResponseId"));
        Assert.assertFalse(requestBody.get().contains("internalActionMapping"));
        Assert.assertFalse(requestBody.get().contains("originalPosition"));
        Assert.assertFalse(requestBody.get().contains("presentedPosition"));
        Assert.assertFalse(requestBody.get().contains("matchesForgeHeuristic"));
        Assert.assertFalse(requestBody.get().contains("ACTION_"));
        Assert.assertFalse(requestBody.get().contains("originalActionId"));
        Assert.assertFalse(requestBody.get().contains("randomizedOrder"));
        Assert.assertFalse(requestBody.get().contains("forge.game"));
        assertDecisionSchema(request, List.of("PASS", context.legalActions().get(0).actionId(),
                context.legalActions().get(1).actionId()));
    }

    @Test
    public void jsonObjectModePreservesLegacyResponseFormat() throws IOException {
        MulliganDecisionContext context = context();
        startServer(200, completion(context, MulliganDecisionContext.KEEP_OPTION_ID), 0);

        Optional<AiDecision> result = provider(true, null, Duration.ofSeconds(2),
                OpenAiResponseFormatMode.JSON_OBJECT).choose(context);

        Assert.assertTrue(result.isPresent());
        JsonObject responseFormat = JsonParser.parseString(requestBody.get()).getAsJsonObject()
                .getAsJsonObject("response_format");
        Assert.assertEquals(responseFormat.get("type").getAsString(), "json_object");
        Assert.assertFalse(responseFormat.has("json_schema"));
    }

    private static forge.ai.decision.CombatDamageOrderDecisionContext.State damageOrderState() {
        var source = new forge.ai.decision.CombatDamageOrderDecisionContext.Creature("PERM_S", "Bull Aurochs", "Creature ? Aurochs", List.of("green"),
                2, 1, 0, List.of(), List.of("trample"));
        var first = new forge.ai.decision.CombatDamageOrderDecisionContext.Creature("PERM_A", "Centaur Courser", "Creature ? Centaur", List.of("green"),
                3, 3, 0, List.of(), List.of());
        var second = new forge.ai.decision.CombatDamageOrderDecisionContext.Creature("PERM_B", "Centaur Token", "Creature ? Centaur", List.of("green", "white"),
                3, 3, 1, List.of(new forge.ai.decision.MainPhaseCounterView("+1/+1", 1)), List.of("indestructible"));
        return new forge.ai.decision.CombatDamageOrderDecisionContext.State(4, "COMBAT_DECLARE_BLOCKERS", "PRE_DAMAGE_ASSIGNMENT_ORDER", true,
                source, List.of(first, second), 12);
    }

    private static forge.ai.decision.CombatDamageOrderDecisionContext latencyContext() {
        return new forge.ai.decision.CombatDamageOrderDecisionContext("00000000-0000-0000-0000-000000000001", "f".repeat(64),
                damageOrderState(), List.of(
                    new forge.ai.decision.CombatDamageOrderOptionView("OPT_" + "A".repeat(64), List.of("PERM_A", "PERM_B"),
                        List.of("Centaur Courser 3/3", "Centaur Token 3/3")),
                    new forge.ai.decision.CombatDamageOrderOptionView("OPT_" + "B".repeat(64), List.of("PERM_B", "PERM_A"),
                        List.of("Centaur Token 3/3", "Centaur Courser 3/3"))));
    }

    @Test public void compactOrdersPreserveEveryCombatantFieldIdentityAndExactEnum() throws Exception {
        var context = latencyContext();
        startServer(200, completion(context.decisionId(), context.stateFingerprint(), context.options().get(1).optionId()), 80);
        var result = provider(true, "secret-test-key", Duration.ofSeconds(2)).chooseWithDiagnostics(context);
        Assert.assertTrue(result.decision().isPresent());
        JsonObject request = JsonParser.parseString(requestBody.get()).getAsJsonObject();
        assertDecisionSchema(request, context.options().stream().map(o -> o.optionId()).toList());
        var payload = JsonParser.parseString(request.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
        Assert.assertEquals(payload.get("decisionId").getAsString(), context.decisionId());
        Assert.assertEquals(payload.get("stateFingerprint").getAsString(), context.stateFingerprint());
        var gson = new com.google.gson.GsonBuilder().serializeNulls().create();
        var originalState = gson.toJsonTree(context.state()).getAsJsonObject();
        var restoredState = payload.getAsJsonObject("state").deepCopy();
        restoredState.getAsJsonObject("source").addProperty("recipientId", context.state().source().recipientId());
        for (int i = 0; i < context.state().recipients().size(); i++) {
            var recipient = restoredState.getAsJsonArray("recipients").get(i).getAsJsonObject();
            Assert.assertEquals(recipient.get("recipientId").getAsString(), "C" + (i + 1));
            recipient.addProperty("recipientId", context.state().recipients().get(i).recipientId());
        }
        Assert.assertEquals(restoredState, originalState);
        for (int i = 0; i < context.options().size(); i++) {
            var option = payload.getAsJsonArray("options").get(i).getAsJsonObject();
            Assert.assertEquals(option.keySet(), Set.of("optionId", "order"));
            var mapped = option.getAsJsonArray("order").asList().stream().map(ref ->
                    context.state().recipients().get(Integer.parseInt(ref.getAsString().substring(1)) - 1).recipientId()).toList();
            Assert.assertEquals(mapped, context.options().get(i).orderedRecipientIds());
        }
        Assert.assertFalse(payload.toString().contains("orderedRecipientSummaries"));
        Assert.assertFalse(payload.toString().contains("PERM_"));
        var diagnostics = result.diagnostics(); Assert.assertNotNull(diagnostics);
        Assert.assertEquals(diagnostics.serializedRequestChars(), requestBody.get().length());
        Assert.assertEquals(diagnostics.userPromptChars(), payload.toString().length());
        Assert.assertEquals(diagnostics.projectedStateChars(), payload.get("state").toString().length());
        Assert.assertEquals(diagnostics.optionCount(), 2);
        Assert.assertTrue(diagnostics.httpLatencyMs() >= 60);
        Assert.assertTrue(diagnostics.requestSerializationNanos() > 0);
        Assert.assertTrue(diagnostics.responseParseNanos() > 0); Assert.assertTrue(diagnostics.identityValidationNanos() > 0);
        Assert.assertTrue(diagnostics.totalProviderLatencyNanos() >= diagnostics.httpLatencyNanos());
        Assert.assertFalse(diagnostics.toString().contains("secret-test-key"));

        // Reconstruct the Stage 11 wire representation for a controlled, reproducible size comparison.
        var baseline = request.deepCopy(); baseline.remove("max_tokens"); baseline.remove("reasoning_effort");
        String oldPrompt = "You are choosing combat damage assignment order in Magic: The Gathering. "
                + "Choose exactly one supplied option ID. Use only visible information. Consider lethal damage, trample, "
                + "deathtouch, indestructible, first/double strike, lifelink, protection and creature value. "
                + "Forge will assign legal damage amounts. Do not invent an ordering. Copy decisionId and stateFingerprint exactly. "
                + "Return only the required structured response, without reasoning.";
        baseline.getAsJsonArray("messages").get(0).getAsJsonObject().addProperty("content", oldPrompt);
        var oldPayload = payload.deepCopy(); oldPayload.add("state", originalState); oldPayload.add("options", gson.toJsonTree(context.options()));
        baseline.getAsJsonArray("messages").get(1).getAsJsonObject().addProperty("content", oldPayload.toString());
        Assert.assertTrue(requestBody.get().length() < baseline.toString().length());
        Assert.assertEquals(baseline.get("response_format"), request.get("response_format"));
        System.out.printf("Stage11.1 size fixture: system=%d->%d user=%d->%d state=%d->%d schema=%d->%d request=%d->%d%n",
                oldPrompt.length(), diagnostics.systemPromptChars(), oldPayload.toString().length(), diagnostics.userPromptChars(),
                originalState.toString().length(), diagnostics.projectedStateChars(), diagnostics.schemaChars(), diagnostics.schemaChars(),
                baseline.toString().length(), diagnostics.serializedRequestChars());
    }

    @Test public void outputBoundAndThinkingControlCoverStableOllamaQwenSurfaces() throws Exception {
        var context = latencyContext();
        var ollama = new OpenAiCompatibleDecisionProvider(new OpenAiCompatibleProviderConfig(true,
                URI.create("http://localhost:11434/v1/chat/completions"), "qwen3:8b", null, Duration.ofSeconds(2)));
        JsonObject request = renderedRequest(ollama, context);
        Assert.assertEquals(request.get("reasoning_effort").getAsString(), "none");
        Assert.assertEquals(request.get("max_tokens").getAsInt(), 512);
        String valid = "{\"decisionId\":\"" + context.decisionId() + "\",\"stateFingerprint\":\"" + context.stateFingerprint()
                + "\",\"optionId\":\"" + context.options().get(0).optionId() + "\"}";
        Assert.assertTrue(request.get("max_tokens").getAsInt() > valid.getBytes(StandardCharsets.UTF_8).length);
        var longIdentity = new forge.ai.decision.CombatDamageOrderDecisionContext("d".repeat(1000), context.stateFingerprint(), context.state(), context.options());
        Assert.assertTrue(renderedRequest(ollama, longIdentity).get("max_tokens").getAsInt() > 1000);
        for (var other : List.of(mainPhaseContext(), stackContext(), context())) {
            var otherRequest = renderedRequest(ollama, other);
            Assert.assertEquals(otherRequest.get("reasoning_effort").getAsString(), "none");
            Assert.assertEquals(otherRequest.get("max_tokens").getAsInt(), 512);
        }
        var remote = new OpenAiCompatibleDecisionProvider(new OpenAiCompatibleProviderConfig(true,
                URI.create("https://example.com/v1/chat/completions"), "qwen3:8b", null, Duration.ofSeconds(2)));
        Assert.assertFalse(renderedRequest(remote, context).has("reasoning_effort"));
    }

    @Test public void malformedAndTimedOutDamageResponsesKeepSizeAndHttpDiagnostics() throws Exception {
        var context = latencyContext();
        startServer(200, "{\"choices\":[", 0);
        var malformed = provider(true, null, Duration.ofSeconds(2)).chooseWithDiagnostics(context);
        Assert.assertEquals(malformed.failureReason(), AiDecisionFailureReason.MALFORMED_RESPONSE);
        Assert.assertTrue(malformed.decision().isEmpty()); Assert.assertEquals(malformed.diagnostics().responseChars(), 12);
        Assert.assertTrue(malformed.diagnostics().responseParseNanos() > 0); stopServer();
        startServer(200, completion(context.decisionId(), context.stateFingerprint(), context.options().get(0).optionId()), 500);
        var timeout = provider(true, null, Duration.ofMillis(100)).chooseWithDiagnostics(context);
        Assert.assertEquals(timeout.failureReason(), AiDecisionFailureReason.TIMEOUT);
        Assert.assertTrue(timeout.diagnostics().httpLatencyMs() >= 70);
        Assert.assertEquals(timeout.diagnostics().responseChars(), 0);
        Assert.assertEquals(timeout.diagnostics().responseParseNanos(), 0L);
        Assert.assertTrue(timeout.diagnostics().serializedRequestChars() > 0);
    }

    private static JsonObject renderedRequest(OpenAiCompatibleDecisionProvider provider, forge.ai.decision.AiDecisionContext context) throws Exception {
        var method = java.util.Arrays.stream(OpenAiCompatibleDecisionProvider.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("requestBody")).findFirst().orElseThrow();
        method.setAccessible(true);
        return JsonParser.parseString((String) method.invoke(provider, context, null)).getAsJsonObject();
    }

    @Test public void integratedCombatRetainsQwenThinkingControlAndOutputBoundsForEverySurface() throws Exception {
        var provider = new OpenAiCompatibleDecisionProvider(new OpenAiCompatibleProviderConfig(true,
                URI.create("http://localhost:11434/v1/chat/completions"), "qwen3:8b", null, Duration.ofSeconds(2)));
        var attackers = new forge.ai.decision.CombatAttackersDecisionContext("attacks", "fingerprint",
                new forge.ai.decision.CombatAttackersDecisionContext.State(4, "COMBAT_DECLARE_ATTACKERS", "PLAYER_0", List.of(), List.of(), List.of()),
                List.of(new forge.ai.decision.CombatAttackOptionView("NO_ATTACK", List.of(), 0, 0)));
        var blockers = new forge.ai.decision.CombatBlockersDecisionContext("blocks", "fingerprint",
                new forge.ai.decision.CombatBlockersDecisionContext.State(4, "COMBAT_DECLARE_BLOCKERS", "PLAYER_1", List.of(), List.of(), List.of()),
                List.of(new forge.ai.decision.CombatBlockOptionView("NO_BLOCK", List.of(), 0, 0)));
        for (var context : List.of(attackers, blockers, latencyContext())) {
            var request = renderedRequest(provider, context);
            Assert.assertEquals(request.get("reasoning_effort").getAsString(), "none");
            Assert.assertEquals(request.get("max_tokens").getAsInt(), 512);
            var payload = JsonParser.parseString(request.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
            Assert.assertEquals(payload.get("decisionType").getAsString(), context.type().name());
            Assert.assertFalse(payload.has("combatSequenceId"));
        }
    }

    private static void assertDecisionSchema(JsonObject request, List<String> expectedOptions) {
        JsonObject responseFormat = request.getAsJsonObject("response_format");
        Assert.assertEquals(responseFormat.get("type").getAsString(), "json_schema");
        JsonObject definition = responseFormat.getAsJsonObject("json_schema");
        Assert.assertTrue(definition.get("strict").getAsBoolean());
        JsonObject schema = definition.getAsJsonObject("schema");
        Assert.assertEquals(schema.get("type").getAsString(), "object");
        Assert.assertFalse(schema.get("additionalProperties").getAsBoolean());
        JsonObject properties = schema.getAsJsonObject("properties");
        Assert.assertEquals(properties.keySet(), Set.of("decisionId", "stateFingerprint", "optionId"));
        Assert.assertTrue(properties.entrySet().stream()
                .allMatch(entry -> "string".equals(entry.getValue().getAsJsonObject()
                        .get("type").getAsString())));
        List<String> required = schema.getAsJsonArray("required").asList().stream()
                .map(JsonElement::getAsString).toList();
        Assert.assertEquals(required, List.of("decisionId", "stateFingerprint", "optionId"));
        List<String> options = properties.getAsJsonObject("optionId").getAsJsonArray("enum").asList().stream()
                .map(JsonElement::getAsString).toList();
        Assert.assertEquals(options, expectedOptions);
    }

    private OpenAiCompatibleDecisionProvider provider(boolean enabled, String apiKey, Duration timeout) {
        return provider(enabled, apiKey, timeout, OpenAiResponseFormatMode.AUTO);
    }

    private OpenAiCompatibleDecisionProvider provider(boolean enabled, String apiKey, Duration timeout,
            OpenAiResponseFormatMode responseFormatMode) {
        OpenAiCompatibleProviderConfig config = new OpenAiCompatibleProviderConfig(
                enabled, endpoint(), "test-model", apiKey, timeout, responseFormatMode);
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
                        new MainPhasePlayerState(ai, 20, true, new MainPhaseManaView(0, 0, 0, 2, 0, 0, 2),
                                List.of(), List.of(),
                                List.of(), List.of(), List.of())), 0);
        return new MainPhaseDecisionContext("main-decision", state, List.of(
                new LegalActionView("ACTION_0", "SPELL", "Shock", "Instant", "DealDamage",
                        "Shock deals 2 damage", "{R}", List.of("Player: Opponent"), List.of(), null, 0, "PREFERRED"),
                new LegalActionView("ACTION_1", "SPELL", "Lightning Bolt", "Instant", "DealDamage",
                        "Lightning Bolt deals 3 damage", "{R}", List.of("Player: Opponent"), List.of(), null, 1, "ALTERNATIVE")));
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
