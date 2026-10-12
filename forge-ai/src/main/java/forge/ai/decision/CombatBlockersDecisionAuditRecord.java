package forge.ai.decision;

import java.util.List;

/** Internal mapping and telemetry are audit-only; modelVisibleDecision is the provider whitelist. */
public record CombatBlockersDecisionAuditRecord(String decisionType,
        CombatBlockersDecisionContext modelVisibleDecision, List<Mapping> internalMapping,
        CombatBlockersDecisionEvent telemetry, boolean agreement, String finalWinner, boolean decidingPlayerWon,
        boolean draw, long gameDurationMs, int finalTurnCount) {
    public CombatBlockersDecisionAuditRecord { internalMapping = List.copyOf(internalMapping); }
    public record Mapping(String modelVisibleOptionId, int originalCandidateIndex, boolean matchesForgeHeuristic,
            List<CombatBlockOptionView.Assignment> originalAssignments) {
        public Mapping { originalAssignments = List.copyOf(originalAssignments); }
    }
}
