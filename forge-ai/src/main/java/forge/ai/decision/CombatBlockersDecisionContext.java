package forge.ai.decision;

import java.util.List;

/** Immutable, battlefield-only request snapshot. */
public record CombatBlockersDecisionContext(String decisionId, String stateFingerprint, State state,
        List<CombatBlockOptionView> options, StrategicMemorySnapshot memorySnapshot) implements AiDecisionContext {
    public CombatBlockersDecisionContext(String id,String fingerprint,State state,List<CombatBlockOptionView> options) {
        this(id,fingerprint,state,options,null);
    }
    public static final String NO_BLOCK = "NO_BLOCK";
    public CombatBlockersDecisionContext { options = List.copyOf(options); }
    @Override public AiDecisionType type() { return AiDecisionType.COMBAT_BLOCKERS; }
    public record State(int turn, String phase, String defendingPlayerId,
            List<CombatAttackersDecisionContext.PlayerState> players, List<Attacker> attackers,
            List<Blocker> blockers) {
        public State { players = List.copyOf(players); attackers = List.copyOf(attackers); blockers = List.copyOf(blockers); }
    }
    public record Attacker(CombatAttackersDecisionContext.Permanent permanent, String targetId) { }
    public record Blocker(CombatAttackersDecisionContext.Permanent permanent, boolean canBlock) { }
}
