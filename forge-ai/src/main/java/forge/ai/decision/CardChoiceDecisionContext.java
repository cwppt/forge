package forge.ai.decision;

import java.util.List;

/** Model-visible live choice: only immutable descriptions, never engine references. */
public record CardChoiceDecisionContext(String decisionId, String stateFingerprint,
        MainPhaseAiState state, String source, String question, List<Option> options) implements AiDecisionContext {
    public record Option(String optionId, String description) { }
    public CardChoiceDecisionContext { options = List.copyOf(options); }
    @Override public AiDecisionType type() { return AiDecisionType.CARD_CHOICE; }
}
