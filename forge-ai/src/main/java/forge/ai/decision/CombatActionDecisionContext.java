package forge.ai.decision;

import java.util.List;

public record CombatActionDecisionContext(String decisionId, String stateFingerprint,
        State state, List<CombatActionOptionView> options, boolean passAvailable, StrategicMemorySnapshot memorySnapshot) implements AiDecisionContext {
    public CombatActionDecisionContext(String id,String fingerprint,State state,List<CombatActionOptionView> options,boolean pass) {
        this(id,fingerprint,state,options,pass,null);
    }
    public CombatActionDecisionContext { options = List.copyOf(options); }
    @Override public AiDecisionType type() { return AiDecisionType.COMBAT_ACTION; }
    public record Permanent(String ref, String name, int controllerSeat, Integer power, Integer toughness,
            int damage, boolean tapped, List<MainPhaseCounterView> counters, List<String> keywords,
            String attackTarget, List<String> blocking, List<String> visibleEffects) {
        public Permanent { counters = List.copyOf(counters); keywords = List.copyOf(keywords); blocking = List.copyOf(blocking); visibleEffects = List.copyOf(visibleEffects); }
    }
    public record Mana(int seat, MainPhaseManaView floating, List<String> availableSources) {
        public Mana { availableSources = List.copyOf(availableSources); }
    }
    public record State(int turn, String window, int decidingSeat, int attackingSeat, int defendingSeat,
            List<Integer> lifeTotals, List<Permanent> combatants, List<Permanent> relevantBattlefield,
            List<Mana> mana) {
        public State {
            lifeTotals = List.copyOf(lifeTotals); combatants = List.copyOf(combatants);
            relevantBattlefield = List.copyOf(relevantBattlefield); mana = List.copyOf(mana);
        }
    }
}
