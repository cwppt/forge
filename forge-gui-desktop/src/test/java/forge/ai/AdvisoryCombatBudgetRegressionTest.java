package forge.ai;

import com.google.gson.JsonParser;
import forge.ai.ability.PeekAndRevealAi;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

public class AdvisoryCombatBudgetRegressionTest extends AITest {
    private Game multiplayer() {
        Game game=initAndCreateThreePlayerGame();
        for(int i=0;i<game.getPlayers().size();i++) {
            game.getPlayers().get(i).setTeam(i);
            fillLibrary(game.getPlayers().get(i),20);
        }
        return game;
    }
    private record Capture(Object result,String output) {}
    private Capture capture(Supplier<?> body) {
        PrintStream previous=System.out;
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        try(PrintStream out=new PrintStream(buffer,true,StandardCharsets.UTF_8)) {
            System.setOut(out);
            return new Capture(body.get(),buffer.toString(StandardCharsets.UTF_8));
        } finally {System.setOut(previous);}
    }
    private List<com.google.gson.JsonObject> events(Capture capture) {
        return capture.output().lines().filter(line->line.startsWith("AI_EVAL_DECISION "))
                .map(line->JsonParser.parseString(line.substring("AI_EVAL_DECISION ".length())).getAsJsonObject()).toList();
    }
    private AutoCloseable policy(String work,String ms,String diagnostics) {
        String[] names={"forge.ai.evalWorkLimit","forge.ai.evalBudgetMs","forge.ai.evalDiagnostics"};
        String[] old=new String[names.length];String[] values={work,ms,diagnostics};
        for(int i=0;i<names.length;i++){old[i]=System.getProperty(names[i]);System.setProperty(names[i],values[i]);}
        return ()->{for(int i=0;i<names.length;i++){if(old[i]==null)System.clearProperty(names[i]);else System.setProperty(names[i],old[i]);}};
    }
    @Test public void standaloneLifePredictionAcquiresAndReleasesBudget() throws Exception {
        Game game=multiplayer();Player ai=game.getPlayers().get(1);
        try(var ignored=policy("1","0","true")) {
            Capture result=capture(()->ComputerUtil.predictNextCombatsRemainingLife(ai,true,true,0,null));
            Assert.assertEquals(result.result(),Integer.MAX_VALUE);
            Assert.assertEquals(events(result).size(),1);
            Assert.assertEquals(events(result).get(0).get("context").getAsString(),"life-prediction");
            Assert.assertFalse(AiEvaluationDiagnostics.usedFallback());
            Capture next=capture(()->ComputerUtil.predictNextCombatsRemainingLife(ai,true,true,0,null));
            Assert.assertEquals(events(next).get(0).get("work").getAsInt(),1,"standalone scope must reset on exit");
        }
    }
    @Test public void largeBoardStandalonePredictionUsesFallbackWithoutChangingBattlefield() throws Exception {
        Game game=multiplayer();Player ai=game.getPlayers().get(1);Player opponent=game.getPlayers().get(0);
        addCards("Steel Hellkite",40,opponent);addCards("Grizzly Bears",20,ai);ai.setLife(1,null);
        int before=game.getCardsIn(ZoneType.Battlefield).size();
        try(var ignored=policy("1","0","true")) {
            Capture result=capture(()->ComputerUtil.predictNextCombatsRemainingLife(ai,true,true,0,null));
            Assert.assertEquals(events(result).size(),1);
            Assert.assertTrue(events(result).get(0).get("fallbackCalls").getAsLong()>0);
            Assert.assertEquals(events(result).get(0).get("work").getAsLong(),2L,"exhaustion must exit the hypothetical search immediately");
            Assert.assertEquals(events(result).get(0).get("predictionAborts").getAsLong(),1L);
            Assert.assertEquals(game.getCardsIn(ZoneType.Battlefield).size(),before);
            Assert.assertTrue(game.getStack().isEmpty());
            Assert.assertEquals(ai.getLife(),1);
        }
    }
    @Test public void nestedPredictionCannotResetEnclosingBudget() throws Exception {
        Game game=multiplayer();Player ai=game.getPlayers().get(1);
        try(var ignored=policy("1","0","true")) {
            Capture result=capture(()->{
                try(var scope=AiEvaluationDiagnostics.begin(ai,1)) {
                    Assert.assertFalse(AiEvaluationDiagnostics.prediction("already-used"));
                    int prediction=ComputerUtil.predictNextCombatsRemainingLife(ai,true,true,0,null);
                    Assert.assertTrue(AiEvaluationDiagnostics.usedFallback());
                    return prediction;
                }
            });
            Assert.assertEquals(result.result(),Integer.MIN_VALUE);
            Assert.assertEquals(events(result).size(),1);
            Assert.assertFalse(events(result).get(0).has("context"));
        }
    }
    @Test public void multiplayerDefenderSelectionSharesOneBudgetAcrossOpponents() throws Exception {
        Game game=multiplayer();Player ai=game.getPlayers().get(1);
        try(var ignored=policy("1","0","true")) {
            Capture result=capture(()->AiAttackController.choosePreferredDefenderPlayer(ai));
            Assert.assertTrue(ai.getOpponents().contains(result.result()));
            Assert.assertEquals(events(result).size(),1,"opponents must not each acquire a fresh budget");
            Assert.assertEquals(events(result).get(0).get("context").getAsString(),"defender-selection");
            Assert.assertTrue(events(result).get(0).get("fallbackCalls").getAsLong()>0);
        }
    }
    private AiAbilityDecision peek(Player ai,SpellAbility sa) {
        try {
            var method=PeekAndRevealAi.class.getDeclaredMethod("checkApiLogic",Player.class,SpellAbility.class);
            method.setAccessible(true);return (AiAbilityDecision)method.invoke(new PeekAndRevealAi(),ai,sa);
        } catch(ReflectiveOperationException e) {throw new AssertionError(e);}
    }
    @Test public void untargetedHeraldsHornDoesNotChooseAnUnusedOpponentOrAdvanceRandom() throws Exception {
        Game game=multiplayer();Player ai=game.getPlayers().get(1);
        var horn=addCard("Herald's Horn",ai);horn.setChosenType("Dragon");
        var ability=AbilityFactory.getAbility(horn.getSVar("TrigPeek"),horn);ability.setActivatingPlayer(ai);
        try(var ignored=policy("10000","1000","true")) {
            MyRandom.setRandom(new Random(741));
            Capture result=capture(()->peek(ai,ability));
            Assert.assertTrue(((AiAbilityDecision)result.result()).willingToPlay());
            Assert.assertTrue(events(result).isEmpty(),"self-library peek must not evaluate opponents");
            Assert.assertEquals(MyRandom.getRandom().nextLong(),new Random(741).nextLong());
            Assert.assertTrue(ability.getTargets().isEmpty());
        }
    }
    @Test public void targetedKeenDuelistStillChoosesALegalOpponent() throws Exception {
        Game game=multiplayer();Player ai=game.getPlayers().get(1);
        var duelist=addCard("Keen Duelist",ai);
        var ability=AbilityFactory.getAbility(duelist.getSVar("TrigReveal"),duelist);ability.setActivatingPlayer(ai);
        try(var ignored=policy("1","0","true")) {
            Assert.assertTrue(peek(ai,ability).willingToPlay());
            Player target=ability.getTargets().getFirstTargetedPlayer();
            Assert.assertTrue(ai.getOpponents().contains(target));
            Assert.assertTrue(ability.canTarget(target,true));
            Assert.assertEquals(ability.getTargets().size(),1);
        }
    }
    @Test public void disabledBudgetRetainsUninstrumentedCleanCase() throws Exception {
        Game game=multiplayer();Player ai=game.getPlayers().get(1);
        try(var ignored=policy("0","0","false")) {
            Capture result=capture(()->ComputerUtil.predictNextCombatsRemainingLife(ai,true,true,0,null));
            Assert.assertEquals(result.result(),Integer.MAX_VALUE);
            Assert.assertTrue(events(result).isEmpty());
        }
    }
    @Test public void abortRestoresOuterScopeAndDoesNotSwallowUnexpectedErrors() throws Exception {
        Game game=multiplayer();Player ai=game.getPlayers().get(1);
        var bounded=AiEvaluationDiagnostics.class.getMethod("boundedPrediction",Supplier.class,Object.class);
        try(var ignored=policy("1","0","true");var scope=AiEvaluationDiagnostics.begin(ai,1)) {
            java.util.concurrent.atomic.AtomicBoolean continued=new java.util.concurrent.atomic.AtomicBoolean();
            Supplier<Integer> expensive=()->{
                AiEvaluationDiagnostics.prediction("first");
                AiEvaluationDiagnostics.prediction("exhausted");
                continued.set(true);return 7;
            };
            Assert.assertEquals(bounded.invoke(null,expensive,Integer.MIN_VALUE),Integer.MIN_VALUE);
            Assert.assertFalse(continued.get());
            Assert.assertTrue(AiEvaluationDiagnostics.prediction("outside-advisory"),"outer fallback must remain usable without throwing");
            Supplier<Integer> broken=()->{throw new IllegalStateException("unexpected fixture failure");};
            try {bounded.invoke(null,broken,Integer.MIN_VALUE);Assert.fail("unexpected errors must propagate");}
            catch(java.lang.reflect.InvocationTargetException expected){Assert.assertTrue(expected.getCause() instanceof IllegalStateException);}
            Assert.assertTrue(AiEvaluationDiagnostics.prediction("after-unexpected-error"));
        }
    }
    @Test public void impossibleLatePhaseAttackSkipsUnusedOpponentScoring() throws Exception {
        Game game=multiplayer();Player ai=game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(forge.game.phase.PhaseType.MAIN2,ai);
        var card=addCardToZone("Steel Hellkite",ai,ZoneType.Exile);
        try(var ignored=policy("10000","1000","true")) {
            MyRandom.setRandom(new Random(741));
            Capture result=capture(()->ComputerUtilCard.doesSpecifiedCreatureAttackAI(ai,card));
            Assert.assertEquals(result.result(),false);
            Assert.assertTrue(events(result).isEmpty(),"a universally illegal attack needs no opponent scoring");
            Assert.assertEquals(MyRandom.getRandom().nextLong(),new Random(741).nextLong());
        }
    }
    @Test public void virtualAttackBudgetCoversTheEntireSearch() throws Exception {
        Game game=multiplayer();Player ai=game.getPlayers().get(1);
        var card=addCard("Steel Hellkite",ai);
        try(var ignored=policy("1","0","true")) {
            Capture result=capture(()->ComputerUtilCard.doesSpecifiedCreatureAttackAI(ai,card));
            Assert.assertEquals(result.result(),false);
            Assert.assertEquals(events(result).size(),1);
            Assert.assertEquals(events(result).get(0).get("context").getAsString(),"virtual-attack");
            Assert.assertTrue(events(result).get(0).get("predictionAborts").getAsLong()>0);
            Assert.assertTrue(game.getStack().isEmpty());
        }
    }
    @Test public void advisoryCheckpointsDoNotApproximateActualCombatChecks() throws Exception {
        Game game=multiplayer();Player ai=game.getPlayers().get(1);Player opponent=game.getPlayers().get(0);
        var card=addCard("Steel Hellkite",ai);
        try(var ignored=policy("1","0","true");var scope=AiEvaluationDiagnostics.begin(ai,1)) {
            Assert.assertTrue(ComputerUtilCombat.canAttackNextTurn(card,opponent));
            Assert.assertFalse(AiEvaluationDiagnostics.usedFallback());
            Assert.assertFalse(AiEvaluationDiagnostics.prediction("first-real-budget-use"),"advisory-only checkpoints must not charge actual combat checks");
        }
    }
    @Test public void sickVirtualAttackerSkipsScoringOnlyAfterEveryOpponentIsIllegal() throws Exception {
        Game game=multiplayer();Player ai=game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(forge.game.phase.PhaseType.MAIN1,ai);
        var card=addCardToZone("Akroma, Angel of Fury",game.getPlayers().get(0),ZoneType.Exile);
        card.setSickness(true);
        Assert.assertTrue(ai.getOpponents().stream().noneMatch(p->forge.game.combat.CombatUtil.canAttack(card,p)));
        try(var ignored=policy("10000","1000","true")) {
            MyRandom.setRandom(new Random(741));
            Capture result=capture(()->ComputerUtilCard.doesSpecifiedCreatureAttackAI(ai,card));
            Assert.assertEquals(result.result(),false);
            Assert.assertFalse(events(result).get(0).getAsJsonObject("counts").has("lifePrediction"));
            Assert.assertEquals(MyRandom.getRandom().nextLong(),new Random(741).nextLong());
        }
    }
    @Test public void oneLegalOpponentStillRunsNormalVirtualAttackScoring() throws Exception {
        Game game=multiplayer();Player ai=game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(forge.game.phase.PhaseType.MAIN1,ai);
        var card=addCard("Steel Hellkite",ai);card.setSickness(false);
        Player first=ai.getOpponents().get(0);Player second=ai.getOpponents().get(1);
        card.addGoad(game.getNextTimestamp(),first);
        Assert.assertFalse(forge.game.combat.CombatUtil.canAttack(card,first));
        Assert.assertTrue(forge.game.combat.CombatUtil.canAttack(card,second));
        try(var ignored=policy("10000","1000","true")) {
            Capture result=capture(()->ComputerUtilCard.doesSpecifiedCreatureAttackAI(ai,card));
            Assert.assertTrue(events(result).get(0).getAsJsonObject("counts").has("lifePrediction"));
        }
    }
    @Test public void mimicVatExistingBlockerReasonSkipsUnusedAttackPrediction() throws Exception {
        Game game=multiplayer();Player ai=game.getPlayers().get(1);Player opponent=game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(forge.game.phase.PhaseType.COMBAT_DECLARE_BLOCKERS,opponent);
        var vat=addCard("Mimic Vat",ai);var imprinted=addCardToZone("Akroma, Angel of Fury",opponent,ZoneType.Exile);
        vat.addImprintedCard(imprinted);
        var combat=new forge.game.combat.Combat(opponent);combat.addAttacker(addCard("Grizzly Bears",opponent),ai);
        game.getPhaseHandler().setCombat(combat);
        var ability=vat.getSpellAbilities().get(0);ability.setActivatingPlayer(ai);
        try(var ignored=policy("10000","1000","true")) {
            MyRandom.setRandom(new Random(741));
            Capture result=capture(()->SpecialCardAi.MimicVat.considerCopy(ai,ability));
            Assert.assertTrue(((AiAbilityDecision)result.result()).willingToPlay());
            Assert.assertTrue(events(result).isEmpty());
            Assert.assertEquals(MyRandom.getRandom().nextLong(),new Random(741).nextLong());
        }
    }
}
