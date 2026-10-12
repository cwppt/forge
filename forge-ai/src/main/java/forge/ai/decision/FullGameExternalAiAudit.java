package forge.ai.decision;

import com.google.common.eventbus.Subscribe;
import com.google.gson.*;
import forge.game.Game;
import forge.game.event.GameEventTurnPhase;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Passive evaluation observer. Never prepares actions, calls a provider, or feeds history to a model. */
public final class FullGameExternalAiAudit {
    private static final Gson JSON = new Gson();
    private static final Gson MEMORY_JSON = new GsonBuilder().serializeNulls().create();
    private static final Map<Integer, FullGameExternalAiAudit> GAMES = new ConcurrentHashMap<>();
    private static final Map<String, JsonObject> USAGE = new ConcurrentHashMap<>();
    private final Game game;
    private final String id;
    private final List<JsonObject> decisions = new ArrayList<>();
    private final Map<Integer, JsonObject> turns = new TreeMap<>();
    private final Set<String> requests = new HashSet<>();
    private boolean closed;

    private FullGameExternalAiAudit(Game game) {
        this.game = game;
        var metadata = AiDecisionMetrics.gameMetadata(game.getId());
        id = gameIdentity(System.getProperty("forge.ai.fullGame.runId", "local"), game.getId(),
                metadata == null ? 0 : metadata.gameIndex(), metadata == null ? null : metadata.runSeed());
    }
    public static String gameIdentity(String namespace, int gameId, int index, Long seed) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                    (namespace + "|" + gameId + "|" + index + "|" + seed).getBytes(StandardCharsets.UTF_8));
            return "GAME_" + HexFormat.of().formatHex(bytes, 0, 12);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String turnIdentity(String gameId, int turn) { return gameId + "_T" + turn; }
    public static void observe(Game game) {
        GAMES.computeIfAbsent(game.getId(), ignored -> {
            var observer = new FullGameExternalAiAudit(game); game.subscribeToEvents(observer); return observer;
        });
    }
    public static void reset() { GAMES.values().forEach(g -> g.closed = true); GAMES.clear(); USAGE.clear(); }
    static void release(int gameId) { GAMES.remove(gameId); }

    @Subscribe public void phase(GameEventTurnPhase event) {
        if (closed) return;
        if (event.phase() == PhaseType.MAIN1) {
            int turn = game.getPhaseHandler().getTurn();
            JsonObject sequence = turn(turn);
            sequence.add("beforeMainPhase", resources());
        }
    }
    private JsonArray resources() {
        JsonArray result = new JsonArray(); int seat = 0;
        for (var player : game.getRegisteredPlayers()) {
            JsonObject p = new JsonObject(); p.addProperty("seat", seat++);
            p.addProperty("life", player.getLife());
            p.addProperty("handSize", player.getCardsIn(ZoneType.Hand).size());
            p.addProperty("battlefieldCount", player.getCardsIn(ZoneType.Battlefield).size());
            p.addProperty("floatingMana", player.getManaPool().totalMana());
            p.addProperty("untappedLands", player.getCardsIn(ZoneType.Battlefield).stream()
                    .filter(c -> c.isLand() && !c.isTapped()).count());
            result.add(p);
        }
        return result;
    }
    private JsonObject turn(int number) {
        return turns.computeIfAbsent(number, n -> {
            JsonObject t = base("TURN_SEQUENCE", n, -1, ""); t.add("decisionRefs", new JsonArray()); return t;
        });
    }
    private JsonObject base(String type, int turn, int seat, String phase) {
        JsonObject out = new JsonObject(); out.addProperty("recordType", type);
        out.addProperty("externalAiGameId", id); out.addProperty("gameId", game.getId());
        var m = AiDecisionMetrics.gameMetadata(game.getId());
        if (m != null) { out.addProperty("gameIndex", m.gameIndex()); out.addProperty("runSeed", m.runSeed()); }
        out.addProperty("turnSequenceId", turnIdentity(id, turn)); out.addProperty("turn", turn);
        out.addProperty("playerSeat", seat); out.addProperty("phase", phase); return out;
    }
    static void record(AiDecisionType type, Object event, AiDecisionContext context) {
        if (GAMES.isEmpty() && (context==null || context.memorySnapshot()==null)) return;
        JsonObject e = JSON.toJsonTree(event).getAsJsonObject();
        forge.ai.ExternalAiStrategicMemory.requested(context,type==AiDecisionType.COMBAT_ACTION || bool(e,"providerAttempted"));
        if(GAMES.isEmpty())return;
        var observer = GAMES.get(integer(e, "gameId"));
        if (observer != null && !observer.closed) observer.append(type, e, context);
    }
    private void append(AiDecisionType type, JsonObject e, AiDecisionContext context) {
        int number = e.has("turnNumber") ? integer(e, "turnNumber") : integer(e, "turn");
        String phase = first(e, "phase", "window");
        if (phase.isEmpty()) phase = type == AiDecisionType.MULLIGAN_KEEP ? "MULLIGAN" : game.getPhaseHandler().getPhase().name();
        JsonObject ref = base("FULL_GAME_DECISION", number, integer(e, "playerSeat"), phase);
        ref.addProperty("surface", type.name()); ref.addProperty("ordinal", decisions.size());
        for (String key : List.of("decisionId", "stateFingerprint", "combatSequenceId"))
            if (e.has(key)) ref.add(key, e.get(key));
        String selected = first(e, "finalOption", "selectedActionId", "selectedResponseId", "selectedOptionId");
        String heuristic = first(e, "heuristicOption", "heuristicActionId", "heuristicResponseId", "heuristicOptionId");
        boolean attempted = type == AiDecisionType.COMBAT_ACTION || bool(e, "providerAttempted");
        boolean fallback = bool(e, "fallback"), accepted = attempted && !fallback;
        ref.addProperty("providerAttempted", attempted); ref.addProperty("accepted", accepted);
        ref.addProperty("memoryEnabled",context!=null && context.memorySnapshot()!=null);
        if(context!=null && context.memorySnapshot()!=null)ref.add("memory",MEMORY_JSON.toJsonTree(context.memorySnapshot()));
        ref.addProperty("fallback", fallback); ref.addProperty("failureReason", first(e, "failureReason", "fallbackReason"));
        ref.addProperty("staleOrRevalidationFailure", bool(e, "staleOrRevalidationFailure"));
        ref.addProperty("providerLatencyMs", e.has("providerLatencyMs") ? number(e, "providerLatencyMs") : number(e, "latencyMs"));
        ref.addProperty("selectedOption", selected); ref.addProperty("heuristicOption", heuristic);
        ref.addProperty("agreement", accepted && selected.equals(heuristic));
        ref.addProperty("passSelection", accepted && selected.equals("PASS"));
        ref.addProperty("noAttackSelection", accepted && bool(e, "noAttackSelected"));
        ref.addProperty("noBlockSelection", accepted && bool(e, "noBlockSelected"));
        JsonArray options = new JsonArray();
        if (context != null) {
            JsonObject c = JSON.toJsonTree(context).getAsJsonObject();
            JsonElement choices = c.has("options") ? c.get("options") : c.get("legalActions");
            if (choices != null && choices.isJsonArray()) for (var option : choices.getAsJsonArray()) {
                JsonObject o = option.getAsJsonObject(); String optionId = first(o, "optionId", "actionId");
                options.add(optionId);
                if (selected.equals(optionId) && type == AiDecisionType.COMBAT_ACTION) {
                    ref.addProperty("actionCategory", first(o, "category"));
                    ref.addProperty("sourceName", first(o, "sourceName"));
                }
            }
        }
        List<String> canonical = new ArrayList<>(); options.forEach(o -> canonical.add(o.getAsString())); Collections.sort(canonical);
        String key = type + "|" + number + "|" + phase + "|" + first(e, "stateFingerprint") + "|" + canonical;
        ref.addProperty("duplicateRequest", attempted && !requests.add(key));
        var usage = USAGE.remove(first(e, "decisionId")); if (usage != null) ref.add("usage", usage);
        decisions.add(ref); turn(number).getAsJsonArray("decisionRefs").add(first(e, "decisionId"));
    }
    /** Allowlist numeric usage only; malformed optional usage cannot affect execution. */
    public static void recordUsage(String decisionId, JsonObject usage) {
        if (GAMES.isEmpty() || usage == null) return;
        JsonObject safe = new JsonObject();
        for (String key : List.of("prompt_tokens", "completion_tokens", "total_tokens")) {
            try { if (usage.has(key) && usage.get(key).getAsLong() >= 0) safe.addProperty(key, usage.get(key).getAsLong()); }
            catch (RuntimeException ignored) { /* optional diagnostic */ }
        }
        if (safe.size() != 0) USAGE.put(decisionId, safe);
    }
    static JsonObject decorate(JsonObject record) {
        JsonObject event = record.has("telemetry") ? record.getAsJsonObject("telemetry") : record;
        var observer = GAMES.get(integer(event, "gameId"));
        if (observer != null) {
            for (var decision : observer.decisions) if (first(decision, "decisionId").equals(first(event, "decisionId"))) {
                for (String key : List.of("externalAiGameId", "turnSequenceId", "gameIndex", "runSeed", "playerSeat", "turn", "phase", "surface", "ordinal"))
                    if (decision.has(key)) record.add(key, decision.get(key));
                break;
            }
        }
        return record;
    }
    static List<JsonObject> completeGame(int gameId, String winner, boolean draw, long duration, int turns) {
        var observer = GAMES.get(gameId); if (observer == null || observer.closed) return List.of();
        observer.closed = true;
        List<JsonObject> out = new ArrayList<>(observer.decisions); out.addAll(observer.turns.values());
        JsonObject summary = observer.base("FULL_GAME_SUMMARY", turns, -1, "GAME_OVER");
        summary.addProperty("winner", winner); summary.addProperty("draw", draw);
        summary.addProperty("durationMs", duration); summary.addProperty("turns", turns);
        summary.addProperty("externalSeats", System.getProperty("forge.ai.fullGame.externalSeats", ""));
        JsonArray decks = new JsonArray(); observer.game.getRegisteredPlayers().forEach(p -> decks.add(p.getName())); summary.add("decks", decks);
        JsonArray deckNames = new JsonArray(); observer.game.getRegisteredPlayers().forEach(p -> deckNames.add(p.getRegisteredPlayer().getDeck().getName()));
        summary.add("deckNames", deckNames);
        summary.add("finalResources", observer.resources());
        summary.add("strategicMemory",JSON.toJsonTree(forge.ai.ExternalAiStrategicMemory.gameStats(gameId)));
        summary.add("metrics", aggregate(observer.decisions));
        JsonObject bySurface = new JsonObject();
        for (var type : AiDecisionType.values()) bySurface.add(type.name(), aggregate(observer.decisions.stream()
                .filter(d -> first(d, "surface").equals(type.name())).toList()));
        summary.add("bySurface", bySurface); JsonArray refs = new JsonArray();
        observer.decisions.forEach(d -> refs.add(first(d, "decisionId"))); summary.add("decisionRefs", refs);
        out.add(summary); return List.copyOf(out);
    }
    public static JsonObject aggregate(List<JsonObject> decisions) {
        JsonObject m = new JsonObject(); List<Long> latencies = decisions.stream().filter(d -> bool(d, "providerAttempted"))
                .map(d -> number(d, "providerLatencyMs")).sorted().toList();
        long total = latencies.stream().mapToLong(Long::longValue).sum();
        m.addProperty("totalDecisions", decisions.size()); m.addProperty("externalFullGameProviderCalls", latencies.size());
        m.addProperty("externalFullGameProviderLatencyMs", total);
        m.addProperty("externalFullGameAvgCallsPerGame", latencies.size()); m.addProperty("externalFullGameAvgLatencyPerGame", total);
        // One completed game here. Cohort percentiles are computed across game totals by the evaluator.
        m.addProperty("externalFullGameP50LatencyPerGame", total); m.addProperty("externalFullGameP95LatencyPerGame", total);
        m.addProperty("externalFullGameMaxCallLatencyMs", latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1));
        m.addProperty("avgProviderLatencyMs", latencies.isEmpty() ? 0 : (double) total / latencies.size());
        m.addProperty("p50ProviderLatencyMs", percentile(latencies, .5)); m.addProperty("p95ProviderLatencyMs", percentile(latencies, .95));
        for (String key : List.of("accepted", "fallback", "agreement", "passSelection", "noAttackSelection", "noBlockSelection", "duplicateRequest", "staleOrRevalidationFailure"))
            m.addProperty(key, decisions.stream().filter(d -> bool(d, key)).count());
        m.addProperty("disagreement", decisions.stream().filter(d -> bool(d, "accepted") && !bool(d, "agreement")).count());
        m.addProperty("combatActionSelections", decisions.stream().filter(d -> first(d, "surface").equals("COMBAT_ACTION")
                && bool(d, "accepted") && !bool(d, "passSelection")).count());
        JsonObject reasons = new JsonObject(); decisions.stream().filter(d -> bool(d, "fallback")).forEach(d -> {
            String reason = first(d, "failureReason"); reasons.addProperty(reason, reasons.has(reason) ? reasons.get(reason).getAsLong() + 1 : 1);
        }); m.add("fallbackReasons", reasons);
        for (String category : List.of("timeout", "providerException", "malformedResponse", "identityMismatch")) {
            m.addProperty(category, decisions.stream().filter(d -> bool(d, "fallback")).filter(d -> {
                String reason = first(d, "failureReason"); return switch (category) {
                    case "timeout" -> reason.equals("TIMEOUT"); case "providerException" -> reason.equals("PROVIDER_EXCEPTION");
                    case "malformedResponse" -> reason.equals("MALFORMED_RESPONSE") || reason.equals("EMPTY_RESPONSE");
                    default -> reason.equals("DECISION_ID_MISMATCH") || reason.equals("FINGERPRINT_MISMATCH") || reason.equals("INVALID_OPTION");
                };
            }).count());
        }
        return m;
    }
    public static long percentile(List<Long> sorted, double percentile) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1));
    }
    private static boolean bool(JsonObject o, String key) { return o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean(); }
    private static int integer(JsonObject o, String key) { return (int) number(o, key); }
    private static long number(JsonObject o, String key) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : 0; }
    private static String first(JsonObject o, String... keys) {
        for (String key : keys) if (o.has(key) && !o.get(key).isJsonNull()) return o.get(key).getAsString(); return "";
    }
}
