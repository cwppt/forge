package forge.ai;
import forge.ai.ability.AnimateAi;
import forge.game.card.*;
import forge.game.combat.*;
import forge.game.phase.PhaseType;
import forge.game.ability.ApiType;
import org.testng.Assert;
import org.testng.annotations.Test;

public class BlockerTest extends AITest {
 boolean enabled(){return Boolean.getBoolean("forge.ai.virtualBlockerBudgetFix");}
 void ready(Card c){c.setSickness(false);c.setTapped(false);}
 void exhaust(){for(int i=0;i<2001;i++)AiEvaluationDiagnostics.prediction("fixtureWork");}
 @Test public void exhaustedDefensiveCases(){
  for(String name:new String[]{"Runeclaw Bear","Silver Knight","White Knight","Fencing Ace","Boggart Brute","Serra Angel"})for(boolean partner:new boolean[]{false,true}){
   var g=initAndCreateThreePlayerGame();var opp=g.getPlayers().get(0);var ai=g.getPlayers().get(1);ai.setLife(1,null);
   Card attacker=addCard(name,opp);ready(attacker);Card needle=addCard("Needle Spires",ai);ready(needle);
   if(partner)ready(addCard("Hill Giant",ai));g.getAction().checkStaticAbilities();
   var combat=new Combat(opp);combat.addAttacker(attacker,ai);g.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS,opp,70);g.getPhaseHandler().setCombat(combat);
   var sa=needle.getSpellAbilities().stream().filter(x->x.getApi()==ApiType.Animate).findFirst().orElseThrow();sa.setActivatingPlayer(ai);var virtual=AnimateAi.becomeAnimated(needle,sa);
   int size=g.getCardsIn(forge.game.zone.ZoneType.Battlefield).size();boolean answer;
   try(var d=AiEvaluationDiagnostics.begin(ai,1)){
    exhaust();answer=ComputerUtilCard.doesSpecifiedCreatureBlock(ai,virtual);
    Assert.assertTrue(AiEvaluationDiagnostics.prediction("afterVirtual"),"caller budget was reset");
    // The inner cancellation must be handled locally, not escape to this enclosing boundary.
    boolean nested=AiEvaluationDiagnostics.boundedPrediction(()->ComputerUtilCard.doesSpecifiedCreatureBlock(ai,virtual),false);
    if(enabled())Assert.assertEquals(nested,answer,"nested abort escaped local boundary");
   }
   if(enabled())Assert.assertEquals(answer,!name.equals("Serra Angel")&&!name.equals("Silver Knight")&&(!name.equals("Boggart Brute")||partner),name+" partner="+partner);
   Assert.assertSame(g.getCombat(),combat);Assert.assertTrue(combat.getAllBlockers().isEmpty());Assert.assertEquals(combat.getAttackers().size(),1);
   Assert.assertEquals(g.getCardsIn(forge.game.zone.ZoneType.Battlefield).size(),size);Assert.assertFalse(needle.isCreature());Assert.assertFalse(needle.isTapped());
   System.out.println("CASE "+name+" partner="+partner+" choice="+answer);
  }
 }
 @Test public void defenderSelectionAndIllegalVirtual(){
  var g=initAndCreateThreePlayerGame();var opp=g.getPlayers().get(0);var ai=g.getPlayers().get(1);var other=g.getPlayers().get(2);
  Card attacker=addCard("Hill Giant",opp);ready(attacker);Card needle=addCard("Needle Spires",ai);ready(needle);g.getAction().checkStaticAbilities();
  var combat=new Combat(opp);combat.addAttacker(attacker,other);g.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS,opp,70);g.getPhaseHandler().setCombat(combat);
  var sa=needle.getSpellAbilities().stream().filter(x->x.getApi()==ApiType.Animate).findFirst().orElseThrow();sa.setActivatingPlayer(ai);var virtual=AnimateAi.becomeAnimated(needle,sa);
  try(var d=AiEvaluationDiagnostics.begin(ai,1)){exhaust();boolean value=ComputerUtilCard.doesSpecifiedCreatureBlock(ai,virtual);if(enabled())Assert.assertFalse(value,"cannot block for a different defender");}
  combat.removeFromCombat(attacker);combat.addAttacker(attacker,ai);virtual.setTapped(true);
  try(var d=AiEvaluationDiagnostics.begin(ai,1)){exhaust();boolean value=ComputerUtilCard.doesSpecifiedCreatureBlock(ai,virtual);if(enabled())Assert.assertFalse(value,"tapped virtual creature cannot block");}
 }
 @Test public void ordinaryAssignmentRemainsLegal(){
  var g=initAndCreateThreePlayerGame();var opp=g.getPlayers().get(0);var ai=g.getPlayers().get(1);ai.setLife(1,null);
  var attacker=addCard("Boggart Brute",opp);ready(attacker);ready(addCard("Hill Giant",ai));ready(addCard("Hill Giant",ai));g.getAction().checkStaticAbilities();
  var combat=new Combat(opp);combat.addAttacker(attacker,ai);g.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS,opp,70);g.getPhaseHandler().setCombat(combat);
  // Even an exhausted enclosing diagnostic is not allowed to abort real blocking.
  try(var d=AiEvaluationDiagnostics.begin(ai,1)){exhaust();new AiBlockController(ai,false).assignBlockersForCombat(combat);}
  Assert.assertEquals(combat.getBlockers(attacker).size(),2);Assert.assertNull(CombatUtil.validateBlocks(combat,ai));
 }
 @Test public void needleActivationPreservedAfterExhaustion(){
  var g=initAndCreateThreePlayerGame();var opp=g.getPlayers().get(0);var ai=g.getPlayers().get(1);ai.setLife(1,null);
  var attacker=addCard("Hill Giant",opp);ready(attacker);var needle=addCard("Needle Spires",ai);ready(needle);
  for(String n:new String[]{"Mountain","Mountain","Plains","Plains"})ready(addCard(n,ai));g.getAction().checkStaticAbilities();
  var combat=new Combat(opp);combat.addAttacker(attacker,ai);g.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS,opp,70);g.getPhaseHandler().setCombat(combat);
  var sa=needle.getSpellAbilities().stream().filter(x->x.getApi()==ApiType.Animate).findFirst().orElseThrow();sa.setActivatingPlayer(ai);
  try(var d=AiEvaluationDiagnostics.begin(ai,1)){exhaust();var result=SpellApiToAi.Converter.get(sa).canPlayWithSubs(ai,sa);System.out.println("ACTIVATION "+result);if(enabled())Assert.assertTrue(result.willingToPlay());}
  Assert.assertFalse(needle.isCreature());Assert.assertTrue(combat.getAllBlockers().isEmpty());
 }
 @Test public void completedPredictionAndScopeCleanup(){
  var g=initAndCreateThreePlayerGame();var opp=g.getPlayers().get(0);var ai=g.getPlayers().get(1);ai.setLife(1,null);
  var attacker=addCard("Runeclaw Bear",opp);ready(attacker);var needle=addCard("Needle Spires",ai);ready(needle);g.getAction().checkStaticAbilities();
  var combat=new Combat(opp);combat.addAttacker(attacker,ai);g.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS,opp,70);g.getPhaseHandler().setCombat(combat);
  var sa=needle.getSpellAbilities().stream().filter(x->x.getApi()==ApiType.Animate).findFirst().orElseThrow();sa.setActivatingPlayer(ai);var virtual=AnimateAi.becomeAnimated(needle,sa);
  try(var d=AiEvaluationDiagnostics.begin(ai,1)){Assert.assertTrue(ComputerUtilCard.doesSpecifiedCreatureBlock(ai,virtual));Assert.assertFalse(AiEvaluationDiagnostics.usedFallback(),"small completed search should not exhaust");}
  // A previous hypothetical call must not leak its checkpoint scope into real assignment.
  ready(addCard("Hill Giant",ai));try(var d=AiEvaluationDiagnostics.begin(ai,1)){exhaust();new AiBlockController(ai,false).assignBlockersForCombat(combat);}
  Assert.assertFalse(combat.getAllBlockers().isEmpty());
 }
 @Test public void partialSearchCancellationCleanup() throws Exception {
  var g=initAndCreateThreePlayerGame();var opp=g.getPlayers().get(0);var ai=g.getPlayers().get(1);ai.setLife(1,null);
  for(int i=0;i<12;i++){ready(addToken("r_6_6_dragon_flying",opp));ready(addToken("r_6_6_dragon_flying",ai));}
  ready(addCard("Hill Giant",opp));var needle=addCard("Needle Spires",ai);ready(needle);g.getAction().checkStaticAbilities();
  var combat=new Combat(opp);for(Card c:opp.getCreaturesInPlay())combat.addAttacker(c,ai);
  g.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS,opp,70);g.getPhaseHandler().setCombat(combat);
  var sa=needle.getSpellAbilities().stream().filter(x->x.getApi()==ApiType.Animate).findFirst().orElseThrow();sa.setActivatingPlayer(ai);var virtual=AnimateAi.becomeAnimated(needle,sa);
  int size=g.getCardsIn(forge.game.zone.ZoneType.Battlefield).size();
  try(var d=AiEvaluationDiagnostics.begin(ai,1)){
   for(int i=0;i<1990;i++)AiEvaluationDiagnostics.prediction("fixtureWork");
   boolean result=ComputerUtilCard.doesSpecifiedCreatureBlock(ai,virtual);if(enabled())Assert.assertTrue(result);
   Assert.assertTrue(AiEvaluationDiagnostics.usedFallback());
   if(enabled()){
    // A later unrelated advisory boundary must not inherit the virtual-search scope.
    boolean cleaned=AiEvaluationDiagnostics.boundedPrediction(()->{
     try{Class.forName("forge.ai.VirtualBlockerBudget").getMethod("checkpoint",String.class).invoke(null,"scopeProbe");}
     catch(Exception ex){throw new RuntimeException(ex);}return true;
    },false);Assert.assertTrue(cleaned);
   }
  }
  Assert.assertSame(g.getCombat(),combat);Assert.assertTrue(combat.getAllBlockers().isEmpty());Assert.assertEquals(combat.getAttackers().size(),13);
  Assert.assertEquals(g.getCardsIn(forge.game.zone.ZoneType.Battlefield).size(),size);Assert.assertFalse(needle.isCreature());Assert.assertFalse(needle.isTapped());
 }
}
