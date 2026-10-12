package forge.ai.decision;

import java.util.List;

/** Compact references to per-decision audits and observed outcomes; never a provider payload. */
public record CombatSequenceAuditRecord(String decisionType, String combatSequenceId, int gameId,
        Integer gameIndex, Long runSeed, int turnNumber, int activePlayerSeat, int defendingPlayerSeat,
        BeforeState beforeState, List<Attacker> declaredAttackers, List<Blocker> declaredBlockers,
        List<Assignment> declaredBlockAssignments, int declaredAttackerCount, int declaredAttackerPower,
        int declaredBlockerCount, int attackersBlocked, int unblockedAttackers, AfterState afterState,
        List<DecisionReference> decisions, int providerCallCount, int fallbackCount,
        long totalProviderLatencyMs, boolean damageCompleted, String completionReason, List<String> coherenceIssues) {
    public CombatSequenceAuditRecord {
        declaredAttackers = List.copyOf(declaredAttackers); declaredBlockers = List.copyOf(declaredBlockers);
        declaredBlockAssignments = List.copyOf(declaredBlockAssignments); decisions = List.copyOf(decisions);
        coherenceIssues = List.copyOf(coherenceIssues);
    }
    public record BeforeState(int activePlayerLife, int defenderLife, int attackerBattlefieldCreatures,
            int defenderBattlefieldCreatures) { }
    public record Attacker(String ref, String name, int visiblePower) { }
    public record Blocker(String ref, String name) { }
    public record Assignment(String blockerRef, String attackerRef) { }
    public record Death(String name, int controllerSeat) { }
    public record AfterState(int activePlayerLife, int defenderLife, int activePlayerLifeLost,
            int defenderLifeLost, List<Death> creaturesDied, int attackersSurviving, int blockersSurviving,
            int combatDamageToActivePlayer, int combatDamageToDefender, boolean lethal) {
        public AfterState { creaturesDied = List.copyOf(creaturesDied); }
    }
    public record DecisionReference(String decisionId, AiDecisionType surface, int decidingPlayerSeat,
            Integer defendingPlayerSeat, String heuristicOptionId, String selectedOptionId,
            boolean accepted, boolean fallback, AiDecisionFailureReason failureReason,
            boolean providerAttempted, long providerLatencyMs) { }
}
