package forge.ai;

import com.google.gson.Gson;
import forge.ai.decision.*;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class Stage16PumpClusterRecoveryTest extends AITest {
    record Fixture(Game game, Player ai, Player enemy, Combat combat, Card blocker, Card source) { }
    @BeforeMethod public void reset() { AiDecisionMetrics.reset(); }
    @DataProvider public Object[][] recovered() { return new Object[][] {
        {"Earthbrawn", 5, 5}, {"Resounding Roar", 5, 5}, {"Righteousness", 9, 9}, {"Hundred-Talon Strike", 3, 2} }; }
    @DataProvider public Object[][] decisions() {
        List<Object[]> rows = new ArrayList<>();
        for (Object[] card : recovered()) for (String choice : List.of("ACTION", "PASS", "FAILURE", "STALE"))
            rows.add(new Object[] {card[0], card[1], card[2], choice});
        return rows.toArray(Object[][]::new);
    }
    private Fixture fixture(String name) {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1), enemy = game.getPlayers().get(0);
        var attacker = addCard("Hill Giant", enemy); var blocker = addCard("Runeclaw Bear", ai);
        attacker.setSickness(false); blocker.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, enemy, false);
        Combat combat = new Combat(enemy); game.getPhaseHandler().setCombat(combat);
        combat.addAttacker(attacker, ai); combat.addBlocker(attacker, blocker); combat.setBlocked(attacker, true);
        game.getPhaseHandler().setPriority(ai); addCards("Forest", 2, ai); addCards("Plains", 2, ai);
        return new Fixture(game, ai, enemy, combat, blocker, addCardToZone(name, ai, ZoneType.Hand));
    }
    private PlayerControllerAi controller(Fixture f, AiDecisionProvider provider) {
        var c = new PlayerControllerAi(f.game(), f.ai(), f.ai().getLobbyPlayer(), provider, false, false, 3);
        f.ai().dangerouslySetController(c); c.getAi().configureExternalCombatActions(true, 4); return c;
    }
    @Test(dataProvider="decisions") public void eachEntryRoutesExecutesPassesAndFallsBack(String name, int power, int toughness, String choice) {
        Fixture f = fixture(name); addCardToZone("Black Lotus", f.enemy(), ZoneType.Hand);
        var c = controller(f, raw -> {
            var ctx = (CombatActionDecisionContext) raw;
            Assert.assertEquals(ctx.options().size(), 1); Assert.assertTrue(ctx.passAvailable());
            var option = ctx.options().get(0);
            Assert.assertEquals(option.sourceName(), name); Assert.assertTrue(option.optionId().startsWith("OPT_"));
            Assert.assertEquals(option.targets().size(), 1); Assert.assertTrue(option.targets().get(0).contains("Runeclaw Bear"));
            String json = new Gson().toJson(raw);
            for (String excluded : List.of("Black Lotus", "VERIFIED_SAFE", "Reinforce", "Cycling", "Splice")) Assert.assertFalse(json.contains(excluded));
            if (choice.equals("FAILURE")) return Optional.empty();
            if (choice.equals("STALE")) f.game().getAction().moveToGraveyard(f.blocker(), null);
            return Optional.of(new AiDecision(ctx.decisionId(), ctx.stateFingerprint(), choice.equals("PASS") ? "PASS" : option.optionId()));
        });
        var selected = c.chooseSpellAbilityToPlay();
        var event = AiDecisionMetrics.combatActionEvents().get(0);
        if (choice.equals("ACTION")) {
            Assert.assertNotNull(selected); Assert.assertTrue(selected.get(0).isSpell());
            Assert.assertTrue(selected.get(0).getTargets().contains(f.blocker()));
            c.playChosenSpellAbility(selected.get(0)); Assert.assertFalse(f.game().getStack().isEmpty());
            f.game().getStack().resolveStack();
            Assert.assertEquals(f.blocker().getNetPower(), power); Assert.assertEquals(f.blocker().getNetToughness(), toughness);
            Assert.assertTrue(f.game().getCardsIn(ZoneType.Graveyard).stream().anyMatch(card -> card.getName().equals(name)));
            if (name.equals("Hundred-Talon Strike")) {
                Assert.assertTrue(f.blocker().hasKeyword(Keyword.FIRST_STRIKE));
                Assert.assertTrue(ExternalCombatActions.state(f.ai(), f.combat(), List.of()).combatants().stream()
                        .anyMatch(p -> p.name().equals("Runeclaw Bear") && p.keywords().contains("First Strike")));
            }
            Assert.assertFalse(event.fallback());
        } else {
            Assert.assertNull(selected); Assert.assertEquals(f.source().getZone().getZoneType(), ZoneType.Hand);
            Assert.assertEquals(event.fallback(), !choice.equals("PASS"));
            if (choice.equals("PASS")) Assert.assertTrue(event.passSelection());
            if (choice.equals("STALE")) Assert.assertTrue(event.staleOrRevalidationFailure());
        }
    }
    @Test(dataProvider="recovered") public void warningsSiblingsAndSurfaceRemainIndependent(String name, int power, int toughness) {
        Fixture f = fixture(name); var ordinary = f.source().getFirstSpellAbility();
        Assert.assertTrue(ExternalAiAbilitySupport.clusterEligible(ordinary));
        Assert.assertEquals(ExternalAiAbilitySupport.status(ordinary, AiDecisionType.COMBAT_ACTION), ExternalAiAbilitySupport.Status.VERIFIED_SAFE);
        for (var surface : AiDecisionType.values()) if (surface != AiDecisionType.COMBAT_ACTION)
            Assert.assertEquals(ExternalAiAbilitySupport.status(ordinary, surface), ExternalAiAbilitySupport.Status.UNSUPPORTED);
        for (var ability : f.source().getSpellAbilities()) if (!ability.isSpell()) {
            Assert.assertEquals(ExternalAiAbilitySupport.status(ability, AiDecisionType.COMBAT_ACTION), ExternalAiAbilitySupport.Status.UNSUPPORTED);
            Assert.assertFalse(ExternalCombatActions.safeTemplate(ability));
        }
        Assert.assertTrue(f.source().getTriggers().stream().allMatch(t -> !ExternalAiAbilitySupport.allowed(t.ensureAbility(), AiDecisionType.COMBAT_ACTION)));
        Deck deck = new Deck("cluster warning"); deck.getMain().add(FModel.getMagicDb().getCommonCards().getCard(name), 1);
        Assert.assertEquals(deck.getUnplayableAICards().inMainDeck, 1);
    }
    @DataProvider public Object[][] unsupported() { return new Object[][] {
        {"Dizzy Spell"}, {"Blazing Shoal"}, {"Ivory Charm"}, {"Triton Tactics"}, {"Rouse"},
        {"Bathe in Light"}, {"Indestructible Aura"}, {"Wojek Siren"}, {"Undying Evil"} }; }
    @Test(dataProvider="unsupported") public void structuralScreenNeverGrantsUnverifiedSupport(String name) {
        Fixture f = fixture(name); var ability = f.source().getFirstSpellAbility();
        Assert.assertEquals(ExternalAiAbilitySupport.status(ability, AiDecisionType.COMBAT_ACTION), ExternalAiAbilitySupport.Status.UNSUPPORTED);
        if (name.equals("Dizzy Spell")) Assert.assertTrue(ExternalAiAbilitySupport.clusterEligible(ability));
        else Assert.assertFalse(ExternalAiAbilitySupport.clusterEligible(ability));
    }
    @Test public void existingIndestructibleProtectionControlStillExecutes() {
        Fixture f = fixture("Withstand Death");
        Assert.assertTrue(ExternalAiAbilitySupport.clusterEligible(f.source().getFirstSpellAbility()));
        Assert.assertEquals(ExternalAiAbilitySupport.status(f.source().getFirstSpellAbility(), AiDecisionType.COMBAT_ACTION), ExternalAiAbilitySupport.Status.DEFAULT);
        var c = controller(f, raw -> { var ctx = (CombatActionDecisionContext) raw;
            return Optional.of(new AiDecision(ctx.decisionId(), ctx.stateFingerprint(), ctx.options().get(0).optionId())); });
        c.playChosenSpellAbility(c.chooseSpellAbilityToPlay().get(0)); f.game().getStack().resolveStack();
        Assert.assertTrue(f.blocker().hasKeyword(Keyword.INDESTRUCTIBLE));
    }
    @Test public void firstStrikeReachesLaterBlockerAndDamageOrderRequests() {
        // Resolve in the native supported post-block window, then project the changed
        // creature into synthetic later blocker/order requests in the same turn.
        Fixture f = fixture("Hundred-Talon Strike");
        Game game = f.game(); Player ai = f.ai(), enemy = f.enemy();
        Card source = f.blocker(), first = addCard("Hill Giant", enemy), second = addCard("Grizzly Bears", enemy);
        source.setSickness(false); game.getRules().setOrderCombatants(true);
        var c = controller(f, raw -> { var ctx = (CombatActionDecisionContext) raw;
            return Optional.of(new AiDecision(ctx.decisionId(), ctx.stateFingerprint(), ctx.options().get(0).optionId())); });
        c.playChosenSpellAbility(c.chooseSpellAbilityToPlay().get(0)); game.getStack().resolveStack();
        Assert.assertTrue(source.hasKeyword(Keyword.FIRST_STRIKE));
        Combat combat = new Combat(ai); game.getPhaseHandler().setCombat(combat); combat.addAttacker(source, enemy);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, ai, false);
        var blockers = ExternalCombatBlockers.prepare(enemy, combat, "later-blockers", 4);
        Assert.assertNotNull(blockers); Assert.assertTrue(new Gson().toJson(blockers.context()).toLowerCase(Locale.ROOT).contains("first strike"));
        combat.addBlocker(source, first); combat.addBlocker(source, second); combat.setBlocked(source, true);
        var order = ExternalCombatDamageOrder.prepare(ai, combat, source, List.of(first, second), true, "later-damage", 4);
        Assert.assertNotNull(order); Assert.assertTrue(order.context().state().source().keywords().contains("first strike"));
    }
    @Test public void splicedOrdinarySpellIsNotTheVerifiedEntry() {
        Fixture f = fixture("Hundred-Talon Strike");
        var variant = f.source().getFirstSpellAbility().copy();
        variant.addSplicedCards(f.source());
        Assert.assertFalse(ExternalAiAbilitySupport.clusterEligible(variant));
        Assert.assertEquals(ExternalAiAbilitySupport.status(variant, AiDecisionType.COMBAT_ACTION), ExternalAiAbilitySupport.Status.UNSUPPORTED);
        Assert.assertFalse(ExternalCombatActions.safeTemplate(variant));
    }
}
