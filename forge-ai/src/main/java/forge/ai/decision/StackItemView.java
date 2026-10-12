package forge.ai.decision;

import java.util.List;

/** Immutable, perspective-safe description of an item currently on the stack. */
public record StackItemView(int position, String sourceName, String controller, String category,
        String rulesSummary, List<String> targets, String objectType) {
    public StackItemView(int position, String sourceName, String controller, String category, String rulesSummary, List<String> targets) {
        this(position, sourceName, controller, category, rulesSummary, targets, "OTHER");
    }

    public StackItemView {
        targets = List.copyOf(targets);
    }
}
