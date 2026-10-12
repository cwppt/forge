package forge.ai.decision;

import java.util.List;

/** Immutable visible snapshot. Forge alone determines damage quantities. */
public record CombatDamageOrderDecisionContext(String decisionId, String stateFingerprint, State state,
        List<CombatDamageOrderOptionView> options) implements AiDecisionContext {
    public CombatDamageOrderDecisionContext { options = List.copyOf(options); }
    @Override public AiDecisionType type() { return AiDecisionType.COMBAT_DAMAGE_ORDER; }
    public record Creature(String recipientId, String name, String type, List<String> colors,
            int power, int toughness, int markedDamage,
            List<MainPhaseCounterView> counters, List<String> keywords) {
        public Creature { colors = List.copyOf(colors); counters = List.copyOf(counters); keywords = List.copyOf(keywords); }
    }
    public record State(int turn, String phase, String damageStep, boolean sourceAttacking,
            Creature source, List<Creature> recipients, Integer defendingPlayerLife) {
        public State { recipients = List.copyOf(recipients); }
    }
}
