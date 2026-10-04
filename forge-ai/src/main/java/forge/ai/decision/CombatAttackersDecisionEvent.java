package forge.ai.decision;

public record CombatAttackersDecisionEvent(String decisionId, String stateFingerprint, int gameId,
        Integer gameIndex, Long runSeed, String playerIdentity, int playerSeat, String deckIdentifier,
        String heuristicOptionId, String selectedOptionId, int optionCount, boolean noAttackSelected,
        boolean providerAttempted, AiDecisionSource source, boolean fallback, AiDecisionFailureReason failureReason,
        boolean staleOrRevalidationFailure, String provider, String model, long providerLatencyMs, long totalLatencyMs,
        String returnedDecisionId, String returnedFingerprint, String returnedOptionId, boolean responseAccepted) { }
