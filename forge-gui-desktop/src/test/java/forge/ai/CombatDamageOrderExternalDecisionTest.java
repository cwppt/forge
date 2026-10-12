package forge.ai;

import com.google.gson.Gson;
import forge.ai.decision.*;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class CombatDamageOrderExternalDecisionTest extends AITest {
    record Fixture(Game game, Player player, Player enemy, Combat combat, Card source, Card first, Card second) { }
    @BeforeMethod public void resetMetrics() { AiDecisionMetrics.reset(); }
    private Fixture fixture() { return fixture("Hill Giant", "Runeclaw Bear", "Grizzly Bears"); }
    private Fixture fixture(String sourceName, String firstName, String secondName) {
        Game game = initAndCreateGame(); Player player = game.getPlayers().get(0), enemy = game.getPlayers().get(1);
        game.getRules().setOrderCombatants(true);
        Card source = addCard(sourceName, player), first = addCard(firstName, enemy), second = addCard(secondName, enemy);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, player);
        Combat combat = new Combat(player); game.getPhaseHandler().setCombat(combat);
        combat.addAttacker(source, enemy); combat.addBlocker(source, first); combat.addBlocker(source, second);
        return new Fixture(game, player, enemy, combat, source, first, second);
    }
    private PlayerControllerAi controller(Fixture f, AiDecisionProvider provider, boolean enabled, int cap) {
        PlayerControllerAi result = new PlayerControllerAi(f.game(), f.player(), f.player().getLobbyPlayer(), provider,
                false, false, 3, false, 0, false, 3, false, false, 4, false, 4, enabled, cap);
        f.player().dangerouslySetController(result); return result;
    }
    private CardCollection heuristic(Fixture f) {
        return AiBlockController.orderBlockers(f.source(), new CardCollection(List.of(f.first(), f.second())));
    }
    private ExternalCombatDamageOrder.Prepared prepare(Fixture f, int cap) {
        return ExternalCombatDamageOrder.prepare(f.player(), f.combat(), f.source(), new ArrayList<>(heuristic(f)), true, "request", cap);
    }
    private AiDecision response(CombatDamageOrderDecisionContext c, String id) {
        return new AiDecision(c.decisionId(), c.stateFingerprint(), id);
    }
    @Test public void disabledPathPreservesForgeOrder() {
        Fixture f = fixture(); AtomicInteger calls = new AtomicInteger();
        var controller = controller(f, c -> { calls.incrementAndGet(); return Optional.empty(); }, false, 6);
        Assert.assertEquals(controller.orderBlockers(f.source(), new CardCollection(List.of(f.first(), f.second()))), heuristic(f));
        Assert.assertEquals(calls.get(), 0); Assert.assertTrue(AiDecisionMetrics.combatDamageOrderEvents().isEmpty());
    }
    @Test public void oneRecipientNoDamageEnoughDamageAndModernRulesBypassProvider() {
        AtomicInteger calls = new AtomicInteger(); AiDecisionProvider provider = c -> { calls.incrementAndGet(); return Optional.empty(); };
        Fixture f = fixture(); var controller = controller(f, provider, true, 6);
        f.combat().removeBlockAssignment(f.source(), f.second());
        controller.orderBlockers(f.source(), new CardCollection(f.first()));
        Assert.assertNull(prepare(fixture("Ornithopter", "Runeclaw Bear", "Grizzly Bears"), 6));
        Assert.assertNull(prepare(fixture("Colossal Dreadmaw", "Runeclaw Bear", "Grizzly Bears"), 6));
        Fixture modern = fixture(); modern.game().getRules().setOrderCombatants(false);
        controller(modern, provider, true, 6).orderBlockers(modern.source(), new CardCollection(List.of(modern.first(), modern.second())));
        Assert.assertEquals(calls.get(), 0);
    }
    @Test public void seamAppliesExactNonDefaultOrderAndRecordsDisagreement() throws Exception {
        Fixture f = fixture(); var expected = new ArrayList<>(heuristic(f)); Collections.reverse(expected);
        controller(f, c -> {
            var context = (CombatDamageOrderDecisionContext)c;
            var names = expected.stream().map(Card::getName).toList();
            var option = context.options().stream().filter(o -> o.orderedRecipientSummaries().stream()
                    .map(s -> s.substring(0, s.lastIndexOf(' '))).toList().equals(names)).findFirst().orElseThrow();
            return Optional.of(response(context, option.optionId()));
        }, true, 6);
        f.combat().orderBlockersForDamageAssignment(f.source(), new CardCollection(List.of(f.first(), f.second())));
        var field = Combat.class.getDeclaredField("blockersOrderedForDamageAssignment"); field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var orders = (com.google.common.base.Supplier<Map<Card, CardCollection>>) field.get(f.combat());
        Assert.assertEquals(orders.get().get(f.source()), new CardCollection(expected));
        var event = AiDecisionMetrics.combatDamageOrderEvents().get(0);
        Assert.assertTrue(event.responseAccepted()); Assert.assertNotEquals(event.selectedOptionId(), event.heuristicOptionId());
    }
    @Test public void blockerOrderingMultipleAttackersUsesSameSeam() {
        Fixture f = fixture(); Card secondAttacker = addCard("Grizzly Bears", f.player());
        f.combat().addAttacker(secondAttacker, f.enemy()); f.combat().addBlocker(secondAttacker, f.first());
        var heuristic = AiBlockController.orderAttackers(f.first(), new CardCollection(List.of(f.source(), secondAttacker)));
        var prepared = ExternalCombatDamageOrder.prepare(f.enemy(), f.combat(), f.first(), new ArrayList<>(heuristic), false, "request", 6);
        Assert.assertNotNull(prepared);
        var alternative = prepared.options().get(1);
        var actual = ExternalCombatDamageOrder.choose(f.enemy(), f.first(), heuristic, false, c -> {
            var context = (CombatDamageOrderDecisionContext)c;
            Assert.assertFalse(context.state().sourceAttacking()); return Optional.of(response(context, alternative.view().optionId()));
        }, 6);
        Assert.assertEquals(actual, new CardCollection(alternative.order()));
    }
    @Test public void idsFingerprintPresentationAndSnapshotsAreDeterministic() {
        Fixture f = fixture(); var first = prepare(f, 6); var second = prepare(f, 6);
        Assert.assertEquals(first.context(), second.context());
        Assert.assertTrue(first.context().options().stream().allMatch(o -> o.optionId().matches("OPT_[A-F0-9]{64}")));
        var ids = first.context().options().stream().map(CombatDamageOrderOptionView::optionId).toList();
        Assert.assertEquals(ids, ids.stream().sorted().toList());
        Assert.expectThrows(UnsupportedOperationException.class, () -> first.context().options().clear());
        Assert.expectThrows(UnsupportedOperationException.class, () -> first.context().options().get(0).orderedRecipientIds().clear());
        String before = new Gson().toJson(first.context()); f.source().setDamage(1);
        Assert.assertEquals(new Gson().toJson(first.context()), before);
        Assert.assertNotEquals(prepare(f, 6).context().stateFingerprint(), first.context().stateFingerprint());
    }
    @Test public void visibleKeywordsLifeAndHiddenZonesAreWhitelisted() {
        Fixture trample = fixture("Colossal Dreadmaw", "Craw Wurm", "Craw Wurm");
        var t = prepare(trample, 6); Assert.assertTrue(t.context().state().source().keywords().contains("trample"));
        Assert.assertEquals(t.context().state().defendingPlayerLife(), Integer.valueOf(trample.enemy().getLife()));
        Fixture death = fixture("Typhoid Rats", "Runeclaw Bear", "Grizzly Bears");
        Assert.assertTrue(prepare(death, 6).context().state().source().keywords().contains("deathtouch"));
        Fixture indestructible = fixture("Hill Giant", "Darksteel Myr", "Grizzly Bears");
        Assert.assertTrue(prepare(indestructible, 6).context().state().recipients().stream().anyMatch(r -> r.keywords().contains("indestructible")));
        addCardToZone("Griselbrand", trample.enemy(), ZoneType.Hand);
        addCardToZone("Black Lotus", trample.player(), ZoneType.Library);
        String json = new Gson().toJson(prepare(trample, 6).context());
        for (String forbidden : List.of("Griselbrand", "Black Lotus", "heuristic", "candidateIndex", "forge.game"))
            Assert.assertFalse(json.contains(forbidden), forbidden);
    }
    @Test public void invalidOptionAndIdentityFailuresPreserveOriginalHeuristic() {
        for (String kind : List.of("OPTION", "DECISION", "FINGERPRINT", "EMPTY", "EXCEPTION")) {
            Fixture f = fixture(); var expected = new CardCollection(heuristic(f));
            var actual = ExternalCombatDamageOrder.choose(f.player(), f.source(), heuristic(f), true, c -> {
                var context = (CombatDamageOrderDecisionContext)c;
                if (kind.equals("EMPTY")) return Optional.empty();
                if (kind.equals("EXCEPTION")) throw new IllegalStateException("failure");
                return Optional.of(new AiDecision(kind.equals("DECISION") ? "wrong" : context.decisionId(),
                        kind.equals("FINGERPRINT") ? "wrong" : context.stateFingerprint(),
                        kind.equals("OPTION") ? "ORDER_0" : context.options().get(0).optionId()));
            }, 6);
            Assert.assertEquals(actual, expected);
            var event = AiDecisionMetrics.combatDamageOrderEvents().get(AiDecisionMetrics.combatDamageOrderEvents().size() - 1);
            Assert.assertTrue(event.fallback()); Assert.assertEquals(event.liveRevalidationResult(), "NOT_RUN");
            Assert.assertEquals(event.selectedOptionId(), event.heuristicOptionId());
        }
    }
    @Test public void unsafeMechanicsWrongPhaseAndSmallCapsAreBypassed() {
        Assert.assertNull(prepare(fixture("Wandering Wolf", "Runeclaw Bear", "Grizzly Bears"), 1));
        Assert.assertNull(prepare(fixture("Benalish Hero", "Runeclaw Bear", "Grizzly Bears"), 6));
        Assert.assertNull(prepare(fixture("Thorn Elemental", "Craw Wurm", "Craw Wurm"), 6));
        Assert.assertNull(prepare(fixture("Glistener Elf", "Runeclaw Bear", "Grizzly Bears"), 6));
        Fixture f = fixture(); f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE, f.player());
        Assert.assertNull(prepare(f, 6));
    }
    @Test public void firstDoubleStrikeAndProtectionRemainVisible() {
        Fixture first = fixture("White Knight", "Hill Giant", "Craw Wurm");
        var state = prepare(first, 6).context().state();
        Assert.assertTrue(state.source().keywords().contains("first strike"));
        Assert.assertTrue(state.source().keywords().stream().anyMatch(k -> k.toLowerCase(Locale.ROOT).contains("protection")));
        Fixture doubleStrike = fixture("Fencing Ace", "Runeclaw Bear", "Grizzly Bears");
        Assert.assertTrue(prepare(doubleStrike, 6).context().state().source().keywords().contains("double strike"));
    }
    @Test public void timeoutAndMalformedDiagnosticsRetainFailureAndLatency() {
        for (AiDecisionFailureReason reason : List.of(AiDecisionFailureReason.TIMEOUT, AiDecisionFailureReason.MALFORMED_RESPONSE)) {
            Fixture f = fixture(); var original = heuristic(f);
            var result = ExternalCombatDamageOrder.choose(f.player(), f.source(), original, true, new AiDecisionProvider() {
                @Override public Optional<AiDecision> choose(AiDecisionContext c) { throw new AssertionError("diagnostics required"); }
                @Override public AiDecisionResult chooseWithDiagnostics(AiDecisionContext c) {
                    return AiDecisionResult.failed(reason, "fake", "test", 17);
                }
            }, 6);
            Assert.assertEquals(result, original);
            var event = AiDecisionMetrics.combatDamageOrderEvents().get(AiDecisionMetrics.combatDamageOrderEvents().size() - 1);
            Assert.assertEquals(event.failureReason(), reason); Assert.assertEquals(event.providerLatencyMs(), 17L);
            Assert.assertTrue(event.providerAttempted()); Assert.assertTrue(event.fallback());
        }
    }
    @Test public void sourceRecipientSetControllerPhaseAndCombatChangesFallBack() {
        for (String kind : List.of("SOURCE", "RECIPIENT", "SET", "CONTROL", "PHASE", "COMBAT", "DAMAGE")) {
            Fixture f = fixture(); var expected = new CardCollection(heuristic(f));
            var actual = ExternalCombatDamageOrder.choose(f.player(), f.source(), heuristic(f), true, c -> {
                var context = (CombatDamageOrderDecisionContext)c;
                switch (kind) {
                    case "SOURCE" -> f.source().setGameTimestamp(f.source().getGameTimestamp() + 1);
                    case "RECIPIENT" -> f.first().setGameTimestamp(f.first().getGameTimestamp() + 1);
                    case "SET" -> f.combat().removeBlockAssignment(f.source(), f.first());
                    case "CONTROL" -> f.first().setController(f.player(), f.game().getNextTimestamp());
                    case "PHASE" -> f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE, f.player());
                    case "COMBAT" -> f.game().getPhaseHandler().setCombat(new Combat(f.player()));
                    case "DAMAGE" -> f.first().setDamage(1);
                }
                return Optional.of(response(context, context.options().get(0).optionId()));
            }, 6);
            Assert.assertEquals(actual, expected, kind);
            var event = AiDecisionMetrics.combatDamageOrderEvents().get(AiDecisionMetrics.combatDamageOrderEvents().size() - 1);
            Assert.assertTrue(event.staleOrRevalidationFailure(), kind); Assert.assertFalse(event.responseAccepted(), kind);
        }
    }
    @Test(timeOut = 10000) public void largeRecipientSetsAreBoundedWithoutPermutationSearch() {
        Fixture f = fixture();
        for (int i = 0; i < 100; i++) f.combat().addBlocker(f.source(), addCard("Grizzly Bears", f.enemy()));
        var heuristic = AiBlockController.orderBlockers(f.source(), new CardCollection(f.combat().getBlockers(f.source())));
        for (int cap : List.of(6, 12, 1000)) {
            var prepared = ExternalCombatDamageOrder.prepare(f.player(), f.combat(), f.source(), new ArrayList<>(heuristic), true, "request", cap);
            Assert.assertTrue(prepared.options().size() <= Math.min(cap, 12));
            Assert.assertTrue(prepared.options().stream().allMatch(o -> o.order().size() == 102 && new HashSet<>(o.order()).size() == 102));
            Assert.assertEquals(prepared.options().get(0).order(), new ArrayList<>(heuristic));
        }
    }
    @Test public void auditIncludesOrdersMappingAcceptanceAndEventualOutcome() throws Exception {
        Fixture f = fixture(); var path = Files.createTempFile("damage-order", ".jsonl");
        try {
            AiDecisionMetrics.configureDecisionAudit(path);
            ExternalCombatDamageOrder.choose(f.player(), f.source(), heuristic(f), true, c -> {
                var context = (CombatDamageOrderDecisionContext)c;
                return Optional.of(response(context, context.options().get(0).optionId()));
            }, 6);
            AiDecisionMetrics.completeGame(f.game().getId(), f.player().getName(), false, 100, 4);
            String audit = Files.readString(path);
            for (String field : List.of("COMBAT_DAMAGE_ORDER", "internalMapping", "originalOrder", "heuristicOptionId", "selectedOptionId", "finalWinner"))
                Assert.assertTrue(audit.contains(field), field);
            Assert.assertTrue(AiDecisionMetrics.summary(1).contains("combatDamageOrderDecisions=1"));
        } finally { AiDecisionMetrics.configureDecisionAudit(null); Files.deleteIfExists(path); }
    }
    @Test public void compactHttpResponseAppliesOriginalMappingAndAuditsLatencyWithoutChangingCandidates() throws Exception {
        Fixture f = fixture(); var prepared = prepare(f, 6);
        String snapshot = new Gson().toJson(prepared.context());
        var alternative = prepared.options().get(1);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        var truncated = new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/v1/chat/completions", exchange -> {
            var request = com.google.gson.JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            var payload = com.google.gson.JsonParser.parseString(request.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
            Assert.assertFalse(payload.toString().contains("orderedRecipientSummaries"));
            var response = new com.google.gson.JsonObject();
            response.addProperty("decisionId", payload.get("decisionId").getAsString());
            response.addProperty("stateFingerprint", payload.get("stateFingerprint").getAsString());
            response.addProperty("optionId", alternative.view().optionId());
            var message = new com.google.gson.JsonObject(); message.addProperty("content", truncated.get() ? "{\"decisionId\":" : response.toString());
            var choice = new com.google.gson.JsonObject(); choice.add("message", message);
            var choices = new com.google.gson.JsonArray(); choices.add(choice);
            var root = new com.google.gson.JsonObject(); root.add("choices", choices);
            byte[] bytes = root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start(); var audit = Files.createTempFile("damage-latency", ".jsonl");
        try {
            AiDecisionMetrics.configureDecisionAudit(audit);
            var provider = new forge.ai.decision.external.OpenAiCompatibleDecisionProvider(new forge.ai.decision.external.OpenAiCompatibleProviderConfig(true,
                    java.net.URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/v1/chat/completions"),
                    "test-model", null, java.time.Duration.ofSeconds(2)));
            var actual = ExternalCombatDamageOrder.choose(f.player(), f.source(), heuristic(f), true, provider, 6);
            Assert.assertEquals(actual, new CardCollection(alternative.order()));
            Assert.assertEquals(prepare(f, 6).context(), prepared.context());
            Assert.assertEquals(new Gson().toJson(prepared.context()), snapshot);
            var event = AiDecisionMetrics.combatDamageOrderEvents().get(0);
            Assert.assertNotNull(event.requestDiagnostics()); Assert.assertTrue(event.liveRevalidationNanos() > 0);
            truncated.set(true);
            Assert.assertEquals(ExternalCombatDamageOrder.choose(f.player(), f.source(), heuristic(f), true, provider, 6), heuristic(f));
            Assert.assertEquals(AiDecisionMetrics.combatDamageOrderEvents().get(1).failureReason(), AiDecisionFailureReason.MALFORMED_RESPONSE);
            AiDecisionMetrics.completeGame(f.game().getId(), f.player().getName(), false, 100, 4);
            String json = Files.readString(audit);
            for (String field : List.of("requestDiagnostics", "systemPromptChars", "userPromptChars", "schemaChars", "serializedRequestChars",
                    "httpLatencyNanos", "responseParseNanos", "identityValidationNanos", "liveRevalidationNanos")) Assert.assertTrue(json.contains(field), field);
        } finally { server.stop(0); AiDecisionMetrics.configureDecisionAudit(null); Files.deleteIfExists(audit); }
    }
}
