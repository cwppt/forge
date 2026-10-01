package forge.ai;

import forge.ai.decision.AiPlayerView;
import forge.ai.decision.LegalActionView;
import forge.ai.decision.MainPhaseAiState;
import forge.ai.decision.MainPhaseCardView;
import forge.ai.decision.MainPhaseDecisionContext;
import forge.ai.decision.MainPhasePlayerState;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Builds the Stage 7 whitelist projection from the deciding player's perspective. */
final class MainPhaseStateProjector {
    private static final String HIDDEN_CARD = "Face-down card";
    private static final Comparator<MainPhaseCardView> CARD_ORDER = Comparator
            .comparing(MainPhaseCardView::name, Comparator.nullsFirst(String::compareTo))
            .thenComparing(MainPhaseCardView::type, Comparator.nullsFirst(String::compareTo))
            .thenComparing(MainPhaseCardView::manaCost, Comparator.nullsFirst(String::compareTo))
            .thenComparing(MainPhaseCardView::oracleText, Comparator.nullsFirst(String::compareTo))
            .thenComparing(MainPhaseCardView::controller, Comparator.nullsFirst(String::compareTo))
            .thenComparing(MainPhaseCardView::tapped);

    private MainPhaseStateProjector() {
    }

    static MainPhaseDecisionContext project(Player decidingPlayer, String decisionId,
            List<PreparedAiAction> preparedActions) {
        List<LegalActionView> actions = new ArrayList<>();
        for (PreparedAiAction prepared : preparedActions) {
            actions.add(actionView(decidingPlayer, prepared));
        }
        List<MainPhasePlayerState> players = new ArrayList<>();
        for (Player current : decidingPlayer.getGame().getPlayers()) {
            boolean self = current.equals(decidingPlayer);
            players.add(new MainPhasePlayerState(
                    playerView(current), current.getLife(), self,
                    self ? current.getManaPool().toString() : null,
                    self ? cards(decidingPlayer, current, ZoneType.Hand) : List.of(),
                    cards(decidingPlayer, current, ZoneType.Battlefield),
                    cards(decidingPlayer, current, ZoneType.Graveyard),
                    cards(decidingPlayer, current, ZoneType.Exile),
                    cards(decidingPlayer, current, ZoneType.Command)));
        }
        Player active = decidingPlayer.getGame().getPhaseHandler().getPlayerTurn();
        MainPhaseAiState withoutFingerprint = new MainPhaseAiState(
                "", decidingPlayer.getGame().getPhaseHandler().getTurn(),
                decidingPlayer.getGame().getPhaseHandler().getPhase().name(),
                active == null ? null : playerView(active), playerView(decidingPlayer), players,
                decidingPlayer.getGame().getStack().size());
        String fingerprint = fingerprint(withoutFingerprint, actions);
        MainPhaseAiState state = new MainPhaseAiState(fingerprint, withoutFingerprint.turnNumber(),
                withoutFingerprint.phase(), withoutFingerprint.activePlayer(), withoutFingerprint.self(),
                withoutFingerprint.players(), withoutFingerprint.stackSize());
        return new MainPhaseDecisionContext(decisionId, state, actions);
    }

    private static List<MainPhaseCardView> cards(Player perspective, Player owner, ZoneType zone) {
        return owner.getCardsIn(zone).stream().map(card -> cardView(perspective, card))
                .sorted(CARD_ORDER).toList();
    }

    private static MainPhaseCardView cardView(Player perspective, Card card) {
        if ((card.isFaceDown() && !card.getOwner().equals(perspective))
                || !card.getView().canBeShownTo(perspective.getView())) {
            return new MainPhaseCardView(HIDDEN_CARD, null, null, null, card.isTapped(),
                    card.getController().getName());
        }
        return new MainPhaseCardView(card.getName(), card.getManaCost().getSimpleString(),
                card.getType().toString(), card.getOracleText(), card.isTapped(),
                card.getController().getName());
    }

    private static LegalActionView actionView(Player perspective, PreparedAiAction prepared) {
        SpellAbility sa = prepared.spellAbility();
        Card source = prepared.sourceCard();
        boolean sourceVisible = source != null && source.getView().canBeShownTo(perspective.getView());
        List<String> targets = new ArrayList<>();
        for (SpellAbility current = sa; current != null; current = current.getSubAbility()) {
            for (GameObject target : current.getTargets()) {
                targets.add(targetSummary(perspective, target));
            }
        }
        List<String> modes = new ArrayList<>();
        if (sa.getChosenList() != null) {
            for (AbilitySub mode : sa.getChosenList()) {
                modes.add(mode.getParamOrDefault("SpellDescription", mode.toString()));
            }
        }
        return new LegalActionView(prepared.actionId(), prepared.category().name(),
                sourceVisible ? source.getName() : HIDDEN_CARD,
                sourceVisible ? source.getType().toString() : null,
                sa.getApi() == null ? null : sa.getApi().name(),
                sourceVisible ? prepared.description() : "Use a prepared hidden action",
                sa.getPayCosts() == null ? null : sa.getPayCosts().toString(), targets, modes,
                sa.getXManaCostPaid(), prepared.originalCandidateIndex());
    }

    private static String targetSummary(Player perspective, GameObject target) {
        if (target instanceof Player targetPlayer) {
            return "Player: " + targetPlayer.getName();
        }
        if (target instanceof Card targetCard) {
            return (!(targetCard.isFaceDown() && !targetCard.getOwner().equals(perspective))
                    && targetCard.getView().canBeShownTo(perspective.getView()))
                    ? "Card: " + targetCard.getName() : "Card: " + HIDDEN_CARD;
        }
        return "Prepared game target";
    }

    private static AiPlayerView playerView(Player player) {
        return new AiPlayerView(player.getGame().getPlayers().indexOf(player), player.getName());
    }

    private static String fingerprint(MainPhaseAiState state, List<LegalActionView> actions) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(bytes)) {
                write(data, "MAIN_PHASE_ACTION_V1");
                data.writeInt(state.turnNumber());
                write(data, state.phase());
                writePlayer(data, state.activePlayer());
                writePlayer(data, state.self());
                data.writeInt(state.stackSize());
                data.writeInt(state.players().size());
                for (MainPhasePlayerState player : state.players()) {
                    writePlayer(data, player.identity());
                    data.writeInt(player.life());
                    data.writeBoolean(player.self());
                    write(data, player.manaPool());
                    writeCards(data, player.hand());
                    writeCards(data, player.battlefield());
                    writeCards(data, player.graveyard());
                    writeCards(data, player.exile());
                    writeCards(data, player.command());
                }
                data.writeInt(actions.size());
                for (LegalActionView action : actions) {
                    write(data, action.actionId());
                    write(data, action.category());
                    write(data, action.sourceName());
                    write(data, action.sourceType());
                    write(data, action.apiType());
                    write(data, action.description());
                    write(data, action.cost());
                    writeStrings(data, action.targets());
                    writeStrings(data, action.modes());
                    data.writeBoolean(action.xValue() != null);
                    if (action.xValue() != null) {
                        data.writeInt(action.xValue());
                    }
                    data.writeInt(action.heuristicPosition());
                }
            }
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to fingerprint main-phase state", e);
        }
    }

    private static void writeCards(DataOutputStream data, List<MainPhaseCardView> cards) throws IOException {
        data.writeInt(cards.size());
        for (MainPhaseCardView card : cards) {
            write(data, card.name());
            write(data, card.manaCost());
            write(data, card.type());
            write(data, card.oracleText());
            data.writeBoolean(card.tapped());
            write(data, card.controller());
        }
    }

    private static void writeStrings(DataOutputStream data, List<String> values) throws IOException {
        data.writeInt(values.size());
        for (String value : values) {
            write(data, value);
        }
    }

    private static void writePlayer(DataOutputStream data, AiPlayerView player) throws IOException {
        data.writeBoolean(player != null);
        if (player != null) {
            data.writeInt(player.id());
            write(data, player.name());
        }
    }

    private static void write(DataOutputStream data, String value) throws IOException {
        data.writeBoolean(value != null);
        if (value != null) {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            data.writeInt(encoded.length);
            data.write(encoded);
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte b : value) {
            result.append(Character.forDigit((b >>> 4) & 0xf, 16));
            result.append(Character.forDigit(b & 0xf, 16));
        }
        return result.toString();
    }
}
