package forge.ai;

import forge.game.spellability.SpellAbility;

/** Engine classification, never inferred from a displayed ability description. */
public enum StackObjectType {
    SPELL, ACTIVATED_ABILITY, TRIGGERED_ABILITY, OTHER;

    public static StackObjectType classify(SpellAbility ability) {
        if (ability == null || ability.isReplacementAbility() || ability.isManaAbility()) return OTHER;
        if (ability.isTrigger()) return TRIGGERED_ABILITY;
        if (ability.isSpell()) return SPELL;
        if (ability.isActivatedAbility() && !ability.isWrapper()) return ACTIVATED_ABILITY;
        return OTHER;
    }
}
