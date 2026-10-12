package forge.ai;

import com.google.gson.Gson;
import forge.ai.decision.*;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.OptionalCost;
import forge.game.zone.ZoneType;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public class Stage16AbilityRecoveryTest extends AITest {
    record Fixture(Game game, Player ai, Player enemy, Card blocker, Card earthbrawn) { }
    @BeforeMethod public void reset() { AiDecisionMetrics.reset(); }
    private Fixture fixture() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1), enemy = game.getPlayers().get(0);
        Card attacker = addCard("Hill Giant", enemy), blocker = addCard("Runeclaw Bear", ai);
        attacker.setSickness(false); blocker.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, enemy, false);
        Combat combat = new Combat(enemy); game.getPhaseHandler().setCombat(combat);
        combat.addAttacker(attacker, ai); combat.addBlocker(attacker, blocker); combat.setBlocked(attacker, true);
        game.getPhaseHandler().setPriority(ai);
        addCards("Forest", 2, ai);
        return new Fixture(game, ai, enemy, blocker, addCardToZone("Earthbrawn", ai, ZoneType.Hand));
    }
    private PlayerControllerAi controller(Fixture f, AiDecisionProvider provider, boolean enabled) {
        var c = new PlayerControllerAi(f.game(), f.ai(), f.ai().getLobbyPlayer(), provider, false, false, 3);
        f.ai().dangerouslySetController(c); c.getAi().configureExternalCombatActions(enabled, 4); return c;
    }
    private static AiDecision select(AiDecisionContext raw, String id) {
        var c = (CombatActionDecisionContext) raw;
        return new AiDecision(c.decisionId(), c.stateFingerprint(), id == null ? c.options().get(0).optionId() : id);
    }
    @Test public void warningRemainsAndSiblingIsDenied() {
        Fixture f = fixture();
        var ordinary = f.earthbrawn().getFirstSpellAbility();
        Assert.assertEquals(ExternalAiAbilitySupport.status(ordinary, AiDecisionType.COMBAT_ACTION), ExternalAiAbilitySupport.Status.VERIFIED_SAFE);
        var siblings = f.earthbrawn().getSpellAbilities().stream().filter(SpellAbility::isActivatedAbility).toList();
        Assert.assertFalse(siblings.isEmpty(), "Reinforce must really exist in this fixture");
        for (var sibling : siblings) for (var surface : AiDecisionType.values()) {
            Assert.assertEquals(ExternalAiAbilitySupport.status(sibling, surface), ExternalAiAbilitySupport.Status.UNSUPPORTED);
        }
        for (var sibling : siblings) {
            Assert.assertFalse(ExternalCombatActions.safeTemplate(sibling));
            var action = new PreparedAiAction("UNSUPPORTED_REINFORCE", sibling, sibling, f.earthbrawn(),
                    PreparedAiAction.ActionCategory.ACTIVATED_ABILITY, 0, sibling.toString(), true);
            Assert.assertNull(ExternalCombatActions.snapshot(f.ai(), f.game().getCombat(), List.of(action), 4));
        }
        Deck deck = new Deck("Earthbrawn recovery warning");
        deck.getMain().add(FModel.getMagicDb().getCommonCards().getCard("Earthbrawn"), 1);
        Assert.assertEquals(deck.getUnplayableAICards().inMainDeck, 1);
        Assert.assertTrue(ComputerUtilCard.isCardRemAIDeck(f.earthbrawn()));
    }
    @Test public void ordinaryPumpRoutesPaysAndResolvesWithNoReinforceMapping() {
        Fixture f = fixture(); AtomicReference<CombatActionDecisionContext> captured = new AtomicReference<>();
        var c = controller(f, raw -> { captured.set((CombatActionDecisionContext) raw); return Optional.of(select(raw, null)); }, true);
        var selected = c.chooseSpellAbilityToPlay();
        Assert.assertNotNull(selected); Assert.assertEquals(selected.size(), 1);
        Assert.assertTrue(selected.get(0).isSpell());
        Assert.assertTrue(selected.get(0).getTargets().contains(f.blocker()));
        Assert.assertEquals(captured.get().options().size(), 1);
        Assert.assertTrue(captured.get().passAvailable());
        Assert.assertTrue(captured.get().options().get(0).optionId().startsWith("OPT_"));
        Assert.assertFalse(new Gson().toJson(captured.get()).contains("Reinforce"));
        c.playChosenSpellAbility(selected.get(0));
        Assert.assertNotNull(f.game().getStack().peekAbility());
        f.game().getStack().resolveStack();
        Assert.assertEquals(f.blocker().getNetToughness(), 5);
        Assert.assertTrue(f.game().getCardsIn(ZoneType.Graveyard).stream().anyMatch(card -> card.getName().equals("Earthbrawn")));
        Assert.assertFalse(AiDecisionMetrics.combatActionEvents().get(0).fallback());
    }
    @Test public void providerPassIsSuccessful() {
        Fixture f = fixture();
        var c = controller(f, raw -> Optional.of(select(raw, "PASS")), true);
        Assert.assertNull(c.chooseSpellAbilityToPlay());
        Assert.assertTrue(AiDecisionMetrics.combatActionEvents().get(0).passSelection());
        Assert.assertFalse(AiDecisionMetrics.combatActionEvents().get(0).fallback());
        Assert.assertEquals(f.earthbrawn().getZone().getZoneType(), ZoneType.Hand);
    }
    @DataProvider public Object[][] failures() { return new Object[][] {{"UNKNOWN_REINFORCE"}, {"EMPTY"}}; }
    @Test(dataProvider="failures") public void failureUsesOriginalForgePass(String response) {
        Fixture f = fixture();
        var c = controller(f, raw -> "EMPTY".equals(response) ? Optional.empty() : Optional.of(select(raw, response)), true);
        Assert.assertNull(c.chooseSpellAbilityToPlay());
        Assert.assertTrue(AiDecisionMetrics.combatActionEvents().get(0).fallback());
        Assert.assertEquals(f.earthbrawn().getZone().getZoneType(), ZoneType.Hand);
    }
    @Test public void staleTargetFallsBackWithoutCasting() {
        Fixture f = fixture();
        var c = controller(f, raw -> {
            var decision = select(raw, null);
            f.game().getAction().moveToGraveyard(f.blocker(), null); return Optional.of(decision);
        }, true);
        Assert.assertNull(c.chooseSpellAbilityToPlay());
        Assert.assertTrue(AiDecisionMetrics.combatActionEvents().get(0).staleOrRevalidationFailure());
        Assert.assertEquals(f.earthbrawn().getZone().getZoneType(), ZoneType.Hand);
    }
    @Test public void disabledLeavesNativeMarkerSemanticsUnchanged() {
        Fixture f = fixture();
        var c = controller(f, raw -> { Assert.fail("Disabled recovery called provider"); return Optional.empty(); }, false);
        Assert.assertNull(c.chooseSpellAbilityToPlay());
    }
    @Test public void surfaceSpecificAndUnverifiedCardsDenied() {
        Fixture f = fixture();
        for (var surface : AiDecisionType.values()) if (surface != AiDecisionType.COMBAT_ACTION)
            Assert.assertEquals(ExternalAiAbilitySupport.status(f.earthbrawn().getFirstSpellAbility(), surface), ExternalAiAbilitySupport.Status.UNSUPPORTED);
        Card unverified = addCardToZone("About Face", f.ai(), ZoneType.Hand);
        Assert.assertEquals(ExternalAiAbilitySupport.status(unverified.getFirstSpellAbility(), AiDecisionType.COMBAT_ACTION), ExternalAiAbilitySupport.Status.UNSUPPORTED);
        Card growth = addCardToZone("Giant Growth", f.ai(), ZoneType.Hand);
        Assert.assertEquals(ExternalAiAbilitySupport.status(growth.getFirstSpellAbility(), AiDecisionType.COMBAT_ACTION), ExternalAiAbilitySupport.Status.DEFAULT);
        Assert.assertTrue(ExternalCombatActions.safeTemplate(growth.getFirstSpellAbility()));
    }
    @Test public void changedTemplateDoesNotRetainVerifiedStatus() {
        Fixture f = fixture(); var changed = f.earthbrawn().getFirstSpellAbility().copy(f.ai());
        changed.getMapParams().put("NumDef", "+4");
        Assert.assertEquals(ExternalAiAbilitySupport.status(changed, AiDecisionType.COMBAT_ACTION), ExternalAiAbilitySupport.Status.UNSUPPORTED);
    }
    @DataProvider public Object[][] changedCosts() { return new Object[][] {{"X"}, {"OPTIONAL"}}; }
    @Test(dataProvider="changedCosts") public void optionalCostAndXCannotRetainVerifiedStatus(String change) {
        Fixture f = fixture(); var changed = f.earthbrawn().getFirstSpellAbility().copy(f.ai());
        if (change.equals("X")) changed.setXManaCostPaid(2);
        else changed.addOptionalCost(OptionalCost.Buyback);
        Assert.assertEquals(ExternalAiAbilitySupport.status(changed, AiDecisionType.COMBAT_ACTION), ExternalAiAbilitySupport.Status.UNSUPPORTED);
    }
    @Test public void unavailableManaFallsBackWithoutCasting() {
        Fixture f = fixture();
        var c = controller(f, raw -> {
            var decision = select(raw, null);
            f.ai().getCardsIn(ZoneType.Battlefield).stream().filter(Card::isLand).forEach(card -> card.setTapped(true));
            return Optional.of(decision);
        }, true);
        Assert.assertNull(c.chooseSpellAbilityToPlay());
        Assert.assertTrue(AiDecisionMetrics.combatActionEvents().get(0).staleOrRevalidationFailure());
    }
    @Test public void hiddenOpponentHandAndSupportClassificationStayOutOfRequest() {
        Fixture f = fixture(); addCardToZone("Black Lotus", f.enemy(), ZoneType.Hand);
        var c = controller(f, raw -> {
            String json = new Gson().toJson(raw);
            Assert.assertFalse(json.contains("Black Lotus")); Assert.assertFalse(json.contains("VERIFIED_SAFE"));
            Assert.assertFalse(json.contains("Reinforce")); return Optional.of(select(raw, "PASS"));
        }, true);
        c.chooseSpellAbilityToPlay();
        Assert.assertEquals(AiDecisionMetrics.combatActionEvents().size(), 1);
    }
    @Test public void internalAuditDistinguishesOrdinaryAndReinforce() throws Exception {
        var file = Files.createTempFile("stage16-1-support", ".jsonl");
        String previous = System.getProperty(CombatActionEligibilityAudit.PROPERTY);
        try {
            System.setProperty(CombatActionEligibilityAudit.PROPERTY, file.toString());
            Fixture f = fixture(); controller(f, raw -> Optional.of(select(raw, "PASS")), true).chooseSpellAbilityToPlay();
            String json = Files.readString(file);
            Assert.assertTrue(json.contains("ABILITY_SUPPORT")); Assert.assertTrue(json.contains("VERIFIED_SAFE"));
            Assert.assertTrue(json.contains("UNSUPPORTED")); Assert.assertTrue(json.contains("hostWarning"));
        } finally {
            if (previous == null) System.clearProperty(CombatActionEligibilityAudit.PROPERTY);
            else System.setProperty(CombatActionEligibilityAudit.PROPERTY, previous);
        }
    }
}
