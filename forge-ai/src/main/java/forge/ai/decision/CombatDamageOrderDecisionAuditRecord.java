package forge.ai.decision;

import java.util.List;

/** Internal mapping and telemetry are audit-only; modelVisibleDecision is the provider whitelist. */
public record CombatDamageOrderDecisionAuditRecord(String decisionType,
        CombatDamageOrderDecisionContext modelVisibleDecision, List<Mapping> internalMapping,
        List<String> forgeHeuristicOrder, List<String> externalSelectedOrder, List<String> appliedOrder,
        CombatDamageOrderDecisionEvent telemetry, boolean agreement, String finalWinner, boolean decidingPlayerWon,
        boolean draw, long gameDurationMs, int finalTurnCount) {
    public CombatDamageOrderDecisionAuditRecord {
        internalMapping = List.copyOf(internalMapping); forgeHeuristicOrder = List.copyOf(forgeHeuristicOrder);
        externalSelectedOrder = List.copyOf(externalSelectedOrder); appliedOrder = List.copyOf(appliedOrder);
    }
    public record Mapping(String modelVisibleOptionId, int suppliedOptionIndex, boolean matchesForgeHeuristic,
            List<String> originalOrder) {
        public Mapping { originalOrder = List.copyOf(originalOrder); }
    }
}
