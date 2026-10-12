package forge.ai.decision;

public record IntegratedCombatMetrics(long integratedCombatSequences, long combatsWithExternalAttackers,
        long combatsWithExternalBlockers, long combatsWithExternalDamageOrder, long combatsWithAllThreeSurfaces,
        long totalCombatProviderCalls, long combatSequencesWithFallback, long combatSequenceFallbackCount,
        double avgProviderCallsPerCombat, double avgProviderLatencyPerCombat,
        long p50ProviderLatencyPerCombat, long p95ProviderLatencyPerCombat, long combatSequencesEndingLethal) { }
