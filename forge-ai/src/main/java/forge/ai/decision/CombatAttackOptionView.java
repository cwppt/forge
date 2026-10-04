package forge.ai.decision;

import java.util.List;

/** Complete visible attacker declaration; references point only into the sanitized battlefield. */
public record CombatAttackOptionView(String optionId, List<Assignment> attackers, int totalPower, int attackerCount) {
    public CombatAttackOptionView { attackers = List.copyOf(attackers); }
    public record Assignment(String attackerId, String defenderId) { }
}
