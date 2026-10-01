package forge.ai.decision;

import forge.game.card.Card;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public final class MulliganStateProjector {
    private static final Comparator<String> NULL_SAFE_STRING = Comparator.nullsFirst(String::compareTo);
    private static final Comparator<MulliganCardView> CARD_ORDER = Comparator
            .comparing(MulliganCardView::name, NULL_SAFE_STRING)
            .thenComparing(MulliganCardView::manaCost, NULL_SAFE_STRING)
            .thenComparing(MulliganCardView::type, NULL_SAFE_STRING)
            .thenComparing(MulliganCardView::oracleText, NULL_SAFE_STRING);

    private MulliganStateProjector() {
    }

    public static MulliganAiState project(Player decidingPlayer, Player startingPlayer, int cardsToReturn) {
        PhaseHandler phaseHandler = decidingPlayer.getGame().getPhaseHandler();
        Integer turnNumber = phaseHandler.getTurn() > 0 ? phaseHandler.getTurn() : null;
        AiPlayerView self = playerView(decidingPlayer);
        AiPlayerView activeOrStartingPlayer = startingPlayer == null ? null : playerView(startingPlayer);
        Integer selfLife = decidingPlayer.getLife() > 0 ? decidingPlayer.getLife() : null;
        List<MulliganCardView> openingHand = decidingPlayer.getCardsIn(ZoneType.Hand).stream()
                .map(MulliganStateProjector::cardView)
                .sorted(CARD_ORDER)
                .collect(Collectors.toList());
        int startingHandSize = decidingPlayer.getStartingHandSize();
        boolean isStartingPlayer = decidingPlayer.equals(startingPlayer);
        String fingerprint = fingerprint(
                turnNumber,
                activeOrStartingPlayer,
                self,
                selfLife,
                openingHand,
                startingHandSize,
                cardsToReturn,
                isStartingPlayer);

        return new MulliganAiState(
                fingerprint,
                turnNumber,
                activeOrStartingPlayer,
                self,
                selfLife,
                openingHand,
                startingHandSize,
                cardsToReturn,
                isStartingPlayer);
    }

    private static AiPlayerView playerView(Player player) {
        return new AiPlayerView(player.getId(), player.getName());
    }

    private static MulliganCardView cardView(Card card) {
        return new MulliganCardView(
                card.getName(),
                card.getManaCost().getSimpleString(),
                card.getType().toString(),
                card.getOracleText());
    }

    private static String fingerprint(
            Integer turnNumber,
            AiPlayerView activeOrStartingPlayer,
            AiPlayerView self,
            Integer selfLife,
            List<MulliganCardView> openingHand,
            int startingHandSize,
            int cardsToReturn,
            boolean startingPlayer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(bytes)) {
                writeString(data, "MULLIGAN_STATE_V1");
                writeNullableInteger(data, turnNumber);
                writePlayer(data, activeOrStartingPlayer);
                writePlayer(data, self);
                writeNullableInteger(data, selfLife);
                data.writeInt(openingHand.size());
                for (MulliganCardView card : openingHand) {
                    writeString(data, card.name());
                    writeString(data, card.manaCost());
                    writeString(data, card.type());
                    writeString(data, card.oracleText());
                }
                data.writeInt(startingHandSize);
                data.writeInt(cardsToReturn);
                data.writeBoolean(startingPlayer);
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            return toHex(digest);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to fingerprint mulligan state", e);
        }
    }

    private static void writePlayer(DataOutputStream data, AiPlayerView player) throws IOException {
        data.writeBoolean(player != null);
        if (player != null) {
            data.writeInt(player.id());
            writeString(data, player.name());
        }
    }

    private static void writeNullableInteger(DataOutputStream data, Integer value) throws IOException {
        data.writeBoolean(value != null);
        if (value != null) {
            data.writeInt(value);
        }
    }

    private static void writeString(DataOutputStream data, String value) throws IOException {
        data.writeBoolean(value != null);
        if (value != null) {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            data.writeInt(encoded.length);
            data.write(encoded);
        }
    }

    private static String toHex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte b : value) {
            result.append(Character.forDigit((b >>> 4) & 0xf, 16));
            result.append(Character.forDigit(b & 0xf, 16));
        }
        return result.toString();
    }
}
