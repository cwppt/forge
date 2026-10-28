package forge.parity;

import com.google.gson.Gson;
import forge.ai.*;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;

public final class ChoiceProbe {
    private static PrintWriter output;
    private static final Gson JSON=new Gson();
    private static final AtomicLong IDS=new AtomicLong();
    private static final ThreadLocal<Deque<Long>> ACTIVE=ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Boolean> INTERNAL=ThreadLocal.withInitial(()->false);
    private static final ThreadLocal<Boolean> WINDOW=ThreadLocal.withInitial(()->false);
    private static final ThreadLocal<Integer> WINDOW_CALLS=ThreadLocal.withInitial(()->0);
    private static final Map<String,AtomicLong> RNG_REQUESTS=new ConcurrentHashMap<>();
    private static Field seed,local,current;
    private static int events;
    public static void initialize() {
        try {
            output=new PrintWriter(Files.newBufferedWriter(Path.of(System.getProperty("forge.choice.output"))));
            seed=Random.class.getDeclaredField("seed");seed.setAccessible(true);
            Runtime.getRuntime().addShutdownHook(new Thread(()->{event("AGENT_END",Map.of("rngProviderRequestsByThread",RNG_REQUESTS,"eventCapReached",events>=2048));output.close();}));
            event("AGENT_BEGIN","Read-only RNG seed; no draws, provider replacement or result edits");
        } catch(Exception x){throw new IllegalStateException(x);}
    }
    public static synchronized void event(String type,Object data) {
        if(events++>=2048 && !type.equals("AGENT_END"))return;
        var row=new LinkedHashMap<String,Object>();row.put("type",type);row.put("thread",Thread.currentThread().getName());row.put("scope",ACTIVE.get().peek());row.put("data",data);
        output.println(JSON.toJson(row));output.flush();
    }
    private static Map<String,Object> rngState(Random rng) {
        try {
            if(local==null){local=MyRandom.class.getDeclaredField("localRandom");local.setAccessible(true);}
            var map=new LinkedHashMap<String,Object>();map.put("providerClass",rng.getClass().getName());
            map.put("seed48",rng.getClass()==Random.class?((AtomicLong)seed.get(rng)).get():null);
            map.put("threadLocal",((ThreadLocal<?>)local.get(null)).get()!=null);return map;
        }catch(Exception x){throw new IllegalStateException(x);}
    }
    private static Map<String,Object> budget() {
        try {
            if(current==null){current=AiEvaluationDiagnostics.class.getDeclaredField("CURRENT");current.setAccessible(true);}
            Object d=((ThreadLocal<?>)current.get(null)).get();var map=new LinkedHashMap<String,Object>();map.put("active",d!=null);
            if(d!=null)for(String name:List.of("data","work","fallbackCalls","predictionAborts","budgetMs","workLimit","start")){
                Field f=AiEvaluationDiagnostics.class.getDeclaredField(name);f.setAccessible(true);map.put(name.equals("start")?"elapsedNs":name,name.equals("start")?System.nanoTime()-f.getLong(d):f.get(d));
            }
            return map;
        }catch(Exception x){throw new IllegalStateException(x);}
    }
    public static boolean target(SpellAbility sa){return sa!=null && sa.getHostCard().getName().equals("Atsushi, the Blazing Sky");}
    private static Object card(Card card){
        var m=new LinkedHashMap<String,Object>();m.put("id",card.getId());m.put("name",card.getName());m.put("type",card.getType().toString());m.put("controller",card.getController().getId());
        m.put("tapped",card.isTapped());m.put("power",card.getNetPower());m.put("toughness",card.getNetToughness());m.put("damage",card.getDamage());m.put("counters",card.getCounters().toString());return m;
    }
    private static Object state(SpellAbility sa){
        var game=sa.getHostCard().getGame();var players=new ArrayList<>();
        for(Player p:game.getPlayers()){
            var zones=new LinkedHashMap<String,Object>();
            for(ZoneType z:List.of(ZoneType.Library,ZoneType.Hand,ZoneType.Battlefield,ZoneType.Graveyard,ZoneType.Exile,ZoneType.Command)){
                var cards=new ArrayList<>();for(Card card:p.getCardsIn(z))cards.add(card(card));zones.put(z.name(),cards);
            }
            var row=new LinkedHashMap<String,Object>();row.put("id",p.getId());row.put("life",p.getLife());row.put("zones",zones);players.add(row);
        }
        return players;
    }
    public static long begin(String kind,SpellAbility sa){
        if(!target(sa))return 0;
        long id=IDS.incrementAndGet();INTERNAL.set(true);
        try{
            var data=new LinkedHashMap<String,Object>();data.put("id",id);data.put("parent",ACTIVE.get().peek());data.put("kind",kind);data.put("host",sa.getHostCard().getId());
            var g=sa.getHostCard().getGame();data.put("turn",g.getPhaseHandler().getTurn());data.put("phase",g.getPhaseHandler().getPhase().name());data.put("trigger",sa.isTrigger());
            data.put("activatingPlayer",sa.getActivatingPlayer().getId());data.put("rng",rngState(MyRandom.getRandom()));data.put("budgetBeforeSnapshot",budget());
            data.put("tokenChancePercent",AiProfileUtil.getIntProperty(sa.getActivatingPlayer(),AiProps.TOKEN_GENERATION_ABILITY_CHANCE));
            data.put("players",state(sa));data.put("rngProviderRequestsByThread",new TreeMap<>(RNG_REQUESTS));ACTIVE.get().push(id);event("CHOICE_BEGIN",data);return id;
        }finally{INTERNAL.set(false);}
    }
    public static void end(long id,SpellAbility sa,Throwable thrown){
        if(id==0)return;INTERNAL.set(true);
        try{var data=new LinkedHashMap<String,Object>();data.put("id",id);data.put("chosen",options(sa.getChosenList()));data.put("rng",rngState(MyRandom.getRandom()));data.put("budget",budget());data.put("exception",thrown==null?null:thrown.toString());event("CHOICE_END",data);ACTIVE.get().pop();}
        finally{INTERNAL.set(false);}
    }
    private static Object options(List<?> list){
        if(list==null)return null;var result=new ArrayList<>();for(Object x:list){SpellAbility sa=(SpellAbility)x;result.add(Map.of("api",sa.getApi().name(),"description",sa.getDescription()));}return result;
    }
    public static void order(List<?> options){if(!ACTIVE.get().isEmpty()){INTERNAL.set(true);try{event("SHUFFLED_ORDER",Map.of("options",options(options),"rngAfterShuffle",rngState(MyRandom.getRandom())));}finally{INTERNAL.set(false);}}}
    public static void check(SpellAbility sa,boolean willing){if(!ACTIVE.get().isEmpty()&&target(sa))event("MODE_CHECK",Map.of("api",sa.getApi().name(),"willing",willing));}
    public static void rng(Random rng){
        if(INTERNAL.get())return;
        RNG_REQUESTS.computeIfAbsent(Thread.currentThread().getName(),x->new AtomicLong()).incrementAndGet();
        if(!ACTIVE.get().isEmpty())event("RNG_PROVIDER",rngState(rng));
        if(WINDOW.get()){
            int count=WINDOW_CALLS.get()+1;WINDOW_CALLS.set(count);
            if(count<=64){var data=new LinkedHashMap<String,Object>();data.put("rng",rngState(rng));data.put("ordinal",count);
                var stack=new ArrayList<String>();for(StackTraceElement f:Thread.currentThread().getStackTrace())if(f.getClassName().startsWith("forge.")&&!f.getClassName().startsWith("forge.parity.")){stack.add(f.toString());if(stack.size()==8)break;}
                data.put("callers",stack);event("BUDGET_RNG",data);}
        }
    }
    public static void budgetBegin(Object diagnostics){
        if(diagnostics==null)return;
        try{
            Field f=diagnostics.getClass().getDeclaredField("data");f.setAccessible(true);Map<?,?> data=(Map<?,?>)f.get(diagnostics);
            if(((Number)data.get("turn")).intValue()!=Integer.getInteger("forge.choice.scopeTurn",40)||!data.get("phase").equals("COMBAT_BEGIN")||((Number)data.get("seat")).intValue()!=Integer.getInteger("forge.choice.scopeSeat",1))return;
            WINDOW.set(true);WINDOW_CALLS.set(0);INTERNAL.set(true);
            try{event("BUDGET_BEGIN",Map.of("budget",budget(),"rng",rngState(MyRandom.getRandom())));}finally{INTERNAL.set(false);}
        }catch(Exception x){throw new IllegalStateException(x);}
    }
    public static void budgetEnd(){
        if(!WINDOW.get())return;INTERNAL.set(true);
        try{event("BUDGET_END",Map.of("budget",budget(),"rng",rngState(MyRandom.getRandom()),"providerRequests",WINDOW_CALLS.get(),"rngRecordsCapped",WINDOW_CALLS.get()>64));}finally{INTERNAL.set(false);}
    }
    public static void budgetReset(){WINDOW.set(false);}
}
