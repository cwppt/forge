package forge.ai.decision;

import java.util.List;

/**
 * Internal audit record, not a provider payload. heuristicResponseId, selectedResponseId,
 * agreement and internalActionDetails' heuristicPosition are telemetry; stack action recommendations
 * are absent, and heuristicPosition is omitted from the model-visible action representation.
 */
public record StackResponseDecisionAuditRecord(
        String decisionType, int gameId, Integer gameIndex, Long runSeed, String playerIdentity,
        int playerSeat, String deckIdentifier, int turn, String phase, String decisionId,
        String fingerprint, int responseActionCount, MainPhaseAiState visibleState,
        List<StackItemView> stackItems,
        List<LegalActionView> internalActionDetails, boolean passAvailable, String heuristicResponseId,
        String selectedResponseId, boolean agreement, String provider, String model,
        long providerLatencyMs, long totalDecisionLatencyMs, AiDecisionSource source,
        boolean fallback, AiDecisionFailureReason failureReason, String returnedDecisionId,
        String returnedFingerprint, String returnedOptionId, boolean responseAccepted,
        String finalWinner, boolean decidingPlayerWon, boolean draw, long gameDurationMs,
        int finalTurnCount, boolean randomizedOrder, List<StackResponseActionMapping> internalActionMapping) {
    public StackResponseDecisionAuditRecord {
        stackItems = List.copyOf(stackItems);
        internalActionDetails = List.copyOf(internalActionDetails);
        internalActionMapping = List.copyOf(internalActionMapping);
    }
}
