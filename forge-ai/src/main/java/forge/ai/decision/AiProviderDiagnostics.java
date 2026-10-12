package forge.ai.decision;

/** Internal size/timing measurements only; never contains prompts, responses or credentials. */
public record AiProviderDiagnostics(int systemPromptChars, int userPromptChars, int schemaChars,
        int serializedRequestChars, int optionCount, int projectedStateChars, int responseChars,
        long requestSerializationNanos, long httpLatencyNanos, long responseParseNanos,
        long identityValidationNanos, long totalProviderLatencyNanos, int reasoningResponseChars,
        int maxOutputTokens, boolean thinkingDisableRequested) {
    public long httpLatencyMs() { return httpLatencyNanos / 1_000_000; }
    public long parseValidationLatencyMs() { return (responseParseNanos + identityValidationNanos) / 1_000_000; }
    public long totalProviderLatencyMs() { return totalProviderLatencyNanos / 1_000_000; }
}
