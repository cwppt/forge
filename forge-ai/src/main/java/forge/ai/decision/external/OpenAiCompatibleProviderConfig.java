package forge.ai.decision.external;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

public record OpenAiCompatibleProviderConfig(
        boolean enabled,
        URI endpoint,
        String model,
        String apiKey,
        Duration timeout,
        OpenAiResponseFormatMode responseFormatMode) {

    public OpenAiCompatibleProviderConfig(boolean enabled, URI endpoint, String model, String apiKey,
            Duration timeout) {
        this(enabled, endpoint, model, apiKey, timeout, OpenAiResponseFormatMode.AUTO);
    }

    public OpenAiCompatibleProviderConfig {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(responseFormatMode, "responseFormatMode");
        if (!"http".equalsIgnoreCase(endpoint.getScheme()) && !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("endpoint must use HTTP or HTTPS");
        }
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
    }

    @Override
    public String toString() {
        return "OpenAiCompatibleProviderConfig[enabled=" + enabled
                + ", endpoint=" + safeEndpoint(endpoint)
                + ", model=" + model
                + ", apiKey=" + (apiKey == null ? "<not configured>" : "<redacted>")
                + ", timeout=" + timeout
                + ", responseFormatMode=" + responseFormatMode + "]";
    }

    private static String safeEndpoint(URI value) {
        String authority = value.getHost();
        if (authority == null) {
            return value.getScheme() + ":<invalid>";
        }
        if (value.getPort() >= 0) {
            authority += ":" + value.getPort();
        }
        return value.getScheme() + "://" + authority + value.getPath();
    }
}
