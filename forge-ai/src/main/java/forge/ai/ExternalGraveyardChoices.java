package forge.ai;

import com.google.gson.Gson;
import forge.ai.decision.*;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Predicate;

/** Opt-in, execution-time choices over a bounded pool supplied and checked by Forge. */
final class ExternalGraveyardChoices {
    static final String ENABLED = "forge.ai.externalCardChoices";
    static final String AUDIT = "forge.ai.externalCardChoiceAudit";
    private static final Gson JSON = new Gson();
    private ExternalGraveyardChoices() { }

    static CardCollection single(Player player, SpellAbility sa, String question, CardCollectionView pool,
            CardCollectionView fallback, Predicate<Card> legal, boolean ownLibrarySearch) {
        // Native decline, variable cardinality, hidden sources and controlled players remain native.
        if (!Boolean.getBoolean(ENABLED) || sa == null || fallback == null || fallback.size() != 1
                || player.isControlled() || !(player.getController() instanceof PlayerControllerAi controller)
                || controller.cardChoiceProvider() == null || sa.getHostCard().getGame() != player.getGame()
                || !sa.getHostCard().getView().canBeShownTo(player.getView())) return copy(fallback);
        List<Card> cards = new ArrayList<>();
        for (Card card : pool) {
            if (card.getGame() != player.getGame() || !legal.test(card)) continue;
            if (!(ownLibrarySearch && card.getOwner() == player && card.isInZone(ZoneType.Library))
                    && !card.getView().canBeShownTo(player.getView())) return copy(fallback);
            cards.add(card);
        }
        if (cards.size() < 2 || cards.size() > 128 || !cards.contains(fallback.get(0))) return copy(fallback);
        cards.sort(Comparator.comparing((Card c) -> c.getName()).thenComparingInt(Card::getId));
        String id = UUID.randomUUID().toString();
        List<CardChoiceDecisionContext.Option> options = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            Card c = cards.get(i);
            options.add(new CardChoiceDecisionContext.Option("CHOICE_" + i,
                    c.getName() + " #" + c.getId() + " | " + c.getZone().getZoneType() + " | " + c.getType()
                    + " | " + c.getManaCost() + " | " + c.getOracleText()));
        }
        MainPhaseAiState state = MainPhaseStateProjector.project(player, id, List.of()).state();
        String fingerprint = fingerprint(player, sa, question, cards);
        CardChoiceDecisionContext context = new CardChoiceDecisionContext(id, fingerprint, state,
                sa.getHostCard().getName(), question, options);
        AiDecisionProvider provider = controller.cardChoiceProvider();
        AiDecisionResult result;
        try { result = provider.chooseWithDiagnostics(context); }
        catch (RuntimeException e) { result = null; }
        if (result == null) result = AiDecisionResult.failed(AiDecisionFailureReason.PROVIDER_EXCEPTION,
                provider.getClass().getSimpleName(), null, 0);
        Card selected = fallback.get(0);
        AiDecisionFailureReason reason = result.failureReason();
        boolean accepted = false;
        if (result.decision().isPresent()) {
            AiDecision decision = result.decision().get();
            if (!id.equals(decision.decisionId())) reason = AiDecisionFailureReason.DECISION_ID_MISMATCH;
            else if (!fingerprint.equals(decision.stateFingerprint())) reason = AiDecisionFailureReason.FINGERPRINT_MISMATCH;
            else {
                int index = -1;
                for (int i = 0; i < options.size(); i++) if (options.get(i).optionId().equals(decision.optionId())) index = i;
                if (index < 0) reason = AiDecisionFailureReason.INVALID_OPTION;
                else if (!fingerprint.equals(fingerprint(player, sa, question, cards))) reason = AiDecisionFailureReason.STALE_ACTION_SET;
                else if (!legal.test(cards.get(index))) reason = AiDecisionFailureReason.REVALIDATION_FAILED;
                else { selected = cards.get(index); accepted = true; reason = null; }
            }
        }
        if (!accepted && reason == null) reason = AiDecisionFailureReason.PROVIDER_EXCEPTION;
        audit(new Event(player.getGame().getId(), player.getName(), context, result.provider(), result.model(),
                result.latencyMs(), result.decision().map(AiDecision::optionId).orElse(null),
                "CHOICE_" + cards.indexOf(selected), "CHOICE_" + cards.indexOf(fallback.get(0)), !accepted, reason));
        return new CardCollection(selected);
    }

    private static CardCollection copy(CardCollectionView cards) { return cards == null ? null : new CardCollection(cards); }

    private static String fingerprint(Player player, SpellAbility sa, String question, List<Card> cards) {
        try {
            List<Object> material = new ArrayList<>();
            material.add(MainPhaseStateProjector.project(player, "CARD_CHOICE", List.of()).state().fingerprint());
            material.add(sa.getHostCard().getId()); material.add(sa.getHostCard().getGameTimestamp());
            material.add(sa.toString()); material.add(question);
            for (Card c : cards) material.add(Arrays.asList(c.getId(), c.getGameTimestamp(),
                    c.getZone() == null ? null : c.getZone().getZoneType(), c.getController().getId(),
                    c.getName(), c.getOracleText()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.toJson(material).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Only independent, exactly-one-target graveyard returns. Multi-target/coupled abilities remain native. */
    static void retarget(Player player, SpellAbility original) {
        final SpellAbility root = original instanceof forge.game.trigger.WrappedAbility wrapper ? wrapper.getWrappedAbility() : original;
        if (!Boolean.getBoolean(ENABLED) || root == null || !supportedFollowup(root)
                || root.getApi() != ApiType.ChangeZone || !"Graveyard".equals(root.getParam("Origin"))
                || !"Battlefield".equals(root.getParam("Destination")) || !root.usesTargeting()
                || root.hasParam("TargetingPlayer") || root.getTargets().size() != 1
                || root.getTargetRestrictions().getMinTargets(root.getHostCard(), root) != 1
                || root.getTargetRestrictions().getMaxTargets(root.getHostCard(), root) != 1
                || root.getTargets().getTargetCards().size() != 1) return;
        Card nativeTarget = root.getTargets().getTargetCards().get(0);
        // Candidate legality must be tested with an empty target set, otherwise uniqueness rejects the incumbent.
        var saved = root.getTargets();
        root.resetTargets();
        try {
            CardCollection pool = new CardCollection();
            for (var entity : root.getTargetRestrictions().getAllCandidates(root)) {
                if (entity instanceof Card card && card.isInZone(ZoneType.Graveyard) && root.canTarget(card)) pool.add(card);
            }
            CardCollection selected = single(player, root, "Return one graveyard permanent to the battlefield", pool,
                    new CardCollection(nativeTarget), c -> c.isInZone(ZoneType.Graveyard) && root.canTarget(c), false);
            if (selected != null && selected.size() == 1 && root.canTarget(selected.get(0))) {
                root.getTargets().add(selected.get(0));
                if (root.isTargetNumberValid()) return;
            }
        } finally {
            if (!root.isTargetNumberValid()) root.setTargets(saved);
        }
    }

    /** Re-derive permission-granted spells in the copied game; never copy a live permission reference. */
    static SpellAbility mapPermissionAbility(forge.ai.simulation.GameCopier copier, SpellAbility original) {
        if (!Boolean.getBoolean(ENABLED) || original.getMayPlay() == null) return null;
        Card copiedCard = copier.find(original.getHostCard());
        Player copiedPlayer = copier.find(original.getMayPlayOption().getPlayer());
        Card permissionHost = original.getMayPlay().getHostCard();
        // Older harness copiers omit an effect card's source. Restore that reference only inside the copy,
        // so Forge regenerates the same permission description/provenance instead of matching display text loosely.
        if (permissionHost.getEffectSource() != null) {
            copier.find(permissionHost).setEffectSource(copier.find(permissionHost.getEffectSource()));
        }
        SpellAbility match = null;
        for (SpellAbility candidate : copiedCard.getAllPossibleAbilities(copiedPlayer, false)) {
            if (candidate.getMayPlay() != null && candidate.getDescription().equals(original.getDescription())
                    && candidate.getMayPlay().getHostCard().getId() == original.getMayPlay().getHostCard().getId()) {
                if (match != null) return null; // Ambiguous alternatives must retain native fallback.
                match = candidate;
            }
        }
        if (match != null) match.setActivatingPlayer(copiedPlayer);
        return match;
    }

    private static boolean supportedFollowup(SpellAbility root) {
        if (root.getSubAbility() == null) return true;
        // Reanimate's life loss reads the returned card's LKI. Never replace or recalculate that engine effect.
        if (!root.getHostCard().getName().equals("Reanimate")) return false;
        for (SpellAbility sub = root.getSubAbility(); sub != null; sub = sub.getSubAbility()) {
            if (sub.usesTargeting() || (sub.getApi() != ApiType.LoseLife && sub.getApi() != ApiType.Cleanup)) return false;
        }
        return true;
    }

    record Event(int gameId, String player, CardChoiceDecisionContext context, String provider, String model,
                 long latencyMs, String returnedOptionId, String selectedOptionId, String nativeOptionId,
                 boolean fallback, AiDecisionFailureReason fallbackReason) { }
    private static void audit(Event event) {
        org.tinylog.Logger.info("aiCardChoiceDecision {}", JSON.toJson(event));
        String path = System.getProperty(AUDIT);
        if (path == null || path.isBlank()) return;
        try {
            synchronized (ExternalGraveyardChoices.class) {
                Files.writeString(Path.of(path), JSON.toJson(event) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception e) { org.tinylog.Logger.warn("Card-choice audit failed: {}", e.getClass().getSimpleName()); }
    }
}

