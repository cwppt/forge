package forge.ai;

import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.ai.decision.AiPlayerView;
import forge.ai.decision.LegalActionView;
import forge.ai.decision.MainPhaseAiState;
import forge.ai.decision.MainPhaseCardView;
import forge.ai.decision.MainPhaseCounterView;
import forge.ai.decision.MainPhaseDecisionContext;
import forge.ai.decision.MainPhaseManaView;
import forge.ai.decision.MainPhasePlayerState;
import forge.ai.decision.StackItemView;
import forge.ai.decision.StackResponseDecisionContext;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.mana.ManaPool;
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
            .thenComparing(MainPhaseCardView::tapped)
            .thenComparing(MainPhaseCardView::power, Comparator.nullsFirst(Integer::compareTo))
            .thenComparing(MainPhaseCardView::toughness, Comparator.nullsFirst(Integer::compareTo))
            .thenComparing(MainPhaseCardView::markedDamage, Comparator.nullsFirst(Integer::compareTo))
            .thenComparing(card -> card.counters().toString());

    private MainPhaseStateProjector() {
    }

    static MainPhaseDecisionContext project(Player decidingPlayer, String decisionId,
            List<PreparedAiAction> preparedActions) {
        return project(decidingPlayer, decisionId, preparedActions, true);
    }

    private static MainPhaseDecisionContext project(Player decidingPlayer, String decisionId,
            List<PreparedAiAction> preparedActions, boolean includeRecommendations) {
        return project(decidingPlayer, decisionId, preparedActions, includeRecommendations, null);
    }

    private static MainPhaseDecisionContext project(Player decidingPlayer, String decisionId,
            List<PreparedAiAction> preparedActions, boolean includeRecommendations, List<Integer> presentationOrder) {
        List<LegalActionView> actions = new ArrayList<>();
        for (int i = 0; i < preparedActions.size(); i++) {
            PreparedAiAction prepared = preparedActions.get(presentationOrder == null ? i : presentationOrder.get(i));
            LegalActionView view = actionView(decidingPlayer, prepared, includeRecommendations);
            actions.add(presentationOrder == null ? view : new LegalActionView("ACTION_" + i,
                    view.category(), view.sourceName(), view.sourceType(), view.apiType(), view.description(),
                    view.cost(), view.targets(), view.modes(), view.xValue(), view.heuristicPosition(), null));
        }
        List<MainPhasePlayerState> players = new ArrayList<>();
        for (Player current : decidingPlayer.getGame().getPlayers()) {
            boolean self = current.equals(decidingPlayer);
            players.add(new MainPhasePlayerState(
                    playerView(current), current.getLife(), self,
                    self ? mana(current.getManaPool()) : null,
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
        String fingerprint = fingerprint(withoutFingerprint, actions, includeRecommendations);
        MainPhaseAiState state = new MainPhaseAiState(fingerprint, withoutFingerprint.turnNumber(),
                withoutFingerprint.phase(), withoutFingerprint.activePlayer(), withoutFingerprint.self(),
                withoutFingerprint.players(), withoutFingerprint.stackSize());
        return new MainPhaseDecisionContext(decisionId, state, actions);
    }

    static StackResponseDecisionContext projectStackResponse(Player decidingPlayer, String decisionId,
            List<PreparedAiAction> preparedActions) {
        return projectStackResponse(decidingPlayer, decisionId, preparedActions, null, false);
    }

    static StackResponseDecisionContext projectStackResponse(Player decidingPlayer, String decisionId,
            List<PreparedAiAction> preparedActions, List<Integer> presentationOrder, boolean randomizedOrder) {
        return projectStackResponse(decidingPlayer, decisionId, preparedActions, presentationOrder, randomizedOrder, true);
    }

    static StackResponseDecisionContext projectStackResponseForOrdering(Player player, String decisionId,
            List<PreparedAiAction> actions) {
        // Preserve Stage 8.2 shuffle seed material exactly; ordinal IDs stay engine-internal.
        return projectStackResponse(player, decisionId, actions, null, false, false);
    }

    private static StackResponseDecisionContext projectStackResponse(Player decidingPlayer, String decisionId,
            List<PreparedAiAction> preparedActions, List<Integer> presentationOrder, boolean randomizedOrder,
            boolean opaqueIds) {
        MainPhaseDecisionContext base = project(decidingPlayer, decisionId, preparedActions, false, presentationOrder);
        List<StackItemView> items = new ArrayList<>();
        int position = 0;
        for (forge.game.spellability.SpellAbilityStackInstance instance : decidingPlayer.getGame().getStack()) {
            SpellAbility ability = instance.getSpellAbility();
            Card source = ability == null ? null : ability.getHostCard();
            boolean visible = source != null && source.getView().canBeShownTo(decidingPlayer.getView());
            List<String> targets = new ArrayList<>();
            if (ability != null) {
                for (GameObject target : ability.getTargets()) {
                    targets.add(targetSummary(decidingPlayer, target));
                }
            }
            items.add(new StackItemView(position++, visible ? source.getName() : HIDDEN_CARD,
                    ability == null || ability.getActivatingPlayer() == null ? null : ability.getActivatingPlayer().getName(),
                    ability == null || ability.getApi() == null ? "SPELL_OR_ABILITY" : ability.getApi().name(),
                    visible && ability != null ? ability.toString() : "Hidden stack item", targets));
        }
        String fingerprint;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(bytes)) {
                write(data, "STACK_RESPONSE_V2");
                write(data, base.state().fingerprint());
                data.writeInt(items.size());
                for (StackItemView item : items) {
                    data.writeInt(item.position());
                    write(data, item.sourceName());
                    write(data, item.controller());
                    write(data, item.category());
                    write(data, item.rulesSummary());
                    writeStrings(data, item.targets());
                }
                write(data, StackResponseDecisionContext.PASS_OPTION_ID);
            }
            fingerprint = hex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to fingerprint stack response", e);
        }
        if (opaqueIds) {
            List<LegalActionView> idMaterials = new ArrayList<>();
            for (int i = 0; i < base.legalActions().size(); i++) {
                LegalActionView view = base.legalActions().get(i);
                int originalPosition = presentationOrder == null ? i : presentationOrder.get(i);
                idMaterials.add(new LegalActionView(preparedActions.get(originalPosition).actionId(),
                        view.category(), view.sourceName(), view.sourceType(), view.apiType(), view.description(),
                        view.cost(), view.targets(), view.modes(), view.xValue(), view.heuristicPosition(), null));
            }
            List<String> ids = StackResponseOptionIds.generate(fingerprint, idMaterials);
            List<LegalActionView> actions = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                LegalActionView view = base.legalActions().get(i);
                actions.add(new LegalActionView(ids.get(i), view.category(), view.sourceName(), view.sourceType(),
                        view.apiType(), view.description(), view.cost(), view.targets(), view.modes(),
                        view.xValue(), view.heuristicPosition(), null));
            }
            // The prior canonical stack digest covers stack state/PASS; the final digest adds
            // the actual visible IDs and action mapping/order using the existing canonical encoder.
            try {
                fingerprint = hex(MessageDigest.getInstance("SHA-256").digest(
                        ("STACK_RESPONSE_OPAQUE_V1|" + fingerprint + "|" + fingerprint(base.state(), actions, false))
                                .getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("Unable to fingerprint stack options", e);
            }
            base = new MainPhaseDecisionContext(decisionId, base.state(), actions);
        }
        List<forge.ai.decision.StackResponseActionMapping> mappings = new ArrayList<>();
        for (int i = 0; i < preparedActions.size(); i++) {
            int originalPosition = presentationOrder == null ? i : presentationOrder.get(i);
            LegalActionView view = base.legalActions().get(i);
            mappings.add(new forge.ai.decision.StackResponseActionMapping(view.actionId(),
                    preparedActions.get(originalPosition).actionId(), originalPosition, i,
                    view.sourceName(), view.description()));
        }
        return new StackResponseDecisionContext(decisionId, fingerprint, base.state(), items,
                base.legalActions(), randomizedOrder, mappings);
    }

    private static List<MainPhaseCardView> cards(Player perspective, Player owner, ZoneType zone) {
        return owner.getCardsIn(zone).stream().map(card -> cardView(perspective, card))
                .sorted(CARD_ORDER).toList();
    }

    static MainPhaseCardView cardView(Player perspective, Card card) {
        if ((card.isFaceDown() && !card.getOwner().equals(perspective))
                || !card.getView().canBeShownTo(perspective.getView())) {
            return new MainPhaseCardView(HIDDEN_CARD, null, null, null, card.isTapped(),
                    card.getController().getName(), null, null, null, List.of());
        }
        boolean creatureInPlay = card.isInPlay() && card.isCreature();
        List<MainPhaseCounterView> counters = card.getCounters().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getElement().getName()))
                .map(entry -> new MainPhaseCounterView(entry.getElement().getName(), entry.getCount()))
                .toList();
        return new MainPhaseCardView(card.getName(), card.getManaCost().getSimpleString(),
                card.getType().toString(), card.getOracleText(), card.isTapped(),
                card.getController().getName(), creatureInPlay ? card.getNetPower() : null,
                creatureInPlay ? card.getNetToughness() : null,
                creatureInPlay ? card.getDamage() : null, counters);
    }

    static MainPhaseManaView mana(ManaPool pool) {
        return new MainPhaseManaView(pool.getAmountOfColor(MagicColor.WHITE),
                pool.getAmountOfColor(MagicColor.BLUE), pool.getAmountOfColor(MagicColor.BLACK),
                pool.getAmountOfColor(MagicColor.RED), pool.getAmountOfColor(MagicColor.GREEN),
                pool.getAmountOfColor((byte) ManaAtom.COLORLESS), pool.totalMana());
    }

    private static LegalActionView actionView(Player perspective, PreparedAiAction prepared,
            boolean includeRecommendations) {
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
                costSummary(sa), targets, modes,
                sa.getXManaCostPaid(), prepared.originalCandidateIndex(),
                includeRecommendations ? recommendation(prepared) : null);
    }

    private static String recommendation(PreparedAiAction action) {
        if (!action.forgeHeuristicAccepted()) {
            return "REJECTED";
        }
        return "ACTION_0".equals(action.actionId()) ? "PREFERRED" : "ALTERNATIVE";
    }

    private static String costSummary(SpellAbility ability) {
        if (ability.getPayCosts() == null) {
            return "unknown";
        }
        String summary = ability.getPayCosts().toSimpleString().trim();
        return summary.isEmpty() ? "none" : summary;
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

    private static String fingerprint(MainPhaseAiState state, List<LegalActionView> actions,
            boolean includeRecommendations) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(bytes)) {
                write(data, includeRecommendations ? "MAIN_PHASE_ACTION_V3" : "STACK_VISIBLE_ACTIONS_V1");
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
                    writeMana(data, player.mana());
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
                    if (includeRecommendations) {
                        write(data, action.forgeRecommendation());
                        data.writeInt(action.heuristicPosition());
                    }
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
            writeInteger(data, card.power());
            writeInteger(data, card.toughness());
            writeInteger(data, card.markedDamage());
            data.writeInt(card.counters().size());
            for (MainPhaseCounterView counter : card.counters()) {
                write(data, counter.name());
                data.writeInt(counter.amount());
            }
        }
    }

    private static void writeMana(DataOutputStream data, MainPhaseManaView mana) throws IOException {
        data.writeBoolean(mana != null);
        if (mana != null) {
            data.writeInt(mana.white());
            data.writeInt(mana.blue());
            data.writeInt(mana.black());
            data.writeInt(mana.red());
            data.writeInt(mana.green());
            data.writeInt(mana.colorless());
            data.writeInt(mana.total());
        }
    }

    private static void writeInteger(DataOutputStream data, Integer value) throws IOException {
        data.writeBoolean(value != null);
        if (value != null) {
            data.writeInt(value);
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
