package forge.ai.decision;

public record CombatBlockersDecisionEvent(String decisionId, String stateFingerprint, int gameId,
        Integer gameIndex, Long runSeed, String playerIdentity, int playerSeat, String deckIdentifier,
        String heuristicOptionId, String selectedOptionId, int optionCount, boolean noBlockSelected,
        boolean providerAttempted, AiDecisionSource source, boolean fallback, AiDecisionFailureReason failureReason,
        boolean staleOrRevalidationFailure, String provider, String model, long providerLatencyMs, long totalLatencyMs,
        String returnedDecisionId, String returnedFingerprint, String returnedOptionId, boolean responseAccepted,
        String liveRevalidationResult,
        String combatSequenceId, int turnNumber, int activePlayerSeat, Integer defendingPlayerSeat,
        AiDecisionType decisionSurface) implements CombatDecisionEvent { }
