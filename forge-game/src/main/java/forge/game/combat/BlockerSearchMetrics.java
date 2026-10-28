package forge.game.combat;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/** Optional aggregate observations; never caches decisions or changes legal options. */
public final class BlockerSearchMetrics {
    public static final boolean FAST=Boolean.getBoolean("forge.blocker.hoistCostPresence");
    public static final boolean ENABLED=Boolean.getBoolean("forge.blocker.metrics");
    private static final LongAdder[] counters=new LongAdder[14];
    static {for(int i=0;i<counters.length;i++)counters[i]=new LongAdder();}
    private BlockerSearchMetrics() { }
    public static void count(int index){if(ENABLED)counters[index].increment();}
    public static long start(){return ENABLED?System.nanoTime():0;}
    public static void elapsed(int index,long start){if(ENABLED)counters[index].add(System.nanoTime()-start);}
    public static void query(int attacker,int blocker,boolean accepted,long start){
        if(!ENABLED)return;count(0);count(accepted?1:2);elapsed(10,start);
    }
    public static long[] snapshot(){long[] out=new long[counters.length];for(int i=0;i<out.length;i++)out[i]=counters[i].sum();return out;}
}
