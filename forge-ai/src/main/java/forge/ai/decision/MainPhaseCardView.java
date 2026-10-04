package forge.ai.decision;

import java.util.List;

/** Strictly whitelisted card information visible to the deciding player. */
public record MainPhaseCardView(
        String name,
        String manaCost,
        String type,
        String oracleText,
        boolean tapped,
        String controller,
        Integer power,
        Integer toughness,
        Integer markedDamage,
        List<MainPhaseCounterView> counters) {

    public MainPhaseCardView {
        counters = List.copyOf(counters);
    }
}
