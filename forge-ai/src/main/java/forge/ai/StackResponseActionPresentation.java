package forge.ai;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Request-local presentation permutation. Engine actions retain their original identity. */
final class StackResponseActionPresentation {
    private final List<PreparedAiAction> originalActions;
    private final List<Integer> originalPositions;
    private final List<String> optionIds;

    private StackResponseActionPresentation(List<PreparedAiAction> actions, List<Integer> positions) {
        this(actions, positions, List.of());
    }

    private StackResponseActionPresentation(List<PreparedAiAction> actions, List<Integer> positions, List<String> ids) {
        originalActions = List.copyOf(actions);
        originalPositions = List.copyOf(positions);
        optionIds = List.copyOf(ids);
    }

    static StackResponseActionPresentation create(List<PreparedAiAction> actions, boolean randomized,
            long runSeed, int gameIndex, int playerSeat, long decisionSequence, String stateFingerprint) {
        // Logical game index and per-player decision sequence survive replay; runtime game IDs
        // and request UUIDs do not. The fingerprint covers the original visible state/action set.
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < actions.size(); i++) positions.add(i);
        if (randomized) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update("STACK_RESPONSE_ORDER_V1".getBytes(StandardCharsets.UTF_8));
                digest.update(ByteBuffer.allocate(24).putLong(runSeed).putInt(gameIndex)
                        .putInt(playerSeat).putLong(decisionSequence).array());
                digest.update(stateFingerprint.getBytes(StandardCharsets.UTF_8));
                // Fisher-Yates through a request-local RNG, independent of Forge's live RNG.
                Collections.shuffle(positions, new Random(ByteBuffer.wrap(digest.digest()).getLong()));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("Unable to seed stack-response presentation", e);
            }
        }
        return new StackResponseActionPresentation(actions, positions);
    }

    List<Integer> originalPositions() { return originalPositions; }

    StackResponseActionPresentation withOptionIds(List<String> ids) {
        if (ids.size() != originalActions.size() || new java.util.HashSet<>(ids).size() != ids.size()
                || ids.contains("PASS")) throw new IllegalArgumentException("Invalid option mapping");
        return new StackResponseActionPresentation(originalActions, originalPositions, ids);
    }

    PreparedAiAction resolve(String presentedId) {
        for (int i = 0; i < originalPositions.size(); i++) {
            if (optionIds.get(i).equals(presentedId)) return originalActions.get(originalPositions.get(i));
        }
        return null;
    }

    String presentedId(String originalId) {
        for (int i = 0; i < originalPositions.size(); i++) {
            if (originalActions.get(originalPositions.get(i)).actionId().equals(originalId)) return optionIds.get(i);
        }
        return originalId; // PASS and FORGE_LEGACY_RESPONSE remain literal internal IDs.
    }
}
