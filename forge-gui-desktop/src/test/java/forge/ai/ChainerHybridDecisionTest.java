package forge.ai;

import com.google.gson.Gson;
import forge.ai.decision.*;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.*;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test; import org.testng.annotations.BeforeMethod; import org.testng.annotations.AfterMethod;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Scripted provider, real card scripts, costs, target checks, zone changes and triggers. */
public class ChainerHybridDecisionTest extends AITest {
    record F(Game g, Player p, Player o) { }
    @BeforeMethod public void enableChoices() { System.setProperty(ExternalGraveyardChoices.ENABLED, "true"); }
    @AfterMethod public void resetChoices() { System.clearProperty(ExternalGraveyardChoices.ENABLED); System.clearProperty(ExternalGraveyardChoices.AUDIT); }
    F fixture() {
        Game g = initAndCreateGame(); Player p = g.getPlayers().get(1);
        for (Player x : g.getPlayers()) { x.setLife(40, null); fillLibrary(x, 25); }
        return new F(g, p, g.getPlayers().get(0));
    }
    PlayerControllerAi controller(F f, AiDecisionProvider provider) {
        var c = new PlayerControllerAi(f.g, f.p, f.p.getLobbyPlayer(), provider, false, false, 3);
        f.p.dangerouslySetController(c); return c;
    }
    AiDecision answer(CardChoiceDecisionContext c, String name) {
        return new AiDecision(c.decisionId(), c.stateFingerprint(), c.options().stream()
                .filter(o -> o.description().startsWith(name + " #")).findFirst().orElseThrow().optionId());
    }
    void mana(F f, int red, int black) { addCards("Mountain", red, f.p); addCards("Swamp", black, f.p); }
    Card ready(F f, String name) { Card c = addCard(name, f.p); c.setSickness(false); f.g.getAction().checkStateEffects(true); f.g.getTriggerHandler().resetActiveTriggers(); return c; }
    SpellAbility spell(F f, String name) { var a = addCardToZone(name, f.p, ZoneType.Hand).getFirstSpellAbility(); a.setActivatingPlayer(f.p); return a; }
    void drain(F f) {
        for (int i = 0; i < 48; i++) { f.g.getAction().checkStateEffects(true); f.g.getStack().addAllTriggeredAbilitiesToStack();
            if (f.g.getStack().isEmpty()) return; f.g.getStack().resolveStack(); }
        Assert.fail("Unbounded trigger loop");
    }
    void play(F f, SpellAbility a) {
        a.setActivatingPlayer(f.p); Assert.assertTrue(a.canPlay(), "Forge restrictions: " + a);
        Assert.assertTrue(ComputerUtilCost.canPayCost(a, f.p, false), "Forge cost: " + a);
        f.p.getController().playChosenSpellAbility(a); drain(f);
    }
    Card live(F f, String name) { return f.p.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals(name)).findFirst().orElseThrow(); }
    SpellAbility chainer(F f) { var c = ready(f, "Chainer, Nightmare Adept"); var a = c.getSpellAbilities().stream().filter(s -> s.getApi() == ApiType.Effect).findFirst().orElseThrow(); a.setActivatingPlayer(f.p); return a; }

    @Test public void discardDragonThenActuallyPayForGraveyardCast() {
        F f = fixture(); mana(f, 6, 0); var a = chainer(f);
        Card dragon = addCardToZone("Shivan Dragon", f.p, ZoneType.Hand);
        addCardToZone("Mountain", f.p, ZoneType.Hand); addCardToZone("Raging Goblin", f.p, ZoneType.Graveyard);
        AtomicInteger calls = new AtomicInteger(); controller(f, ctx -> { calls.incrementAndGet(); return Optional.of(answer((CardChoiceDecisionContext)ctx, "Shivan Dragon")); });
        play(f, a); Assert.assertEquals(calls.get(), 1); Assert.assertEquals(countCardsWithName(f.g, "Shivan Dragon", ZoneType.Graveyard), 1);
        Card grave = f.p.getCardsIn(ZoneType.Graveyard).stream().filter(c -> c.getId() == dragon.getId()).findFirst().orElseThrow();
        var cast = grave.getFirstSpellAbility(); cast.setActivatingPlayer(f.p);
        var legal = ComputerUtilAbility.getOriginalAndAltCostAbilities(List.of(cast), f.p).stream().filter(SpellAbility::canPlay).findFirst().orElseThrow();
        play(f, legal); Assert.assertTrue(live(f, "Shivan Dragon").hasKeyword("Haste")); Assert.assertFalse(a.canPlay());
        Assert.assertTrue(f.p.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isLand).anyMatch(Card::isTapped));
    }

    @Test public void chainerNecromancerBladewingAndDragonTempestResolveAsPaidSequence() {
        F f = fixture(); mana(f, 3, 5); var activate = chainer(f); ready(f, "Dragon Tempest");
        addCardToZone("Mountain", f.p, ZoneType.Hand); addCardToZone("Chaos Warp", f.p, ZoneType.Hand);
        addCardToZone("Doomed Necromancer", f.p, ZoneType.Graveyard);
        Card blade = addCardToZone("Bladewing the Risen", f.p, ZoneType.Graveyard);
        addCardToZone("Shivan Dragon", f.p, ZoneType.Graveyard);
        AtomicInteger targets = new AtomicInteger();
        controller(f, ctx -> { var c = (CardChoiceDecisionContext)ctx;
            String name = c.question().startsWith("Discard") ? "Mountain" : targets.getAndIncrement() == 0 ? "Bladewing the Risen" : "Shivan Dragon";
            return Optional.of(answer(c, name)); });
        play(f, activate);
        Card necro = f.p.getCardsIn(ZoneType.Graveyard).stream().filter(c -> c.getName().equals("Doomed Necromancer")).findFirst().orElseThrow();
        var cast = necro.getFirstSpellAbility(); cast.setActivatingPlayer(f.p);
        var allowed = ComputerUtilAbility.getOriginalAndAltCostAbilities(List.of(cast), f.p).stream().filter(SpellAbility::canPlay).findFirst().orElseThrow();
        play(f, allowed); Card body = live(f, "Doomed Necromancer"); Assert.assertTrue(body.hasKeyword("Haste"));
        var revive = body.getSpellAbilities().stream().filter(s -> s.getApi() == ApiType.ChangeZone).findFirst().orElseThrow();
        revive.setActivatingPlayer(f.p); revive.getTargets().add(blade); play(f, revive);
        Assert.assertEquals(countCardsWithName(f.g, "Doomed Necromancer", ZoneType.Graveyard), 1);
        Assert.assertNotNull(live(f, "Bladewing the Risen")); Assert.assertNotNull(live(f, "Shivan Dragon"));
        Assert.assertTrue(f.o.getLife() < 40, "Real Dragon Tempest triggers dealt damage");
        Assert.assertTrue(targets.get() >= 1, "External reanimation target offered");
    }

    @Test public void sickNecromancerCannotActivateWithoutChainerHaste() {
        F f = fixture(); mana(f, 0, 1); Card c = addCard("Doomed Necromancer", f.p); c.setSickness(true);
        var a = c.getSpellAbilities().stream().filter(s -> s.getApi() == ApiType.ChangeZone).findFirst().orElseThrow(); a.setActivatingPlayer(f.p);
        a.getTargets().add(addCardToZone("Shivan Dragon", f.p, ZoneType.Graveyard)); Assert.assertFalse(a.canPlay());
    }

    @Test public void reanimationSelectionUsesActualTargetRestrictions() {
        F f = fixture(); mana(f, 0, 1); Card creature = addCardToZone("Shivan Dragon", f.p, ZoneType.Graveyard);
        addCardToZone("Mountain", f.p, ZoneType.Graveyard); addCardToZone("Scourge of Valkas", f.p, ZoneType.Graveyard);
        AtomicReference<CardChoiceDecisionContext> captured = new AtomicReference<>();
        controller(f, ctx -> { var c = (CardChoiceDecisionContext)ctx; captured.set(c); return Optional.of(answer(c, "Scourge of Valkas")); });
        var a = spell(f, "Reanimate"); a.getTargets().add(creature); play(f, a);
        Assert.assertEquals(captured.get().options().size(), 2); Assert.assertNotNull(live(f, "Scourge of Valkas"));
        Assert.assertEquals(countCardsWithName(f.g, "Shivan Dragon", ZoneType.Graveyard), 1);
        Assert.assertEquals(f.p.getLife(), 35, "Reanimate life cost follows selected card");
    }

    @Test public void bladewingCannotReturnNonDragonAndCanChooseScourge() {
        F f = fixture(); mana(f, 4, 3); ready(f, "Dragon Tempest");
        addCardToZone("Shivan Dragon", f.p, ZoneType.Graveyard); addCardToZone("Scourge of Valkas", f.p, ZoneType.Graveyard);
        Card necro = addCardToZone("Doomed Necromancer", f.p, ZoneType.Graveyard);
        AtomicInteger calls = new AtomicInteger(); controller(f, ctx -> { calls.incrementAndGet(); var c = (CardChoiceDecisionContext)ctx;
            Assert.assertTrue(c.options().stream().noneMatch(o -> o.description().startsWith("Doomed Necromancer")));
            return Optional.of(answer(c, "Scourge of Valkas")); });
        play(f, spell(f, "Bladewing the Risen"));
        Assert.assertTrue(calls.get() >= 1); Assert.assertNotNull(live(f, "Scourge of Valkas"));
        Assert.assertTrue(f.p.getCardsIn(ZoneType.Graveyard).contains(necro)); Assert.assertTrue(f.o.getLife() < 40);
    }

    @Test public void tutorSearchSelectsBoardAppropriateAnswerFromAuthorizedPool() {
        F f = fixture(); mana(f, 1, 1); ready(f, "Chainer, Nightmare Adept");
        addCardToZone("Chaos Warp", f.p, ZoneType.Library); addCardToZone("Shivan Dragon", f.p, ZoneType.Library);
        addCard("Rest in Peace", f.o); f.g.getAction().checkStateEffects(true);
        AtomicReference<CardChoiceDecisionContext> captured = new AtomicReference<>(); controller(f, ctx -> { var c = (CardChoiceDecisionContext)ctx; captured.set(c); return Optional.of(answer(c, "Chaos Warp")); });
        play(f, spell(f, "Demonic Tutor")); Assert.assertEquals(countCardsWithName(f.g, "Chaos Warp", ZoneType.Hand), 1);
        Assert.assertNotNull(captured.get()); Assert.assertTrue(captured.get().options().size() >= 2);
    }

    @Test public void sacrificeGoblinPreservesChainerEngine() {
        F f = fixture(); ready(f, "Chainer, Nightmare Adept"); ready(f, "Raging Goblin");
        controller(f, ctx -> Optional.of(answer((CardChoiceDecisionContext)ctx, "Raging Goblin")));
        var a = spell(f, "Diabolic Edict"); a.getTargets().add(f.p); mana(f, 1, 1); play(f, a);
        Assert.assertNotNull(live(f, "Chainer, Nightmare Adept")); Assert.assertEquals(countCardsWithName(f.g, "Raging Goblin", ZoneType.Graveyard), 1);
    }

    @Test public void invalidMetadataAndProviderFailuresRetainNativeCard() {
        for (String failure : List.of("id", "fingerprint", "option", "throw", "null", "timeout")) {
            F f = fixture(); var sa = chainer(f); Card dragon = addCardToZone("Shivan Dragon", f.p, ZoneType.Hand);
            Card land = addCardToZone("Mountain", f.p, ZoneType.Hand);
            controller(f, new AiDecisionProvider() {
                public Optional<AiDecision> choose(AiDecisionContext ctx) { return Optional.empty(); }
                public AiDecisionResult chooseWithDiagnostics(AiDecisionContext ctx) {
                    var c = (CardChoiceDecisionContext)ctx;
                    if (failure.equals("throw")) throw new IllegalStateException(); if (failure.equals("null")) return null;
                    if (failure.equals("timeout")) return AiDecisionResult.failed(AiDecisionFailureReason.TIMEOUT, "test", null, 3);
                    var d = new AiDecision(failure.equals("id") ? "stale" : c.decisionId(), failure.equals("fingerprint") ? "stale" : c.stateFingerprint(), failure.equals("option") ? "INVENTED" : c.options().get(0).optionId());
                    return AiDecisionResult.accepted(d, "test", null, 1);
                }
            });
            var selected = ExternalGraveyardChoices.single(f.p, sa, "Discard", new CardCollection(List.of(dragon, land)), new CardCollection(land), c -> c.isInZone(ZoneType.Hand), false);
            Assert.assertEquals(selected.get(0), land, failure);
        }
    }

    @Test public void changedLiveStateRejectsRecommendation() {
        F f = fixture(); var sa = chainer(f); Card dragon = addCardToZone("Shivan Dragon", f.p, ZoneType.Hand), land = addCardToZone("Mountain", f.p, ZoneType.Hand);
        controller(f, ctx -> { f.o.setLife(39, null); return Optional.of(answer((CardChoiceDecisionContext)ctx, "Shivan Dragon")); });
        var selected = ExternalGraveyardChoices.single(f.p, sa, "Discard", new CardCollection(List.of(dragon, land)), new CardCollection(land), c -> c.isInZone(ZoneType.Hand), false);
        Assert.assertEquals(selected.get(0), land);
    }

    @Test public void hiddenOpponentHandAndUnsearchedLibraryNeverEnterProjection() {
        F f = fixture(); var sa = chainer(f); Card dragon = addCardToZone("Shivan Dragon", f.p, ZoneType.Hand), land = addCardToZone("Mountain", f.p, ZoneType.Hand);
        addCardToZone("Black Lotus", f.o, ZoneType.Hand); addCardToZone("Ancestral Recall", f.p, ZoneType.Library);
        controller(f, ctx -> { String json = new Gson().toJson(ctx); Assert.assertFalse(json.contains("Black Lotus")); Assert.assertFalse(json.contains("Ancestral Recall")); return Optional.of(answer((CardChoiceDecisionContext)ctx, "Shivan Dragon")); });
        Assert.assertEquals(ExternalGraveyardChoices.single(f.p, sa, "Discard", new CardCollection(List.of(dragon, land)), new CardCollection(land), c -> true, false).get(0), dragon);
    }

    @Test public void mainPhaseChoosesLegalGraveyardCreatureOverHandCreature() {
        F f = fixture(); mana(f, 6, 3); var activate = chainer(f);
        addCardToZone("Mountain", f.p, ZoneType.Hand); addCardToZone("Chaos Warp", f.p, ZoneType.Hand);
        addCardToZone("Doomed Necromancer", f.p, ZoneType.Graveyard); addCardToZone("Shivan Dragon", f.p, ZoneType.Graveyard);
        controller(f, ctx -> Optional.of(answer((CardChoiceDecisionContext)ctx, "Mountain"))); play(f, activate);
        addCardToZone("Hill Giant", f.p, ZoneType.Hand);
        f.g.getPhaseHandler().devModeSet(forge.game.phase.PhaseType.MAIN2, f.p);
        f.p.setLandsPlayedThisTurn(1);
        AtomicReference<MainPhaseDecisionContext> captured = new AtomicReference<>();
        AiDecisionProvider provider = ctx -> {
            var c = (MainPhaseDecisionContext)ctx; captured.set(c);
            var option = c.legalActions().stream().filter(a -> a.sourceName().equals("Doomed Necromancer")).findFirst().orElseThrow();
            return Optional.of(new AiDecision(c.decisionId(), c.state().fingerprint(), option.actionId()));
        };
        var hybrid = new PlayerControllerAi(f.g, f.p, f.p.getLobbyPlayer(), provider, false, true, 8);
        f.p.dangerouslySetController(hybrid);
        AiDecisionMetrics.reset();
        var choices = hybrid.chooseSpellAbilityToPlay();
        Assert.assertNotNull(captured.get());
        Assert.assertTrue(captured.get().legalActions().stream().anyMatch(a -> a.sourceName().equals("Hill Giant")));
        var selected = choices.get(0); Assert.assertEquals(selected.getHostCard().getName(), "Doomed Necromancer");
        Assert.assertTrue(selected.canPlay());
        Assert.assertSame(selected.getMayPlay().getHostCard().getGame(), f.g, "Live action must retain a live permission");
        play(f, selected); Assert.assertTrue(live(f, "Doomed Necromancer").hasKeyword("Haste"));
        Card remaining = f.p.getCardsIn(ZoneType.Graveyard).stream().filter(c -> c.getName().equals("Shivan Dragon")).findFirst().orElseThrow();
        Assert.assertTrue(remaining.getAllPossibleAbilities(f.p, false).stream().filter(SpellAbility::isSpell).noneMatch(SpellAbility::canPlay),
                "The real one-creature permission must be consumed");
    }

    @Test public void costPreviewCannotInvokeExternalDiscardChoice() {
        F f = fixture(); mana(f, 6, 0); var a = chainer(f);
        addCardToZone("Mountain", f.p, ZoneType.Hand); addCardToZone("Shivan Dragon", f.p, ZoneType.Hand);
        controller(f, ctx -> { Assert.fail("A preview called the provider"); return Optional.empty(); });
        var cost = a.getPayCosts().getCostParts().stream().filter(c -> c instanceof forge.game.cost.CostDiscard).findFirst().orElseThrow();
        Assert.assertNotNull(cost.accept(new AiCostDecision(f.p, a, false)));
    }

    @Test public void disabledSingletonAndNativeDeclineNeverCallProvider() {
        F f = fixture(); var sa = chainer(f); Card land = addCardToZone("Mountain", f.p, ZoneType.Hand), dragon = addCardToZone("Shivan Dragon", f.p, ZoneType.Hand);
        controller(f, ctx -> { Assert.fail("Trivial/disabled choice invoked provider"); return Optional.empty(); });
        var pool = new CardCollection(List.of(land, dragon));
        ExternalGraveyardChoices.single(f.p, sa, "Discard", pool, CardCollection.EMPTY, c -> true, false);
        ExternalGraveyardChoices.single(f.p, sa, "Discard", new CardCollection(land), new CardCollection(land), c -> true, false);
        System.clearProperty(ExternalGraveyardChoices.ENABLED);
        Assert.assertEquals(ExternalGraveyardChoices.single(f.p, sa, "Discard", pool, new CardCollection(land), c -> true, false).get(0), land);
    }
}

