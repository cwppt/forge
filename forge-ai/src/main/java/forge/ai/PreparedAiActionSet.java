package forge.ai;

import java.util.List;

/** Internal result and diagnostics for an explicit bounded candidate-enumeration request. */
record PreparedAiActionSet(
        List<PreparedAiAction> actions,
        int rawCandidateCount,
        int evaluatedCandidateCount,
        int copiedGameCount,
        long preparationNanos) {

    PreparedAiActionSet {
        actions = List.copyOf(actions);
    }
}
