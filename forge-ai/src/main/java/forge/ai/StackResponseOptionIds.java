package forge.ai;

import forge.ai.decision.LegalActionView;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Opaque labels from canonical decision/action material and a private original action identity. */
final class StackResponseOptionIds {
    private StackResponseOptionIds() { }

    static List<String> generate(String decisionMaterial, List<LegalActionView> actions) {
        return generate(decisionMaterial, actions, 24);
    }

    // Short prefixes let focused tests exercise actual truncation collisions.
    static List<String> generate(String decisionMaterial, List<LegalActionView> actions, int prefixLength) {
        if (prefixLength < 1 || prefixLength > 64) throw new IllegalArgumentException("Invalid digest length");
        List<String> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (LegalActionView action : actions) {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream data = new DataOutputStream(bytes)) {
                    write(data, "STACK_OPTION_V1");
                    write(data, decisionMaterial);
                    // Private identity distinguishes otherwise identical prepared actions. It is
                    // hashed, never emitted as an ordinal or copied into the provider payload.
                    write(data, action.actionId());
                    write(data, action.category());
                    write(data, action.sourceName());
                    write(data, action.sourceType());
                    write(data, action.apiType());
                    write(data, action.description());
                    write(data, action.cost());
                    writeList(data, action.targets());
                    writeList(data, action.modes());
                    data.writeBoolean(action.xValue() != null);
                    if (action.xValue() != null) data.writeInt(action.xValue());
                }
                byte[] material = bytes.toByteArray();
                String id = "OPT_" + hash(material).substring(0, prefixLength);
                int retry = 0;
                while (!used.add(id)) {
                    // Salt and retry at production width; no numeric suffix or positional label.
                    ByteArrayOutputStream retryBytes = new ByteArrayOutputStream();
                    try (DataOutputStream data = new DataOutputStream(retryBytes)) {
                        data.write(material);
                        data.writeInt(++retry);
                    }
                    id = "OPT_" + hash(retryBytes.toByteArray()).substring(0, Math.max(24, prefixLength));
                }
                result.add(id);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to encode stack option", e);
            }
        }
        return List.copyOf(result);
    }

    private static String hash(byte[] material) {
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(material));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to hash stack option", e);
        }
    }

    private static void write(DataOutputStream data, String value) throws IOException {
        data.writeBoolean(value != null);
        if (value != null) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            data.writeInt(bytes.length);
            data.write(bytes);
        }
    }

    private static void writeList(DataOutputStream data, List<String> values) throws IOException {
        data.writeInt(values.size());
        for (String value : values) write(data, value);
    }
}
