package forge.ai.decision;

import org.tinylog.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AiDecisionMetrics {
    private static final List<MulliganDecisionEvent> EVENTS = new ArrayList<>();
    private static final List<MainPhaseDecisionEvent> ACTION_EVENTS = new ArrayList<>();
    private static final Map<Integer, GameRunMetadata> GAME_METADATA = new ConcurrentHashMap<>();

    private AiDecisionMetrics() {
    }

    public static synchronized void record(MulliganDecisionEvent event) {
        EVENTS.add(event);
        Logger.info("aiDecision decisionId={} fingerprint={} gameId={} gameIndex={} runSeed={} player={} "
                        + "seat={} deck={} provider={} model={} source={} heuristicOption={} finalOption={} "
                        + "latencyMs={} fallback={} fallbackReason={} cardsToReturn={} openingHandSize={}",
                event.decisionId(), event.stateFingerprint(), event.gameId(), value(event.gameIndex()),
                value(event.runSeed()), event.playerIdentity(), event.playerSeat(), value(event.deckIdentifier()),
                value(event.provider()), value(event.model()), event.source(),
                event.heuristicOption(), event.finalOption(), event.latencyMs(), event.fallback(),
                value(event.fallbackReason()), event.cardsToReturn(), event.openingHandSize());
    }

    public static void registerGame(int gameId, int gameIndex, Long runSeed) {
        GAME_METADATA.put(gameId, new GameRunMetadata(gameIndex, runSeed));
    }

    public static synchronized void record(MainPhaseDecisionEvent event) {
        ACTION_EVENTS.add(event);
        Logger.info("aiActionDecision decisionId={} fingerprint={} gameId={} gameIndex={} runSeed={} player={} "
                        + "seat={} deck={} provider={} model={} source={} candidates={} heuristicAction={} "
                        + "selectedAction={} category={} actionSource={} providerLatencyMs={} totalLatencyMs={} "
                        + "fallback={} fallbackReason={} staleOrRevalidationFailure={}",
                event.decisionId(), event.stateFingerprint(), event.gameId(), value(event.gameIndex()),
                value(event.runSeed()), event.playerIdentity(), event.playerSeat(), value(event.deckIdentifier()),
                value(event.provider()), value(event.model()), event.source(), event.acceptedCandidateCount(),
                event.heuristicActionId(), event.selectedActionId(), event.actionCategory(), event.sourceName(),
                event.providerLatencyMs(), event.totalLatencyMs(), event.fallback(), value(event.fallbackReason()),
                event.staleOrRevalidationFailure());
    }

    public static GameRunMetadata gameMetadata(int gameId) {
        return GAME_METADATA.get(gameId);
    }

    public static synchronized void reset() {
        EVENTS.clear();
        ACTION_EVENTS.clear();
        GAME_METADATA.clear();
    }

    public static synchronized List<MulliganDecisionEvent> events() {
        return List.copyOf(EVENTS);
    }

    public static synchronized List<MainPhaseDecisionEvent> actionEvents() {
        return List.copyOf(ACTION_EVENTS);
    }

    public static synchronized AiDecisionMetricsSnapshot snapshot(long games) {
        long attempts = EVENTS.stream().filter(MulliganDecisionEvent::providerAttempted).count();
        long accepted = EVENTS.stream().filter(e -> e.source() == AiDecisionSource.EXTERNAL_PROVIDER).count();
        long agreements = EVENTS.stream().filter(e -> e.source() == AiDecisionSource.EXTERNAL_PROVIDER
                && e.heuristicOption().equals(e.finalOption())).count();
        long disagreements = accepted - agreements;
        long rejected = EVENTS.stream().filter(e -> e.providerAttempted()
                && isRejectedResponse(e.fallbackReason())).count();
        long failures = EVENTS.stream().filter(e -> e.providerAttempted()
                && isProviderFailure(e.fallbackReason())).count();
        long timeouts = EVENTS.stream().filter(e -> e.fallbackReason() == AiDecisionFailureReason.TIMEOUT).count();
        long fallbacks = EVENTS.stream().filter(MulliganDecisionEvent::fallback).count();
        long keeps = EVENTS.stream().filter(e -> MulliganDecisionContext.KEEP_OPTION_ID.equals(e.finalOption())).count();
        long mulligans = EVENTS.size() - keeps;
        List<Long> latencies = EVENTS.stream().filter(MulliganDecisionEvent::providerAttempted)
                .map(MulliganDecisionEvent::latencyMs).sorted().toList();
        double average = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        double finalHand = EVENTS.stream()
                .filter(e -> MulliganDecisionContext.KEEP_OPTION_ID.equals(e.finalOption()))
                .mapToInt(e -> Math.max(0, e.openingHandSize() - e.cardsToReturn())).average().orElse(0);
        return new AiDecisionMetricsSnapshot(EVENTS.size(), attempts, accepted, rejected, failures, timeouts,
                fallbacks, keeps, mulligans, average, percentile(latencies, 0.50), percentile(latencies, 0.95),
                games > 0 ? (double) mulligans / games : 0, finalHand, agreements, disagreements,
                accepted == 0 ? 0 : (double) disagreements / accepted);
    }

    public static synchronized void writeCsv(Path path) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("decisionId,stateFingerprint,provider,model,source,option,latencyMs,providerAttempted,"
                + "fallback,fallbackReason,cardsToReturn,openingHandSize,gameId,gameIndex,runSeed,"
                + "playerIdentity,playerSeat,deckIdentifier,heuristicOption,finalOption,decisionType,"
                + "candidateCount,rawCandidateCount,evaluatedCandidateCount,copiedGameCount,enumerationNanos,"
                + "totalLatencyMs,staleOrRevalidationFailure,actionCategory,actionSource");
        EVENTS.stream().sorted(Comparator.comparing(MulliganDecisionEvent::decisionId)).forEach(e -> lines.add(
                csv(e.decisionId()) + "," + csv(e.stateFingerprint()) + "," + csv(e.provider()) + ","
                        + csv(e.model()) + "," + e.source() + "," + e.option() + "," + e.latencyMs() + ","
                        + e.providerAttempted() + "," + e.fallback() + "," + value(e.fallbackReason()) + ","
                        + e.cardsToReturn() + "," + e.openingHandSize() + "," + e.gameId() + ","
                        + value(e.gameIndex()) + "," + value(e.runSeed()) + "," + csv(e.playerIdentity()) + ","
                        + e.playerSeat() + "," + csv(e.deckIdentifier()) + "," + e.heuristicOption() + ","
                        + e.finalOption() + ",MULLIGAN_KEEP,,,,,,,,,"));
        ACTION_EVENTS.stream().sorted(Comparator.comparing(MainPhaseDecisionEvent::decisionId)).forEach(e -> lines.add(
                csv(e.decisionId()) + "," + csv(e.stateFingerprint()) + "," + csv(e.provider()) + ","
                        + csv(e.model()) + "," + e.source() + "," + e.selectedActionId() + ","
                        + e.providerLatencyMs() + "," + e.providerAttempted() + "," + e.fallback() + ","
                        + value(e.fallbackReason()) + ",,," + e.gameId() + "," + value(e.gameIndex()) + ","
                        + value(e.runSeed()) + "," + csv(e.playerIdentity()) + "," + e.playerSeat() + ","
                        + csv(e.deckIdentifier()) + "," + e.heuristicActionId() + "," + e.selectedActionId()
                        + ",MAIN_PHASE_ACTION," + e.acceptedCandidateCount() + "," + e.rawCandidateCount() + ","
                        + e.evaluatedCandidateCount() + "," + e.copiedGameCount() + "," + e.enumerationNanos()
                        + "," + e.totalLatencyMs() + "," + e.staleOrRevalidationFailure() + ","
                        + csv(e.actionCategory()) + "," + csv(e.sourceName())));
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    public static String summary(long games) {
        AiDecisionMetricsSnapshot s = snapshot(games);
        return String.format(Locale.ROOT,
                "games=%d mulliganDecisions=%d providerCalls=%d accepted=%d rejected=%d providerFailures=%d "
                        + "timeouts=%d fallbacks=%d fallbackRate=%.4f avgLatencyMs=%.2f p50LatencyMs=%d "
                        + "p95LatencyMs=%d avgMulligans=%.4f avgFinalOpeningHandSize=%.2f "
                        + "externalAgreements=%d externalDisagreements=%d disagreementRate=%.4f "
                        + "mainPhaseDecisions=%d mainPhaseProviderCalls=%d mainPhaseExternal=%d "
                        + "mainPhaseDisagreements=%d",
                games, s.mulliganCallbacks(), s.providerAttempts(), s.acceptedExternalDecisions(),
                s.rejectedExternalDecisions(), s.providerFailures(), s.timeouts(), s.heuristicFallbacks(),
                s.providerAttempts() == 0 ? 0 : (double) s.heuristicFallbacks() / s.providerAttempts(),
                s.averageLatencyMs(), s.p50LatencyMs(), s.p95LatencyMs(), s.averageMulligansPerGame(),
                s.averageFinalOpeningHandSize(), s.externalAgreements(), s.externalDisagreements(),
                s.externalDisagreementRate(), ACTION_EVENTS.size(),
                ACTION_EVENTS.stream().filter(MainPhaseDecisionEvent::providerAttempted).count(),
                ACTION_EVENTS.stream().filter(e -> e.source() == AiDecisionSource.EXTERNAL_PROVIDER).count(),
                ACTION_EVENTS.stream().filter(e -> e.source() == AiDecisionSource.EXTERNAL_PROVIDER
                        && !e.heuristicActionId().equals(e.selectedActionId())).count());
    }

    public record GameRunMetadata(int gameIndex, Long runSeed) {
    }

    private static boolean isRejectedResponse(AiDecisionFailureReason reason) {
        return reason == AiDecisionFailureReason.EMPTY_RESPONSE
                || reason == AiDecisionFailureReason.MALFORMED_RESPONSE
                || reason == AiDecisionFailureReason.INVALID_OPTION
                || reason == AiDecisionFailureReason.DECISION_ID_MISMATCH
                || reason == AiDecisionFailureReason.FINGERPRINT_MISMATCH;
    }

    private static boolean isProviderFailure(AiDecisionFailureReason reason) {
        return reason == AiDecisionFailureReason.TIMEOUT
                || reason == AiDecisionFailureReason.CONNECTION
                || reason == AiDecisionFailureReason.HTTP_ERROR
                || reason == AiDecisionFailureReason.PROVIDER_EXCEPTION;
    }

    private static long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    private static String csv(Object value) {
        String text = value(value).replace("\"", "\"\"");
        return "\"" + text + "\"";
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }
}
