package forge.game.cost;

import java.util.concurrent.atomic.LongAdder;

/** Opt-in live-source filtering and bounded aggregate counters; no game-state cache. */
public final class CostSourceMetrics {
    public static final boolean FAST_PATH = Boolean.getBoolean("forge.cost.liveSourceFilter");
    private static final boolean ENABLED = Boolean.getBoolean("forge.cost.sourceMetrics");
    private static final LongAdder calls = new LongAdder(), skipped = new LongAdder();
    private CostSourceMetrics() { }
    public static void call() { if (ENABLED) calls.increment(); }
    public static void skip() { if (ENABLED) skipped.increment(); }
    public static long[] snapshot() { return new long[]{calls.sum(), skipped.sum()}; }
}
