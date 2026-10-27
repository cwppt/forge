package forge.game.card;
import java.util.concurrent.atomic.LongAdder;

/** Startup switches and optional aggregate diagnostics. No game-state cache. */
public final class BlockCostMetrics {
    public static final boolean FAST_PATH=Boolean.getBoolean("forge.combat.blockCostFastPath");
    public static final boolean ENABLED=Boolean.getBoolean("forge.combat.blockCostMetrics");
    private static final LongAdder calls=new LongAdder(),skipped=new LongAdder(),emptyCosts=new LongAdder();
    private BlockCostMetrics() { }
    public static void call(){if(ENABLED)calls.increment();}
    public static void skip(){if(ENABLED)skipped.increment();}
    public static void emptyCost(){if(ENABLED)emptyCosts.increment();}
    public static long[] snapshot(){return new long[]{calls.sum(),skipped.sum(),emptyCosts.sum()};}
}
