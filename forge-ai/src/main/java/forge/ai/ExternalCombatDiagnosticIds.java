package forge.ai;

import forge.ai.decision.AiDecisionMetrics;
import forge.ai.decision.AiDecisionType;
import forge.game.Game;
import forge.game.player.Player;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Optional simulation identity policy; counters never carry strategic information between surfaces. */
final class ExternalCombatDiagnosticIds {
    static final String ALL_SURFACES_PROPERTY = "forge.ai.external.combat.seededIds";
    private static final Map<Game, Map<String, Integer>> OCCURRENCES = new WeakHashMap<>();

    private ExternalCombatDiagnosticIds() { }

    static String next(Player player, AiDecisionType type, String fingerprint, String ordinaryId) {
        if (type != AiDecisionType.COMBAT_ATTACKERS && type != AiDecisionType.COMBAT_BLOCKERS
                && type != AiDecisionType.COMBAT_DAMAGE_ORDER) return ordinaryId;
        if (!Boolean.getBoolean(ALL_SURFACES_PROPERTY)
                && !(type == AiDecisionType.COMBAT_BLOCKERS && Boolean.getBoolean(ExternalCombatBlockers.DIAGNOSTIC_IDS_PROPERTY))) return ordinaryId;
        Game game = player.getGame();
        var metadata = AiDecisionMetrics.gameMetadata(game.getId());
        if (metadata == null || metadata.runSeed() == null) return ordinaryId;
        String material = metadata.runSeed() + ":" + metadata.gameIndex() + ":"
                + game.getRegisteredPlayers().indexOf(player) + ":" + fingerprint;
        String counterKey = type + ":" + material;
        int occurrence;
        synchronized (OCCURRENCES) {
            occurrence = OCCURRENCES.computeIfAbsent(game, g -> new HashMap<>()).merge(counterKey, 1, Integer::sum);
        }
        // Keep the established blocker-only diagnostic mapping compatible.
        String version = type == AiDecisionType.COMBAT_BLOCKERS ? "BLOCKER_DIAGNOSTIC_V1" : "COMBAT_DIAGNOSTIC_V1:" + type;
        return UUID.nameUUIDFromBytes((version + ":" + material + ":" + occurrence).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
