package forge.ai.decision;

import java.util.List;

/** Request-time immutable view plus internal audit diagnostics; only view is sent to the model. */
public record StrategicMemorySnapshot(StrategicMemoryView view, int gameId, int observerSeat,
        int storedFacts, int selectedFacts, List<String> categories, long expiredFacts,
        long invalidatedFacts, long buildNanos, int addedViewChars) {
    public StrategicMemorySnapshot { categories=List.copyOf(categories); }
}
