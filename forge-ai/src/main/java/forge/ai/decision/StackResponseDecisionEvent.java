package forge.ai.decision;

public record StackResponseDecisionEvent(
        String decisionId, String stateFingerprint, String provider, String model,
        AiDecisionSource source, String heuristicResponseId, String selectedResponseId,
        int responseActionCount, boolean passSelected, boolean providerAttempted,
        boolean fallback, AiDecisionFailureReason fallbackReason, boolean staleOrRevalidationFailure,
        long providerLatencyMs, long totalLatencyMs, int gameId, Integer gameIndex, Long runSeed,
        String playerIdentity, int playerSeat, String deckIdentifier, int turnNumber, String phase,
        String returnedDecisionId, String returnedFingerprint, String returnedOptionId,
        boolean responseAccepted) {
}
