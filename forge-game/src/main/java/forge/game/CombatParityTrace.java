package forge.game;

import com.google.common.eventbus.Subscribe;
import com.google.gson.Gson;
import forge.game.card.*;
import forge.game.event.*;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Opt-in native observation only. Never evaluates actions, mutates a game, or consumes RNG. */
public final class CombatParityTrace implements AutoCloseable {
    private static final Map<Game,CombatParityTrace> LIVE=new ConcurrentHashMap<>();
    private static final Gson JSON=new com.google.gson.GsonBuilder().serializeNulls().create();
    private final Game game;
    private final BufferedWriter writer;
    private final Deque<Long> batches=new ArrayDeque<>();
    private long sequence,nextBatch;
    private boolean closed;
    private IOException failure;
    private CombatParityTrace(Game g,Path p) throws IOException {
        game=g;writer=Files.newBufferedWriter(p,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }
    public static void begin(Game g) throws IOException {
        String p=System.getProperty("forge.parity.trace");if(p==null)return;
        var o=new CombatParityTrace(g,Path.of(p));
        if(LIVE.putIfAbsent(g,o)!=null){o.writer.close();throw new IOException("Duplicate parity observer");}
        g.subscribeToEvents(o);o.emit("BEGIN",Map.of("schema",1,"state",o.state()));
    }
    public static void finish(Game g) throws IOException {
        var o=LIVE.remove(g);if(o!=null)o.close();
    }
    private static String key(GameEntity e) {return (e instanceof Card?"card:":"player:")+e.getId();}
    private static Object describe(Object v) {
        if(v==null || v instanceof String || v instanceof Number || v instanceof Boolean)return v;
        if(v instanceof Enum<?>)return v.toString();
        if(v instanceof CounterType)return v.toString();
        if(v instanceof com.google.common.collect.Multiset<?> m){var r=new LinkedHashMap<String,Object>();for(var e:m.entrySet())r.put(String.valueOf(e.getElement()),e.getCount());return r;}
        if(v instanceof GameEntity e)return key(e);
        if(v instanceof SpellAbility a)return ability(a);
        if(v instanceof CardDamageTable t)return damage(t);
        if(v instanceof forge.game.spellability.SpellAbilityStackInstance s)return ability(s.getSpellAbility());
        if(v instanceof Trigger t)return Map.of("host",t.getHostCard().getId(),"mode",t.getMode().name(),"params",new LinkedHashMap<>(t.getMapParams()));
        if(v instanceof com.google.common.collect.Table<?,?,?> t){var r=new LinkedHashMap<String,Object>();for(var cell:t.cellSet())r.put(String.valueOf(describe(cell.getRowKey()))+"->"+String.valueOf(describe(cell.getColumnKey())),describe(cell.getValue()));return r;}
        if(v instanceof Map<?,?> m){var r=new LinkedHashMap<String,Object>();for(var e:m.entrySet())r.put(String.valueOf(describe(e.getKey())),describe(e.getValue()));return r;}
        if(v instanceof Iterable<?> xs){var r=new ArrayList<Object>();for(var x:xs)r.add(describe(x));return r;}
        // Unknown context is exposed, never falsely described as complete coverage.
        return Map.of("unexpandedType",v.getClass().getName());
    }
    private static Map<String,Object> ability(SpellAbility a) {
        var r=new LinkedHashMap<String,Object>();r.put("host",a.getHostCard().getId());
        r.put("api",String.valueOf(a.getApi()));r.put("description",a.getDescription());
        r.put("controller",a.getActivatingPlayer()==null?null:a.getActivatingPlayer().getId());
        r.put("trigger",a.isTrigger());r.put("params",new LinkedHashMap<>(a.getMapParams()));
        var targets=new ArrayList<Object>();for(var choices:a.getAllTargetChoices())targets.add(describe(choices));r.put("targets",targets);
        return r;
    }
    private static Object damage(CardDamageTable table) {
        var out=new LinkedHashMap<String,Object>();
        for(var c:table.cellSet())out.put(c.getRowKey().getId()+"->"+key(c.getColumnKey()),c.getValue());
        return out;
    }
    private Object state() {
        var players=new LinkedHashMap<String,Object>();
        for(var p:game.getRegisteredPlayers()) {
            var cards=new LinkedHashMap<String,Object>();
            for(var c:p.getCardsIn(ZoneType.Battlefield)) {
                var r=new LinkedHashMap<String,Object>();r.put("name",c.getName());r.put("controller",c.getController().getId());
                r.put("owner",c.getOwner().getId());r.put("type",c.getType().toString());r.put("token",c.isToken());
                r.put("tapped",c.isTapped());r.put("power",c.getNetPower());r.put("toughness",c.getNetToughness());
                r.put("damage",c.getDamage());r.put("counters",describe(c.getCounters()));cards.put(String.valueOf(c.getId()),r);
            }
            players.put(String.valueOf(p.getId()),Map.of("life",p.getLife(),"counters",describe(p.getCounters()),"battlefield",cards));
        }
        return Map.of("players",players,"stackSize",game.getStack().size(),"gameOver",game.isGameOver());
    }
    private Object assignments() {
        var combat=game.getCombat();var out=new LinkedHashMap<String,Object>();
        if(combat!=null)for(var a:combat.getAttackers()) {
            var blockers=new ArrayList<Integer>();for(var b:combat.getBlockers(a))blockers.add(b.getId());
            out.put(String.valueOf(a.getId()),Map.of("defender",key(combat.getDefenderByAttacker(a)),"blockers",blockers));
        }
        return out;
    }
    private synchronized void emit(String type,Object data) {
        if(closed)return;
        var r=new LinkedHashMap<String,Object>();r.put("eventIndex",sequence++);r.put("type",type);
        r.put("turn",game.getPhaseHandler().getTurn());r.put("phase",String.valueOf(game.getPhaseHandler().getPhase()));
        r.put("batch",batches.peek());r.put("data",data);
        try{writer.write(JSON.toJson(r));writer.newLine();writer.flush();}
        catch(IOException e){failure=e;throw new UncheckedIOException(e);}
    }
    public static void beginDamage(Game g,boolean combat,CardDamageTable table,SpellAbility cause) {
        var o=LIVE.get(g);if(o==null)return;o.batches.push(++o.nextBatch);
        var r=new LinkedHashMap<String,Object>();r.put("combat",combat);r.put("requested",damage(table));
        r.put("cause",cause==null?null:ability(cause));r.put("assignments",o.assignments());r.put("state",o.state());o.emit("BATCH_BEGIN",r);
    }
    public static void replacedDamage(Game g,CardDamageTable table) {
        var o=LIVE.get(g);if(o!=null)o.emit("BATCH_REPLACED",damage(table));
    }
    public static void endDamage(Game g) {
        var o=LIVE.get(g);if(o!=null){o.emit("BATCH_END",o.state());o.batches.pop();}
    }
    public static void trigger(Game g,Trigger t,SpellAbility a) {
        var o=LIVE.get(g);if(o==null)return;
        o.emit("TRIGGER_READY",Map.of("mode",t.getMode().name(),"params",new LinkedHashMap<>(t.getMapParams()),"ability",ability(a),"context",describe(a.getTriggeringObjects())));
    }
    public static void resolving(Game g,SpellAbility a) {
        var o=LIVE.get(g);if(o!=null)o.emit("RESOLVE_START",Map.of("ability",ability(a),"state",o.state()));
    }
    public static void staticDispatch(Game g,SpellAbility a,boolean begin) {
        var o=LIVE.get(g);if(o!=null)o.emit(begin?"STATIC_DISPATCH_BEGIN":"STATIC_DISPATCH_END",Map.of("ability",ability(a),"state",o.state()));
    }
    @Subscribe public void observe(GameEvent e) {
        try { observeEvent(e); }
        catch(RuntimeException error) { failure=new IOException("Parity subscriber failed",error);throw error; }
    }
    private void observeEvent(GameEvent e) {
        if(closed)return;
        if(e instanceof GameEventAttackersDeclared)oAssignments("ATTACK_ASSIGNMENTS");
        else if(e instanceof GameEventBlockersDeclared)oAssignments("BLOCK_ASSIGNMENTS");
        else if(e instanceof GameEventCardDamaged d)emit("DAMAGE",Map.of("source",d.source()==null?-1:d.source().getId(),"recipient","card:"+d.card().getId(),"amount",d.amount(),"kind",d.type().name()));
        else if(e instanceof GameEventPlayerDamaged d)emit("DAMAGE",Map.of("source",d.source()==null?-1:d.source().getId(),"recipient","player:"+d.target().getId(),"amount",d.amount(),"combat",d.combat(),"infect",d.infect()));
        else if(e instanceof GameEventSpellAbilityCast d)emit("STACK_ADD",Map.of("host",d.sa().getHostCard().getId(),"description",d.sa().getDescription(),"trigger",d.si().isTrigger(),"targets",String.valueOf(d.targetDescription()),"controller",d.si().getActivatingPlayer().getId()));
        else if(e instanceof GameEventSpellResolved d)emit("RESOLVED",Map.of("host",d.spell().getHostCard().getId(),"description",d.stackDescription(),"fizzled",d.hasFizzled(),"state",state()));
        else if(e instanceof GameEventCardChangeZone d)emit("ZONE",Map.of("card",d.card().getId(),"from",d.from()==null?"none":d.from().zoneType().name(),"to",d.to()==null?"none":d.to().zoneType().name(),"state",state()));
        else if(e instanceof GameEventTurnPhase || e instanceof GameEventCombatEnded || e instanceof GameEventCardCounters || e instanceof GameEventCardPhased)emit("STATE",state());
        else if(batches.isEmpty() && (e instanceof GameEventPlayerLivesChanged || e instanceof GameEventCardTapped || e instanceof GameEventCardAttachment))emit("STATE",state());
    }
    private void oAssignments(String type){emit(type,Map.of("assignments",assignments(),"state",state()));}
    @Override public synchronized void close() throws IOException {
        if(closed)return;
        try{if(!batches.isEmpty())throw new IOException("Unclosed damage batch");emit("END",state());if(failure!=null)throw failure;}
        finally{closed=true;writer.close();}
    }
}
