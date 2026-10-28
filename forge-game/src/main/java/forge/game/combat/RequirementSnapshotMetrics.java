package forge.game.combat;
import java.util.concurrent.atomic.LongAdder;

/** No state cache: observes a local proof that the requirement helper cannot read its list. */
public final class RequirementSnapshotMetrics {
    public static final boolean FAST=Boolean.getBoolean("forge.blocker.lazyRequirementSnapshots");
    private static final boolean ENABLED=Boolean.getBoolean("forge.blocker.snapshotMetrics");
    private static final LongAdder queries=new LongAdder(),avoided=new LongAdder();
    private RequirementSnapshotMetrics() { }
    public static void query(){if(ENABLED)queries.increment();}
    public static void avoid(){if(ENABLED)avoided.increment();}
    public static long[] snapshot(){return new long[]{queries.sum(),avoided.sum()};}
}
