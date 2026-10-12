package forge.ai;

import com.google.common.eventbus.Subscribe;
import com.google.gson.Gson;
import forge.ai.decision.*;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.event.*;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Per-observer, per-game facts from authoritative observations. Never touches AiCardMemory. */
public final class ExternalAiStrategicMemory {
    public static final int HARD_CAP=32;
    private static final Map<Game,Map<Integer,ExternalAiStrategicMemory>> GAMES=new WeakHashMap<>();
    private static final Gson JSON=new com.google.gson.GsonBuilder().serializeNulls().create();
    private static final Set<ZoneType> PUBLIC=Set.of(ZoneType.Battlefield,ZoneType.Graveyard,ZoneType.Exile,ZoneType.Stack,ZoneType.Command);
    private static final Set<AiDecisionType> SURFACES=Set.of(AiDecisionType.MAIN_PHASE_ACTION,AiDecisionType.STACK_RESPONSE,
            AiDecisionType.COMBAT_ATTACKERS,AiDecisionType.COMBAT_BLOCKERS,AiDecisionType.COMBAT_ACTION);
    private final WeakReference<Player> observer;
    private final int limit;
    private final Map<String,Entry> facts=new TreeMap<>();
    // Scalar identity bookkeeping is internal only, bounded with facts, never model-visible.
    private final Map<Integer,String> known=new HashMap<>();
    private int turn, maxActive;
    private long recorded, expired, invalidated, evicted, requestsWithMemory;
    private boolean closed;
    private record Entry(StrategicMemoryView.Fact fact, boolean turnOnly) { }

    private ExternalAiStrategicMemory(Player p,int max) { observer=new WeakReference<>(p);limit=Math.min(HARD_CAP,Math.max(1,max));turn=p.getGame().getPhaseHandler()==null?0:p.getGame().getPhaseHandler().getTurn(); }
    public static synchronized void configure(Player p,boolean enabled,int max) {
        var map=GAMES.get(p.getGame());
        if(map!=null) { var previous=map.remove(p.getId());if(previous!=null)previous.close(); }
        if(enabled) {
            var memory=new ExternalAiStrategicMemory(p,max);
            GAMES.computeIfAbsent(p.getGame(),g->new HashMap<>()).put(p.getId(),memory);
            p.getGame().subscribeToEvents(memory);
        }
    }
    public static synchronized ExternalAiStrategicMemory get(Player p) {
        var map=GAMES.get(p.getGame());return map==null?null:map.get(p.getId());
    }
    public static synchronized void reset() { GAMES.values().forEach(m->m.values().forEach(ExternalAiStrategicMemory::close));GAMES.clear(); }
    public static synchronized void release(int gameId) { GAMES.entrySet().removeIf(e->{if(e.getKey().getId()!=gameId)return false;e.getValue().values().forEach(ExternalAiStrategicMemory::close);return true;}); }
    private Player player() { return observer.get(); }
    private boolean active() { return !closed && player()!=null && player().getGame().getRegisteredPlayers().size()==2; }
    private String source(int playerId) { return playerId==player().getId()?"SELF":"OPPONENT"; }
    private static String bounded(String s) { return s==null?null:s.substring(0,Math.min(120,s.length())); }
    private static String interaction(Card c) {
        Set<String> apis=new HashSet<>();
        for(var a:c.getSpellAbilities())for(var sub=a;sub!=null;sub=sub.getSubAbility())if(sub.getApi()!=null)apis.add(sub.getApi().name());
        if(apis.contains("Counter"))return "COUNTER";
        if(apis.contains("DestroyAll"))return "MASS_REMOVAL";
        if(apis.contains("Destroy"))return "REMOVAL";
        if(apis.contains("Protection")||apis.contains("ProtectionAll"))return "PROTECTION";
        if(apis.contains("Pump")||apis.contains("PumpAll"))return "PUMP_OR_DEBUFF";
        if(apis.contains("DealDamage")||apis.contains("DamageAll"))return "DAMAGE";
        if(apis.contains("ChangeZone"))return "ZONE_INTERACTION";
        return c.isInstant()?"INSTANT_SPEED":"OTHER";
    }
    private void put(String key,StrategicMemoryView.Fact fact,boolean turnOnly) {
        if(!active())return;
        if(!facts.containsKey(key))recorded++;
        facts.put(key,new Entry(fact,turnOnly));
        if(facts.size()>limit) {
            var worst=facts.entrySet().stream().max(Comparator
                    .comparing((Map.Entry<String,Entry> e)->e.getValue().fact().turn()==turn?0:1)
                    .thenComparing(e->-e.getValue().fact().turn()).thenComparing(e->importance(e.getValue().fact()))
                    .thenComparing(e->JSON.toJson(e.getValue().fact())).thenComparing(Map.Entry::getKey)).orElseThrow();
            facts.remove(worst.getKey());known.values().removeIf(worst.getKey()::equals);evicted++;
        }
        maxActive=Math.max(maxActive,facts.size());
    }
    private static int importance(StrategicMemoryView.Fact f) { return switch(f.category()) {case "INTERACTION_SHOWN"->0;case "RESOURCE_USED"->1;case "KNOWN_CARD"->2;default->3;}; }
    private void known(CardView c,ZoneType zone,boolean reveal) {
        if(!active() || zone==null || c==null || c.isFaceDown() || c.getOwner()==null || c.getOwner().getId()==player().getId())return;
        if(!reveal && (!PUBLIC.contains(zone)||!c.canBeShownTo(player().getView())))return;
        if(zone==ZoneType.Library&&!reveal)return;
        Card engine=player().getGame().findByView(c);
        if(engine==null || engine.isLand())return;
        String name=bounded(c.getCurrentState().getName()), kind=interaction(engine);
        String status=reveal && zone==ZoneType.Hand?"CURRENTLY_KNOWN":PUBLIC.contains(zone)?"PUBLIC_ZONE_CONFIRMED":"PREVIOUSLY_REVEALED_LOCATION_UNKNOWN";
        String location=status.equals("PREVIOUSLY_REVEALED_LOCATION_UNKNOWN")?null:zone.name();
        String key="KNOWN_CARD|"+c.getId();
        known.put(c.getId(),key);
        put(key,new StrategicMemoryView.Fact("KNOWN_CARD","OPPONENT",turn,name,kind,status,location,
                status.equals("PREVIOUSLY_REVEALED_LOCATION_UNKNOWN")?"Previously observed card; current location and availability unknown":"Observed card; current state overrides remembered location"),false);
    }
    /** Called only by this observer's legal reveal/look callback, never by another controller. */
    public static void revealed(Player observer,Iterable<CardView> cards,ZoneType zone) {
        var memory=get(observer);if(memory!=null)for(var c:cards)memory.known(c,zone,true);
    }
    private void uncertain(String key) {
        Entry e=facts.get(key);if(e==null || e.fact().knowledgeStatus().equals("PREVIOUSLY_REVEALED_LOCATION_UNKNOWN"))return;
        var f=e.fact();invalidated++;
        facts.put(key,new Entry(new StrategicMemoryView.Fact(f.category(),f.source(),f.turn(),f.cardName(),f.interaction(),
                "PREVIOUSLY_REVEALED_LOCATION_UNKNOWN",null,"Previously observed card; current location and availability unknown"),false));
    }
    @Subscribe public void zone(GameEventCardChangeZone e) {
        if(!active())return;
        ZoneType destination=e.to()==null?null:e.to().zoneType();
        if(e.from()!=null && e.from().zoneType()==ZoneType.Hand && e.from().player()!=null
                && e.from().player().getId()!=player().getId()) {
            // Identical copies are indistinguishable to the observer. Never use engine IDs
            // to infer that a different, still-hidden copy remains available in hand.
            facts.entrySet().stream().filter(x->"Hand".equals(x.getValue().fact().location()))
                    .map(Map.Entry::getKey).toList().forEach(this::uncertain);
        }
        if(destination==null || !PUBLIC.contains(destination) || e.card().isFaceDown() || !e.card().canBeShownTo(player().getView())) {
            // An unobservable move cannot tell us which known hand card moved.
            for(var entry:new ArrayList<>(facts.entrySet()))if(entry.getValue().fact().category().equals("KNOWN_CARD")
                    && entry.getKey().equals(known.get(e.card().getId())))uncertain(entry.getKey());
            return;
        }
        if(known.containsKey(e.card().getId()) || destination==ZoneType.Graveyard || destination==ZoneType.Exile)known(e.card(),destination,false);
    }
    @Subscribe public void cast(GameEventSpellAbilityCast e) {
        if(!active() || e.si()==null || e.si().isTrigger() || e.si().getActivatingPlayer()==null)return;
        CardView view=e.si().getSourceCard();
        if(view==null || view.getZone()==null || view.isFaceDown() || !view.canBeShownTo(player().getView()) || !PUBLIC.contains(view.getZone()))return;
        var engine=player().getGame().findByView(view);if(engine==null)return;
        var instance=java.util.stream.StreamSupport.stream(player().getGame().getStack().spliterator(),false)
                .filter(i->i.getId()==e.si().getId()).findFirst().orElse(null);
        if(instance==null || instance.getSpellAbility().isManaAbility())return;
        String actor=source(e.si().getActivatingPlayer().getId()),name=bounded(view.getCurrentState().getName());
        var ability=instance.getSpellAbility();
        String kind=ability.getApi()==null?"OTHER":switch(ability.getApi().name()) {
            case "Counter"->"COUNTER";case "Destroy"->"REMOVAL";case "DestroyAll"->"MASS_REMOVAL";
            case "Protection","ProtectionAll"->"PROTECTION";case "Pump","PumpAll"->"PUMP_OR_DEBUFF";
            case "DealDamage","DamageAll"->"DAMAGE";case "ChangeZone"->"ZONE_INTERACTION";
            default->engine.isInstant()?"INSTANT_SPEED":"OTHER";
        };
        if(actor.equals("OPPONENT") && !kind.equals("OTHER"))put("INTERACTION|"+name+"|"+kind,
                new StrategicMemoryView.Fact("INTERACTION_SHOWN",actor,turn,name,kind,null,null,"Opponent previously "+(e.sa().isSpell()?"cast ":"activated ")+name+" this game; availability not inferred"),false);
        String category=e.sa().isSpell()?"TURN_ACTION":"RESOURCE_USED";
        put(category+"|"+actor+"|"+name,new StrategicMemoryView.Fact(category,actor,turn,name,kind,null,null,
                actor+" "+(e.sa().isSpell()?"cast ":"activated ")+name+" this turn"
                        +(!e.sa().isSpell() && "1".equals(ability.getParam("ActivationLimit"))?"; once-per-turn activation consumed":"")),true);
    }
    @Subscribe public void attackers(GameEventAttackersDeclared e) {
        if(active())combat(source(e.player().getId()),"declared "+e.attackersMap().size()+" attackers");
    }
    @Subscribe public void blockers(GameEventBlockersDeclared e) {
        if(active())combat(source(e.defendingPlayer().getId()),"declared "+e.blockers().values().stream().mapToInt(m->m.values().size()).sum()+" blocker assignments");
    }
    private void combat(String actor,String summary) { put("COMBAT|"+actor+"|"+summary,new StrategicMemoryView.Fact("COMBAT_EVENT",actor,turn,null,"COMBAT",null,null,actor+" "+summary+" this turn"),true); }
    @Subscribe public void began(GameEventTurnBegan e) { if(active())advance(e.turnNumber()); }
    @Subscribe public void ended(GameEventTurnEnded e) { if(active())expireTurn(); }
    @Subscribe public void outcome(GameEventGameOutcome e) { close(); }
    @Subscribe public void finished(GameEventGameFinished e) { close(); }
    @Subscribe public void restarted(GameEventGameRestarted e) { clear(); }
    @Subscribe public void restored(GameEventSnapshotRestored e) { clear(); }
    private void clear() { expired+=facts.size();facts.clear();known.clear(); }
    private void close() { clear();closed=true; }
    private void expireTurn() { int size=facts.size();facts.values().removeIf(Entry::turnOnly);expired+=size-facts.size(); }
    private void advance(int next) { if(turn!=next) { expireTurn();turn=next; } }
    public StrategicMemorySnapshot snapshot(AiDecisionType surface,Collection<String> optionNames) {
        long start=System.nanoTime();if(!active()||!SURFACES.contains(surface))return null;
        advance(player().getGame().getPhaseHandler().getTurn());
        // Refresh certainty against visible public state without inspecting hidden identities.
        Set<String> visibleKnown=new HashSet<>();
        for(Card c:player().getGame().getCardsIn(List.copyOf(PUBLIC)))if(known.containsKey(c.getId())) {
            String key=known.get(c.getId());
            if(c.isFaceDown() || !c.getView().canBeShownTo(player().getView()))uncertain(key);
            else {
                visibleKnown.add(key);var entry=facts.get(key);
                if(entry!=null && (!c.getZone().getZoneType().name().equals(entry.fact().location())
                        || !c.getView().getCurrentState().getName().equals(entry.fact().cardName())))known(c.getView(),c.getZone().getZoneType(),false);
            }
        }
        facts.entrySet().stream().filter(e->"PUBLIC_ZONE_CONFIRMED".equals(e.getValue().fact().knowledgeStatus()) && !visibleKnown.contains(e.getKey()))
                .map(Map.Entry::getKey).toList().forEach(this::uncertain);
        boolean combat=surface.name().startsWith("COMBAT");
        var ordered=facts.values().stream().map(Entry::fact).filter(f->!combat || surface==AiDecisionType.COMBAT_ACTION || !"COUNTER".equals(f.interaction()))
                .filter(f->!f.category().equals("KNOWN_CARD") || !combat || !f.interaction().equals("OTHER") || direct(f,optionNames))
                .sorted(Comparator.comparing((StrategicMemoryView.Fact f)->f.turn()==turn?0:1)
                        .thenComparing(f->direct(f,optionNames)?0:1).thenComparing(f->-f.turn()).thenComparing(ExternalAiStrategicMemory::importance)
                        .thenComparing(f->JSON.toJson(f))).limit(limit).toList();
        var view=new StrategicMemoryView(ordered.stream().filter(f->f.category().equals("KNOWN_CARD")).toList(),
                ordered.stream().filter(f->f.category().equals("INTERACTION_SHOWN")).toList(),
                ordered.stream().filter(f->f.category().equals("RESOURCE_USED")||f.category().equals("TURN_ACTION")).toList(),
                ordered.stream().filter(f->f.category().equals("COMBAT_EVENT")).toList());
        return new StrategicMemorySnapshot(view,player().getGame().getId(),player().getGame().getPlayers().indexOf(player()),facts.size(),view.size(),
                ordered.stream().map(StrategicMemoryView.Fact::category).distinct().sorted().toList(),expired,invalidated,System.nanoTime()-start,JSON.toJson(view).length());
    }
    private static boolean direct(StrategicMemoryView.Fact f,Collection<String> names) { return f.cardName()!=null && names.stream().anyMatch(n->n!=null&&n.contains(f.cardName())); }
    public static StrategicMemorySnapshot snapshot(Player p,AiDecisionType surface,Collection<String> names) { var m=get(p);return m==null?null:m.snapshot(surface,names); }
    public static String fingerprint(String original,StrategicMemorySnapshot memory) {
        if(memory==null)return original;
        try { return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(
                ("STRATEGIC_MEMORY_V1|"+original+"|"+JSON.toJson(memory.view())).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static synchronized void requested(AiDecisionContext context,boolean called) {
        if(!called||context==null||context.memorySnapshot()==null)return;
        var s=context.memorySnapshot();for(var e:GAMES.entrySet())if(e.getKey().getId()==s.gameId())
            for(var m:e.getValue().values())if(m.player()!=null && e.getKey().getPlayers().indexOf(m.player())==s.observerSeat() && s.selectedFacts()>0)m.requestsWithMemory++;
    }
    public static synchronized Map<String,Long> gameStats(int gameId) {
        Map<String,Long> stats=new TreeMap<>();
        for(var e:GAMES.entrySet())if(e.getKey().getId()==gameId)for(var m:e.getValue().values()) {
            stats.merge("factsRecorded",m.recorded,Long::sum);stats.merge("factsExpired",m.expired,Long::sum);
            stats.merge("factsInvalidated",m.invalidated,Long::sum);stats.merge("factsEvicted",m.evicted,Long::sum);
            stats.merge("maxActiveFacts",(long)m.maxActive,Math::max);stats.merge("requestsContainingMemory",m.requestsWithMemory,Long::sum);
        }
        return Map.copyOf(stats);
    }
}
