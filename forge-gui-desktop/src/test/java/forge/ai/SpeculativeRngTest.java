package forge.ai;

import com.google.gson.Gson;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.testng.Assert;
import org.testng.annotations.Test;

/** Actual native danger prediction and java.util.Random draws, never provider-lookup counts. */
public final class SpeculativeRngTest extends AITest {
    private static final Gson JSON=new Gson();
    private static final ThreadLocal<String> ROLE=ThreadLocal.withInitial(()->"UNLABELLED");
    private static final long GAME_SEED=573307, LOCAL_SEED=0x7b12345L;
    public static final class DrawRandom extends Random {
        long draws; final List<Map<String,Object>> trace=new ArrayList<>();
        DrawRandom(long seed){super(seed);}
        @Override protected synchronized int next(int bits){
            int value=super.next(bits);long ordinal=++draws;
            if(trace.size()<128 || ROLE.get().equals("COMMITTED")){
                var row=new LinkedHashMap<String,Object>();row.put("ordinal",ordinal);row.put("bits",bits);row.put("value",value);row.put("role",ROLE.get());row.put("thread",Thread.currentThread().getName());
                String operation="unknown",site="unknown";
                for(StackTraceElement f:Thread.currentThread().getStackTrace()){
                    if(f.getClassName().equals("java.util.Random")&&!f.getMethodName().equals("next"))operation=f.getMethodName();
                    if(f.getClassName().startsWith("forge.")&&!f.getClassName().startsWith("forge.ai.SpeculativeRngTest")){site=f.toString();break;}
                }
                row.put("operation",operation);row.put("source",site);trace.add(row);
            }
            return value;
        }
    }
    private record Context(Game game,Player ai,Combat combat){}
    private Context context(){
        Assert.assertEquals(Long.getLong("forge.ai.evalBudgetMs").longValue(),500);
        Assert.assertEquals(Long.getLong("forge.ai.evalWorkLimit").longValue(),2000);
        var g=initAndCreateGame();var ai=g.getPlayers().get(0);var opp=g.getPlayers().get(1);
        var attacker=addCard("Runeclaw Bear",opp);addCard("Hill Giant",ai);
        var combat=new Combat(opp);combat.addAttacker(attacker,ai);
        // Warm class initialization outside the measured budget using an explicit private stream.
        MyRandom.withRandom(new Random(LOCAL_SEED),()->ComputerUtilCombat.lifeInDanger(ai,combat));
        return new Context(g,ai,combat);
    }
    private String state(Context ctx){
        var players=new ArrayList<Object>();
        for(Player p:ctx.game().getPlayers()){
            var zones=new LinkedHashMap<String,Object>();
            for(ZoneType z:List.of(ZoneType.Library,ZoneType.Hand,ZoneType.Battlefield,ZoneType.Graveyard,ZoneType.Exile,ZoneType.Command)){
                var cards=new ArrayList<Object>();for(Card card:p.getCardsIn(z))cards.add(List.of(card.getId(),card.getName(),card.getController().getId(),card.isTapped(),card.getNetPower(),card.getNetToughness(),card.getDamage(),card.getCounters().toString()));zones.put(z.name(),cards);
            }
            players.add(List.of(p.getId(),p.getLife(),zones));
        }
        return JSON.toJson(List.of(players,ctx.combat().getAttackers().stream().map(Card::getId).toList(),
            AiProfileUtil.getIntProperty(ctx.ai(),AiProps.AI_IN_DANGER_THRESHOLD),AiProfileUtil.getIntProperty(ctx.ai(),AiProps.AI_IN_DANGER_MAX_THRESHOLD)));
    }
    private Map<String,Object> runCase(String label,int depth,boolean isolated,String exit,long seed){
        Context ctx=context();String before=state(ctx);DrawRandom gameplay=new DrawRandom(seed),local=new DrawRandom(LOCAL_SEED);MyRandom.setRandom(gameplay);
        ROLE.set("SPECULATIVE");long start=System.nanoTime();boolean[] completed={false};String[] error={null};
        try(var budget=AiEvaluationDiagnostics.begin(ctx.ai(),depth)){
            Supplier<Boolean> work=()->AiEvaluationDiagnostics.boundedPrediction(()->{
                for(int i=0;i<depth;i++){
                    AiEvaluationDiagnostics.advisoryCheckpoint("rngFixtureIteration");
                    ComputerUtilCombat.lifeInDanger(ctx.ai(),ctx.combat());
                    if(exit.equals("exception"))throw new IllegalStateException("fixture-native-search-abort");
                    if(exit.equals("wall")){
                        try{Thread.sleep(520);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException();}
                        AiEvaluationDiagnostics.advisoryCheckpoint("afterRealWallBudget");
                    }
                }
                completed[0]=true;return true;
            },false);
            try{if(isolated)MyRandom.withRandom(local,work);else work.get();}catch(IllegalStateException ex){Assert.assertEquals(exit,"exception");error[0]=ex.getMessage();}
        }
        long elapsed=System.nanoTime()-start;Assert.assertEquals(state(ctx),before,"Read-only speculative fixture changed committed context");
        Assert.assertSame(MyRandom.getRandom(),gameplay,"Provider not restored after completion/abort");
        long beforeCommit=gameplay.draws;ROLE.set("COMMITTED");int committed=MyRandom.getRandom().nextInt();ROLE.remove();
        var row=new LinkedHashMap<String,Object>();row.put("case",label);row.put("isolatedFixture",isolated);row.put("depth",depth);row.put("exit",exit);row.put("seed",seed);
        row.put("completedSearch",completed[0]);row.put("exception",error[0]);row.put("speculativeGlobalDraws",beforeCommit);row.put("speculativeLocalDraws",local.draws);row.put("nextCommittedDraw",committed);
        row.put("wallNsIncludingTracing",elapsed);row.put("contextBefore",before);row.put("contextAfter",state(ctx));row.put("actualGlobalDrawTrace",gameplay.trace);row.put("actualLocalDrawTrace",local.trace);
        System.out.println("RNG_CASE "+JSON.toJson(row));return row;
    }
    @Test public void actualNativeDepthLeaksIntoCommittedRandomness(){
        var small=runCase("global-small",1,false,"normal",GAME_SEED);var large=runCase("global-large",5,false,"normal",GAME_SEED);
        Assert.assertTrue((long)small.get("speculativeGlobalDraws")>0,"Native method made no observed draw");
        Assert.assertTrue((long)large.get("speculativeGlobalDraws")>(long)small.get("speculativeGlobalDraws"));
        Assert.assertEquals(small.get("contextBefore"),large.get("contextBefore"));Assert.assertNotEquals(small.get("nextCommittedDraw"),large.get("nextCommittedDraw"));
    }
    @Test public void explicitPrivateStreamKeepsGameplayDrawStableAcrossDepth(){
        var small=runCase("private-small",1,true,"normal",GAME_SEED);var large=runCase("private-large",5,true,"normal",GAME_SEED);
        Assert.assertEquals(small.get("contextBefore"),large.get("contextBefore"));Assert.assertEquals(small.get("nextCommittedDraw"),large.get("nextCommittedDraw"));
        Assert.assertEquals(small.get("speculativeGlobalDraws"),0L);Assert.assertEquals(large.get("speculativeGlobalDraws"),0L);
    }
    @Test public void workLimitExhaustionRetainsOldDrawsAndRestoresPrivateBoundary(){
        for(boolean isolate:new boolean[]{false,true}){
            var row=runCase("work-abort-"+isolate,3000,isolate,"work",GAME_SEED);Assert.assertEquals(row.get("completedSearch"),false);
            Assert.assertTrue((long)row.get("wallNsIncludingTracing")<500_000_000L,"Wall limit intervened; work-only proof unavailable");
            Assert.assertEquals((long)row.get("speculativeGlobalDraws")==0,isolate);
        }
    }
    @Test public void realWallBudgetAbortDoesNotRollBackGlobalRng(){
        for(boolean isolate:new boolean[]{false,true}){
            var row=runCase("wall-abort-"+isolate,1,isolate,"wall",GAME_SEED);Assert.assertEquals(row.get("completedSearch"),false);
            Assert.assertTrue((long)row.get("wallNsIncludingTracing")>=500_000_000L);Assert.assertEquals((long)row.get("speculativeGlobalDraws")==0,isolate);
        }
    }
    @Test public void exceptionalExitRestoresPrivateProvider(){
        for(boolean isolate:new boolean[]{false,true}){
            var row=runCase("exception-"+isolate,2,isolate,"exception",GAME_SEED);Assert.assertEquals(row.get("exception"),"fixture-native-search-abort");
            Assert.assertEquals((long)row.get("speculativeGlobalDraws")==0,isolate);
        }
    }
    @Test public void nestedPrivateStreamsRestoreOuterThenGameplay(){
        Context ctx=context();var game=new DrawRandom(GAME_SEED);var outer=new DrawRandom(11);var inner=new DrawRandom(12);MyRandom.setRandom(game);
        MyRandom.withRandom(outer,()->{
            ComputerUtilCombat.lifeInDanger(ctx.ai(),ctx.combat());
            try{MyRandom.withRandom(inner,()->{ComputerUtilCombat.lifeInDanger(ctx.ai(),ctx.combat());throw new IllegalStateException("nested");});}catch(IllegalStateException expected){Assert.assertEquals(expected.getMessage(),"nested");}
            Assert.assertSame(MyRandom.getRandom(),outer);ComputerUtilCombat.lifeInDanger(ctx.ai(),ctx.combat());return true;
        });
        Assert.assertSame(MyRandom.getRandom(),game);Assert.assertEquals(game.draws,0L);Assert.assertTrue(outer.draws>0&&inner.draws>0);
    }
    @Test public void threadLocalOwnershipDoesNotAutomaticallyPropagateToWorkers() throws Exception {
        Context ctx=context();var gameplay=new DrawRandom(GAME_SEED);var parent=new DrawRandom(11);MyRandom.setRandom(gameplay);var executor=Executors.newSingleThreadExecutor();
        try{
            MyRandom.withRandom(parent,()->{
                try{executor.submit(()->{ROLE.set("SPECULATIVE_WORKER");try{return ComputerUtilCombat.lifeInDanger(ctx.ai(),ctx.combat());}finally{ROLE.remove();}}).get(5,TimeUnit.SECONDS);}catch(Exception x){throw new IllegalStateException(x);}return true;
            });
        }finally{executor.shutdownNow();Assert.assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
        Assert.assertTrue(gameplay.draws>0);Assert.assertEquals(parent.draws,0L);Assert.assertSame(MyRandom.getRandom(),gameplay);
    }
    @Test public void workerExplicitIsolationAndCancellationAwaitTermination() throws Exception {
        for(boolean isolate:new boolean[]{false,true}){
            Context ctx=context();var gameplay=new DrawRandom(GAME_SEED);var local=new DrawRandom(44);MyRandom.setRandom(gameplay);
            var started=new CountDownLatch(1);var finished=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
            Future<?> future=executor.submit(()->{
                ROLE.set("SPECULATIVE_CANCELLED_WORKER");
                try(var budget=AiEvaluationDiagnostics.begin(ctx.ai(),1)){
                    Supplier<Boolean> prediction=()->AiEvaluationDiagnostics.boundedPrediction(()->{
                        ComputerUtilCombat.lifeInDanger(ctx.ai(),ctx.combat());started.countDown();
                        try{new CountDownLatch(1).await();}catch(InterruptedException x){Thread.currentThread().interrupt();throw new CancellationException("cancelled fixture");}
                        return true;
                    },false);
                    if(isolate)MyRandom.withRandom(local,prediction);else prediction.get();
                }finally{ROLE.remove();finished.countDown();}
            });
            Assert.assertTrue(started.await(5,TimeUnit.SECONDS));future.cancel(true);executor.shutdownNow();Assert.assertTrue(finished.await(5,TimeUnit.SECONDS));Assert.assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));
            long draws=gameplay.draws;Thread.sleep(20);Assert.assertEquals(gameplay.draws,draws,"Draws continued after confirmed worker termination");Assert.assertEquals(draws==0,isolate);
            System.out.println("RNG_CANCELLATION "+JSON.toJson(Map.of("isolatedFixture",isolate,"workerTerminated",true,"globalDraws",draws,"localDraws",local.draws,"noPostTerminationDraws",true)));
        }
    }
    @Test public void drawRecorderPreservesJavaRandomDistribution(){
        for(long seed:new long[]{0,1,573307,Long.MAX_VALUE}){
            var expected=new Random(seed);var actual=new DrawRandom(seed);
            for(int i=0;i<64;i++){Assert.assertEquals(actual.nextInt(80),expected.nextInt(80));Assert.assertEquals(actual.nextFloat(),expected.nextFloat());Assert.assertEquals(actual.nextLong(),expected.nextLong());}
        }
    }
    @Test public void controlledNativeModeDistributionsAreReportedAcrossSeeds(){
        var distributions=new LinkedHashMap<String,Map<String,Integer>>();
        for(boolean isolated:new boolean[]{false,true})for(int depth:new int[]{1,5}){
            var histogram=new TreeMap<String,Integer>();
            for(int index=0;index<16;index++){
                Context ctx=context();var p=ctx.ai();var card=addCard("Atsushi, the Blazing Sky",p);
                ctx.game().getAction().moveToLibrary(addCard("Mountain",p),null);ctx.game().getAction().moveToLibrary(addCard("Mountain",p),null);
                var sa=forge.game.ability.AbilityFactory.getAbility(card.getSVar("TrigCharm"),card);sa.setActivatingPlayer(p);sa.setTrigger(card.getTriggers().get(0));
                MyRandom.setRandom(new Random(index*4096L));
                try(var d=AiEvaluationDiagnostics.begin(p,depth)){
                    Supplier<Boolean> work=()->AiEvaluationDiagnostics.boundedPrediction(()->{for(int j=0;j<depth;j++)ComputerUtilCombat.lifeInDanger(p,ctx.combat());return true;},false);
                    if(isolated)MyRandom.withRandom(new Random(LOCAL_SEED),work);else work.get();
                }
                var choices=p.getController().chooseModeForAbility(sa,forge.game.ability.effects.CharmEffect.makePossibleOptions(sa),1,1,false);
                Assert.assertNotNull(choices);Assert.assertEquals(choices.size(),1);histogram.merge(choices.get(0).getApi().name(),1,Integer::sum);
            }
            distributions.put((isolated?"private":"global")+"-depth-"+depth,histogram);
        }
        Assert.assertEquals(distributions.get("private-depth-1"),distributions.get("private-depth-5"));
        System.out.println("RNG_CHOICE_DISTRIBUTIONS "+JSON.toJson(distributions));
    }
}
