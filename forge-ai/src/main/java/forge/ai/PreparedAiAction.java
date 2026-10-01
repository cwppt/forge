package forge.ai;

import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

/**
 * A request-local, fully evaluated action produced by the traditional Forge AI.
 * This is deliberately engine-internal and is not an external-provider DTO.
 */
record PreparedAiAction(
        String actionId,
        SpellAbility spellAbility,
        SpellAbility originalAbility,
        Card sourceCard,
        ActionCategory category,
        int originalCandidateIndex,
        String description) {

    enum ActionCategory {
        SPELL,
        ACTIVATED_ABILITY,
        TRIGGER,
        OTHER
    }
}
