package forge.ai.decision;

import java.util.List;

/** Complete model-visible declaration; no ranking or engine objects. */
public record CombatBlockOptionView(String optionId, List<Assignment> assignments,
        int blockerCount, int blockedAttackerCount, TacticalConsequences tacticalConsequences) {
    public CombatBlockOptionView { assignments = List.copyOf(assignments); }
    public CombatBlockOptionView(String optionId, List<Assignment> assignments, int blockerCount, int blockedAttackerCount) {
        this(optionId, assignments, blockerCount, blockedAttackerCount, null);
    }
    /** Conditional public-board projection, never a prediction of future spells or choices. */
    public record TacticalConsequences(String basis, int projectedCreatureLossesSelf,
            int projectedCreatureLossesOpponent, int projectedDamagePrevented, int projectedLifeAfterCombat,
            boolean preventsLethal, boolean nearLethalProtection, boolean unfavorableTrade,
            boolean chumpBlock, boolean preservesBoard, boolean unnecessaryLosingBlock) { }
    public record Assignment(String blockerId, String attackerId) { }
}
