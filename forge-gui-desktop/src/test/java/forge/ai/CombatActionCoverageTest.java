package forge.ai;

import forge.ai.decision.*;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Uses the real priority seam, copied preparation, payment and stack execution; no Ollama. */
public class CombatActionCoverageTest extends AITest {
    record Fixture(Game game, Player ai, Player enemy, Combat combat, Card attacker, Card blocker) { }
    @BeforeMethod public void resetMetrics() { AiDecisionMetrics.reset(); }
    private Fixture fixture(PhaseType phase) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1), enemy = game.getPlayers().get(0);
        Card attacker = addCard("Hill Giant", enemy), blocker = addCard(
                phase == PhaseType.COMBAT_FIRST_STRIKE_DAMAGE ? "Giant Spider" : "Runeclaw Bear", ai);
        attacker.setSickness(false); blocker.setSickness(false);
        if (phase == PhaseType.COMBAT_FIRST_STRIKE_DAMAGE) {
            attacker.addIntrinsicKeyword("Double strike");
        }
        game.getPhaseHandler().devModeSet(phase, enemy, false);
        Combat combat = new Combat(enemy); game.getPhaseHandler().setCombat(combat);
        combat.addAttacker(attacker, ai);
        if (phase != PhaseType.COMBAT_DECLARE_ATTACKERS) {
            combat.addBlocker(attacker, blocker); combat.setBlocked(attacker, true);
        }
        if (phase == PhaseType.COMBAT_FIRST_STRIKE_DAMAGE) {
            // Forge performs first-strike damage before this priority window. The 2/4
            // survives three damage but will die to the double striker's normal damage.
            combat.orderBlockersForDamageAssignment(); combat.orderAttackersForDamageAssignment();
            Assert.assertTrue(combat.assignCombatDamage(true)); combat.dealAssignedDamage();
            Assert.assertEquals(blocker.getDamage(), 3);
        }
        game.getPhaseHandler().setPriority(ai);
        addCards("Forest", 5, ai); addCards("Swamp", 5, ai); addCards("Plains", 5, ai);
        return new Fixture(game, ai, enemy, combat, attacker, blocker);
    }
    private PlayerControllerAi controller(Fixture f, String source, boolean pass, AtomicInteger calls) {
        AiDecisionProvider provider = raw -> {
            calls.incrementAndGet();
            Assert.assertTrue(raw instanceof CombatActionDecisionContext);
            var ctx = (CombatActionDecisionContext) raw;
            Assert.assertEquals(ctx.state().window(), f.game().getPhaseHandler().getPhase().name());
            Assert.assertTrue(ctx.passAvailable());
            var option = ctx.options().stream().filter(o -> o.sourceName().equals(source)).findFirst().orElseThrow();
            Assert.assertNotNull(option.optionId());
            return Optional.of(new AiDecision(ctx.decisionId(), ctx.stateFingerprint(), pass ? "PASS" : option.optionId()));
        };
        var controller = new PlayerControllerAi(f.game(), f.ai(), f.ai().getLobbyPlayer(), provider, false, false, 3);
        f.ai().dangerouslySetController(controller);
        controller.getAi().configureExternalCombatActions(true, 4);
        return controller;
    }
    private void chooseAndExecute(Fixture f, String source) {
        AtomicInteger calls = new AtomicInteger();
        var controller = controller(f, source, false, calls);
        var selected = controller.chooseSpellAbilityToPlay();
        Assert.assertEquals(calls.get(), 1, "Expected real COMBAT_ACTION routing for " + source);
        Assert.assertNotNull(selected);
        Assert.assertEquals(selected.get(0).getHostCard().getName(), source);
        controller.playChosenSpellAbility(selected.get(0));
        var stack = f.game().getStack().peekAbility();
        Assert.assertNotNull(stack, "Prepared action must successfully pay and enter the stack");
        Assert.assertEquals(stack.getHostCard().getName(), source);
        Assert.assertFalse(AiDecisionMetrics.combatActionEvents().get(0).fallback());
    }
    @DataProvider public Object[][] windows() { return new Object[][] {
        {PhaseType.COMBAT_DECLARE_ATTACKERS}, {PhaseType.COMBAT_DECLARE_BLOCKERS}, {PhaseType.COMBAT_FIRST_STRIKE_DAMAGE}
    }; }
    @Test(dataProvider = "windows") public void removalRoutesAndExecutesInEveryWindow(PhaseType phase) {
        Fixture f = fixture(phase); addCardToZone("Doom Blade", f.ai(), ZoneType.Hand);
        chooseAndExecute(f, "Doom Blade");
        Assert.assertTrue(f.game().getStack().peekAbility().getTargets().contains(f.attacker()));
        f.game().getStack().resolveStack(); Assert.assertFalse(f.attacker().isInPlay());
    }
    @Test public void pumpRoutesAndExecutes() {
        Fixture f = fixture(PhaseType.COMBAT_DECLARE_BLOCKERS);
        addCardToZone("Giant Growth", f.ai(), ZoneType.Hand); chooseAndExecute(f, "Giant Growth");
        Assert.assertTrue(f.game().getStack().peekAbility().getTargets().contains(f.blocker()));
        f.game().getStack().resolveStack(); Assert.assertEquals(f.blocker().getNetPower(), 5);
    }
    @Test public void activatedPumpRoutesAndExecutes() {
        Fixture f = fixture(PhaseType.COMBAT_DECLARE_BLOCKERS);
        Card rootwalla = addCard("Rootwalla", f.ai()); rootwalla.setSickness(false);
        f.combat().addBlocker(f.attacker(), rootwalla); chooseAndExecute(f, "Rootwalla");
        f.game().getStack().resolveStack(); Assert.assertEquals(rootwalla.getNetPower(), 4);
    }
    @Test public void protectionSourceOutsideCombatRoutesAndExecutes() {
        Fixture f = fixture(PhaseType.COMBAT_DECLARE_BLOCKERS);
        Card acolyte = addCard("Crimson Acolyte", f.ai()); acolyte.setSickness(false);
        chooseAndExecute(f, "Crimson Acolyte");
        Assert.assertTrue(f.game().getStack().peekAbility().getTargets().contains(f.blocker()));
        f.game().getStack().resolveStack();
        Assert.assertTrue(f.blocker().hasKeyword("Protection from red"));
    }
    @Test public void hexproofProtectionRoutesAndExecutes() {
        Fixture f = fixture(PhaseType.COMBAT_DECLARE_BLOCKERS);
        addCardToZone("Blossoming Defense", f.ai(), ZoneType.Hand); chooseAndExecute(f, "Blossoming Defense");
    }
    @Test public void passWithRealPreparedActionIsAccepted() {
        Fixture f = fixture(PhaseType.COMBAT_DECLARE_BLOCKERS);
        addCardToZone("Giant Growth", f.ai(), ZoneType.Hand);
        AtomicInteger calls = new AtomicInteger(); var controller = controller(f, "Giant Growth", true, calls);
        Assert.assertNull(controller.chooseSpellAbilityToPlay()); Assert.assertEquals(calls.get(), 1);
        Assert.assertTrue(AiDecisionMetrics.combatActionEvents().get(0).passSelection());
        Assert.assertFalse(AiDecisionMetrics.combatActionEvents().get(0).fallback());
        Assert.assertTrue(f.game().getStack().isEmpty());
    }
    @Test public void eligibilityInspectionUsesCopiedState() {
        Fixture f = fixture(PhaseType.COMBAT_DECLARE_BLOCKERS);
        Card card = addCardToZone("Doom Blade", f.ai(), ZoneType.Hand);
        var audit = CombatActionEligibilityAudit.inspect(f.ai(), java.util.List.of(card.getFirstSpellAbility()),
                "NO_PREPARED_COMBAT_ACTION", java.util.List.of(), null);
        Assert.assertNotNull(audit.get("candidates"));
    }
    @Test public void unrelatedAndUnsupportedCombatChoicesAreDistinguished() {
        Fixture f = fixture(PhaseType.COMBAT_DECLARE_BLOCKERS);
        Card opt = addCardToZone("Opt", f.ai(), ZoneType.Hand);
        Card protection = addCardToZone("Gods Willing", f.ai(), ZoneType.Hand);
        addCard("Island", f.ai());
        var audit = CombatActionEligibilityAudit.inspect(f.ai(), java.util.List.of(opt.getFirstSpellAbility(), protection.getFirstSpellAbility()),
                "NO_SUPPORTED_TEMPLATE", java.util.List.of(), null);
        String json = new com.google.gson.Gson().toJson(audit);
        Assert.assertTrue(json.contains("TIMING_COST_AND_TARGETS_AVAILABLE"));
        Assert.assertTrue(json.contains("\"supportedTemplate\":false"));
        Assert.assertTrue(json.contains("\"combatTargetCount\":1"));
        Assert.assertTrue(json.contains("\"combatTargetCount\":0"));
        Assert.assertEquals(opt.getFirstSpellAbility().getTargets().size(), 0, "Inspection must not prepare live targets");
    }
    @Test public void noLegalActionAndNoCandidateAreDistinguished() {
        Fixture f = fixture(PhaseType.COMBAT_DECLARE_BLOCKERS);
        var empty = CombatActionEligibilityAudit.inspect(f.ai(), java.util.List.of(), "NO_SUPPORTED_TEMPLATE", java.util.List.of(), null);
        Assert.assertEquals(empty.get("engineAvailability"), "NO_INSTANT_SPEED_CANDIDATES");
        Card removal = addCardToZone("Doom Blade", f.ai(), ZoneType.Hand);
        f.ai().getCardsIn(ZoneType.Battlefield).forEach(c -> c.setTapped(true));
        var unavailable = CombatActionEligibilityAudit.inspect(f.ai(), java.util.List.of(removal.getFirstSpellAbility()),
                "NO_PREPARED_COMBAT_ACTION", java.util.List.of(), null);
        Assert.assertEquals(unavailable.get("engineAvailability"), "NO_ENGINE_PLAYABLE_ACTION");
        Assert.assertTrue(new com.google.gson.Gson().toJson(unavailable).contains("COST_UNPAYABLE"));
    }
    @Test public void nonProtectionReplacementGuardIsPreserved() {
        Fixture f = fixture(PhaseType.COMBAT_DECLARE_BLOCKERS);
        addCard("Winding Constrictor", f.ai());
        Assert.assertFalse(ExternalCombatActions.supported(f.ai(), f.combat()));
    }
    @Test public void settledTappedLandDoesNotHideCombatProtection() {
        Fixture f = fixture(PhaseType.COMBAT_DECLARE_BLOCKERS);
        addCard("Foul Orchard", f.ai());
        addCardToZone("Blossoming Defense", f.ai(), ZoneType.Hand);
        chooseAndExecute(f, "Blossoming Defense");
    }
    @Test public void settledSelfEntryCreatureDoesNotHideCombatRemoval() {
        Fixture f = fixture(PhaseType.COMBAT_DECLARE_BLOCKERS);
        addCard("Gnarlid Pack", f.ai());
        addCardToZone("Doom Blade", f.ai(), ZoneType.Hand);
        chooseAndExecute(f, "Doom Blade");
    }
    @Test public void settledCombatantSelfEntryDoesNotHidePump() {
        Fixture f = fixture(PhaseType.COMBAT_DECLARE_BLOCKERS);
        Card pack = addCard("Gnarlid Pack", f.ai());
        f.combat().removeFromCombat(f.blocker()); f.combat().addBlocker(f.attacker(), pack);
        addCardToZone("Giant Growth", f.ai(), ZoneType.Hand);
        chooseAndExecute(f, "Giant Growth");
        Assert.assertTrue(f.game().getStack().peekAbility().getTargets().contains(pack));
    }
    @Test public void eligibilityFileIsOptInAndRecordsProviderRoute() throws Exception {
        Fixture f = fixture(PhaseType.COMBAT_DECLARE_BLOCKERS);
        addCardToZone("Giant Growth", f.ai(), ZoneType.Hand);
        var path = java.nio.file.Files.createTempFile("combat-eligibility", ".jsonl");
        String previous = System.getProperty(CombatActionEligibilityAudit.PROPERTY);
        try {
            System.clearProperty(CombatActionEligibilityAudit.PROPERTY);
            CombatActionEligibilityAudit.record(f.ai(), java.util.List.of(), "TEST", java.util.List.of(), null);
            Assert.assertEquals(java.nio.file.Files.size(path), 0L);
            System.setProperty(CombatActionEligibilityAudit.PROPERTY, path.toString());
            chooseAndExecute(f, "Giant Growth");
            var json = new com.google.gson.Gson().fromJson(java.nio.file.Files.readString(path), com.google.gson.JsonObject.class);
            Assert.assertEquals(json.get("outcome").getAsString(), "PROVIDER_CALLED");
            Assert.assertEquals(json.getAsJsonObject("selection").get("exposed").getAsInt(), 1);
        } finally {
            if (previous == null) System.clearProperty(CombatActionEligibilityAudit.PROPERTY);
            else System.setProperty(CombatActionEligibilityAudit.PROPERTY, previous);
            java.nio.file.Files.deleteIfExists(path);
        }
    }
    @Test public void boundingAndDedupHaveSeparateAuditCounts() {
        Fixture f = fixture(PhaseType.COMBAT_DECLARE_BLOCKERS);
        var actions = new java.util.ArrayList<PreparedAiAction>();
        for (String name : java.util.List.of("Giant Growth", "Titanic Growth")) {
            Card card = addCardToZone(name, f.ai(), ZoneType.Hand);
            var sa = card.getFirstSpellAbility().copy(f.ai()); sa.setActivatingPlayer(f.ai()); sa.getTargets().add(f.blocker());
            actions.add(new PreparedAiAction("internal", sa, card.getFirstSpellAbility(), card,
                    PreparedAiAction.ActionCategory.SPELL, 0, name, true));
        }
        actions.add(actions.get(0));
        var exposed = ExternalCombatActions.snapshot(f.ai(), f.combat(), actions, 1);
        var counts = ExternalCombatActions.selectionCounts(f.ai(), f.combat(), actions, exposed);
        Assert.assertEquals(counts.get("duplicates"), Integer.valueOf(1));
        Assert.assertEquals(counts.get("boundedOut"), Integer.valueOf(1));
        Assert.assertEquals(counts.get("exposed"), Integer.valueOf(1));
    }
    @Test public void excludedWindowReportsSafePreparedActionWithoutCallingProvider() {
        Fixture f = fixture(PhaseType.COMBAT_DECLARE_BLOCKERS);
        Card card = addCardToZone("Doom Blade", f.ai(), ZoneType.Hand);
        var c = controller(f, "Doom Blade", false, new AtomicInteger());
        var actions = c.getAi().prepareCombatActionCandidates(java.util.List.of(card.getFirstSpellAbility()), 4).actions();
        Assert.assertFalse(actions.isEmpty());
        f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE, f.enemy(), false);
        Assert.assertFalse(ExternalCombatActions.window(f.ai()));
        Assert.assertTrue(c.getAi().revalidatePreparedAction(actions.get(0)));
        var audit = CombatActionEligibilityAudit.inspect(f.ai(), java.util.List.of(card.getFirstSpellAbility()),
                "WINDOW_EXCLUDED", actions, null);
        Assert.assertEquals(audit.get("outcome"), "WINDOW_EXCLUDED");
        Assert.assertEquals(((java.util.Map<?, ?>) audit.get("selection")).get("relevantPrepared"), 1);
        Assert.assertTrue(AiDecisionMetrics.combatActionEvents().isEmpty());
    }
    @Test public void globalReplacementStillBypassesCombatAction() {
        Fixture f = fixture(PhaseType.COMBAT_DECLARE_BLOCKERS);
        addCard("Furnace of Rath", f.enemy());
        Assert.assertFalse(ExternalCombatActions.supported(f.ai(), f.combat()));
    }
}
