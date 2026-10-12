package forge.ai;

import forge.ai.decision.CombatBlockOptionView.TacticalConsequences;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only, deliberately partial combat annotation. No candidate ranking or enforcement. */
final class BlockerTacticalProjection {
    private static final Set<Keyword> SIMPLE = Set.of(Keyword.TRAMPLE, Keyword.FIRST_STRIKE,
            Keyword.DEATHTOUCH, Keyword.FLYING, Keyword.REACH, Keyword.VIGILANCE, Keyword.DEFENDER,
            Keyword.FLASH, Keyword.HASTE, Keyword.CUMULATIVE_UPKEEP, Keyword.SHROUD);

    private static boolean supported(Player defender, Combat combat) {
        if (combat == null || defender.cantLose() || defender.cantLoseForZeroOrLessLife()
                || !defender.canLoseLife() || !defender.getGame().getStack().isEmpty()) return false;
        // Do not guess effects from hidden characteristics, death/block/damage triggers,
        // replacements, prevention shields or conditional combat statics anywhere in play.
        for (Card c : defender.getGame().getCardsIn(List.of(ZoneType.Battlefield, ZoneType.Command))) {
            if (c.isFaceDown() || !c.getView().canBeShownTo(defender.getView())
                    || c.getReplacementEffects().stream().anyMatch(r ->
                            !("Moved".equals(r.getParam("Event")) && "Battlefield".equals(r.getParam("Destination"))))
                    || c.getPreventNextDamageTotalShields() != 0) return false;
            for (var trigger : c.getTriggers()) {
                boolean alreadyResolvedAttack = trigger.getMode() == TriggerType.Attacks
                        && trigger.ensureAbility() != null
                        && (trigger.ensureAbility().getApi() == forge.game.ability.ApiType.Pump
                        || trigger.ensureAbility().getApi() == forge.game.ability.ApiType.PumpAll);
                boolean unrelatedUpkeep = trigger.getMode() == TriggerType.Phase
                        && "Upkeep".equals(trigger.getParam("Phase"));
                boolean entryOnly = trigger.getMode() == TriggerType.ChangesZone
                        && "Battlefield".equals(trigger.getParam("Destination"));
                if (!alreadyResolvedAttack && !unrelatedUpkeep && !entryOnly) return false;
            }
            for (var ability : c.getStaticAbilities()) {
                if (ability.getMapParams().values().stream().anyMatch(v ->
                        v.toLowerCase(java.util.Locale.ROOT).contains("attacking")
                        || v.toLowerCase(java.util.Locale.ROOT).contains("blocking"))) return false;
            }
        }
        return combat.getAttackers().stream().allMatch(a -> simple(a) && !a.isCommander()
                && combat.getDefenderByAttacker(a) == defender);
    }

    private static boolean simple(Card c) {
        return !c.isWitherDamage() && !c.toughnessAssignsDamage()
                && c.getNetCombatDamage() == c.getNetPower()
                && c.getKeywords().stream().allMatch(k -> SIMPLE.contains(k.getKeyword()))
                && !c.hasSVar("DestroyWhenDamaged") && !c.hasSVar("MustBeBlocked");
    }

    static TacticalConsequences project(Player defender, Combat original,
            List<ExternalCombatBlockers.Assignment> assignments) {
        if (!supported(defender, original)) return null;
        Combat candidate = new Combat(original.getAttackingPlayer());
        original.getAttackersAndDefenders().forEach(candidate::addAttacker);
        Set<Card> assignedBlockers = new HashSet<>(), assignedAttackers = new HashSet<>();
        for (var a : assignments) {
            // Multi-block/multi-attacker damage order is not inferred by pairwise helpers.
            // Preserve the legal option but omit its projection entirely.
            if (!simple(a.blocker()) || !assignedBlockers.add(a.blocker())
                    || !assignedAttackers.add(a.attacker())) return null;
            candidate.addBlocker(a.attacker(), a.blocker());
        }
        int baselineDamage = 0, damage = 0, selfLosses = 0, opponentLosses = 0;
        for (Card attacker : candidate.getAttackers()) {
            int unblocked = ComputerUtilCombat.damageIfUnblocked(attacker, defender, candidate, true);
            baselineDamage += unblocked;
            List<Card> blockers = candidate.getBlockers(attacker);
            if (blockers.isEmpty()) { damage += unblocked; continue; }
            Card blocker = blockers.get(0);
            boolean blockerDies = ComputerUtilCombat.canDestroyBlocker(defender, blocker, attacker, candidate, true);
            boolean attackerDies = ComputerUtilCombat.canDestroyAttacker(defender, attacker, blocker, candidate, true);
            if (blockerDies) selfLosses++;
            if (attackerDies) opponentLosses++;
            if (attacker.hasKeyword(Keyword.TRAMPLE)
                    && !(blocker.hasFirstStrike() && !attacker.hasFirstStrike() && attackerDies)) {
                int available = Math.max(0, attacker.getNetCombatDamage());
                // Forge's lethal requirement handles marked damage and deathtouch.
                int lethal = ComputerUtilCombat.getEnoughDamageToKill(blocker, available, attacker, true);
                damage += ComputerUtilCombat.predictDamageTo(defender, Math.max(0, available - lethal), attacker, true);
            }
        }
        int baselineLife = defender.getLife() - baselineDamage;
        int life = defender.getLife() - damage;
        int prevented = Math.max(0, baselineDamage - damage);
        boolean lethalProtection = baselineLife <= 0 && life > 0;
        // Relative danger: after taking the hit, another hit of the same visible size
        // would be lethal. This is a conservative buffer, not a forecast of next turn.
        boolean nearProtection = baselineLife > 0 && baselineLife <= baselineDamage && prevented > 0;
        boolean chump = selfLosses > 0 && opponentLosses == 0;
        boolean unnecessary = chump && !lethalProtection && !nearProtection
                && baselineLife > baselineDamage && (long)prevented * 2 < baselineLife;
        return new TacticalConsequences("Current visible combat only; assumes no further spells or activated abilities. "
                + "Creature losses describe combat damage, not future card value.", selfLosses, opponentLosses,
                prevented, life, lethalProtection, nearProtection, chump && !lethalProtection && !nearProtection,
                chump, selfLosses == 0 && life > 0, unnecessary);
    }
}
