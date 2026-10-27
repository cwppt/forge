package forge.ai.decision;

import com.google.common.eventbus.Subscribe;
import com.google.gson.*;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.event.*;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Opt-in omniscient diagnostics. Reads live state, never enumerates/evaluates extra actions. */
public final class CommanderRoleTelemetry implements AutoCloseable {
    private static final InheritableThreadLocal<CommanderRoleTelemetry> CURRENT = new InheritableThreadLocal<>();
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private final Game game;
    private final int index;
    private final Long seed;
    private final BufferedWriter writer;
    private long sequence;
    private volatile boolean closed;
    private final Map<Integer,Integer> ownTurns = new HashMap<>();
    private final Map<String,JsonObject> candidates = new LinkedHashMap<>();

    private CommanderRoleTelemetry(Game game, int index, Long seed, Path path) throws IOException {
        this.game=game; this.index=index; this.seed=seed;
        writer=Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    public static CommanderRoleTelemetry begin(Game game, int index, Long seed, Path path) throws IOException {
        CommanderRoleTelemetry observer=new CommanderRoleTelemetry(game,index,seed,path);
        CURRENT.set(observer);
        game.subscribeToEvents(observer);
        JsonObject start=new JsonObject(); JsonArray players=new JsonArray();
        for(Player p:game.getRegisteredPlayers()) {
            JsonObject row=new JsonObject(); row.addProperty("playerId",p.getId());
            row.addProperty("playerSeat",game.getRegisteredPlayers().indexOf(p)); row.addProperty("name",p.getName()); players.add(row);
        }
        start.add("players",players); observer.emit("START",start);
        return observer;
    }
    public static void observeCandidate(Player player, SpellAbility ability, String opinion) {
        CommanderRoleTelemetry o=CURRENT.get();
        // Copied evaluation states must not be presented as real game opportunities.
        if(o==null || o.closed || player.getGame()!=o.game) return;
        synchronized(o) {
        if(o.closed) return;
        String key=o.game.getPhaseHandler().getTurn()+":"+o.game.getPhaseHandler().getPhase()+":"+player.getId()+":"+ability.getHostCard().getId()+":"+ability.getApi()+":"+ability.isSpell()+":"+opinion;
        JsonObject previous=o.candidates.get(key);
        if(previous!=null) { previous.addProperty("evaluationCount",previous.get("evaluationCount").getAsInt()+1); return; }
        JsonObject row=new JsonObject(); row.addProperty("playerId",player.getId());
        row.addProperty("playerSeat",o.game.getRegisteredPlayers().indexOf(player));
        JsonObject host=new JsonObject(); host.addProperty("id",ability.getHostCard().getId());
        host.addProperty("name",ability.getHostCard().getName()); host.addProperty("mv",ability.getHostCard().getCMC());
        row.add("card",host); row.addProperty("representativeAbilityId",ability.getId());
        row.addProperty("spell",ability.isSpell()); row.addProperty("api",ability.getApi()==null?null:ability.getApi().name());
        row.addProperty("opinion",opinion); row.addProperty("basis","existing AI evaluations aggregated by turn/phase/card/API/opinion; alternative abilities merged; not a complete legal-action census");
        row.addProperty("evaluationCount",1); row.addProperty("rawTurn",o.game.getPhaseHandler().getTurn());
        row.addProperty("phase",String.valueOf(o.game.getPhaseHandler().getPhase()));
        o.candidates.put(key,row);
        }
    }
    private synchronized void flushCandidates() {
        for(JsonObject row:candidates.values()) emit("CANDIDATE",row);
        candidates.clear();
    }
    private synchronized void emit(String type, JsonObject row) {
        if(closed) return;
        row.addProperty("schemaVersion",1); row.addProperty("gameId",game.getId());
        row.addProperty("gameIndex",index); row.addProperty("runSeed",seed);
        row.addProperty("eventIndex",sequence++); row.addProperty("type",type);
        if(!row.has("rawTurn")) row.addProperty("rawTurn",game.getPhaseHandler().getTurn());
        if(!row.has("phase")) row.addProperty("phase",String.valueOf(game.getPhaseHandler().getPhase()));
        try { writer.write(JSON.toJson(row)); writer.newLine(); }
        catch(IOException e) { throw new UncheckedIOException(e); }
    }
    private static JsonObject card(Card c) {
        JsonObject row=new JsonObject(); row.addProperty("id",c.getId()); row.addProperty("name",c.getName());
        row.addProperty("ownerId",c.getOwner().getId()); row.addProperty("controllerId",c.getController().getId());
        row.addProperty("mv",c.getCMC()); row.addProperty("manaCost",c.getManaCost().toString());
        row.addProperty("types",c.getType().toString()); row.addProperty("tapped",c.isTapped());
        row.addProperty("creature",c.isCreature()); row.addProperty("land",c.isLand());
        row.addProperty("token",c.isToken()); row.addProperty("sick",c.isSick());
        JsonArray mana=new JsonArray();
        for(SpellAbility a:c.getManaAbilities()) {
            JsonObject m=new JsonObject(); m.addProperty("produced",a.getParamOrDefault("Produced",""));
            m.addProperty("amount",a.getParamOrDefault("Amount","1"));
            m.addProperty("cost",a.getPayCosts()==null?null:a.getPayCosts().toString());
            mana.add(m);
        }
        row.add("manaAbilities",mana); return row;
    }
    private static JsonObject cardView(CardView c) {
        JsonObject row=new JsonObject(); row.addProperty("id",c.getId()); row.addProperty("name",c.getName());
        row.addProperty("ownerId",c.getOwner()==null?null:c.getOwner().getId());
        row.addProperty("controllerId",c.getController()==null?null:c.getController().getId());
        row.addProperty("types",c.getCurrentState().getType().toString()); return row;
    }
    private JsonObject resolving() {
        if(!game.getStack().isResolving()) return null;
        // Resolution cleanup can emit events after the stack item has been removed.
        // Preserve those events without inventing a resolving source.
        var top=game.getStack().peek();
        if(top==null) return null;
        SpellAbility a=top.getSpellAbility(); if(a==null) return null;
        JsonObject row=new JsonObject(); row.addProperty("abilityId",a.getId());
        row.addProperty("cardId",a.getHostCard().getId()); row.addProperty("card",a.getHostCard().getName());
        row.addProperty("api",a.getApi()==null?null:a.getApi().name());
        row.addProperty("description",a.getDescription());
        row.addProperty("playerId",a.getActivatingPlayer()==null?null:a.getActivatingPlayer().getId());
        return row;
    }
    @Subscribe public void turn(GameEventTurnBegan e) {
        if(closed) return;
        flushCandidates();
        ownTurns.merge(e.turnOwner().getId(),1,Integer::sum);
        JsonObject row=new JsonObject(); row.addProperty("playerId",e.turnOwner().getId());
        row.addProperty("ownTurn",ownTurns.get(e.turnOwner().getId())); emit("TURN",row);
    }
    @Subscribe public void phase(GameEventTurnPhase e) {
        if(closed) return;
        flushCandidates();
        if(!Set.of("MAIN1","COMBAT_BEGIN","MAIN2","END_OF_TURN","CLEANUP").contains(e.phase().name())) return;
        JsonObject row=new JsonObject(); JsonArray players=new JsonArray();
        for(Player p:game.getRegisteredPlayers()) {
            if(p.getId()!=e.playerTurn().getId()) continue;
            JsonObject r=new JsonObject(); r.addProperty("playerId",p.getId()); r.addProperty("playerSeat",game.getRegisteredPlayers().indexOf(p));
            r.addProperty("name",p.getName()); r.addProperty("ownTurn",ownTurns.getOrDefault(p.getId(),0));
            r.addProperty("active",p.getId()==e.playerTurn().getId()); r.addProperty("floatingMana",p.getManaPool().totalMana());
            for(ZoneType z:List.of(ZoneType.Hand,ZoneType.Battlefield,ZoneType.Graveyard,ZoneType.Exile,ZoneType.Command)) {
                JsonArray list=new JsonArray(); for(Card c:p.getCardsIn(z)) list.add(card(c)); r.add(z.name(),list);
            }
            r.addProperty("librarySize",p.getCardsIn(ZoneType.Library).size()); players.add(r);
        }
        row.add("players",players); row.addProperty("phase",e.phase().name()); emit("SNAPSHOT",row);
    }
    @Subscribe public void zone(GameEventCardChangeZone e) {
        JsonObject row=new JsonObject(); row.add("card",cardView(e.card()));
        row.addProperty("from",e.from()==null?null:e.from().zoneType().name());
        row.addProperty("to",e.to()==null?null:e.to().zoneType().name());
        row.addProperty("fromPlayerId",e.from()==null||e.from().player()==null?null:e.from().player().getId());
        row.addProperty("toPlayerId",e.to()==null||e.to().player()==null?null:e.to().player().getId());
        row.add("resolving",resolving()); emit("ZONE",row);
    }
    @Subscribe public void cast(GameEventSpellAbilityCast e) {
        JsonObject row=new JsonObject(); row.add("card",cardView(e.sa().getHostCard()));
        row.addProperty("abilityId",e.sa().getId()); row.addProperty("stackItemId",e.si().getId());
        row.addProperty("playerId",e.si().getActivatingPlayer().getId()); row.addProperty("spell",e.sa().isSpell());
        row.addProperty("description",e.sa().getDescription()); row.addProperty("targets",e.targetDescription());
        emit("STACK_ADD",row);
    }
    @Subscribe public void resolved(GameEventSpellResolved e) {
        JsonObject row=new JsonObject(); row.add("card",cardView(e.spell().getHostCard()));
        row.addProperty("abilityId",e.spell().getId()); row.addProperty("fizzled",e.hasFizzled());
        row.addProperty("description",e.stackDescription()); emit("RESOLVED",row);
    }
    @Subscribe public void damage(GameEventCardDamaged e) {
        JsonObject row=new JsonObject(); row.add("card",cardView(e.card()));
        row.add("source",e.source()==null?null:cardView(e.source())); row.addProperty("amount",e.amount());
        row.addProperty("damageType",e.type().name()); row.add("resolving",resolving()); emit("CARD_DAMAGE",row);
    }
    @Subscribe public void playerDamage(GameEventPlayerDamaged e) {
        JsonObject row=new JsonObject(); row.addProperty("playerId",e.target().getId());
        row.add("source",e.source()==null?null:cardView(e.source())); row.addProperty("amount",e.amount());
        row.addProperty("combat",e.combat()); row.addProperty("infect",e.infect());
        row.add("resolving",resolving()); emit("PLAYER_DAMAGE",row);
    }
    public synchronized void finish(boolean timedOut, String error) throws IOException {
        if(closed) return;
        flushCandidates();
        JsonObject row=new JsonObject(); row.addProperty("timedOut",timedOut); row.addProperty("simulationError",error);
        emit("END",row); close();
    }
    @Override public synchronized void close() throws IOException {
        if(closed) return; closed=true; writer.close(); if(CURRENT.get()==this) CURRENT.remove();
    }
}
