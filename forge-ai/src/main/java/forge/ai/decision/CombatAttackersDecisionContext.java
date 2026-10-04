package forge.ai.decision;

import java.util.List;

/** Battlefield-only whitelist: no hands, libraries, heuristic labels, or engine objects. */
public record CombatAttackersDecisionContext(String decisionId, String stateFingerprint,
        State state, List<CombatAttackOptionView> options) implements AiDecisionContext {
    public static final String NO_ATTACK = "NO_ATTACK";
    public CombatAttackersDecisionContext { options = List.copyOf(options); }
    @Override public AiDecisionType type() { return AiDecisionType.COMBAT_ATTACKERS; }

    public record State(int turn, String phase, String attackingPlayerId, List<PlayerState> players,
            List<Defender> legalDefenders, List<String> restrictions) {
        public State {
            players = List.copyOf(players); legalDefenders = List.copyOf(legalDefenders);
            restrictions = List.copyOf(restrictions);
        }
    }
    public record PlayerState(String playerId, String name, int life, boolean self,
            MainPhaseManaView mana, List<Permanent> battlefield) {
        public PlayerState { battlefield = List.copyOf(battlefield); }
    }
    public record Permanent(String permanentId, MainPhaseCardView card, boolean summoningSick,
            boolean canAttack, List<String> keywords) {
        public Permanent { keywords = List.copyOf(keywords); }
    }
    public record Defender(String defenderId, String name, String kind, String controllerId, String defendingPlayerId) {
        public Defender(String defenderId, String name, String kind, String controllerId) {
            this(defenderId, name, kind, controllerId, controllerId);
        }
    }
}
