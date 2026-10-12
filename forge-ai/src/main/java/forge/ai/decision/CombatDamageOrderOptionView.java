package forge.ai.decision;

import java.util.List;

/** Complete visible ordering, without heuristic labels or engine references. */
public record CombatDamageOrderOptionView(String optionId, List<String> orderedRecipientIds,
        List<String> orderedRecipientSummaries) {
    public CombatDamageOrderOptionView {
        orderedRecipientIds = List.copyOf(orderedRecipientIds);
        orderedRecipientSummaries = List.copyOf(orderedRecipientSummaries);
    }
}
