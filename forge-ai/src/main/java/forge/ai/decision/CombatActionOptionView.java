package forge.ai.decision;

import java.util.List;

/** Immutable public description; all choices have already been made by Forge. */
public record CombatActionOptionView(String optionId, String sourceRef, String sourceName,
        String category, String cost, List<String> targets, String effect,
        List<String> modes, Integer xValue, List<String> optionalCosts) {
    public CombatActionOptionView {
        targets = List.copyOf(targets); modes = List.copyOf(modes); optionalCosts = List.copyOf(optionalCosts);
    }
}
