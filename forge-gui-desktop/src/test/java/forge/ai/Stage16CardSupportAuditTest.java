package forge.ai;

import forge.ai.decision.AiDecision;
import forge.ai.decision.AiDecisionMetrics;
import forge.ai.decision.StackResponseDecisionContext;
import forge.card.CardRules;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Read-only support probes: never alter scripts, AI metadata or production routing. */
public class Stage16CardSupportAuditTest extends AITest {
    @Test
    public void removeRandomIsNotThePoorAiWarning() {
        var sustenance = FModel.getMagicDb().getCommonCards().getCard("Sustenance");
        Assert.assertTrue(sustenance.getRules().getAiHints().getRemRandomDecks());
        Assert.assertFalse(sustenance.getRules().getAiHints().getRemAIDecks());
    }

    @Test
    public void warningsCountEntriesNotCopiesAndDoNotRemoveCards() {
        Deck deck = new Deck("support audit");
        var yeti = FModel.getMagicDb().getCommonCards().getCard("Stalking Yeti");
        deck.getMain().add(yeti, 4);
        Assert.assertEquals(deck.getUnplayableAICards().inMainDeck, 1);
        Assert.assertEquals(deck.getMain().countAll(), 4);
        Assert.assertEquals(deck.getValid().getLeft().getMain().countAll(), 4);
    }

    @Test
    public void missingRulesSupportIsSeparateFromAiWarning() {
        Assert.assertTrue(CardRules.getUnsupportedCardNamed("Stage16 missing card").isUnsupported());
        Assert.assertFalse(FModel.getMagicDb().getCommonCards().getCard("Stalking Yeti")
                .getRules().isUnsupported());
    }

    @Test
    public void warnedPermanentIsExcludedFromOrdinaryPriorityEnumeration() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        addCards("Mountain", 4, player);
        Card yeti = addCardToZone("Stalking Yeti", player, ZoneType.Hand);
        Assert.assertTrue(ComputerUtilCard.isCardRemAIDeck(yeti));
        var controller = (PlayerControllerAi) player.getController();
        // Its ETB target drawback also prevents direct preparation on an empty opponent board.
        var prepared = controller.getAi().prepareSpellAbilityActions(List.of(yeti.getFirstSpellAbility()), true);
        Assert.assertTrue(prepared.isEmpty());
        Assert.assertNull(controller.chooseSpellAbilityToPlay());
    }

    @DataProvider
    public Object[][] warnedPumps() { return new Object[][] { {"Earthbrawn"}, {"Dizzy Spell"} }; }

    @Test(dataProvider = "warnedPumps")
    public void warnedPumpsHaveDifferentPreparationOutcomes(String name) {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1), enemy = game.getPlayers().get(0);
        Card attacker = addCard("Hill Giant", enemy), blocker = addCard("Runeclaw Bear", player);
        attacker.setSickness(false); blocker.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, enemy, false);
        Combat combat = new Combat(enemy); game.getPhaseHandler().setCombat(combat);
        combat.addAttacker(attacker, player); combat.addBlocker(attacker, blocker);
        combat.setBlocked(attacker, true); game.getPhaseHandler().setPriority(player);
        addCards("Forest", 2, player); addCards("Island", 2, player);
        Card spell = addCardToZone(name, player, ZoneType.Hand);
        Assert.assertTrue(ComputerUtilCard.isCardRemAIDeck(spell));
        var controller = new PlayerControllerAi(game, player, player.getLobbyPlayer(), context -> {
            Assert.fail("The All-marked spell must not enter the normal provider route");
            return Optional.empty();
        }, false, false, 3);
        player.dangerouslySetController(controller);
        // Native/disabled routing remains excluded; Stage 16.1 tests enabled recovery separately.
        Assert.assertNull(controller.chooseSpellAbilityToPlay());
        var set = controller.getAi().prepareCombatActionCandidates(List.of(spell.getFirstSpellAbility()), 4);
        if (name.equals("Dizzy Spell")) {
            Assert.assertTrue(set.actions().isEmpty(), "This fixture does not prove a safely prepared debuff");
            return;
        }
        Assert.assertFalse(set.actions().isEmpty());
        var prepared = set.actions().get(0);
        Assert.assertTrue(controller.getAi().revalidatePreparedAction(prepared));
        Assert.assertFalse(prepared.spellAbility().getTargets().isEmpty());
        Assert.assertTrue(ComputerUtil.playStack(prepared.spellAbility(), player, game));
        game.getStack().resolveStack();
        Assert.assertEquals(blocker.getNetToughness(), 5);
    }

    @Test
    public void unmarkedSimpleSpellIsStillPrepared() {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        addCard("Mountain", player);
        Card shock = addCardToZone("Shock", player, ZoneType.Hand);
        game.getPlayers().get(0).setLife(2, null);
        var prepared = ((PlayerControllerAi) player.getController()).getAi()
                .prepareSpellAbilityActions(List.of(shock.getFirstSpellAbility()), true);
        Assert.assertEquals(prepared.size(), 1);
        Assert.assertTrue(((PlayerControllerAi) player.getController()).getAi()
                .revalidatePreparedAction(prepared.get(0)));
    }

    @Test
    public void warnedSwanSongEntersExistingStackMappingAndPreservesExactTarget() {
        AiDecisionMetrics.reset();
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Player opponent = game.getPlayers().get(0);
        addCardToZone("Black Lotus", opponent, ZoneType.Hand);
        addCard("Island", player);
        Card song = addCardToZone("Swan Song", player, ZoneType.Hand);
        Assert.assertTrue(ComputerUtilCard.isCardRemAIDeck(song));
        Card pendingCard = addCardToZone("Divination", opponent, ZoneType.Hand);
        SpellAbility pending = pendingCard.getFirstSpellAbility();
        pending.setActivatingPlayer(opponent);
        game.getStackZone().add(pendingCard);
        game.getStack().add(pending);
        AtomicReference<StackResponseDecisionContext> captured = new AtomicReference<>();
        PlayerControllerAi controller = new PlayerControllerAi(game, player, player.getLobbyPlayer(), context -> {
            StackResponseDecisionContext stack = (StackResponseDecisionContext) context;
            captured.set(stack);
            return Optional.of(new AiDecision(stack.decisionId(), stack.stateFingerprint(),
                    stack.legalActions().get(0).actionId()));
        }, false, false, 3, false, 1, true, 3);
        player.dangerouslySetController(controller);
        SpellAbility selected = controller.chooseSpellAbilityToPlay().get(0);
        Assert.assertNotNull(captured.get());
        Assert.assertFalse(captured.get().toString().contains("Black Lotus"));
        Assert.assertTrue(captured.get().legalActions().get(0).actionId().startsWith("OPT_"));
        Assert.assertEquals(selected.getHostCard(), song);
        Assert.assertTrue(selected.getTargets().contains(pending));
        Assert.assertNotNull(selected.getSubAbility(), "Token consequence must survive preparation");
        PreparedAiAction action = new PreparedAiAction("AUDIT", selected, song.getFirstSpellAbility(),
                song, PreparedAiAction.ActionCategory.SPELL, 0, selected.toString(), true);
        Assert.assertTrue(controller.getAi().revalidatePreparedAction(action));
        Assert.assertFalse(AiDecisionMetrics.stackResponseEvents().get(0).fallback());
        // Execute through Forge's normal payment/stack seam, then resolve the counter and token consequence.
        Assert.assertTrue(ComputerUtil.playStack(selected, player, game));
        game.getStack().resolveStack();
        Assert.assertEquals(pendingCard.getZone().getZoneType(), ZoneType.Graveyard);
        Assert.assertTrue(opponent.getCreaturesInPlay().stream().anyMatch(c -> c.isToken()
                && c.getName().contains("Bird")));
    }
}
