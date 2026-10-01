package forge.ai.decision;

public record AiDecision(String decisionId, String stateFingerprint, String optionId) {
    public AiDecision(String decisionId, String optionId) {
        this(decisionId, null, optionId);
    }
}
