package forge.ai.decision;

/** Strictly whitelisted card information visible to the deciding player. */
public record MainPhaseCardView(
        String name,
        String manaCost,
        String type,
        String oracleText,
        boolean tapped,
        String controller) {
}
