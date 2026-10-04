package forge.ai.decision;

/** Current floating mana only; this does not estimate mana that permanents could produce. */
public record MainPhaseManaView(
        int white,
        int blue,
        int black,
        int red,
        int green,
        int colorless,
        int total) {
}
