package forge.ai.decision.external;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.decision.AiDecision;
import forge.ai.decision.AiDecisionContext;
import forge.ai.decision.AiDecisionFailureReason;
import forge.ai.decision.AiDecisionProvider;
import forge.ai.decision.AiDecisionResult;
import forge.ai.decision.AiOptionView;
import forge.ai.decision.AiPlayerView;
import forge.ai.decision.MulliganAiState;
import forge.ai.decision.MulliganCardView;
import forge.ai.decision.MulliganDecisionContext;
import forge.ai.decision.LegalActionView;
import forge.ai.decision.MainPhaseAiState;
import forge.ai.decision.MainPhaseCardView;
import forge.ai.decision.MainPhaseDecisionContext;
import forge.ai.decision.MainPhasePlayerState;
import org.tinylog.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

public final class OpenAiCompatibleDecisionProvider implements AiDecisionProvider {
    private static final String MULLIGAN_SYSTEM_PROMPT = "You are making one strategic Magic: The Gathering mulligan decision. "
            + "Forge is the authoritative rules engine. Choose only one supplied option ID. "
            + "Do not invent cards, actions, rules, or hidden information. "
            + "Return only JSON with decisionId, stateFingerprint, and optionId.";
    private static final String ACTION_SYSTEM_PROMPT = "Choose one Forge-prepared Magic: The Gathering action. "
            + "Forge is the authoritative rules engine. Choose exactly one supplied action ID. "
            + "Do not invent actions, alter targets, or assume hidden information. "
            + "Return only JSON with decisionId, stateFingerprint, and optionId.";

    private final OpenAiCompatibleProviderConfig config;
    private final HttpClient httpClient;

    public OpenAiCompatibleDecisionProvider(OpenAiCompatibleProviderConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(config.timeout()).build());
    }

    OpenAiCompatibleDecisionProvider(OpenAiCompatibleProviderConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    @Override
    public Optional<AiDecision> choose(AiDecisionContext context) {
        return chooseWithDiagnostics(context).decision();
    }

    @Override
    public AiDecisionResult chooseWithDiagnostics(AiDecisionContext context) {
        long started = System.nanoTime();
        if (!config.enabled()) {
            Logger.debug("External AI provider disabled; using Forge fallback");
            return failure(AiDecisionFailureReason.DISABLED, started);
        }
        if (!(context instanceof MulliganDecisionContext) && !(context instanceof MainPhaseDecisionContext)) {
            return reject(AiDecisionFailureReason.PROVIDER_EXCEPTION, "unsupported decision type", started);
        }

        String endpoint = safeEndpoint(config.endpoint());
        Logger.info("Calling external AI provider model={} endpoint={}", config.model(), endpoint);
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(config.endpoint())
                    .timeout(config.timeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(context)));
            if (config.apiKey() != null) {
                requestBuilder.header("Authorization", "Bearer " + config.apiKey());
            }

            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return reject(AiDecisionFailureReason.HTTP_ERROR,
                        "HTTP status " + response.statusCode(), started);
            }
            AiDecisionResult result = parseResponse(response.body(), context, started);
            if (result.decision().isPresent()) {
                Logger.info("External AI response accepted for decisionId={} model={}",
                        context.decisionId(), config.model());
            }
            return result;
        } catch (java.net.http.HttpTimeoutException e) {
            Logger.warn("External AI provider timed out for model={} endpoint={}; using Forge fallback",
                    config.model(), endpoint);
            return failure(AiDecisionFailureReason.TIMEOUT, started);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.warn("External AI provider request interrupted for model={}; using Forge fallback", config.model());
            return failure(AiDecisionFailureReason.PROVIDER_EXCEPTION, started);
        } catch (Exception e) {
            Logger.warn("External AI provider failure for model={} endpoint={}: {}; using Forge fallback",
                    config.model(), endpoint, e.getClass().getSimpleName());
            return failure(AiDecisionFailureReason.CONNECTION, started);
        }
    }

    private AiDecisionResult parseResponse(String body, AiDecisionContext context, long started) {
        if (body == null || body.isBlank()) {
            return reject(AiDecisionFailureReason.EMPTY_RESPONSE, "empty response", started);
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray choices = requiredArray(root, "choices");
            if (choices.size() != 1 || !choices.get(0).isJsonObject()) {
                return reject(AiDecisionFailureReason.MALFORMED_RESPONSE,
                        "response must contain exactly one choice", started);
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject message = requiredObject(choice, "message");
            String content = requiredString(message, "content");
            JsonObject decision = JsonParser.parseString(content).getAsJsonObject();
            String decisionId = requiredString(decision, "decisionId");
            String optionId = requiredString(decision, "optionId");
            if (!context.decisionId().equals(decisionId)) {
                return reject(AiDecisionFailureReason.DECISION_ID_MISMATCH, "mismatched decisionId", started);
            }
            String expectedFingerprint = fingerprint(context);
            String fingerprint = decision.has("stateFingerprint")
                    ? requiredString(decision, "stateFingerprint") : null;
            if (context instanceof MainPhaseDecisionContext && fingerprint == null) {
                return reject(AiDecisionFailureReason.FINGERPRINT_MISMATCH,
                        "missing state fingerprint", started);
            }
            if (fingerprint != null) {
                if (!expectedFingerprint.equals(fingerprint)) {
                    return reject(AiDecisionFailureReason.FINGERPRINT_MISMATCH,
                            "mismatched state fingerprint", started);
                }
            }
            if (!validOption(context, optionId)) {
                return reject(AiDecisionFailureReason.INVALID_OPTION, "invalid optionId", started);
            }
            return AiDecisionResult.accepted(new AiDecision(decisionId, fingerprint, optionId),
                    "openai-compatible", config.model(), AiDecisionResult.elapsedMillis(started));
        } catch (RuntimeException e) {
            return reject(AiDecisionFailureReason.MALFORMED_RESPONSE,
                    "malformed or unsupported response", started);
        }
    }

    private String requestBody(AiDecisionContext context) {
        JsonObject request = new JsonObject();
        request.addProperty("model", config.model());
        request.addProperty("stream", false);
        request.addProperty("temperature", 0);

        JsonArray messages = new JsonArray();
        messages.add(message("system", context instanceof MainPhaseDecisionContext
                ? ACTION_SYSTEM_PROMPT : MULLIGAN_SYSTEM_PROMPT));
        messages.add(message("user", decisionPayload(context).toString()));
        request.add("messages", messages);

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        request.add("response_format", responseFormat);
        return request.toString();
    }

    private static JsonObject decisionPayload(AiDecisionContext context) {
        if (context instanceof MulliganDecisionContext mulligan) {
            return decisionPayload(mulligan);
        }
        if (context instanceof MainPhaseDecisionContext mainPhase) {
            return decisionPayload(mainPhase);
        }
        throw new IllegalArgumentException("Unsupported decision context");
    }

    private static JsonObject decisionPayload(MainPhaseDecisionContext context) {
        MainPhaseAiState state = context.state();
        JsonObject payload = new JsonObject();
        payload.addProperty("decisionId", context.decisionId());
        payload.addProperty("decisionType", context.type().name());
        payload.addProperty("stateFingerprint", state.fingerprint());
        JsonObject projected = new JsonObject();
        projected.addProperty("turnNumber", state.turnNumber());
        projected.addProperty("phase", state.phase());
        projected.add("activePlayer", player(state.activePlayer()));
        projected.add("self", player(state.self()));
        projected.addProperty("stackSize", state.stackSize());
        JsonArray players = new JsonArray();
        for (MainPhasePlayerState player : state.players()) {
            JsonObject value = new JsonObject();
            value.add("identity", player(player.identity()));
            value.addProperty("life", player.life());
            value.addProperty("self", player.self());
            addNullableString(value, "manaPool", player.manaPool());
            value.add("hand", cards(player.hand()));
            value.add("battlefield", cards(player.battlefield()));
            value.add("graveyard", cards(player.graveyard()));
            value.add("exile", cards(player.exile()));
            value.add("command", cards(player.command()));
            players.add(value);
        }
        projected.add("players", players);
        payload.add("state", projected);
        JsonArray actions = new JsonArray();
        for (LegalActionView action : context.legalActions()) {
            JsonObject value = new JsonObject();
            value.addProperty("actionId", action.actionId());
            value.addProperty("category", action.category());
            addNullableString(value, "sourceName", action.sourceName());
            addNullableString(value, "sourceType", action.sourceType());
            addNullableString(value, "apiType", action.apiType());
            addNullableString(value, "description", action.description());
            addNullableString(value, "cost", action.cost());
            value.add("targets", strings(action.targets()));
            value.add("modes", strings(action.modes()));
            addNullableNumber(value, "xValue", action.xValue());
            value.addProperty("heuristicPosition", action.heuristicPosition());
            actions.add(value);
        }
        payload.add("legalActions", actions);
        return payload;
    }

    private static JsonArray cards(java.util.List<MainPhaseCardView> cards) {
        JsonArray result = new JsonArray();
        for (MainPhaseCardView card : cards) {
            JsonObject value = new JsonObject();
            addNullableString(value, "name", card.name());
            addNullableString(value, "manaCost", card.manaCost());
            addNullableString(value, "type", card.type());
            addNullableString(value, "oracleText", card.oracleText());
            value.addProperty("tapped", card.tapped());
            addNullableString(value, "controller", card.controller());
            result.add(value);
        }
        return result;
    }

    private static JsonArray strings(java.util.List<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private static String fingerprint(AiDecisionContext context) {
        if (context instanceof MulliganDecisionContext mulligan) {
            return mulligan.state().fingerprint();
        }
        return ((MainPhaseDecisionContext) context).state().fingerprint();
    }

    private static boolean validOption(AiDecisionContext context, String optionId) {
        if (context instanceof MulliganDecisionContext) {
            return MulliganDecisionContext.KEEP_OPTION_ID.equals(optionId)
                    || MulliganDecisionContext.MULLIGAN_OPTION_ID.equals(optionId);
        }
        return ((MainPhaseDecisionContext) context).legalActions().stream()
                .anyMatch(action -> action.actionId().equals(optionId));
    }

    private static JsonObject decisionPayload(MulliganDecisionContext context) {
        MulliganAiState state = context.state();
        JsonObject payload = new JsonObject();
        payload.addProperty("decisionId", context.decisionId());
        payload.addProperty("decisionType", context.type().name());
        payload.addProperty("stateFingerprint", state.fingerprint());

        JsonObject projectedState = new JsonObject();
        addNullableNumber(projectedState, "turnNumber", state.turnNumber());
        projectedState.add("activeOrStartingPlayer", player(state.activeOrStartingPlayer()));
        projectedState.add("self", player(state.self()));
        addNullableNumber(projectedState, "selfLife", state.selfLife());
        projectedState.addProperty("startingHandSize", state.startingHandSize());
        projectedState.addProperty("cardsToReturn", state.cardsToReturn());
        projectedState.addProperty("startingPlayer", state.startingPlayer());

        JsonArray hand = new JsonArray();
        for (MulliganCardView card : state.openingHand()) {
            JsonObject cardJson = new JsonObject();
            addNullableString(cardJson, "name", card.name());
            addNullableString(cardJson, "manaCost", card.manaCost());
            addNullableString(cardJson, "type", card.type());
            addNullableString(cardJson, "oracleText", card.oracleText());
            hand.add(cardJson);
        }
        projectedState.add("openingHand", hand);
        payload.add("state", projectedState);

        JsonArray options = new JsonArray();
        for (AiOptionView option : context.options()) {
            JsonObject optionJson = new JsonObject();
            optionJson.addProperty("id", option.id());
            optionJson.addProperty("label", option.label());
            options.add(optionJson);
        }
        payload.add("allowedOptions", options);
        return payload;
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static JsonElement player(AiPlayerView player) {
        if (player == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        JsonObject result = new JsonObject();
        result.addProperty("id", player.id());
        result.addProperty("name", player.name());
        return result;
    }

    private static void addNullableNumber(JsonObject target, String name, Integer value) {
        if (value == null) {
            target.add(name, com.google.gson.JsonNull.INSTANCE);
        } else {
            target.addProperty(name, value);
        }
    }

    private static void addNullableString(JsonObject target, String name, String value) {
        if (value == null) {
            target.add(name, com.google.gson.JsonNull.INSTANCE);
        } else {
            target.addProperty(name, value);
        }
    }

    private static JsonArray requiredArray(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("missing array: " + name);
        }
        return value.getAsJsonArray();
    }

    private static JsonObject requiredObject(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("missing object: " + name);
        }
        return value.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("missing string: " + name);
        }
        String result = value.getAsString();
        if (result.isBlank()) {
            throw new IllegalArgumentException("blank string: " + name);
        }
        return result;
    }

    private AiDecisionResult reject(AiDecisionFailureReason failureReason, String reason, long started) {
        Logger.warn("External AI response rejected: {}; using Forge fallback", reason);
        return failure(failureReason, started);
    }

    private AiDecisionResult failure(AiDecisionFailureReason reason, long started) {
        return AiDecisionResult.failed(reason, "openai-compatible", config.model(),
                AiDecisionResult.elapsedMillis(started));
    }

    private static String safeEndpoint(URI endpoint) {
        String authority = endpoint.getHost();
        if (authority == null) {
            return endpoint.getScheme() + ":<invalid>";
        }
        if (endpoint.getPort() >= 0) {
            authority += ":" + endpoint.getPort();
        }
        return endpoint.getScheme() + "://" + authority + endpoint.getPath();
    }
}
