package forge.ai.decision;

/** Separate from Stage 12 metrics: sharing a sequence does not redefine trio totals. */
public record CombatActionDecisionEvent(String decisionId, String stateFingerprint,
        String combatSequenceId, int gameId, int turn, String window, int playerSeat,
        String provider, String model, int optionCount, String heuristicOptionId,
        String returnedOptionId, String selectedOptionId, boolean passSelection,
        boolean fallback, AiDecisionFailureReason failureReason, boolean staleOrRevalidationFailure,
        long providerLatencyMs, long totalLatencyMs, String liveRevalidationOutcome, String disagreementCategory) {
    public CombatActionDecisionEvent {
        disagreementCategory = fallback || heuristicOptionId.equals(selectedOptionId) ? "NONE"
                : "PASS".equals(heuristicOptionId) ? "FORGE_PASS_EXTERNAL_ACTION"
                : "PASS".equals(selectedOptionId) ? "FORGE_ACTION_EXTERNAL_PASS" : "DIFFERENT_ACTION";
    }
}
