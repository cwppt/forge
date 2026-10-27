package forge.view;

import com.google.common.eventbus.Subscribe;
import com.google.gson.Gson;
import forge.game.Game;
import forge.game.event.*;
import forge.game.zone.ZoneType;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Opt-in compact actual-game turn telemetry. No additional decisions or full-state snapshots. */
public final class SimulationTurnMetrics {
    private final Game game;
    private final BufferedWriter writer;
    private final Map<Integer,Integer> triggersOnStack=new HashMap<>();
    private long start=System.nanoTime(), gcStart=gc(), resolved, triggers, maximumStack;
    private int turn=-1;
    private boolean closed;
    private static final Gson JSON=new Gson();
    private SimulationTurnMetrics(Game game, Path path) throws IOException {
        this.game=game;
        writer=Files.newBufferedWriter(path,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        game.subscribeToEvents(this);
    }
    public static SimulationTurnMetrics begin(Game game, Path path) {
        try {return new SimulationTurnMetrics(game,path);}
        catch(IOException e){throw new UncheckedIOException(e);}
    }
    private static long gc(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionTime())).sum();}
    @Subscribe public synchronized void turn(GameEventTurnBegan event) {
        if(closed)return;
        if(turn>=0)emit("TURN");
        turn=event.turnNumber();start=System.nanoTime();gcStart=gc();resolved=triggers=maximumStack=0;
    }
    @Subscribe public synchronized void cast(GameEventSpellAbilityCast event) {
        if(closed)return;
        maximumStack=Math.max(maximumStack,game.getStack().size());
        if(event.si().isTrigger())triggersOnStack.merge(event.sa().getId(),1,Integer::sum);
    }
    @Subscribe public synchronized void resolved(GameEventSpellResolved event) {
        if(closed)return;
        resolved++;
        int pending=triggersOnStack.getOrDefault(event.spell().getId(),0);
        if(pending>0){triggers++;if(pending==1)triggersOnStack.remove(event.spell().getId());else triggersOnStack.put(event.spell().getId(),pending-1);}
    }
    private void emit(String type) {
        var row=new LinkedHashMap<String,Object>();row.put("schemaVersion",1);row.put("type",type);row.put("turn",turn);
        row.put("elapsedMs",(System.nanoTime()-start)/1e6);row.put("gcMs",gc()-gcStart);
        row.put("heapUsed",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        long creatures=0,tokens=0,artifacts=0,treasure=0,permanents=0;
        for(var card:game.getCardsIn(ZoneType.Battlefield)) {
            permanents++;if(card.isCreature())creatures++;if(card.isToken())tokens++;
            if(card.isArtifact())artifacts++;if(card.getType().hasSubtype("Treasure"))treasure++;
        }
        row.put("permanents",permanents);row.put("creatures",creatures);row.put("tokens",tokens);row.put("artifacts",artifacts);row.put("treasure",treasure);
        row.put("resolvedAbilities",resolved);row.put("resolvedTriggers",triggers);row.put("maximumObservedStackDepth",maximumStack);
        try{writer.write(JSON.toJson(row));writer.newLine();writer.flush();}
        catch(IOException e){throw new UncheckedIOException(e);}
    }
    public synchronized void finish() {
        if(closed)return;
        emit("END");closed=true;
        try{writer.close();}catch(IOException e){throw new UncheckedIOException(e);}
    }
}
