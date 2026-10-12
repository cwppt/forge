package forge.ai.decision;

import java.util.List;

public record MainPhaseDecisionContext(
        String decisionId,
        MainPhaseAiState state,
        List<LegalActionView> legalActions, StrategicMemorySnapshot memorySnapshot) implements AiDecisionContext {

    public MainPhaseDecisionContext(String decisionId, MainPhaseAiState state, List<LegalActionView> legalActions) {
        this(decisionId,state,legalActions,null);
    }

    public MainPhaseDecisionContext {
        legalActions = List.copyOf(legalActions);
    }

    @Override
    public AiDecisionType type() {
        return AiDecisionType.MAIN_PHASE_ACTION;
    }
}
