package forge.ai.decision.external;

public enum OpenAiResponseFormatMode {
    /** Prefer strict JSON Schema. Use JSON_OBJECT explicitly for endpoints that do not support it. */
    AUTO,
    JSON_SCHEMA,
    JSON_OBJECT
}
