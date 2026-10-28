package forge.ai;

/** Benchmark-only optional counters. No rules/AI work depends on this provider. */
public final class BenchmarkOptionalMetrics {
    private BenchmarkOptionalMetrics() { }
    public static long[] read(String provider, boolean enabled) {
        if (!enabled) return null;
        final Class<?> type;
        try { type=Class.forName(provider); }
        catch (ClassNotFoundException absent) { return null; }
        try {
            Object value=type.getMethod("snapshot").invoke(null);
            if (!(value instanceof long[] counters) || counters.length!=2)
                throw new IllegalStateException("Invalid optional counter schema: "+provider);
            return counters;
        } catch (ReflectiveOperationException broken) {
            throw new IllegalStateException("Present optional counter provider failed: "+provider,broken);
        }
    }
}
