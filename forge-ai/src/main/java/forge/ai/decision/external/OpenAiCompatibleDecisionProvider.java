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
import forge.ai.decision.StackResponseDecisionContext;
import forge.ai.decision.CombatAttackersDecisionContext;
import org.tinylog.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

public final class OpenAiCompatibleDecisionProvider implements AiDecisionProvider {
    private static final String MULLIGAN_SYSTEM_PROMPT = "You are making one strategic Magic: The Gathering mulligan decision. "
            + "Forge is authoritative. Choose exactly one supplied optionId. Copy decisionId and stateFingerprint "
            + "exactly. Do not invent option IDs or hidden information. Return only the required JSON object: "
            + "no explanation, Markdown, or code fences.";
    private static final String ACTION_SYSTEM_PROMPT = "Choose one Forge-prepared Magic: The Gathering action. "
            + "Forge is authoritative. Choose exactly one supplied optionId. Copy decisionId and stateFingerprint "
            + "exactly. Forge's recommendation is advisory; you may select any supplied action ID. Do not invent option IDs, alter targets, or assume hidden information. Return only the "
            + "required JSON object: no explanation, Markdown, or code fences.";
    private static final String STACK_SYSTEM_PROMPT = "Choose one Forge-prepared Magic: The Gathering stack response. "
            + "Choose exactly one supplied option ID or PASS based on the visible game state. Copy decisionId and "
            + "stateFingerprint exactly. Do not invent actions or hidden information. Return only the required "
            + "JSON object: no explanation, Markdown, or code fences.";

    private static final String COMBAT_SYSTEM_PROMPT = "You are choosing attackers in Magic: The Gathering. "
            + "Choose exactly one supplied option ID. Use only visible information. Consider damage, blockers, "
            + "combat keywords, life totals, and preserving creatures. Do not invent attackers or targets. "
            + "Copy decisionId and stateFingerprint exactly. Return only the required JSON object: no explanation, Markdown, or code fences.";

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
        if (!(context instanceof MulliganDecisionContext) && !(context instanceof MainPhaseDecisionContext)
                && !(context instanceof StackResponseDecisionContext) && !(context instanceof CombatAttackersDecisionContext)) {
            return reject(AiDecisionFailureReason.PROVIDER_EXCEPTION, "unsupported decision type", started);
        }

        String endpoint = safeEndpoint(config.endpoint());
        OpenAiResponseFormatMode responseFormatMode = effectiveResponseFormatMode();
        Logger.debug("External AI response format mode={} jsonSchemaRequested={}",
                responseFormatMode, responseFormatMode == OpenAiResponseFormatMode.JSON_SCHEMA);
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
                if (responseFormatMode == OpenAiResponseFormatMode.JSON_SCHEMA) {
                    Logger.debug("External AI endpoint rejected a JSON Schema request with HTTP status {}; "
                            + "configure JSON_OBJECT if the endpoint lacks structured-output support",
                            response.statusCode());
                }
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
            if ((context instanceof MainPhaseDecisionContext || context instanceof StackResponseDecisionContext
                    || context instanceof CombatAttackersDecisionContext) && fingerprint == null) {
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
        messages.add(message("system", context instanceof CombatAttackersDecisionContext ? COMBAT_SYSTEM_PROMPT
                : context instanceof StackResponseDecisionContext ? STACK_SYSTEM_PROMPT
                : context instanceof MainPhaseDecisionContext ? ACTION_SYSTEM_PROMPT : MULLIGAN_SYSTEM_PROMPT));
        messages.add(message("user", decisionPayload(context).toString()));
        request.add("messages", messages);

        JsonObject responseFormat = effectiveResponseFormatMode() == OpenAiResponseFormatMode.JSON_SCHEMA
                ? jsonSchemaResponseFormat(context) : jsonObjectResponseFormat();
        request.add("response_format", responseFormat);
        return request.toString();
    }

    private OpenAiResponseFormatMode effectiveResponseFormatMode() {
        return config.responseFormatMode() == OpenAiResponseFormatMode.AUTO
                ? OpenAiResponseFormatMode.JSON_SCHEMA : config.responseFormatMode();
    }

    private static JsonObject jsonObjectResponseFormat() {
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        return responseFormat;
    }

    private static JsonObject jsonSchemaResponseFormat(AiDecisionContext context) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);

        JsonObject properties = new JsonObject();
        properties.add("decisionId", stringSchema());
        properties.add("stateFingerprint", stringSchema());
        JsonObject optionId = stringSchema();
        JsonArray allowed = new JsonArray();
        allowedOptions(context).forEach(allowed::add);
        optionId.add("enum", allowed);
        properties.add("optionId", optionId);
        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("decisionId");
        required.add("stateFingerprint");
        required.add("optionId");
        schema.add("required", required);

        JsonObject definition = new JsonObject();
        definition.addProperty("name", "forge_ai_decision");
        definition.addProperty("strict", true);
        definition.add("schema", schema);
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_schema");
        responseFormat.add("json_schema", definition);
        return responseFormat;
    }

    private static JsonObject stringSchema() {
        JsonObject value = new JsonObject();
        value.addProperty("type", "string");
        return value;
    }

    private static java.util.List<String> allowedOptions(AiDecisionContext context) {
        if (context instanceof CombatAttackersDecisionContext combat) return combat.options().stream()
                .map(option -> option.optionId()).toList();
        if (context instanceof MulliganDecisionContext mulligan) {
            return mulligan.options().stream().map(AiOptionView::id).toList();
        }
        if (context instanceof MainPhaseDecisionContext main) {
            return main.legalActions().stream().map(LegalActionView::actionId).toList();
        }
        StackResponseDecisionContext stack = (StackResponseDecisionContext) context;
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(StackResponseDecisionContext.PASS_OPTION_ID),
                stack.legalActions().stream().map(LegalActionView::actionId)).toList();
    }

    private static JsonObject decisionPayload(AiDecisionContext context) {
        if (context instanceof CombatAttackersDecisionContext combat) {
            JsonObject payload = new JsonObject();
            payload.addProperty("decisionId", combat.decisionId());
            payload.addProperty("decisionType", combat.type().name());
            payload.addProperty("stateFingerprint", combat.stateFingerprint());
            // This context contains only immutable whitelist records; internal mappings live elsewhere.
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().serializeNulls().create();
            payload.add("state", gson.toJsonTree(combat.state()));
            payload.add("options", gson.toJsonTree(combat.options()));
            return payload;
        }
        if (context instanceof MulliganDecisionContext mulligan) {
            return decisionPayload(mulligan);
        }
        if (context instanceof MainPhaseDecisionContext mainPhase) {
            return decisionPayload(mainPhase);
        }
        if (context instanceof StackResponseDecisionContext stack) {
            JsonObject payload = new JsonObject();
            payload.addProperty("decisionId", stack.decisionId());
            payload.addProperty("decisionType", stack.type().name());
            payload.addProperty("stateFingerprint", stack.stateFingerprint());
            payload.add("state", projectedState(stack.visibleState()));
            JsonArray items = new JsonArray();
            for (forge.ai.decision.StackItemView item : stack.stackItems()) {
                JsonObject value = new JsonObject(); value.addProperty("position", item.position());
                addNullableString(value, "sourceName", item.sourceName()); addNullableString(value, "controller", item.controller());
                addNullableString(value, "category", item.category()); addNullableString(value, "rulesSummary", item.rulesSummary());
                value.add("targets", strings(item.targets())); items.add(value);
            }
            payload.add("stackItems", items);
            payload.addProperty("passAvailable", true);
            payload.add("legalActions", actions(stack.legalActions(), false));
            return payload;
        }
        throw new IllegalArgumentException("Unsupported decision context");
    }

    private static JsonObject decisionPayload(MainPhaseDecisionContext context) {
        MainPhaseAiState state = context.state();
        JsonObject payload = new JsonObject();
        payload.addProperty("decisionId", context.decisionId());
        payload.addProperty("decisionType", context.type().name());
        payload.addProperty("stateFingerprint", state.fingerprint());
        payload.add("state", projectedState(state));
        payload.add("legalActions", actions(context.legalActions(), true));
        return payload;
    }

    private static JsonObject projectedState(MainPhaseAiState state) {
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
            if (player.mana() == null) {
                value.add("mana", com.google.gson.JsonNull.INSTANCE);
            } else {
                JsonObject mana = new JsonObject();
                mana.addProperty("white", player.mana().white());
                mana.addProperty("blue", player.mana().blue());
                mana.addProperty("black", player.mana().black());
                mana.addProperty("red", player.mana().red());
                mana.addProperty("green", player.mana().green());
                mana.addProperty("colorless", player.mana().colorless());
                mana.addProperty("total", player.mana().total());
                value.add("mana", mana);
            }
            value.add("hand", cards(player.hand()));
            value.add("battlefield", cards(player.battlefield()));
            value.add("graveyard", cards(player.graveyard()));
            value.add("exile", cards(player.exile()));
            value.add("command", cards(player.command()));
            players.add(value);
        }
        projected.add("players", players);
        return projected;
    }

    private static JsonArray actions(java.util.List<LegalActionView> legalActions, boolean includeRecommendations) {
        JsonArray actions = new JsonArray();
        for (LegalActionView action : legalActions) {
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
            if (includeRecommendations) {
                value.addProperty("heuristicPosition", action.heuristicPosition());
                value.addProperty("forgeRecommendation", action.forgeRecommendation());
            }
            actions.add(value);
        }
        return actions;
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
            addNullableNumber(value, "power", card.power());
            addNullableNumber(value, "toughness", card.toughness());
            addNullableNumber(value, "markedDamage", card.markedDamage());
            JsonArray counters = new JsonArray();
            card.counters().forEach(counter -> {
                JsonObject counterJson = new JsonObject();
                counterJson.addProperty("name", counter.name());
                counterJson.addProperty("amount", counter.amount());
                counters.add(counterJson);
            });
            value.add("counters", counters);
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
        if (context instanceof CombatAttackersDecisionContext combat) return combat.stateFingerprint();
        if (context instanceof MulliganDecisionContext mulligan) {
            return mulligan.state().fingerprint();
        }
        if (context instanceof MainPhaseDecisionContext main) return main.state().fingerprint();
        return ((StackResponseDecisionContext) context).stateFingerprint();
    }

    private static boolean validOption(AiDecisionContext context, String optionId) {
        if (context instanceof CombatAttackersDecisionContext combat) return combat.options().stream()
                .anyMatch(option -> option.optionId().equals(optionId));
        if (context instanceof MulliganDecisionContext) {
            return MulliganDecisionContext.KEEP_OPTION_ID.equals(optionId)
                    || MulliganDecisionContext.MULLIGAN_OPTION_ID.equals(optionId);
        }
        if (context instanceof MainPhaseDecisionContext main) return main.legalActions().stream()
                .anyMatch(action -> action.actionId().equals(optionId));
        StackResponseDecisionContext stack = (StackResponseDecisionContext) context;
        return StackResponseDecisionContext.PASS_OPTION_ID.equals(optionId) || stack.legalActions().stream()
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
