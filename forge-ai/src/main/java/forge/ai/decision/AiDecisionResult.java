package forge.ai.decision;

import java.util.Optional;

public record AiDecisionResult(
        Optional<AiDecision> decision,
        AiDecisionFailureReason failureReason,
        String provider,
        String model,
        long latencyMs) {

    public AiDecisionResult {
        decision = decision == null ? Optional.empty() : decision;
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must not be negative");
        }
    }

    public static AiDecisionResult accepted(AiDecision decision, String provider, String model, long latencyMs) {
        return new AiDecisionResult(Optional.of(decision), null, provider, model, latencyMs);
    }

    public static AiDecisionResult failed(
            AiDecisionFailureReason reason, String provider, String model, long latencyMs) {
        return new AiDecisionResult(Optional.empty(), reason, provider, model, latencyMs);
    }

    public static long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }
}
