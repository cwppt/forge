package forge.ai;
import forge.game.card.*;
import forge.game.combat.*;
import forge.game.cost.*;
import forge.game.keyword.Keyword;
import forge.game.staticability.StaticAbility;
import forge.game.zone.ZoneType;
import forge.card.mana.ManaCost;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.*;
import org.testng.Assert;
import org.testng.annotations.Test;

public final class CombinedFeatureTest extends AITest {
    private StaticAbility tax(Card c){return new StaticAbility("Mode$ CantBlockUnless | ValidCard$ Creature | Cost$ 2",c,c.getCurrentState());}
    private StaticAbility raise(Card c){return new StaticAbility("Mode$ RaiseCost | ValidCard$ Card | Amount$ 3",c,c.getCurrentState());}
    private int adjusted(forge.game.spellability.SpellAbility sa){return CostAdjustment.adjust(new Cost(ManaCost.get(6),false),sa,false).getCostMana().getMana().getCMC();}
    @Test public void sharedDynamicSourceChangesCombatAndSpellCosts(){
        var g=initAndCreateGame();var a=addCard("Prized Unicorn",g.getPlayers().get(0));var b=addCard("Hill Giant",g.getPlayers().get(1));var host=addCard("Mountain",a.getController());
        var sa=addCardToZone("Shivan Dragon",b.getController(),ZoneType.Hand).getFirstSpellAbility();sa.setActivatingPlayer(b.getController());var combat=new Combat(a.getController());combat.addAttacker(a,b.getController());
        Assert.assertTrue(CombatUtil.mustBlockAnAttacker(b,combat,null));Assert.assertEquals(adjusted(sa),6);
        var block=tax(host);var spell=raise(host);host.addStaticAbility(block);host.addStaticAbility(spell);
        Assert.assertFalse(CombatUtil.mustBlockAnAttacker(b,combat,null));Assert.assertEquals(CombatUtil.getBlockCost(g,b,a).getCostMana().getMana().getCMC(),2);Assert.assertEquals(adjusted(sa),9);
        block.setSuppressed(true);spell.setSuppressed(true);Assert.assertTrue(CombatUtil.mustBlockAnAttacker(b,combat,null));Assert.assertEquals(adjusted(sa),6);
        block.setSuppressed(false);spell.setSuppressed(false);Assert.assertFalse(CombatUtil.mustBlockAnAttacker(b,combat,null));Assert.assertEquals(adjusted(sa),9);
        host.getCurrentState().removeStaticAbility(block);host.getCurrentState().removeStaticAbility(spell);Assert.assertTrue(CombatUtil.mustBlockAnAttacker(b,combat,null));Assert.assertEquals(adjusted(sa),6);
    }
    @Test public void keywordSourcesAndRequirementSnapshotsInteractWithoutStaleProofs(){
        var g=initAndCreateGame();var a=addCard("Runeclaw Bear",g.getPlayers().get(0));var b=addCard("Hill Giant",g.getPlayers().get(1));var host=addCard("Mountain",a.getController());
        a.getCurrentState().getCachedKeywords().insert(Keyword.getInstance("CARDNAME must be blocked if able."));
        var sa=addCardToZone("Shivan Dragon",b.getController(),ZoneType.Hand).getFirstSpellAbility();sa.setActivatingPlayer(b.getController());var combat=new Combat(a.getController());combat.addAttacker(a,b.getController());
        Assert.assertTrue(CombatUtil.mustBlockAnAttacker(b,combat,null));
        var keyword=Keyword.getInstance("Flying");host.getCurrentState().getCachedKeywords().insert(keyword);keyword.addStaticAbility(tax(host));keyword.addStaticAbility(raise(host));
        Assert.assertFalse(CombatUtil.mustBlockAnAttacker(b,combat,null));Assert.assertEquals(adjusted(sa),9);
        host.getCurrentState().getCachedKeywords().removeAll(Keyword.FLYING);Assert.assertTrue(CombatUtil.mustBlockAnAttacker(b,combat,null));Assert.assertEquals(adjusted(sa),6);
    }
    private long[] metric(String name)throws Exception{return (long[])Class.forName(name).getMethod("snapshot").invoke(null);}
    @Test public void effectiveFlagsProvenanceAndExecutedFastPaths() throws Exception {
        boolean source=Boolean.getBoolean("adoption.expectedSourceFeatures"),huge=Boolean.getBoolean("adoption.expectedHugeCompile");
        Path expected=Path.of(System.getProperty("adoption.engine")).toRealPath();var proof=new LinkedHashMap<String,Object>();
        for(String name:List.of("forge.game.card.CardState","forge.game.cost.CostAdjustment","forge.game.combat.CombatUtil","forge.ai.AiController","forge.ai.AiEvaluationDiagnostics","forge.util.MyRandom")){
            Path actual=Path.of(Class.forName(name).getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();Assert.assertEquals(actual,expected,"Engine class shadowed: "+name);proof.put(name,actual.toString());
        }
        String[][] features={{"forge.game.card.BlockCostMetrics","FAST_PATH"},{"forge.game.cost.CostSourceMetrics","FAST_PATH"},{"forge.game.combat.BlockerSearchMetrics","FAST"},{"forge.game.combat.RequirementSnapshotMetrics","FAST"}};
        for(String[] feature:features){
            try{Class<?> cls=Class.forName(feature[0]);Assert.assertEquals(cls.getField(feature[1]).getBoolean(null),source);Assert.assertEquals(Path.of(cls.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath(),expected);proof.put(feature[0],source);}
            catch(ClassNotFoundException absent){Assert.assertFalse(source,"Enabled feature implementation missing: "+feature[0]);proof.put(feature[0],"absent in validated control; disabled");}
        }
        String option=ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class).getVMOption("DontCompileHugeMethods").getValue();Assert.assertEquals(option,huge?"false":"true");proof.put("DontCompileHugeMethods",option);
        if(source){
            long[] blockBefore=metric(features[0][0]),costBefore=metric(features[1][0]),hoistBefore=metric(features[2][0]),snapshotBefore=metric(features[3][0]);
            var g=initAndCreateGame();var a=addCard("Runeclaw Bear",g.getPlayers().get(0));var b=addCard("Hill Giant",g.getPlayers().get(1));var combat=new Combat(a.getController());combat.addAttacker(a,b.getController());
            CombatUtil.getBlockCost(g,b,a);CombatUtil.mustBlockAnAttacker(b,combat,null);
            var sa=addCardToZone("Shivan Dragon",b.getController(),ZoneType.Hand).getFirstSpellAbility();sa.setActivatingPlayer(b.getController());Assert.assertEquals(adjusted(sa),6);
            long[] block=metric(features[0][0]),cost=metric(features[1][0]),hoist=metric(features[2][0]),snapshot=metric(features[3][0]);
            Assert.assertTrue(block[1]>blockBefore[1],"Block-cost filter did not execute");Assert.assertTrue(cost[1]>costBefore[1],"Cost-source filter did not execute");Assert.assertTrue(hoist[13]>hoistBefore[13],"Invocation-local tax proof did not avoid a query");Assert.assertTrue(snapshot[1]>snapshotBefore[1],"Unused requirement snapshot was not avoided");
            proof.put("blockCost",block);proof.put("costSource",cost);proof.put("blockerHoist",hoist);proof.put("requirementSnapshot",snapshot);proof.put("allFourExecuted",true);
        }
        System.out.println("ADOPTION_FEATURE_PROOF "+new com.google.gson.Gson().toJson(proof));
    }
}
