package forge.ai.decision;

import java.util.List;

/**
 * Sanitized request for one priority decision; PASS is an option id, never an engine ability.
 * randomizedOrder and internalActionMapping are audit-only and excluded by provider serialization.
 */
public record StackResponseDecisionContext(String decisionId, String stateFingerprint,
        MainPhaseAiState visibleState, List<StackItemView> stackItems,
        List<LegalActionView> legalActions, boolean randomizedOrder,
        List<StackResponseActionMapping> internalActionMapping) implements AiDecisionContext {
    public static final String PASS_OPTION_ID = "PASS";
    public StackResponseDecisionContext(String decisionId, String stateFingerprint,
            MainPhaseAiState visibleState, List<StackItemView> stackItems, List<LegalActionView> legalActions) {
        this(decisionId, stateFingerprint, visibleState, stackItems, legalActions, false, List.of());
    }
    public StackResponseDecisionContext {
        stackItems = List.copyOf(stackItems);
        legalActions = List.copyOf(legalActions);
        internalActionMapping = List.copyOf(internalActionMapping);
    }
    @Override public AiDecisionType type() { return AiDecisionType.STACK_RESPONSE; }
}
