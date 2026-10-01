package forge.player;

import forge.ai.decision.AiDecisionProvider;
import forge.ai.decision.external.OpenAiCompatibleDecisionProvider;
import forge.ai.decision.external.OpenAiCompatibleProviderConfig;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import org.tinylog.Logger;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

public final class AiDecisionProviderFactory {
    private AiDecisionProviderFactory() {
    }

    public static Optional<AiDecisionProvider> create(ForgePreferences preferences) {
        return create(
                preferences.getPrefBoolean(FPref.AI_EXTERNAL_MULLIGAN_ENABLED)
                        || preferences.getPrefBoolean(FPref.AI_EXTERNAL_MAIN_PHASE_ENABLED),
                preferences.getPref(FPref.AI_EXTERNAL_MULLIGAN_ENDPOINT),
                preferences.getPref(FPref.AI_EXTERNAL_MULLIGAN_MODEL),
                preferences.getPref(FPref.AI_EXTERNAL_MULLIGAN_API_KEY),
                preferences.getPref(FPref.AI_EXTERNAL_MULLIGAN_TIMEOUT_SECONDS));
    }

    static Optional<AiDecisionProvider> create(
            boolean enabled, String endpoint, String model, String apiKey, String timeoutSeconds) {
        if (!enabled) {
            Logger.debug("External AI provider disabled");
            return Optional.empty();
        }

        try {
            int timeout = Integer.parseInt(timeoutSeconds);
            OpenAiCompatibleProviderConfig config = new OpenAiCompatibleProviderConfig(
                    true, URI.create(endpoint), model, apiKey, Duration.ofSeconds(timeout));
            Logger.info("External AI provider enabled: endpoint={} model={} timeout={}s",
                    safeEndpoint(config.endpoint()), config.model(), timeout);
            return Optional.of(new OpenAiCompatibleDecisionProvider(config));
        } catch (RuntimeException e) {
            Logger.warn("External AI provider configuration is invalid ({}); provider disabled",
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String safeEndpoint(URI endpoint) {
        String authority = endpoint.getHost();
        if (authority == null) {
            return endpoint.getScheme() + ":<invalid>";
        }
        if (endpoint.getPort() >= 0) {
            authority += ":" + endpoint.getPort();
        }
        return endpoint.getScheme() + "://" + authority + endpoint.getPath();
    }
}
