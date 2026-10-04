package forge.ai.decision;

import java.util.List;

/** Immutable, perspective-safe description of an item currently on the stack. */
public record StackItemView(int position, String sourceName, String controller, String category,
        String rulesSummary, List<String> targets) {
    public StackItemView {
        targets = List.copyOf(targets);
    }
}
