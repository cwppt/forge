package forge.ai.decision;

import java.util.List;

public record MulliganDecisionContext(
        String decisionId,
        MulliganAiState state,
        List<AiOptionView> options) implements AiDecisionContext {

    public static final String KEEP_OPTION_ID = "KEEP";
    public static final String MULLIGAN_OPTION_ID = "MULLIGAN";

    public MulliganDecisionContext {
        options = List.copyOf(options);
    }

    @Override
    public AiDecisionType type() {
        return AiDecisionType.MULLIGAN_KEEP;
    }
}
