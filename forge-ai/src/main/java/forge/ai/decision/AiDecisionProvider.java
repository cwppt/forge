package forge.ai.decision;

import java.util.Optional;

@FunctionalInterface
public interface AiDecisionProvider {
    Optional<AiDecision> choose(AiDecisionContext context);

    default AiDecisionResult chooseWithDiagnostics(AiDecisionContext context) {
        long started = System.nanoTime();
        try {
            Optional<AiDecision> decision = choose(context);
            long latency = AiDecisionResult.elapsedMillis(started);
            if (decision != null && decision.isPresent()) {
                return AiDecisionResult.accepted(decision.get(), getClass().getSimpleName(), null, latency);
            }
            return AiDecisionResult.failed(AiDecisionFailureReason.PROVIDER_EXCEPTION,
                    getClass().getSimpleName(), null, latency);
        } catch (RuntimeException e) {
            return AiDecisionResult.failed(AiDecisionFailureReason.PROVIDER_EXCEPTION,
                    getClass().getSimpleName(), null, AiDecisionResult.elapsedMillis(started));
        }
    }
}
