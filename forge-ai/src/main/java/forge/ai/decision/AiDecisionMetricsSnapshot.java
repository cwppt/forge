package forge.ai.decision;

public record AiDecisionMetricsSnapshot(
        long mulliganCallbacks,
        long providerAttempts,
        long acceptedExternalDecisions,
        long rejectedExternalDecisions,
        long providerFailures,
        long timeouts,
        long heuristicFallbacks,
        long keepSelections,
        long mulliganSelections,
        double averageLatencyMs,
        long p50LatencyMs,
        long p95LatencyMs,
        double averageMulligansPerGame,
        double averageFinalOpeningHandSize,
        long externalAgreements,
        long externalDisagreements,
        double externalDisagreementRate) {
}
