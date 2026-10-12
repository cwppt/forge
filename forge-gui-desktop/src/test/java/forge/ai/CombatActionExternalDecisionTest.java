package forge.ai;

import com.google.gson.Gson;
import forge.ai.decision.*;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class CombatActionExternalDecisionTest extends AITest {
    record Fixture(Game game, Player ai, Player enemy, Combat combat, Card attacker, Card blocker) { }
    @BeforeMethod public void reset() { AiDecisionMetrics.reset(); }
    private Fixture fixture() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1), enemy = game.getPlayers().get(0);
        Card attacker = addCard("Hill Giant", enemy), blocker = addCard("Runeclaw Bear", ai);
        attacker.setSickness(false); blocker.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, enemy);
        game.getPhaseHandler().setPriority(ai);
        Combat combat = new Combat(enemy); game.getPhaseHandler().setCombat(combat);
        combat.addAttacker(attacker, ai); combat.addBlocker(attacker, blocker);
        addCards("Forest", 5, ai); addCards("Mountain", 5, ai); addCards("Swamp", 5, ai);
        return new Fixture(game, ai, enemy, combat, attacker, blocker);
    }
    private PlayerControllerAi controller(Fixture f, AiDecisionProvider provider, boolean enabled) {
        PlayerControllerAi c = new PlayerControllerAi(f.game(), f.ai(), f.ai().getLobbyPlayer(), provider, false, false, 3);
        f.ai().dangerouslySetController(c); c.getAi().configureExternalCombatActions(enabled, 4); return c;
    }
    private PreparedAiAction action(Fixture f, String name, Card target) {
        Card card = addCardToZone(name, f.ai(), ZoneType.Hand);
        SpellAbility sa = card.getFirstSpellAbility().copy(f.ai()); sa.setActivatingPlayer(f.ai());
        if (target != null) sa.getTargets().add(target);
        return new PreparedAiAction("INTERNAL", sa, card.getFirstSpellAbility(), card,
                PreparedAiAction.ActionCategory.SPELL, 0, sa.toString(), true);
    }
    private ExternalCombatActions.Prepared prepare(Fixture f, PreparedAiAction... actions) {
        return ExternalCombatActions.snapshot(f.ai(), f.combat(), List.of(actions), 4);
    }
    private AiDecision respond(CombatActionDecisionContext c, String option) {
        return new AiDecision(c.decisionId(), c.stateFingerprint(), option);
    }
    @Test public void disabledDoesNotCallProvider() {
        Fixture f = fixture(); action(f, "Giant Growth", f.blocker());
        AtomicInteger calls = new AtomicInteger();
        controller(f, c -> { calls.incrementAndGet(); return Optional.empty(); }, false).chooseSpellAbilityToPlay();
        Assert.assertEquals(calls.get(), 0); Assert.assertTrue(AiDecisionMetrics.combatActionEvents().isEmpty());
    }
    @DataProvider public Object[][] outsideWindows() { return new Object[][] {
        {PhaseType.MAIN1}, {PhaseType.MAIN2}, {PhaseType.COMBAT_BEGIN}, {PhaseType.COMBAT_DAMAGE}, {PhaseType.COMBAT_END}, {PhaseType.END_OF_TURN} }; }
    @Test(dataProvider="outsideWindows") public void noProviderOutsideWindows(PhaseType phase) {
        Fixture f = fixture(); action(f, "Giant Growth", f.blocker());
        f.game().getPhaseHandler().devModeSet(phase, f.enemy()); f.game().getPhaseHandler().setPriority(f.ai());
        Assert.assertFalse(ExternalCombatActions.window(f.ai()));
        AtomicInteger calls = new AtomicInteger();
        controller(f, c -> { calls.incrementAndGet(); return Optional.empty(); }, true).chooseSpellAbilityToPlay();
        Assert.assertEquals(calls.get(), 0);
    }
    @DataProvider public Object[][] supportedWindows() { return new Object[][] {
        {PhaseType.COMBAT_DECLARE_ATTACKERS}, {PhaseType.COMBAT_DECLARE_BLOCKERS}, {PhaseType.COMBAT_FIRST_STRIKE_DAMAGE} }; }
    @Test(dataProvider="supportedWindows") public void supportedWindowAndPass(PhaseType phase) {
        Fixture f = fixture(); f.game().getPhaseHandler().devModeSet(phase, f.enemy(), false); f.game().getPhaseHandler().setCombat(f.combat()); f.game().getPhaseHandler().setPriority(f.ai());
        var p = prepare(f, action(f, "Giant Growth", f.blocker())); Assert.assertNotNull(p);
        Assert.assertTrue(p.context().passAvailable()); Assert.assertTrue(ExternalCombatActions.window(f.ai()));
        Assert.assertTrue(ExternalCombatActions.revalidate(controller(f, null, false).getAi(), f.ai(), p, null, true));
    }
    @Test public void passAppliedAndIdenticalPriorityDeduplicated() {
        Fixture f = fixture(); action(f, "Giant Growth", f.blocker());
        AtomicInteger calls = new AtomicInteger();
        var c = controller(f, raw -> { calls.incrementAndGet(); var ctx = (CombatActionDecisionContext)raw;
            Assert.assertTrue(ctx.passAvailable()); Assert.assertFalse(ctx.options().isEmpty()); return Optional.of(respond(ctx, "PASS")); }, true);
        Assert.assertNull(c.chooseSpellAbilityToPlay()); Assert.assertNull(c.chooseSpellAbilityToPlay());
        Assert.assertEquals(calls.get(), 1); Assert.assertTrue(AiDecisionMetrics.combatActionEvents().get(0).passSelection());
    }
    @Test public void realPreparationAndExternalActionPreserveTarget() {
        Fixture f = fixture(); Card growth = action(f, "Giant Growth", f.blocker()).sourceCard();
        AtomicReference<CombatActionDecisionContext> request = new AtomicReference<>();
        var c = controller(f, raw -> { var ctx = (CombatActionDecisionContext)raw; request.set(ctx);
            return Optional.of(respond(ctx, ctx.options().get(0).optionId())); }, true);
        var chosen = c.chooseSpellAbilityToPlay(); Assert.assertNotNull(chosen); Assert.assertNotNull(request.get());
        Assert.assertEquals(chosen.get(0).getHostCard(), growth);
        Assert.assertTrue(chosen.get(0).getTargets().contains(f.blocker()));
        Assert.assertFalse(AiDecisionMetrics.combatActionEvents().get(0).fallback());
    }
    @DataProvider public Object[][] usefulSpells() { return new Object[][] {
        {"Giant Growth", true}, {"Blossoming Defense", true}, {"Doom Blade", false}, {"Shock", false} }; }
    @Test(dataProvider="usefulSpells") public void combatEffectWhitelist(String card, boolean own) {
        Fixture f = fixture(); var a = action(f, card, own ? f.blocker() : f.attacker());
        Assert.assertTrue(ExternalCombatActions.relevant(a, f.combat())); Assert.assertNotNull(prepare(f, a));
        Assert.assertTrue(ExternalCombatActions.revalidate(controller(f, null, false).getAi(), f.ai(), prepare(f,a), a, false));
    }
    @DataProvider public Object[][] unrelated() { return new Object[][] { {"Opt"}, {"Divination"}, {"Fog"}, {"Counterspell"} }; }
    @Test(dataProvider="unrelated") public void unsupportedOrUnrelatedExcluded(String name) {
        Fixture f = fixture(); Assert.assertNull(prepare(f, action(f, name, null)));
    }
    @Test public void nonCombatTargetExcluded() {
        Fixture f = fixture(); Card other = addCard("Grizzly Bears", f.ai());
        Assert.assertNull(prepare(f, action(f,"Giant Growth",other)));
    }
    @Test public void simpleFlashCreatureBeforeBlockersOnly() {
        Fixture f=fixture();f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS,f.enemy(),false);
        var a=action(f,"Ashcoat Bear",null);Assert.assertNotNull(prepare(f,a));
        f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS,f.enemy(),false);
        Assert.assertNull(prepare(f,a));
    }
    @Test public void flashEtbNestedCallbackExcluded() {
        Fixture f=fixture();Assert.assertFalse(ExternalCombatActions.safeTemplate(action(f,"Briarpack Alpha",null).spellAbility()));
    }
    @Test public void colorChoiceProtectionExcluded() {
        Fixture f=fixture();Assert.assertNull(prepare(f,action(f,"Gods Willing",f.blocker())));
    }
    @Test public void mandatoryTriggerExcluded() {
        Fixture f=fixture();var sa=org.mockito.Mockito.mock(SpellAbility.class);
        org.mockito.Mockito.when(sa.getHostCard()).thenReturn(f.blocker());org.mockito.Mockito.when(sa.isTrigger()).thenReturn(true);
        Assert.assertFalse(ExternalCombatActions.safeTemplate(sa));
    }
    @Test public void replacementExcluded() {
        Fixture f=fixture();var sa=org.mockito.Mockito.mock(SpellAbility.class);
        org.mockito.Mockito.when(sa.getHostCard()).thenReturn(f.blocker());org.mockito.Mockito.when(sa.isReplacementAbility()).thenReturn(true);
        Assert.assertFalse(ExternalCombatActions.safeTemplate(sa));
    }
    @Test public void changedCombatRejectsEvenPass() {
        Fixture f=fixture();var p=prepare(f,action(f,"Giant Growth",f.blocker()));
        f.game().getPhaseHandler().setCombat(new Combat(f.enemy()));
        Assert.assertFalse(ExternalCombatActions.revalidate(controller(f,null,false).getAi(),f.ai(),p,null,true));
    }
    @Test public void damageOrderStillRoutesAfterCombatActionPass() {
        Fixture f=fixture();f.game().getRules().setOrderCombatants(true);
        var second=addCard("Grizzly Bears",f.ai());f.combat().addBlocker(f.attacker(),second);
        var a=action(f,"Giant Growth",f.blocker());AiDecisionProvider pass=raw -> Optional.of(respond((CombatActionDecisionContext)raw,"PASS"));
        var c=controller(f,pass,true);new ExternalCombatActions().choose(c.getAi(),f.ai(),pass,List.of(a.originalAbility()),a,a.spellAbility(),4);
        var heuristic=new forge.game.card.CardCollection(f.combat().getBlockers(f.attacker()));
        var chosen=ExternalCombatDamageOrder.choose(f.enemy(),f.attacker(),heuristic,true,raw -> {
            var ctx=(CombatDamageOrderDecisionContext)raw;return Optional.of(new AiDecision(ctx.decisionId(),ctx.stateFingerprint(),ctx.options().get(0).optionId()));
        },6);
        Assert.assertEquals(new HashSet<>(chosen),new HashSet<>(heuristic));Assert.assertEquals(AiDecisionMetrics.combatDamageOrderEvents().size(),1);
    }
    @Test public void manaAbilityExcluded() {
        Fixture f = fixture(); Card forest = addCard("Forest", f.ai());
        Assert.assertFalse(ExternalCombatActions.safeTemplate(forest.getManaAbilities().get(0)));
    }
    @Test public void activatedCombatantSelfPumpSupported() {
        Fixture f = fixture(); Card rootwalla = addCard("Rootwalla", f.ai()); rootwalla.setSickness(false);
        f.combat().addBlocker(f.attacker(), rootwalla);
        SpellAbility sa = rootwalla.getSpellAbilities().stream().filter(SpellAbility::isActivatedAbility).findFirst().orElseThrow();
        sa.setActivatingPlayer(f.ai());
        var action = new PreparedAiAction("internal", sa, sa, rootwalla, PreparedAiAction.ActionCategory.ACTIVATED_ABILITY, 0, sa.toString(), true);
        Assert.assertTrue(ExternalCombatActions.relevant(action,f.combat())); Assert.assertNotNull(prepare(f,action));
    }
    @Test public void noSafeActionNoCall() {
        Fixture f = fixture(); action(f,"Opt",null); AtomicInteger calls = new AtomicInteger();
        controller(f, c -> { calls.incrementAndGet(); return Optional.empty(); },true).chooseSpellAbilityToPlay();
        Assert.assertEquals(calls.get(),0);
    }
    @Test public void opaqueIdsAndFingerprintDeterministicAndBounded() {
        Fixture f = fixture(); List<PreparedAiAction> actions = new ArrayList<>();
        for(String card : List.of("Giant Growth","Titanic Growth","Might of Old Krosa","Groundswell","Mutagenic Growth","Blossoming Defense","Ranger's Guile","Brute Force","Bull Rush","Invigorate","Colossal Might","Vines of Vastwood")) actions.add(action(f,card,f.blocker()));
        var one = ExternalCombatActions.snapshot(f.ai(),f.combat(),actions,100);
        var two = ExternalCombatActions.snapshot(f.ai(),f.combat(),actions,100);
        Assert.assertEquals(one.context().options().size(),8);
        Assert.assertEquals(one.context().stateFingerprint(),two.context().stateFingerprint());
        Assert.assertEquals(one.context().options(),two.context().options());
        Assert.assertTrue(one.context().options().stream().allMatch(o -> o.optionId().matches("OPT_[0-9a-f]{64}")));
        Assert.assertEquals(ExternalCombatActions.snapshot(f.ai(),f.combat(),actions,1).mapping().size(),1);
    }
    @Test public void engineTimestampIsOnlyAnInternalStalenessGuard() {
        Fixture f=fixture();var a=action(f,"Giant Growth",f.blocker());var p=prepare(f,a);
        a.sourceCard().setGameTimestamp(a.sourceCard().getGameTimestamp()+100);
        var current=prepare(f,a);Assert.assertEquals(p.context().options(),current.context().options());
        Assert.assertFalse(ExternalCombatActions.revalidate(controller(f,null,false).getAi(),f.ai(),p,a,false));
    }
    @Test public void duplicatePreparedVariantsDeduplicated() {
        Fixture f = fixture(); var a = action(f,"Giant Growth",f.blocker());
        Assert.assertEquals(prepare(f,a,a,a).mapping().size(),1);
    }
    @Test public void relevantCostStaticShownWithoutUnrelatedBattlefieldDump() {
        Fixture f=fixture();addCard("Thalia, Guardian of Thraben",f.enemy());addCard("Grizzly Bears",f.enemy());
        var state=prepare(f,action(f,"Giant Growth",f.blocker())).context().state();
        Assert.assertTrue(state.relevantBattlefield().stream().anyMatch(c -> c.name().equals("Thalia, Guardian of Thraben") && !c.visibleEffects().isEmpty()));
        Assert.assertFalse(state.relevantBattlefield().stream().anyMatch(c -> c.name().equals("Grizzly Bears")));
    }
    @Test public void changedCostStaticRejectsPreparedSnapshot() {
        Fixture f=fixture();var a=action(f,"Giant Growth",f.blocker());var p=prepare(f,a);
        addCard("Thalia, Guardian of Thraben",f.enemy());
        Assert.assertFalse(ExternalCombatActions.revalidate(controller(f,null,false).getAi(),f.ai(),p,a,false));
    }
    @Test public void opponentHiddenInformationAbsent() {
        Fixture f=fixture(); addCardToZone("Black Lotus",f.enemy(),ZoneType.Hand);
        String json=new Gson().toJson(prepare(f,action(f,"Giant Growth",f.blocker())).context());
        Assert.assertFalse(json.contains("Black Lotus")); Assert.assertFalse(json.contains("heuristic")); Assert.assertFalse(json.contains("originalCandidateIndex"));
    }
    @DataProvider public Object[][] invalidResponses() { return new Object[][] { {"id"}, {"fingerprint"}, {"option"}, {"exception"}, {"empty"}, {"stale"} }; }
    @Test(dataProvider="invalidResponses") public void invalidResponseRetainsNativeAction(String invalid) {
        Fixture f=fixture(); var a=action(f,"Giant Growth",f.blocker());
        AiDecisionProvider provider=raw -> { var ctx=(CombatActionDecisionContext)raw;
            if(invalid.equals("exception")) throw new IllegalStateException("test");
            if(invalid.equals("empty")) return Optional.empty();
            if(invalid.equals("stale")) f.blocker().setTapped(true);
            return Optional.of(new AiDecision(invalid.equals("id")?"wrong":ctx.decisionId(),
                invalid.equals("fingerprint")?"wrong":ctx.stateFingerprint(), invalid.equals("option")?"UNKNOWN":ctx.options().get(0).optionId()));
        };
        var c=controller(f,provider,true);
        SpellAbility chosen=new ExternalCombatActions().choose(c.getAi(),f.ai(),provider,List.of(a.originalAbility()),a,a.spellAbility(),4);
        Assert.assertSame(chosen,a.spellAbility());
        Assert.assertTrue(AiDecisionMetrics.combatActionEvents().get(0).fallback());
    }
    @Test public void sourceIncarnationAndTargetStateAndPayabilityRevalidated() {
        Fixture f=fixture();var a=action(f,"Giant Growth",f.blocker());var p=prepare(f,a);var ai=controller(f,null,false).getAi();
        f.ai().getZone(ZoneType.Hand).remove(a.sourceCard()); f.ai().getZone(ZoneType.Graveyard).add(a.sourceCard());
        Assert.assertFalse(ExternalCombatActions.revalidate(ai,f.ai(),p,a,false));
        Fixture g=fixture();var b=action(g,"Giant Growth",g.blocker());var q=prepare(g,b);var brain=controller(g,null,false).getAi();
        g.blocker().setTapped(true);Assert.assertFalse(ExternalCombatActions.revalidate(brain,g.ai(),q,b,false));
        Fixture h=fixture();var d=action(h,"Giant Growth",h.blocker());var t=prepare(h,d);
        h.ai().getCardsIn(ZoneType.Battlefield).forEach(card -> card.setTapped(true));
        Assert.assertFalse(ExternalCombatActions.revalidate(controller(h,null,false).getAi(),h.ai(),t,d,false));
    }
    @Test public void passRejectsChangedPriority() {
        Fixture f=fixture();var p=prepare(f,action(f,"Giant Growth",f.blocker()));f.game().getPhaseHandler().setPriority(f.enemy());
        Assert.assertFalse(ExternalCombatActions.revalidate(controller(f,null,false).getAi(),f.ai(),p,null,true));
    }
    @Test public void opposingStackResponseTakesPrecedenceWithoutDuplicateCall() {
        Fixture f=fixture();addCards("Island",2,f.ai());addCardToZone("Counterspell",f.ai(),ZoneType.Hand);
        var pending=addCardToZone("Shock",f.enemy(),ZoneType.Hand).getFirstSpellAbility();
        pending.setActivatingPlayer(f.enemy());pending.getTargets().add(f.blocker());f.game().getStack().add(pending);
        AtomicInteger calls=new AtomicInteger();
        AiDecisionProvider provider=raw -> {calls.incrementAndGet();Assert.assertTrue(raw instanceof StackResponseDecisionContext);
            var ctx=(StackResponseDecisionContext)raw;return Optional.of(new AiDecision(ctx.decisionId(),ctx.stateFingerprint(),"PASS"));};
        var c=controller(f,provider,true);c.getAi().configureExternalStackResponses(provider,true,4,false);
        Assert.assertNull(c.chooseSpellAbilityToPlay());Assert.assertEquals(calls.get(),1);
        Assert.assertTrue(AiDecisionMetrics.combatActionEvents().isEmpty());Assert.assertEquals(AiDecisionMetrics.stackResponseEvents().size(),1);
    }
    @Test public void opposingStackBypassesCombatAction() {
        Fixture f=fixture();var pending=action(f,"Shock",f.attacker()).spellAbility();
        f.game().getStack().add(pending); Assert.assertFalse(ExternalCombatActions.window(f.ai()));
    }
    @Test public void preparedXPreservedAndMutationRejected() {
        Fixture f=fixture();var a=action(f,"Untamed Might",f.blocker());a.spellAbility().setXManaCostPaid(3);
        var p=prepare(f,a);Assert.assertNotNull(p);Assert.assertEquals(p.context().options().get(0).xValue(),Integer.valueOf(3));
        a.spellAbility().setXManaCostPaid(4);
        Assert.assertFalse(ExternalCombatActions.revalidate(controller(f,null,false).getAi(),f.ai(),p,a,false));
    }
    @Test public void preparedModesPreservedAndUnresolvedModesExcluded() {
        Fixture f=fixture();addCard("Island",f.ai());addCard("Plains",f.ai());
        Card card=addCardToZone("Dromar's Charm",f.ai(),ZoneType.Hand);
        var c=controller(f,null,false);
        Assert.assertNull(prepare(f,new PreparedAiAction("x",card.getFirstSpellAbility(),card.getFirstSpellAbility(),card,
                PreparedAiAction.ActionCategory.SPELL,0,"charm",true)));
        SpellAbility sa=card.getFirstSpellAbility().copy(f.ai());sa.setActivatingPlayer(f.ai());
        var mode=forge.game.ability.effects.CharmEffect.makePossibleOptions(sa).stream()
                .filter(m -> m.getApi()==forge.game.ability.ApiType.Pump).findFirst().orElseThrow();
        mode.getTargets().add(f.attacker());sa.setChosenList(List.of(mode));sa.setSubAbility(mode);
        var a=new PreparedAiAction("mode",sa,card.getFirstSpellAbility(),card,PreparedAiAction.ActionCategory.SPELL,0,"charm",true);
        var prepared=prepare(f,a);Assert.assertNotNull(prepared);Assert.assertFalse(prepared.context().options().get(0).modes().isEmpty());
        Assert.assertTrue(ExternalCombatActions.revalidate(c.getAi(),f.ai(),prepared,a,false));
    }
    @Test public void executingPreparedModalActionPreservesModesAndTargets() {
        Fixture f=fixture();addCard("Island",f.ai());addCard("Plains",f.ai());
        Card card=addCardToZone("Dromar's Charm",f.ai(),ZoneType.Hand);
        SpellAbility sa=card.getFirstSpellAbility().copy(f.ai());sa.setActivatingPlayer(f.ai());
        var mode=forge.game.ability.effects.CharmEffect.makePossibleOptions(sa).stream()
                .filter(m -> m.getApi()==forge.game.ability.ApiType.Pump).findFirst().orElseThrow();
        mode.getTargets().add(f.attacker());sa.setChosenList(List.of(mode));sa.setSubAbility(mode);
        var a=new PreparedAiAction("mode",sa,card.getFirstSpellAbility(),card,PreparedAiAction.ActionCategory.SPELL,0,"charm",true);
        AiDecisionProvider provider=raw -> {var ctx=(CombatActionDecisionContext)raw;return Optional.of(respond(ctx,ctx.options().get(0).optionId()));};
        var c=controller(f,provider,true);
        var chosen=new ExternalCombatActions().choose(c.getAi(),f.ai(),provider,List.of(card.getFirstSpellAbility()),a,sa,4);
        Assert.assertSame(chosen,sa);c.playChosenSpellAbility(chosen);
        var onStack=f.game().getStack().peekAbility();Assert.assertNotNull(onStack);
        Assert.assertEquals(onStack.getChosenList().size(),1);
        Assert.assertEquals(onStack.getChosenList().get(0).getApi(),forge.game.ability.ApiType.Pump);
        Assert.assertTrue(onStack.getSubAbility().getTargets().contains(f.attacker()));
    }
    @Test public void executingPreparedXPreservesPaidValue() {
        Fixture f=fixture();var a=action(f,"Untamed Might",f.blocker());a.spellAbility().setXManaCostPaid(3);
        AiDecisionProvider provider=raw -> {var ctx=(CombatActionDecisionContext)raw;return Optional.of(respond(ctx,ctx.options().stream().filter(o -> Integer.valueOf(3).equals(o.xValue())).findFirst().orElseThrow().optionId()));};
        var c=controller(f,provider,true);var chosen=new ExternalCombatActions().choose(c.getAi(),f.ai(),provider,
                List.of(a.originalAbility()),a,a.spellAbility(),4);Assert.assertEquals(chosen.getXManaCostPaid(),Integer.valueOf(3));c.playChosenSpellAbility(chosen);
        var onStack=f.game().getStack().peekAbility();Assert.assertNotNull(onStack);
        Assert.assertEquals(onStack.getXManaCostPaid(),Integer.valueOf(3));Assert.assertTrue(onStack.getTargets().contains(f.blocker()));
    }
    @Test public void nonDefaultActionAppliedWithExactPreparedTargets() {
        Fixture f=fixture();var first=action(f,"Giant Growth",f.blocker());var second=action(f,"Titanic Growth",f.blocker());
        AiDecisionProvider provider=raw -> {var ctx=(CombatActionDecisionContext)raw;return Optional.of(respond(ctx,
                ctx.options().stream().filter(o -> o.sourceName().equals("Titanic Growth")).findFirst().orElseThrow().optionId()));};
        var c=controller(f,provider,true);
        var chosen=new ExternalCombatActions().choose(c.getAi(),f.ai(),provider,List.of(second.originalAbility()),first,first.spellAbility(),4);
        Assert.assertEquals(chosen.getHostCard().getName(),"Titanic Growth");Assert.assertTrue(chosen.getTargets().contains(f.blocker()));
        Assert.assertEquals(AiDecisionMetrics.combatActionEvents().get(0).disagreementCategory(),"DIFFERENT_ACTION");
    }
    @Test public void forgePassModelActionDisagreementTracked() {
        Fixture f=fixture();var a=action(f,"Giant Growth",f.blocker());
        AiDecisionProvider provider=raw -> {var ctx=(CombatActionDecisionContext)raw;return Optional.of(respond(ctx,ctx.options().get(0).optionId()));};
        var c=controller(f,provider,true);
        Assert.assertNotNull(new ExternalCombatActions().choose(c.getAi(),f.ai(),provider,List.of(a.originalAbility()),null,null,4));
        Assert.assertEquals(AiDecisionMetrics.combatActionEvents().get(0).disagreementCategory(),"FORGE_PASS_EXTERNAL_ACTION");
    }
    @Test public void forgeActionModelPassDisagreementTracked() {
        Fixture f=fixture();var a=action(f,"Giant Growth",f.blocker());
        AiDecisionProvider provider=raw -> Optional.of(respond((CombatActionDecisionContext)raw,"PASS"));var c=controller(f,provider,true);
        Assert.assertNull(new ExternalCombatActions().choose(c.getAi(),f.ai(),provider,List.of(a.originalAbility()),a,a.spellAbility(),4));
        Assert.assertEquals(AiDecisionMetrics.combatActionEvents().get(0).disagreementCategory(),"FORGE_ACTION_EXTERNAL_PASS");
    }
    @Test public void providerRejectedSelectionRetainedForAuditOnly() {
        Fixture f=fixture();var a=action(f,"Giant Growth",f.blocker());
        AiDecisionProvider provider=new AiDecisionProvider() {
            public Optional<AiDecision> choose(AiDecisionContext c) { return Optional.empty(); }
            public AiDecisionResult chooseWithDiagnostics(AiDecisionContext raw) {
                var c=(CombatActionDecisionContext)raw;
                return new AiDecisionResult(Optional.empty(),AiDecisionFailureReason.INVALID_OPTION,"test",null,1,
                        new AiDecision(c.decisionId(),c.stateFingerprint(),"UNKNOWN"));
            }
        };
        var c=controller(f,provider,true);Assert.assertSame(new ExternalCombatActions().choose(c.getAi(),f.ai(),provider,
                List.of(a.originalAbility()),a,a.spellAbility(),4),a.spellAbility());
        var event=AiDecisionMetrics.combatActionEvents().get(0);Assert.assertEquals(event.returnedOptionId(),"UNKNOWN");
        Assert.assertEquals(event.liveRevalidationOutcome(),"NOT_RUN");Assert.assertTrue(event.fallback());
    }
    @Test public void nullProviderResultFallbackAndAgreementMetric() {
        Fixture f=fixture();var a=action(f,"Giant Growth",f.blocker());
        AiDecisionProvider provider=new AiDecisionProvider() {
            public Optional<AiDecision> choose(AiDecisionContext c) { return Optional.empty(); }
            public AiDecisionResult chooseWithDiagnostics(AiDecisionContext c) { return null; }
        };
        var c=controller(f,provider,true);Assert.assertSame(new ExternalCombatActions().choose(c.getAi(),f.ai(),provider,
                List.of(a.originalAbility()),a,a.spellAbility(),4),a.spellAbility());
        Assert.assertTrue(AiDecisionMetrics.combatActionSummary().contains("combatActionFallbacks=1"));
    }
    @DataProvider public Object[][] failures() { return new Object[][] {{AiDecisionFailureReason.TIMEOUT},{AiDecisionFailureReason.MALFORMED_RESPONSE},{AiDecisionFailureReason.PROVIDER_EXCEPTION}}; }
    @Test(dataProvider="failures") public void failedTransportRetainsOriginal(AiDecisionFailureReason reason) {
        Fixture f=fixture();var a=action(f,"Giant Growth",f.blocker());
        AiDecisionProvider provider=new AiDecisionProvider() {
            public Optional<AiDecision> choose(AiDecisionContext c) { return Optional.empty(); }
            public AiDecisionResult chooseWithDiagnostics(AiDecisionContext c) { return AiDecisionResult.failed(reason,"test",null,1); }
        };
        var c=controller(f,provider,true);Assert.assertSame(new ExternalCombatActions().choose(c.getAi(),f.ai(),provider,
                List.of(a.originalAbility()),a,a.spellAbility(),4),a.spellAbility());
        Assert.assertEquals(AiDecisionMetrics.combatActionEvents().get(0).failureReason(),reason);
    }
    @Test public void staleTargetZoneAndNewProtectionRejected() {
        Fixture f=fixture();var a=action(f,"Shock",f.attacker());var p=prepare(f,a);
        f.enemy().getZone(ZoneType.Battlefield).remove(f.attacker());f.enemy().getZone(ZoneType.Graveyard).add(f.attacker());
        Assert.assertFalse(ExternalCombatActions.revalidate(controller(f,null,false).getAi(),f.ai(),p,a,false));
        Fixture g=fixture();var b=action(g,"Shock",g.attacker());var q=prepare(g,b);
        g.attacker().addIntrinsicKeyword("Shroud");
        Assert.assertFalse(ExternalCombatActions.revalidate(controller(g,null,false).getAi(),g.ai(),q,b,false));
    }
    @Test public void immutableSnapshotCannotBeMutated() {
        Fixture f=fixture();var p=prepare(f,action(f,"Giant Growth",f.blocker()));
        Assert.expectThrows(UnsupportedOperationException.class,() -> p.context().options().clear());
        Assert.expectThrows(UnsupportedOperationException.class,() -> p.context().options().get(0).targets().clear());
    }
    @DataProvider public Object[][] declarationPaths() {return new Object[][] {{true,true},{true,false},{false,true},{false,false}};}
    @Test(dataProvider="declarationPaths") public void declarationSuccessOrFallbackThenCombatAction(boolean attackers,boolean accepted) {
        Fixture f=fixture();
        AiDecisionProvider declaration=raw -> {
            if(!accepted)return Optional.empty();
            if(raw instanceof CombatAttackersDecisionContext c) return Optional.of(new AiDecision(c.decisionId(),c.stateFingerprint(),
                    c.options().stream().max(Comparator.comparingInt(o -> o.attackers().size())).orElseThrow().optionId()));
            var c=(CombatBlockersDecisionContext)raw;return Optional.of(new AiDecision(c.decisionId(),c.stateFingerprint(),
                    c.options().stream().max(Comparator.comparingInt(o -> o.assignments().size())).orElseThrow().optionId()));
        };
        if(attackers) {
            f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS,f.enemy(),false);
            ExternalCombatAttackers.choose(f.enemy(),f.combat(),declaration,4);
        } else ExternalCombatBlockers.choose(f.ai(),f.combat(),declaration,4);
        f.game().getPhaseHandler().setPriority(f.ai());
        var a=action(f,"Shock",f.attacker());AiDecisionProvider provider=raw -> Optional.of(respond((CombatActionDecisionContext)raw,"PASS"));
        var c=controller(f,provider,true);
        Assert.assertNull(new ExternalCombatActions().choose(c.getAi(),f.ai(),provider,List.of(a.originalAbility()),a,a.spellAbility(),4));
        Assert.assertEquals(AiDecisionMetrics.combatActionEvents().size(),1);
    }
    @Test public void auditContainsPublicOptionsTelemetryAndEventualSequence() throws Exception {
        Fixture f=fixture();var path=java.nio.file.Files.createTempFile("combat-action-audit",".jsonl");
        AiDecisionMetrics.configureDecisionAudit(path);AiDecisionMetrics.observeCombatSequences(f.game());
        var a=action(f,"Giant Growth",f.blocker());AiDecisionProvider provider=raw -> Optional.of(respond((CombatActionDecisionContext)raw,"PASS"));
        var c=controller(f,provider,true);new ExternalCombatActions().choose(c.getAi(),f.ai(),provider,List.of(a.originalAbility()),a,a.spellAbility(),4);
        AiDecisionMetrics.completeGame(f.game().getId(),f.ai().getName(),false,10,3);
        String json=java.nio.file.Files.readString(path);Assert.assertTrue(json.contains("COMBAT_ACTION"));
        Assert.assertTrue(json.contains("eventualCombatResult"));Assert.assertTrue(json.contains("FORGE_ACTION_EXTERNAL_PASS" ) || AiDecisionMetrics.combatActionSummary().contains("combatActionForgeActionExternalPass=1"));
        var csv=java.nio.file.Files.createTempFile("combat-action-csv",".csv");AiDecisionMetrics.writeCsv(csv);
        var lines=java.nio.file.Files.readAllLines(csv);Assert.assertEquals(lines.size(),2);
        Assert.assertEquals(lines.get(0).split(",",-1).length,lines.get(1).split(",",-1).length);
        AiDecisionMetrics.reset();java.nio.file.Files.delete(path);java.nio.file.Files.delete(csv);
    }
    @Test public void sequenceCorrelationSharedWithoutInflatingStage12Calls() {
        Fixture f=fixture();var a=AiDecisionMetrics.combatCorrelation(f.game(),f.combat(),AiDecisionType.COMBAT_ATTACKERS);
        var b=AiDecisionMetrics.combatCorrelation(f.game(),f.combat(),AiDecisionType.COMBAT_ACTION);
        Assert.assertEquals(a.combatSequenceId(),b.combatSequenceId());
        Assert.assertTrue(AiDecisionMetrics.combatActionSummary().contains("combatActionProviderCalls=0"));
    }
}
