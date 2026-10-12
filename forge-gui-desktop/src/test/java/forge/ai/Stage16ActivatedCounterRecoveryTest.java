package forge.ai;

import com.google.gson.Gson;
import forge.ai.decision.*;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Stage16ActivatedCounterRecoveryTest extends AITest {
    record Fixture(Game game, Player ai, Player enemy, Card counter, Card rod, SpellAbility pending) { }
    @BeforeMethod public void reset() { AiDecisionMetrics.reset(); }
    @DataProvider public Object[][] cards() { return new Object[][] {{"Stifle"}, {"Rust"}, {"Disallow"}}; }
    @DataProvider public Object[][] choices() {
        List<Object[]> rows = new ArrayList<>();
        for (Object[] c : cards()) for (String choice : List.of("ACTION", "PASS", "FAILURE", "STALE", "TYPE", "COST", "ID", "FINGERPRINT", "UNKNOWN"))
            rows.add(new Object[] {c[0], choice});
        return rows.toArray(Object[][]::new);
    }
    Fixture fixture(String name) {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1), enemy = game.getPlayers().get(0);
        ai.setLife(1, null); game.getPhaseHandler().devModeSet(PhaseType.MAIN1, enemy, false);
        addCards("Island", 3, ai); addCard("Forest", ai); addCards("Mountain", 3, enemy);
        Card counter = addCardToZone(name, ai, ZoneType.Hand), rod = addCard("Rod of Ruin", enemy);
        rod.setSickness(false); SpellAbility pending = rod.getSpellAbilities().stream().filter(SpellAbility::isActivatedAbility).findFirst().orElseThrow();
        pending.setActivatingPlayer(enemy); pending.getTargets().add(ai); game.getPhaseHandler().setPriority(enemy);
        if (!ComputerUtil.playStack(pending, enemy, game)) throw new IllegalStateException("Activated ability must actually pay its cost");
        pending = game.getStack().peekAbility();
        if (!rod.isTapped()) throw new IllegalStateException("Activated tap cost not paid");
        game.getPhaseHandler().setPriority(ai);
        addCardToZone("Black Lotus", enemy, ZoneType.Hand);
        return new Fixture(game, ai, enemy, counter, rod, pending);
    }
    PlayerControllerAi controller(Fixture f, AiDecisionProvider provider, boolean enabled) {
        var c = new PlayerControllerAi(f.game(), f.ai(), f.ai().getLobbyPlayer(), provider, false, false, 3, false, 1, enabled, 4);
        f.ai().dangerouslySetController(c); return c;
    }
    @Test(dataProvider="choices") public void exactTargetExecutionPassAndFallback(String name, String choice) {
        Fixture f = fixture(name);
        var c = controller(f, raw -> {
            var ctx = (StackResponseDecisionContext) raw;
            Assert.assertEquals(ctx.legalActions().size(), 1);
            Assert.assertEquals(ctx.stackItems().get(0).objectType(), "ACTIVATED_ABILITY");
            Assert.assertEquals(ctx.stackItems().get(0).sourceName(), "Rod of Ruin");
            Assert.assertFalse(new Gson().toJson(ctx).contains("Black Lotus"));
            Assert.assertFalse(new Gson().toJson(ctx).contains("VERIFIED_SAFE"));
            Assert.assertTrue(ctx.legalActions().get(0).actionId().startsWith("OPT_"));
            if (choice.equals("FAILURE")) return Optional.empty();
            if (choice.equals("STALE")) f.game().getStack().remove(f.game().getStack().getInstanceMatchingSpellAbilityID(f.pending()));
            if (choice.equals("TYPE")) f.pending().setTrigger(addCard("Soul Warden", f.enemy()).getTriggers().get(0));
            if (choice.equals("COST")) for (var land : f.ai().getLandsInPlay()) land.setTapped(true);
            return Optional.of(new AiDecision(choice.equals("ID") ? "wrong" : ctx.decisionId(),
                    choice.equals("FINGERPRINT") ? "wrong" : ctx.stateFingerprint(),
                    choice.equals("PASS") ? "PASS" : choice.equals("UNKNOWN") ? "OPT_unknown" : ctx.legalActions().get(0).actionId()));
        }, true);
        var selected = c.chooseSpellAbilityToPlay(); var event = AiDecisionMetrics.stackResponseEvents().get(0);
        if (choice.equals("PASS")) { Assert.assertNull(selected); Assert.assertEquals(event.selectedResponseId(), "PASS"); Assert.assertFalse(event.fallback()); }
        else if (choice.equals("ACTION")) {
            Assert.assertNotNull(selected); var action = selected.get(0);
            Assert.assertTrue(action.getTargets().getFirstTargetedSpell() == f.pending());
            Assert.assertEquals(ExternalAiAbilitySupport.status(action, AiDecisionType.STACK_RESPONSE), ExternalAiAbilitySupport.Status.VERIFIED_SAFE);
            Assert.assertTrue(ComputerUtil.playStack(action, f.ai(), f.game())); f.game().getStack().resolveStack();
            Assert.assertNull(f.game().getStack().getInstanceMatchingSpellAbilityID(f.pending()));
            Assert.assertEquals(f.ai().getLife(), 1); Assert.assertEquals(f.rod().getZone().getZoneType(), ZoneType.Battlefield);
            Assert.assertTrue(f.game().getCardsIn(ZoneType.Graveyard).stream().anyMatch(card -> card.getName().equals(name)));
            Assert.assertFalse(event.fallback());
        } else { Assert.assertTrue(event.fallback()); Assert.assertEquals(event.selectedResponseId(), event.heuristicResponseId()); }
    }
    SpellAbility trigger(Fixture f) {
        Card warden = addCard("Soul Warden", f.enemy()); var trigger = warden.getTriggers().get(0);
        SpellAbility sa = trigger.ensureAbility(); sa.setTrigger(trigger); sa.setActivatingPlayer(f.enemy());
        f.game().getStack().add(sa); return f.game().getStack().peekAbility();
    }
    SpellAbility spell(Fixture f) {
        Card card = addCardToZone("Divination", f.enemy(), ZoneType.Hand); SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(f.enemy()); f.game().getStackZone().add(card); f.game().getStack().add(sa); return f.game().getStack().peekAbility();
    }
    @Test(dataProvider="cards") public void triggeredTargetNeverHasExternalMapping(String name) {
        Fixture f = fixture(name); SpellAbility trigger = trigger(f);
        Assert.assertEquals(StackObjectType.classify(trigger), StackObjectType.TRIGGERED_ABILITY);
        var c = controller(f, raw -> { Assert.fail("Trigger top must not route"); return Optional.empty(); }, true);
        Assert.assertTrue(c.getAi().prepareStackResponseActions(List.of(f.counter().getFirstSpellAbility()), 4).actions().isEmpty());
        c.chooseSpellAbilityToPlay(); Assert.assertTrue(AiDecisionMetrics.stackResponseEvents().isEmpty());
        var targeting = f.counter().getFirstSpellAbility().copy(); targeting.setActivatingPlayer(f.ai()); targeting.getTargets().add(trigger);
        Assert.assertFalse(ExternalAiAbilitySupport.stackResponseAllowed(targeting));
        Assert.assertEquals(ExternalAiAbilitySupport.status(targeting, AiDecisionType.STACK_RESPONSE), ExternalAiAbilitySupport.Status.UNSUPPORTED);
    }
    @Test(dataProvider="cards") public void ordinarySpellIsOnlyAllowedThroughExistingSpellRules(String name) {
        Fixture f = fixture(name); var pending = spell(f); var c = controller(f, raw -> Optional.empty(), true);
        var set = c.getAi().prepareStackResponseActions(List.of(f.counter().getFirstSpellAbility()), 4);
        var prepared = f.counter().getFirstSpellAbility().copy(); prepared.setActivatingPlayer(f.ai()); prepared.getTargets().add(pending);
        Assert.assertEquals(prepared.canTargetSpellAbility(pending), name.equals("Disallow"));
        Assert.assertEquals(ExternalAiAbilitySupport.stackResponseAllowed(prepared), name.equals("Disallow"));
        if (!name.equals("Disallow")) Assert.assertTrue(set.actions().isEmpty());
        for (var action : set.actions()) Assert.assertTrue(action.spellAbility().getTargets().contains(pending));
    }
    @Test(dataProvider="cards") public void mixedStackKeepsExactActivatedTarget(String name) {
        Fixture f = fixture(name); f.game().getStack().remove(f.game().getStack().getInstanceMatchingSpellAbilityID(f.pending()));
        spell(f); trigger(f); f.game().getStack().add(f.pending());
        SpellAbility mixedTarget = f.game().getStack().peekAbility();
        var c = controller(f, raw -> { var ctx = (StackResponseDecisionContext) raw;
            Assert.assertEquals(ctx.stackItems().stream().map(StackItemView::objectType).collect(java.util.stream.Collectors.toSet()),
                    Set.of("SPELL", "ACTIVATED_ABILITY", "TRIGGERED_ABILITY"));
            return Optional.of(new AiDecision(ctx.decisionId(), ctx.stateFingerprint(), ctx.legalActions().get(0).actionId())); }, true);
        var selected = c.chooseSpellAbilityToPlay().get(0);
        Assert.assertTrue(selected.getTargets().getFirstTargetedSpell() == mixedTarget);
        Assert.assertTrue(ExternalAiAbilitySupport.stackResponseAllowed(selected));
    }
    @Test(dataProvider="cards") public void warningAndSurfaceRemainIndependent(String name) {
        Fixture f = fixture(name); var sa = f.counter().getFirstSpellAbility().copy(); sa.setActivatingPlayer(f.ai()); sa.getTargets().add(f.pending());
        Assert.assertTrue(ExternalAiAbilitySupport.verifiedCounterTemplate(sa));
        Assert.assertEquals(ExternalAiAbilitySupport.status(sa, AiDecisionType.COMBAT_ACTION), ExternalAiAbilitySupport.Status.UNSUPPORTED);
        Deck deck = new Deck("warned"); deck.getMain().add(FModel.getMagicDb().getCommonCards().getCard(name), 1);
        Assert.assertEquals(deck.getUnplayableAICards().inMainDeck, 1);
        sa.getMapParams().put("UnlessCost", "1"); Assert.assertFalse(ExternalAiAbilitySupport.stackResponseAllowed(sa));
    }
    @Test public void disabledProviderPathRemainsNative() {
        Fixture f = fixture("Stifle"); AtomicInteger calls = new AtomicInteger();
        var c = controller(f, raw -> { calls.incrementAndGet(); return Optional.empty(); }, false);
        c.chooseSpellAbilityToPlay(); Assert.assertEquals(calls.get(), 0);
    }
    @Test public void unverifiedComplexCountersRemainDenied() {
        Fixture f = fixture("Stifle");
        for (String name : List.of("Trickbind", "Interdict", "Repudiate")) {
            var card = addCardToZone(name, f.ai(), ZoneType.Hand); var sa = card.getFirstSpellAbility();
            sa.setActivatingPlayer(f.ai()); sa.getTargets().add(f.pending());
            Assert.assertFalse(ExternalAiAbilitySupport.verifiedCounterTemplate(sa));
            Assert.assertFalse(ExternalAiAbilitySupport.stackResponseAllowed(sa));
        }
        Assert.assertEquals(StackObjectType.classify(null), StackObjectType.OTHER);
    }
    @Test public void missingStackObjectAndCounterReplacementsFailClosed() {
        Fixture f = fixture("Stifle"); var sa = f.counter().getFirstSpellAbility().copy();
        sa.setActivatingPlayer(f.ai()); sa.getTargets().add(f.pending());
        Assert.assertTrue(ExternalAiAbilitySupport.stackResponseAllowed(sa));
        var instance = f.game().getStack().getInstanceMatchingSpellAbilityID(f.pending());
        f.game().getStack().remove(instance);
        Assert.assertFalse(ExternalAiAbilitySupport.stackResponseAllowed(sa));
        f.game().getStack().add(f.pending());
        Assert.assertFalse(ExternalAiAbilitySupport.stackResponseAllowed(sa), "Reactivation copy must not reuse the old mapping");
        sa.resetTargets(); sa.getTargets().add(f.game().getStack().peekAbility());
        Assert.assertTrue(ExternalAiAbilitySupport.stackResponseAllowed(sa));
        addCard("Carnage Tyrant", f.enemy());
        Assert.assertFalse(ExternalAiAbilitySupport.stackResponseAllowed(sa), "Counter replacements conservatively bypass recovery");
    }

    @Test public void mixedTargetsCannotUseSpellCounterBypass() {
        Fixture f = fixture("Disallow"); SpellAbility spell = spell(f), trigger = trigger(f);
        var sa = f.counter().getFirstSpellAbility().copy(); sa.setActivatingPlayer(f.ai());
        sa.getTargets().add(spell); sa.getTargets().add(trigger);
        Assert.assertFalse(ExternalAiAbilitySupport.stackResponseAllowed(sa));
        sa.getMapParams().remove("TargetType");
        Assert.assertFalse(ExternalAiAbilitySupport.stackResponseAllowed(sa), "Missing type declaration must not bypass mixed-target rejection");
    }

}
