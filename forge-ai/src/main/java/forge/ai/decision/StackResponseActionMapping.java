package forge.ai.decision;

/** Internal audit metadata; never serialized into the provider payload. */
public record StackResponseActionMapping(String modelVisibleOptionId, String originalActionId,
        int originalPosition, int presentedPosition, String sourceName, String description,
        boolean matchesForgeHeuristic) {
    public StackResponseActionMapping(String modelVisibleOptionId, String originalActionId,
            int originalPosition, int presentedPosition, String sourceName, String description) {
        this(modelVisibleOptionId, originalActionId, originalPosition, presentedPosition, sourceName, description, false);
    }

    public StackResponseActionMapping withHeuristic(String heuristicOptionId) {
        return new StackResponseActionMapping(modelVisibleOptionId, originalActionId, originalPosition,
                presentedPosition, sourceName, description, modelVisibleOptionId.equals(heuristicOptionId));
    }
}
