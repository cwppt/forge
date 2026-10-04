package forge.ai.decision;

import java.util.List;

/** Internal mapping and telemetry are audit-only; modelVisibleDecision is the provider whitelist. */
public record CombatAttackersDecisionAuditRecord(String decisionType,
        CombatAttackersDecisionContext modelVisibleDecision, List<Mapping> internalMapping,
        CombatAttackersDecisionEvent telemetry, boolean agreement, String finalWinner, boolean decidingPlayerWon,
        boolean draw, long gameDurationMs, int finalTurnCount) {
    public CombatAttackersDecisionAuditRecord { internalMapping = List.copyOf(internalMapping); }
    public record Mapping(String modelVisibleOptionId, int originalCandidateIndex, boolean matchesForgeHeuristic,
            List<CombatAttackOptionView.Assignment> originalAssignments) {
        public Mapping { originalAssignments = List.copyOf(originalAssignments); }
    }
}
