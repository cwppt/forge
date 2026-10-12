package forge.ai.decision;

/** Shared audit contract, independent of the three immutable decision requests. */
public interface CombatDecisionEvent {
    String decisionId();
    int gameId();
    String combatSequenceId();
    int turnNumber();
    int activePlayerSeat();
    Integer defendingPlayerSeat();
    AiDecisionType decisionSurface();
    int playerSeat();
    String heuristicOptionId();
    String selectedOptionId();
    boolean responseAccepted();
    boolean fallback();
    boolean providerAttempted();
    AiDecisionFailureReason failureReason();
    long providerLatencyMs();
}
