package forge.ai;

import forge.ai.decision.AiDecisionType;
import forge.game.ability.ApiType;
import forge.game.cost.CostPartMana;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;

import java.util.Map;
import java.util.Set;

/** External capability registry, independent of native deck warnings and heuristic support.
 * A reviewed ability is keyed by stable script properties, never its runtime object/index.
 * Shape checks alone cannot certify all cards with triggers, keywords or alternate costs.
 */
final class ExternalAiAbilitySupport {
    enum Status { DEFAULT, VERIFIED_SAFE, UNSUPPORTED }
    private record Key(String source, ApiType api, AiDecisionType surface) { }
    private record VerifiedAbility(Map<String, String> template, String manaCost, String scriptCost, String verification) { }
    private static final Map<Key, VerifiedAbility> VERIFIED = Map.of(
            new Key("Earthbrawn", ApiType.Pump, AiDecisionType.COMBAT_ACTION), new VerifiedAbility(Map.of(
                    "SP", "Pump", "ValidTgts", "Creature", "NumAtt", "+3", "NumDef", "+3",
                    "SpellDescription", "Target creature gets +3/+3 until end of turn."), "{1}{G}", "1 G", "Stage 16.1 ordinary pump; Reinforce excluded"),
            new Key("Resounding Roar", ApiType.Pump, AiDecisionType.COMBAT_ACTION), new VerifiedAbility(Map.of(
                    "SP", "Pump", "ValidTgts", "Creature", "NumAtt", "+3", "NumDef", "+3",
                    "SpellDescription", "Target creature gets +3/+3 until end of turn."), "{1}{G}", "1 G", "Stage 16.2 ordinary pump; cycling and cycling trigger excluded"),
            new Key("Righteousness", ApiType.Pump, AiDecisionType.COMBAT_ACTION), new VerifiedAbility(Map.of(
                    "SP", "Pump", "ValidTgts", "Creature.blocking", "NumAtt", "+7", "NumDef", "+7",
                    "TgtPrompt", "Select target blocking creature", "SpellDescription", "Target blocking creature gets +7/+7 until end of turn."),
                    "{W}", "W", "Stage 16.2 blocking-creature survival pump"),
            new Key("Hundred-Talon Strike", ApiType.Pump, AiDecisionType.COMBAT_ACTION), new VerifiedAbility(Map.of(
                    "SP", "Pump", "ValidTgts", "Creature", "NumAtt", "+1", "KW", "First Strike",
                    "SpellDescription", "Target creature gets +1/+0 and gains first strike until end of turn."),
                    "{W}", "W", "Stage 16.2 ordinary pump/first strike; splice excluded"),
            new Key("Stifle", ApiType.Counter, AiDecisionType.STACK_RESPONSE), new VerifiedAbility(Map.of(
                    "SP", "Counter", "TgtPrompt", "Choose target ability", "ValidTgts", "Card,Emblem",
                    "TargetType", "Activated,Triggered", "SpellDescription", "Counter target activated or triggered ability."),
                    "{U}", "U", "Stage 16.3 activated targets only; triggers excluded"),
            new Key("Rust", ApiType.Counter, AiDecisionType.STACK_RESPONSE), new VerifiedAbility(Map.of(
                    "SP", "Counter", "TargetType", "Activated", "TgtPrompt", "Select target ability", "ValidTgts", "Artifact",
                    "SpellDescription", "Counter target activated ability from an artifact source."),
                    "{G}", "G", "Stage 16.3 artifact activated targets only"),
            new Key("Disallow", ApiType.Counter, AiDecisionType.STACK_RESPONSE), new VerifiedAbility(Map.of(
                    "SP", "Counter", "TargetType", "Spell,Activated,Triggered", "TgtPrompt", "Select target spell or ability",
                    "ValidTgts", "Card,Emblem", "SpellDescription", "Counter target spell, activated ability, or triggered ability."),
                    "{1}{U}{U}", "1 U U", "Stage 16.3 activated targets only; existing spell path separate"));

    private ExternalAiAbilitySupport() { }

    /** A candidate screen, never automatic verification or a provider eligibility grant. */
    static boolean clusterEligible(SpellAbility ability) {
        if (ability == null || ability.getHostCard() == null || !ability.isSpell()
                || ability.getRootAbility() != ability || !ability.getHostCard().isInstant()
                || ability.getApi() == null || !Set.of(ApiType.Pump, ApiType.Protection, ApiType.PreventDamage).contains(ability.getApi())
                || ability.getSubAbility() != null || ability.isTrigger() || ability.isReplacementAbility()
                || ability.getAlternativeCost() != null || ability.getOptionalCosts().iterator().hasNext()
                || !ability.getOptionalKeywords().isEmpty() || ability.getXManaCostPaid() != null
                || ability.getSplicedCards() != null && !ability.getSplicedCards().isEmpty()
                || ability.getPayCosts() == null || ability.getPayCosts().getCostMana() == null
                || ability.getPayCosts().getCostMana().getAmountOfX() != 0
                || ability.getPayCosts().getCostParts().stream().anyMatch(p -> !(p instanceof CostPartMana))
                || ability.getHostCard().getStaticAbilities().stream().anyMatch(s -> "AlternativeCost".equals(s.getParam("Mode")))
                || !ability.usesTargeting() || ability.getTargetRestrictions().getMinTargets(ability.getHostCard(), ability) != 1
                || ability.getTargetRestrictions().getMaxTargets(ability.getHostCard(), ability) != 1) return false;
        String targets = ability.getParamOrDefault("ValidTgts", "");
        if (!targets.matches("Creature(?:\\.[A-Za-z0-9+]+)?")) return false;
        var params = ability.getMapParams();
        if (!Set.of("SP", "ValidTgts", "NumAtt", "NumDef", "KW", "Gains", "Amount", "IsCurse",
                "SpellDescription", "Cost", "TgtPrompt").containsAll(params.keySet())) return false;
        for (String amount : Set.of("NumAtt", "NumDef", "Amount"))
            if (params.containsKey(amount) && !params.get(amount).matches("[+-]?\\d+")) return false;
        if (params.containsKey("KW") && !Set.of("First Strike", "Indestructible", "Hexproof", "Shroud",
                "Flying", "Trample", "Reach", "Vigilance", "Lifelink").contains(params.get("KW"))) return false;
        if (params.containsKey("Gains") && (params.get("Gains").contains("Choice") || params.get("Gains").contains("Chosen"))) return false;
        return true;
    }

    /** Candidate admission requires an exact reviewed template; it does not certify targets. */
    static boolean verifiedCounterTemplate(SpellAbility ability) {
        return matchesVerified(ability, AiDecisionType.STACK_RESPONSE);
    }

    /** Ordinary spell counters keep their existing path. Ability targets require positive proof. */
    static boolean stackResponseAllowed(SpellAbility ability) {
        if (ability == null) return false;
        if (ability.getApi() != ApiType.Counter) return true;
        boolean declaresAbilities = ability.hasParam("TargetType") &&
                (ability.getParam("TargetType").contains("Activated") || ability.getParam("TargetType").contains("Triggered"));
        var target = ability.getTargets().getFirstTargetedSpell();
        boolean onlySpells = target != null;
        for (SpellAbility chosen : ability.getTargets().getTargetSpells())
            if (StackObjectType.classify(chosen) != StackObjectType.SPELL) onlySpells = false;
        if (onlySpells) return ability.canTargetSpellAbility(target);
        if (!declaresAbilities && target == null) return true;
        return verifiedCounterTemplate(ability) && verifiedActivatedTarget(ability);
    }

    static boolean verifiedActivatedTarget(SpellAbility ability) {
        if (ability.getTargets().size() != 1) return false;
        var target = ability.getTargets().getFirstTargetedSpell();
        if (StackObjectType.classify(target) != StackObjectType.ACTIVATED_ABILITY
                || target.getActivatingPlayer() == null || target.getHostCard() == null
                || target.getHostCard().getGame() != ability.getHostCard().getGame()
                || ability.getActivatingPlayer() == null
                || !target.getHostCard().getView().canBeShownTo(ability.getActivatingPlayer().getView())
                || !target.isCounterableBy(ability) || !ability.canTargetSpellAbility(target)) return false;
        var game = ability.getHostCard().getGame();
        var instance = game.getStack().getInstanceMatchingSpellAbilityID(target);
        if (instance == null || instance.getSpellAbility() != target) return false;
        // Conservative bypass: do not run replacement callbacks while preparing a request.
        return game.getCardsInGame().stream().noneMatch(card -> card.getReplacementEffects().stream()
                .anyMatch(r -> r.getMode() == ReplacementType.Counter));
    }

    static Status status(SpellAbility ability, AiDecisionType surface) {
        if (ability == null || ability.getHostCard() == null) return Status.UNSUPPORTED;
        if (!ComputerUtilCard.isCardRemAIDeck(ability.getHostCard())) return Status.DEFAULT;
        return matchesVerified(ability, surface) && (ability.getApi() != ApiType.Counter || verifiedActivatedTarget(ability))
                ? Status.VERIFIED_SAFE : Status.UNSUPPORTED;
    }

    private static boolean matchesVerified(SpellAbility ability, AiDecisionType surface) {
        if (ability == null || ability.getHostCard() == null) return false;
        VerifiedAbility verified = VERIFIED.get(new Key(ability.getHostCard().getName(), ability.getApi(), surface));
        if (verified == null || ability.getApi() != ApiType.Counter && !clusterEligible(ability)
                || !ability.isSpell() || ability.getRootAbility() != ability || !ability.getHostCard().isInstant()
                || ability.getSubAbility() != null || ability.isTrigger() || ability.isReplacementAbility()
                || ability.getAlternativeCost() != null || ability.getOptionalCosts().iterator().hasNext()
                || !ability.getOptionalKeywords().isEmpty() || ability.getXManaCostPaid() != null
                || ability.getChosenList() != null && !ability.getChosenList().isEmpty()
                || ability.getSplicedCards() != null && !ability.getSplicedCards().isEmpty()
                || ability.getPayCosts() == null || ability.getPayCosts().getCostMana() == null
                || ability.getPayCosts().getCostMana().getAmountOfX() != 0
                || !verified.manaCost().equals(ability.getPayCosts().getCostMana().getMana().toString())
                || ability.getPayCosts().getCostParts().stream().anyMatch(p -> !(p instanceof CostPartMana))
                || ability.getHostCard().getStaticAbilities().stream().anyMatch(st -> "AlternativeCost".equals(st.getParam("Mode")))
                || !ability.usesTargeting() || ability.getTargetRestrictions().getMinTargets(ability.getHostCard(), ability) != 1
                || ability.getTargetRestrictions().getMaxTargets(ability.getHostCard(), ability) != 1) return false;
        Map<String, String> params = ability.getMapParams();
        if (!verified.template().entrySet().stream().allMatch(e -> e.getValue().equals(params.get(e.getKey())))
                || params.keySet().stream().anyMatch(key -> !key.equals("Cost") && !verified.template().containsKey(key))
                || params.containsKey("Cost") && !verified.scriptCost().equals(params.get("Cost"))) return false;
        return true;
    }

    static boolean allowed(SpellAbility ability, AiDecisionType surface) {
        return status(ability, surface) != Status.UNSUPPORTED;
    }
}
