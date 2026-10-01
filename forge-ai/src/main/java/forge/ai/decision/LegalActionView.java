package forge.ai.decision;

import java.util.List;

/** Visibility-safe, immutable description of one Forge-prepared action. */
public record LegalActionView(
        String actionId,
        String category,
        String sourceName,
        String sourceType,
        String apiType,
        String description,
        String cost,
        List<String> targets,
        List<String> modes,
        Integer xValue,
        int heuristicPosition) {

    public LegalActionView {
        targets = List.copyOf(targets);
        modes = List.copyOf(modes);
    }
}
