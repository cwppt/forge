package forge.ai.decision;

public record MulliganDecisionEvent(
        String decisionId,
        String stateFingerprint,
        String provider,
        String model,
        AiDecisionSource source,
        String heuristicOption,
        String finalOption,
        long latencyMs,
        boolean providerAttempted,
        boolean fallback,
        AiDecisionFailureReason fallbackReason,
        int cardsToReturn,
        int openingHandSize,
        int gameId,
        Integer gameIndex,
        Long runSeed,
        String playerIdentity,
        int playerSeat,
        String deckIdentifier) {
    /** Retained for source compatibility with Stage 5 telemetry consumers. */
    public String option() {
        return finalOption;
    }
}
