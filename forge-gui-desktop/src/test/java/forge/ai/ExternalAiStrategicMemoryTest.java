package forge.ai;

import com.google.gson.*;
import forge.ai.decision.*;
import forge.ai.decision.external.*;
import forge.game.*;
import forge.game.card.*;
import forge.game.combat.Combat;
import forge.game.event.*;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import java.net.URI;
import java.time.Duration;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ExternalAiStrategicMemoryTest extends AITest {
    record F(Game game,Player self,Player opponent) { }
    @BeforeMethod public void reset() { AiDecisionMetrics.reset(); }
    private F fixture(int max) {
        Game g=initAndCreateGame();Player p=g.getPlayers().get(1);
        ExternalAiStrategicMemory.configure(p,true,max);return new F(g,p,g.getPlayers().get(0));
    }
    private StrategicMemorySnapshot view(F f,AiDecisionType type) { return ExternalAiStrategicMemory.snapshot(f.self(),type,List.of()); }
    private String json(F f) { return new Gson().toJson(view(f,AiDecisionType.MAIN_PHASE_ACTION).view()); }
    private Card reveal(F f,String name,ZoneType zone) {
        Card c=addCardToZone(name,f.opponent(),zone);
        ((PlayerControllerAi)f.self().getController()).reveal(List.of(c.getView()),zone,f.opponent().getView(),null,true);return c;
    }
    private Card cast(F f,String name) {
        Card c=addCardToZone(name,f.opponent(),ZoneType.Hand);SpellAbility sa=c.getFirstSpellAbility();
        sa.setActivatingPlayer(f.opponent());
        if(sa.usesTargeting())sa.getTargets().add(addCard("Runeclaw Bear",f.opponent()));
        c=f.game().getAction().moveToStack(c,sa);sa.setHostCard(c);f.game().getStack().add(sa);return c;
    }
    @Test public void unknownOpponentHandNeverStored() {
        F f=fixture(12);addCardToZone("Counterspell",f.opponent(),ZoneType.Hand);
        Assert.assertFalse(json(f).contains("Counterspell"));Assert.assertEquals(view(f,AiDecisionType.MAIN_PHASE_ACTION).storedFacts(),0);
    }
    @Test public void unknownLibraryIdentityAndOrderNeverStored() {
        F f=fixture(12);addCardToZone("Counterspell",f.opponent(),ZoneType.Library);addCardToZone("Giant Growth",f.opponent(),ZoneType.Library);
        Assert.assertFalse(json(f).contains("Counterspell"));Assert.assertFalse(json(f).contains("Library"));
    }
    @Test public void hiddenFaceDownIdentityNeverStored() {
        F f=fixture(12);Card c=addCard("Counterspell",f.opponent());c.turnFaceDown(true);
        f.game().fireEvent(new GameEventCardChangeZone(c,null,f.opponent().getZone(ZoneType.Battlefield)));
        ExternalAiStrategicMemory.revealed(f.self(),List.of(c.getView()),ZoneType.Battlefield);
        Assert.assertFalse(json(f).contains("Counterspell"));
    }
    @Test public void legalRecipientRevealIsRemembered() {
        F f=fixture(12);reveal(f,"Counterspell",ZoneType.Hand);
        var known=view(f,AiDecisionType.MAIN_PHASE_ACTION).view().knownOpponentCards();
        Assert.assertEquals(known.size(),1);Assert.assertEquals(known.get(0).knowledgeStatus(),"CURRENTLY_KNOWN");
        Assert.assertEquals(known.get(0).location(),"Hand");Assert.assertEquals(known.get(0).interaction(),"COUNTER");
    }
    @Test public void revealDeliveredOnlyToAnotherPlayerNeverBroadcast() {
        F f=fixture(12);ExternalAiStrategicMemory.configure(f.opponent(),true,12);
        Card c=addCardToZone("Counterspell",f.opponent(),ZoneType.Hand);
        ((PlayerControllerAi)f.opponent().getController()).reveal(List.of(c.getView()),ZoneType.Hand,f.opponent().getView(),null,true);
        Assert.assertFalse(json(f).contains("Counterspell"));
    }
    @DataProvider public Object[][] publicZones() { return new Object[][]{{ZoneType.Graveyard},{ZoneType.Exile}}; }
    @Test(dataProvider="publicZones") public void visiblePublicZoneRemembered(ZoneType zone) {
        F f=fixture(12);Card c=addCardToZone("Counterspell",f.opponent(),zone);
        f.game().fireEvent(new GameEventCardChangeZone(c,null,f.opponent().getZone(zone)));
        var fact=view(f,AiDecisionType.MAIN_PHASE_ACTION).view().knownOpponentCards().get(0);
        Assert.assertEquals(fact.knowledgeStatus(),"PUBLIC_ZONE_CONFIRMED");Assert.assertEquals(fact.location(),zone.name());
    }
    @Test public void libraryRevealStoresPastIdentityButNeverLibraryLocationOrPosition() {
        F f=fixture(12);reveal(f,"Counterspell",ZoneType.Library);
        var fact=view(f,AiDecisionType.MAIN_PHASE_ACTION).view().knownOpponentCards().get(0);
        Assert.assertEquals(fact.knowledgeStatus(),"PREVIOUSLY_REVEALED_LOCATION_UNKNOWN");Assert.assertNull(fact.location());
        Assert.assertFalse(json(f).contains("Library"));
    }
    @Test public void hiddenTransitionInvalidatesLocation() {
        F f=fixture(12);Card c=reveal(f,"Counterspell",ZoneType.Hand);
        f.game().fireEvent(new GameEventCardChangeZone(c,f.opponent().getZone(ZoneType.Hand),f.opponent().getZone(ZoneType.Library)));
        var fact=view(f,AiDecisionType.MAIN_PHASE_ACTION).view().knownOpponentCards().get(0);
        Assert.assertNull(fact.location());Assert.assertEquals(fact.knowledgeStatus(),"PREVIOUSLY_REVEALED_LOCATION_UNKNOWN");
        Assert.assertTrue(view(f,AiDecisionType.MAIN_PHASE_ACTION).invalidatedFacts()>0);
    }
    @Test public void identicalHiddenCopyCannotProveKnownCopyRemainsInHand() {
        F f=fixture(12);reveal(f,"Counterspell",ZoneType.Hand);
        Card other=addCardToZone("Counterspell",f.opponent(),ZoneType.Hand);
        f.game().fireEvent(new GameEventCardChangeZone(other,f.opponent().getZone(ZoneType.Hand),f.opponent().getZone(ZoneType.Library)));
        Assert.assertTrue(view(f,AiDecisionType.MAIN_PHASE_ACTION).view().knownOpponentCards().stream().noneMatch(x->"CURRENTLY_KNOWN".equals(x.knowledgeStatus())));
    }
    @Test public void unrelatedHiddenDrawDoesNotInventOrInvalidateKnownIdentity() {
        F f=fixture(12);reveal(f,"Counterspell",ZoneType.Hand);
        Card c=addCardToZone("Doom Blade",f.opponent(),ZoneType.Hand);
        f.game().fireEvent(new GameEventCardChangeZone(c,f.opponent().getZone(ZoneType.Library),f.opponent().getZone(ZoneType.Hand)));
        Assert.assertFalse(json(f).contains("Doom Blade"));Assert.assertTrue(json(f).contains("CURRENTLY_KNOWN"));
    }
    @Test public void faceDownTransitionRemovesPublicCertaintyWithoutReadingNewIdentity() {
        F f=fixture(12);Card c=reveal(f,"Counterspell",ZoneType.Battlefield);c.turnFaceDown(true);
        Assert.assertEquals(view(f,AiDecisionType.MAIN_PHASE_ACTION).view().knownOpponentCards().get(0).knowledgeStatus(),"PREVIOUSLY_REVEALED_LOCATION_UNKNOWN");
    }
    @Test public void gameResetAndEndClearFacts() {
        F f=fixture(12);reveal(f,"Counterspell",ZoneType.Hand);
        f.game().fireEvent(new GameEventGameRestarted(f.self().getView()));Assert.assertEquals(view(f,AiDecisionType.MAIN_PHASE_ACTION).selectedFacts(),0);
        reveal(f,"Giant Growth",ZoneType.Hand);ExternalAiStrategicMemory.release(f.game().getId());Assert.assertNull(ExternalAiStrategicMemory.get(f.self()));
    }
    @Test public void noHistoryCrossesGamesOrObservers() {
        F f=fixture(12);reveal(f,"Counterspell",ZoneType.Hand);F next=fixture(12);
        Assert.assertFalse(json(next).contains("Counterspell"));Assert.assertNull(ExternalAiStrategicMemory.get(f.opponent()));
        ExternalAiStrategicMemory.reset();Assert.assertNull(ExternalAiStrategicMemory.get(f.self()));
    }
    @Test public void turnFactsExpireButDemonstratedInteractionRemains() {
        F f=fixture(12);cast(f,"Giant Growth");
        Assert.assertFalse(view(f,AiDecisionType.COMBAT_ACTION).view().currentTurnFacts().isEmpty());
        Assert.assertFalse(view(f,AiDecisionType.COMBAT_ACTION).view().interactionObserved().isEmpty());
        f.game().fireEvent(new GameEventTurnEnded());
        Assert.assertTrue(view(f,AiDecisionType.COMBAT_ACTION).view().currentTurnFacts().isEmpty());
        Assert.assertFalse(view(f,AiDecisionType.COMBAT_ACTION).view().interactionObserved().isEmpty());
    }
    @Test public void observedOncePerTurnResourceExpires() {
        F f=fixture(12);Card c=addCard("Rootwalla",f.opponent());
        var ability=c.getSpellAbilities().stream().filter(a->a.getApi()!=null&&a.getApi().name().equals("Pump")).findFirst().orElseThrow();
        ability.setActivatingPlayer(f.opponent());f.game().getStack().add(ability);
        var view=view(f,AiDecisionType.COMBAT_ACTION).view();
        Assert.assertTrue(view.currentTurnFacts().stream().anyMatch(x->x.category().equals("RESOURCE_USED")&&x.summary().contains("once-per-turn")));
        f.game().fireEvent(new GameEventTurnEnded());Assert.assertTrue(view(f,AiDecisionType.COMBAT_ACTION).view().currentTurnFacts().isEmpty());
    }
    @Test public void configuredAndHardBoundsAreEnforced() {
        F f=fixture(1000);for(int i=0;i<45;i++)reveal(f,"Counterspell",ZoneType.Hand);
        Assert.assertEquals(view(f,AiDecisionType.MAIN_PHASE_ACTION).storedFacts(),32);Assert.assertEquals(view(f,AiDecisionType.MAIN_PHASE_ACTION).selectedFacts(),32);
        ExternalAiStrategicMemory.configure(f.self(),true,3);for(int i=0;i<10;i++)reveal(f,"Counterspell",ZoneType.Hand);
        Assert.assertEquals(view(f,AiDecisionType.MAIN_PHASE_ACTION).storedFacts(),3);
    }
    @Test public void publicLocationIsRefreshedWithoutHiddenIdentityScan() {
        F f=fixture(12);Card c=reveal(f,"Counterspell",ZoneType.Graveyard);
        c=f.game().getAction().exile(c,null,null);
        var fact=view(f,AiDecisionType.MAIN_PHASE_ACTION).view().knownOpponentCards().get(0);
        Assert.assertEquals(fact.location(),"Exile");Assert.assertEquals(fact.knowledgeStatus(),"PUBLIC_ZONE_CONFIRMED");
        f.game().getAction().moveToLibrary(c,0,null);
        fact=view(f,AiDecisionType.MAIN_PHASE_ACTION).view().knownOpponentCards().get(0);
        Assert.assertNull(fact.location());Assert.assertEquals(fact.knowledgeStatus(),"PREVIOUSLY_REVEALED_LOCATION_UNKNOWN");
    }
    @Test public void memoryDefaultsAreIndependentOfFullGamePreset() {
        var prefs=new forge.localinstance.properties.ForgePreferences();prefs.reset();
        var enabled=forge.localinstance.properties.ForgePreferences.FPref.AI_EXTERNAL_MEMORY_ENABLED;
        Assert.assertFalse(prefs.getPrefBoolean(enabled));
        Assert.assertEquals(prefs.getPrefInt(forge.localinstance.properties.ForgePreferences.FPref.AI_EXTERNAL_MEMORY_MAX_FACTS),12);
        prefs.setPref(forge.localinstance.properties.ForgePreferences.FPref.AI_EXTERNAL_FULL_GAME_ENABLED,true);
        Assert.assertFalse(prefs.getPrefBoolean(enabled));
        Assert.assertFalse(forge.localinstance.properties.ForgePreferences.EXTERNAL_STABLE_SURFACES.contains(enabled));
    }
    @Test public void summaryIsImmutableAndFingerprintUsesOnlyVisibleFacts() {
        F f=fixture(12);var empty=view(f,AiDecisionType.MAIN_PHASE_ACTION);reveal(f,"Counterspell",ZoneType.Hand);
        var a=view(f,AiDecisionType.MAIN_PHASE_ACTION);var b=view(f,AiDecisionType.MAIN_PHASE_ACTION);
        Assert.assertEquals(ExternalAiStrategicMemory.fingerprint("state",a),ExternalAiStrategicMemory.fingerprint("state",b));
        Assert.assertNotEquals(ExternalAiStrategicMemory.fingerprint("state",empty),ExternalAiStrategicMemory.fingerprint("state",a));
        Assert.expectThrows(UnsupportedOperationException.class,()->a.view().knownOpponentCards().clear());
        String data=new Gson().toJson(a.view());Assert.assertFalse(data.contains("gameId"));Assert.assertFalse(data.contains("buildNanos"));
    }
    @Test public void irrelevantCombatFactsFilteredAndExcludedSurfacesHaveNoMemory() {
        F f=fixture(12);reveal(f,"Counterspell",ZoneType.Hand);
        Assert.assertEquals(view(f,AiDecisionType.COMBAT_ATTACKERS).selectedFacts(),0);
        Assert.assertNull(view(f,AiDecisionType.MULLIGAN_KEEP));Assert.assertNull(view(f,AiDecisionType.COMBAT_DAMAGE_ORDER));
    }
    @Test public void laterMainAndStackRequestsContainKnownCounterWithoutChangingOptionIds() {
        F f=fixture(12);var old=MainPhaseStateProjector.project(f.self(),"request",List.of());
        reveal(f,"Counterspell",ZoneType.Hand);var main=MainPhaseStateProjector.project(f.self(),"request",List.of());
        var stack=MainPhaseStateProjector.projectStackResponse(f.self(),"request",List.of());
        Assert.assertNotNull(main.memorySnapshot());Assert.assertNotNull(stack.memorySnapshot());
        Assert.assertTrue(new Gson().toJson(main.memorySnapshot().view()).contains("Counterspell"));
        Assert.assertNotEquals(old.state().fingerprint(),main.state().fingerprint());
    }
    @Test public void memoryPreservesNonemptyPreparedOptionsAndStackOpaqueMapping() {
        F f=fixture(12);ExternalAiStrategicMemory.configure(f.self(),false,12);
        Card known=addCardToZone("Counterspell",f.opponent(),ZoneType.Hand);
        Card source=addCardToZone("Shock",f.self(),ZoneType.Hand);var ability=source.getFirstSpellAbility();
        ability.setActivatingPlayer(f.self());ability.getTargets().add(f.opponent());
        var actions=List.of(new PreparedAiAction("ACTION_0",ability,ability,source,PreparedAiAction.ActionCategory.SPELL,0,ability.getStackDescription(),true));
        var mainBefore=MainPhaseStateProjector.project(f.self(),"same",actions);
        var stackBefore=MainPhaseStateProjector.projectStackResponse(f.self(),"same",actions);
        ExternalAiStrategicMemory.configure(f.self(),true,12);
        ExternalAiStrategicMemory.revealed(f.self(),List.of(known.getView()),ZoneType.Hand);
        var mainAfter=MainPhaseStateProjector.project(f.self(),"same",actions);
        var stackAfter=MainPhaseStateProjector.projectStackResponse(f.self(),"same",actions);
        Assert.assertFalse(mainBefore.legalActions().isEmpty());
        Assert.assertEquals(mainAfter.legalActions(),mainBefore.legalActions());
        Assert.assertEquals(stackAfter.legalActions(),stackBefore.legalActions());
        Assert.assertEquals(stackAfter.internalActionMapping(),stackBefore.internalActionMapping());
        Assert.assertNotEquals(mainAfter.state().fingerprint(),mainBefore.state().fingerprint());
        Assert.assertNotEquals(stackAfter.stateFingerprint(),stackBefore.stateFingerprint());
    }
    @Test public void gameFinishedDiscardsFactsAndStopsCollecting() {
        F f=fixture(12);reveal(f,"Counterspell",ZoneType.Hand);
        f.game().fireEvent(new GameEventGameFinished());
        Assert.assertNull(view(f,AiDecisionType.MAIN_PHASE_ACTION));
        reveal(f,"Giant Growth",ZoneType.Hand);Assert.assertNull(view(f,AiDecisionType.COMBAT_ACTION));
    }
    @Test public void boundedSummaryIsIndependentOfEngineIdsAndObservationOrder() {
        F first=fixture(2);for(String n:List.of("Counterspell","Giant Growth","Shock"))reveal(first,n,ZoneType.Hand);
        var a=view(first,AiDecisionType.MAIN_PHASE_ACTION);
        F second=fixture(2);for(String n:List.of("Shock","Giant Growth","Counterspell"))reveal(second,n,ZoneType.Hand);
        var b=view(second,AiDecisionType.MAIN_PHASE_ACTION);
        Assert.assertEquals(a.selectedFacts(),2);Assert.assertEquals(a.view(),b.view());
        Assert.assertEquals(ExternalAiStrategicMemory.fingerprint("same-state",a),ExternalAiStrategicMemory.fingerprint("same-state",b));
    }
    @Test public void multiplayerDoesNotCollectOrSupplyHistory() {
        Game game=initAndCreateThreePlayerGame();Player self=game.getPlayers().get(1);
        ExternalAiStrategicMemory.configure(self,true,12);
        Card card=addCardToZone("Counterspell",game.getPlayers().get(0),ZoneType.Hand);
        ExternalAiStrategicMemory.revealed(self,List.of(card.getView()),ZoneType.Hand);
        Assert.assertNull(ExternalAiStrategicMemory.snapshot(self,AiDecisionType.MAIN_PHASE_ACTION,List.of()));
        Assert.assertEquals(ExternalAiStrategicMemory.gameStats(game.getId()).get("factsRecorded"),Long.valueOf(0));
    }
    @Test public void combatAttackersAndBlockersReceiveEarlierTrick() {
        F f=fixture(12);reveal(f,"Giant Growth",ZoneType.Hand);
        Card a=addCard("Hill Giant",f.opponent()),b=addCard("Runeclaw Bear",f.self());a.setSickness(false);b.setSickness(false);
        f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS,f.opponent());
        Combat combat=new Combat(f.opponent());f.game().getPhaseHandler().setCombat(combat);combat.addAttacker(a,f.self());
        var blockers=ExternalCombatBlockers.prepare(f.self(),combat,"request",4);
        Assert.assertTrue(new Gson().toJson(blockers.context().memorySnapshot().view()).contains("Giant Growth"));
        f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS,f.self());
        Combat own=new Combat(f.self());f.game().getPhaseHandler().setCombat(own);
        var attackers=ExternalCombatAttackers.prepare(f.self(),own,"request",4);
        Assert.assertNotNull(attackers);Assert.assertTrue(new Gson().toJson(attackers.context().memorySnapshot().view()).contains("Giant Growth"));
    }
    @Test public void enabledMainRoutingCallsProviderOnceAndPreservesFallback() throws Exception {
        F f=fixture(12);reveal(f,"Counterspell",ZoneType.Hand);addCards("Mountain",2,f.self());
        reveal(f,"Giant Growth",ZoneType.Library);
        addCardToZone("Shock",f.self(),ZoneType.Hand);addCardToZone("Lightning Bolt",f.self(),ZoneType.Hand);f.opponent().setLife(2,null);
        AtomicInteger calls=new AtomicInteger();
        var observed=new java.util.concurrent.atomic.AtomicReference<StrategicMemoryView>();
        var controller=new PlayerControllerAi(f.game(),f.self(),f.self().getLobbyPlayer(),c->{
            calls.incrementAndGet();Assert.assertNotNull(c.memorySnapshot());observed.set(c.memorySnapshot().view());return Optional.empty();
        },false,true,3);f.self().dangerouslySetController(controller);
        Path audit=Files.createTempFile("stage15-memory-audit", ".jsonl");AiDecisionMetrics.configureDecisionAudit(audit);
        Assert.assertNotNull(controller.chooseSpellAbilityToPlay());Assert.assertEquals(calls.get(),1);Assert.assertTrue(AiDecisionMetrics.actionEvents().get(0).fallback());
        AiDecisionMetrics.completeGame(f.game().getId(),"p1",false,1,1);AiDecisionMetrics.configureDecisionAudit(null);
        var row=JsonParser.parseString(Files.readString(audit).lines().filter(l->l.contains("MAIN_PHASE_ACTION")).findFirst().orElseThrow()).getAsJsonObject();
        Assert.assertTrue(row.get("memoryEnabled").getAsBoolean());
        var fact=row.getAsJsonObject("memory").getAsJsonObject("view").getAsJsonArray("knownOpponentCards").get(0).getAsJsonObject();
        Assert.assertTrue(fact.has("cardName"));Assert.assertEquals(fact.get("cardName").getAsString(),"Counterspell");
        Assert.assertEquals(row.getAsJsonObject("memory").get("view"),new GsonBuilder().serializeNulls().create().toJsonTree(observed.get()));
    }
    @Test public void enabledCombatActionReceivesTrickAndPassExecutesWithoutExtraCalls() {
        F f=fixture(12);reveal(f,"Giant Growth",ZoneType.Hand);
        Card a=addCard("Hill Giant",f.opponent()),b=addCard("Runeclaw Bear",f.self());a.setSickness(false);b.setSickness(false);
        addCards("Forest",5,f.self());addCardToZone("Giant Growth",f.self(),ZoneType.Hand);
        f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS,f.opponent(),false);Combat combat=new Combat(f.opponent());
        f.game().getPhaseHandler().setCombat(combat);combat.addAttacker(a,f.self());combat.addBlocker(a,b);combat.setBlocked(a,true);f.game().getPhaseHandler().setPriority(f.self());
        AtomicInteger calls=new AtomicInteger();var controller=new PlayerControllerAi(f.game(),f.self(),f.self().getLobbyPlayer(),c->{
            calls.incrementAndGet();var ctx=(CombatActionDecisionContext)c;Assert.assertTrue(new Gson().toJson(ctx.memorySnapshot().view()).contains("Giant Growth"));
            return Optional.of(new AiDecision(ctx.decisionId(),ctx.stateFingerprint(),"PASS"));
        },false,false,3);f.self().dangerouslySetController(controller);controller.getAi().configureExternalCombatActions(true,4);
        Assert.assertNull(controller.chooseSpellAbilityToPlay());Assert.assertEquals(calls.get(),1);Assert.assertFalse(AiDecisionMetrics.combatActionEvents().get(0).fallback());
    }
    private Path root() { Path p=Path.of("").toAbsolutePath();while(p!=null&&!Files.exists(p.resolve("tools/ai-evaluation/fixtures/stage15-disabled-wire.json")))p=p.getParent();return Objects.requireNonNull(p); }
    @Test public void disabledWireExactlyMatchesFivePreStage15Requests() throws Exception {
        var provider=new OpenAiCompatibleDecisionProvider(new OpenAiCompatibleProviderConfig(true,URI.create("http://localhost:11434/v1/chat/completions"),"qwen3:8b",null,Duration.ofSeconds(60),OpenAiResponseFormatMode.JSON_SCHEMA));
        var method=Arrays.stream(provider.getClass().getDeclaredMethods()).filter(m->m.getName().equals("requestBody")&&m.getParameterCount()==2).findFirst().orElseThrow();method.setAccessible(true);
        var fixtures=JsonParser.parseString(Files.readString(root().resolve("tools/ai-evaluation/fixtures/stage15-disabled-wire.json"))).getAsJsonArray();
        for(var e:fixtures) {
            var entry=e.getAsJsonObject();Class<? extends AiDecisionContext> type=switch(entry.get("surface").getAsString()) {
                case "MAIN_PHASE_ACTION"->MainPhaseDecisionContext.class;case "STACK_RESPONSE"->StackResponseDecisionContext.class;
                case "COMBAT_ACTION"->CombatActionDecisionContext.class;case "COMBAT_ATTACKERS"->CombatAttackersDecisionContext.class;default->CombatBlockersDecisionContext.class;
            };
            var c=new Gson().fromJson(entry.get("context"),type);Assert.assertNull(c.memorySnapshot());
            String wire=(String)method.invoke(provider,c,null);
            var body=JsonParser.parseString(wire);Assert.assertEquals(body,entry.get("request"),type.getName());
            Assert.assertEquals(wire,entry.get("request").toString(),"Exact disabled wire: "+type.getName());
        }
    }
}
