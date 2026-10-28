package forge.ai;

import com.google.gson.Gson;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import java.lang.management.ManagementFactory;
import java.util.*;
import org.testng.Assert;
import org.testng.annotations.Test;

/** Deterministic legal-board projections, not exact replay of a historical game. */
public final class BusyBoardBenchmark extends AITest {
    private static final String[] CASES={"early","large-four-player","dragon-tokens","treasure-heavy",
            "lathliss-ganax","lathliss-throne","tempest-valkas","large-combat","wipe-recovery","late-100"};
    private Game board(String name) {
        forge.util.MyRandom.setRandom(new Random(771230));
        var seats=new ArrayList<RegisteredPlayer>();
        for(int i=0;i<4;i++)seats.add(new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("seat"+i,null)));
        var rules=new GameRules(GameType.Commander);rules.setAppliedVariants(EnumSet.of(GameType.Commander));
        var game=new Game(seats,rules,new Match(rules,seats,"busy-board"));game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1,game.getPlayers().get(0),50);
        for(var p:game.getPlayers()) {
            p.setLife(1000,null);for(int i=0;i<40;i++)addCardToZone("Shivan Dragon",p,ZoneType.Library);
            int creatures=name.equals("early")?2:name.equals("large-four-player")?16:name.equals("large-combat")?12:name.equals("late-100")?14:5;
            for(int i=0;i<(name.equals("early")?3:12);i++)addCard("Mountain",p);
            for(int i=0;i<creatures;i++){var c=addCard("Shivan Dragon",p);c.setSickness(false);}
            if(name.equals("dragon-tokens"))for(int i=0;i<20;i++){var c=addToken("r_6_6_dragon_flying",p);c.setSickness(false);}
            if(name.equals("treasure-heavy"))for(int i=0;i<35;i++)addToken("c_a_treasure_sac",p);
            if(name.startsWith("lathliss")){addCard("Lathliss, Dragon Queen",p);addCard("Ganax, Astral Hunter",p);}
            if(name.equals("tempest-valkas")){addCard("Dragon Tempest",p);addCard("Scourge of Valkas",p);}
            addCardToZone("Shivan Dragon",p,ZoneType.Hand);addCardToZone("Lightning Bolt",p,ZoneType.Hand);
        }
        game.getAction().checkStateEffects(true);game.getTriggerHandler().resetActiveTriggers();
        if(name.equals("lathliss-throne"))for(var p:game.getPlayers()) {
            var c=addCardToZone("Roaming Throne",p,ZoneType.Hand);game.getAction().moveToPlay(c,null,AbilityKey.newMap());drain(game);
            Assert.assertEquals(c.getChosenType(),"Dragon");
        }
        if(name.equals("wipe-recovery")) {
            var p=game.getPlayers().get(0);var wrath=addCardToZone("Wrath of God",p,ZoneType.Hand);
            var a=wrath.getFirstSpellAbility();a.setActivatingPlayer(p);game.getStack().add(a);drain(game);
            Assert.assertTrue(game.getCardsIn(ZoneType.Battlefield).stream().noneMatch(Card::isCreature));
            for(var player:game.getPlayers())addCard("Shivan Dragon",player).setSickness(false);
        }
        Assert.assertEquals(game.getPlayers().size(),4);return game;
    }
    void drain(Game game) {
        for(int i=0;i<2000;i++) {
            game.getAction().checkStateEffects(true);game.getStack().addAllTriggeredAbilitiesToStack();
            if(game.getStack().isEmpty())return;game.getStack().resolveStack();
        }
        throw new AssertionError("Unbounded fixture stack");
    }
    long gc(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionTime())).sum();}
    private String label(Card c) {
        var p=c.getController();return p.getName()+":"+c.getName()+":"+p.getCreaturesInPlay().indexOf(c);
    }
    private Object legal(Game game,Combat combat) {
        var out=new ArrayList<String>();var p=game.getPlayers().get(0);
        for(var a:p.getCreaturesInPlay())for(var defender:game.getPlayers())if(defender!=p) {
            if(forge.game.combat.CombatUtil.canAttack(a,defender))out.add("attack:"+label(a)+":"+defender.getName());
            for(var b:defender.getCreaturesInPlay())if(forge.game.combat.CombatUtil.canBlock(a,b,combat))out.add("block:"+label(a)+":"+label(b));
        }
        return out;
    }
    private Object state(Game game,Combat combat) {
        var out=new LinkedHashMap<String,Object>();var players=new ArrayList<Object>();
        for(var p:game.getRegisteredPlayers()) {
            var row=new LinkedHashMap<String,Object>();row.put("player",p.getName());row.put("life",p.getLife());
            for(var zone:List.of(ZoneType.Battlefield,ZoneType.Graveyard,ZoneType.Hand,ZoneType.Library,ZoneType.Exile)) {
                var cards=new ArrayList<Object>();for(var c:p.getCardsIn(zone))cards.add(Arrays.asList(c.getName(),c.getType().toString(),c.isToken(),c.isTapped(),c.getNetPower(),c.getNetToughness(),c.getDamage(),c.getCounters().toString(),c.getTriggers().size()));row.put(zone.name(),cards);
            }
            players.add(row);
        }
        out.put("players",players);out.put("stackSize",game.getStack().size());out.put("gameOver",game.isGameOver());
        if(game.isGameOver())out.put("draw",game.getOutcome().isDraw());return out;
    }
    private Object assignments(Combat combat) {
        var out=new ArrayList<Object>();for(var a:combat.getAttackers())out.add(Arrays.asList(label(a),String.valueOf(combat.getDefenderByAttacker(a)),combat.getBlockers(a).stream().map(this::label).toList()));return out;
    }
    @Test public void deterministicWorkloads() {
        var thread=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        var selected=Set.of(System.getProperty("forge.bench.cases",String.join(",",CASES)).split(","));
        var paths=List.of(System.getProperty("forge.bench.paths","main,attack,block,triggers").split(","));
        int repeats=Integer.getInteger("forge.bench.repeats",3),warmup=Integer.getInteger("forge.bench.warmup",0);
        boolean verify=Boolean.getBoolean("forge.bench.verify");
        for(String name:CASES)if(selected.contains(name))for(String path:paths)for(int rep=-warmup;rep<repeats;rep++) {
            Game game=board(name);var p=game.getPlayers().get(0);var opponent=game.getPlayers().get(1);
            if(path.equals("attack") || path.equals("block"))game.getPhaseHandler().devModeSet(
                    path.equals("attack")?PhaseType.COMBAT_DECLARE_ATTACKERS:PhaseType.COMBAT_DECLARE_BLOCKERS,p,50);
            var combat=new Combat(p);
            if(path.equals("block"))for(Card c:p.getCreaturesInPlay())combat.addAttacker(c,opponent);
            if(path.equals("attack") || path.equals("block"))game.getPhaseHandler().setCombat(combat);
            Object legalBefore=verify?legal(game,combat):null;
            long[] countsBefore=forge.game.card.BlockCostMetrics.snapshot();
            long[] costBefore=forge.game.cost.CostSourceMetrics.snapshot();
            long alloc=thread.isThreadAllocatedMemorySupported()?thread.getThreadAllocatedBytes(Thread.currentThread().getId()):0;
            long cpu=thread.getCurrentThreadCpuTime(),gc=gc(),start=System.nanoTime();Object result;
            if(path.equals("main")) result=((PlayerControllerAi)p.getController()).getAi().chooseSpellAbilityToPlay();
            else if(path.equals("attack"))result=new AiAttackController(p).declareAttackers(combat);
            else if(path.equals("block")){new AiBlockController(opponent,false).assignBlockersForCombat(combat);result=combat.getAllBlockers().size();}
            else {var c=addCardToZone("Shivan Dragon",p,ZoneType.Hand);game.getAction().moveToPlay(c,null,AbilityKey.newMap());drain(game);result=game.getCardsIn(ZoneType.Battlefield).size();}
            var row=new LinkedHashMap<String,Object>();row.put("case",name);row.put("path",path);row.put("rep",rep);
            row.put("wallMs",(System.nanoTime()-start)/1e6);row.put("cpuMs",(thread.getCurrentThreadCpuTime()-cpu)/1e6);
            row.put("gcMs",gc()-gc);row.put("allocatedBytesCurrentThread",thread.isThreadAllocatedMemorySupported()?thread.getThreadAllocatedBytes(Thread.currentThread().getId())-alloc:null);
            row.put("heapUsed",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            row.put("permanents",game.getCardsIn(ZoneType.Battlefield).size());
            row.put("tokens",game.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isToken).count());
            row.put("warmup",rep<0);
            long[] countsAfter=forge.game.card.BlockCostMetrics.snapshot();row.put("blockCostCalls",countsAfter[0]-countsBefore[0]);row.put("staticCollectionsAvoided",countsAfter[1]-countsBefore[1]);row.put("zeroCostObjectsAvoided",countsAfter[2]-countsBefore[2]);
            long[] costAfter=forge.game.cost.CostSourceMetrics.snapshot();row.put("costAdjustmentCalls",costAfter[0]-costBefore[0]);row.put("costStaticCollectionsAvoided",costAfter[1]-costBefore[1]);
            if(verify){row.put("legal",legalBefore);row.put("assignments",assignments(combat));row.put("state",state(game,combat));
                if(path.equals("attack") || path.equals("block")){combat.orderBlockersForDamageAssignment();combat.orderAttackersForDamageAssignment();combat.fireTriggersForUnblockedAttackers(game);drain(game);combat.assignCombatDamage(true);combat.dealAssignedDamage();drain(game);combat.assignCombatDamage(false);combat.dealAssignedDamage();drain(game);game.getAction().checkStateEffects(true);row.put("afterDamage",state(game,combat));}}
            row.put("result",String.valueOf(result));System.out.println("BUSY_BENCH "+new Gson().toJson(row));
        }
    }
}
