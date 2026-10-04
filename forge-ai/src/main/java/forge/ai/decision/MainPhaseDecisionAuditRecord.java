package forge.ai.decision;

import java.util.List;

/** Immutable, visibility-safe JSONL representation of one Stage 7 decision and its eventual game result. */
public record MainPhaseDecisionAuditRecord(
        String decisionType,
        int gameId,
        Integer gameIndex,
        Long runSeed,
        String playerIdentity,
        int playerSeat,
        String deckIdentifier,
        int turn,
        String phase,
        String decisionId,
        String fingerprint,
        int configuredMaxActions,
        int rawCandidateCount,
        int evaluatedCandidateCount,
        int acceptedCandidateCount,
        int exposedCandidateCount,
        int copiedGameCount,
        long enumerationNanos,
        boolean candidateSetTruncated,
        String heuristicActionId,
        String selectedActionId,
        boolean agreement,
        String provider,
        String model,
        long providerLatencyMs,
        long totalStrategicDecisionLatencyMs,
        AiDecisionSource source,
        boolean fallback,
        AiDecisionFailureReason failureReason,
        MainPhaseAiState state,
        List<LegalActionView> legalActions,
        String returnedDecisionId,
        String returnedFingerprint,
        String returnedOptionId,
        boolean responseAccepted,
        String finalWinner,
        boolean decidingPlayerWon,
        boolean draw,
        long gameDurationMs,
        int finalTurnCount) {

    public MainPhaseDecisionAuditRecord {
        legalActions = List.copyOf(legalActions);
    }
}
