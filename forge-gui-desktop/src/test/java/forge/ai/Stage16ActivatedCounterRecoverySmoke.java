package forge.ai;

import com.google.gson.Gson;
import forge.ai.decision.*;
import forge.ai.decision.external.*;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Manual local-provider validation, not a networked unit test or win-rate experiment. */
public final class Stage16ActivatedCounterRecoverySmoke extends Stage16ActivatedCounterRecoveryTest {
    public static void main(String[] args) {
        try { new Stage16ActivatedCounterRecoverySmoke().run(args); }
        catch (Throwable error) { error.printStackTrace(); System.exit(1); }
        System.exit(0);
    }
    private void run(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("output-directory model endpoint comma-separated-seeds required");
        initializeModel(); Path output = Path.of(args[0]); Files.createDirectories(output);
        System.setProperty("forge.ai.fullGame.audit", "true");
        List<Map<String, Object>> results = new ArrayList<>(); int executed = 0;
        for (String name : List.of("Stifle", "Rust", "Disallow")) for (String seedText : args[3].split(",")) {
            long seed = Long.parseLong(seedText); MyRandom.setRandom(new Random(seed)); AiDecisionMetrics.reset();
            String prefix = output.resolve(name.toLowerCase() + "-" + seed).toString();
            System.setProperty(OpenAiCompatibleDecisionProvider.COMBAT_REQUEST_CAPTURE_PROPERTY, prefix + ".requests.jsonl");
            AiDecisionMetrics.configureDecisionAudit(Path.of(prefix + ".decisions.jsonl"));
            Fixture f = fixture(name);
            var provider = new OpenAiCompatibleDecisionProvider(new OpenAiCompatibleProviderConfig(true, URI.create(args[2]), args[1], null, Duration.ofSeconds(60)));
            var c = controller(f, new AiDecisionProvider() {
                public Optional<AiDecision> choose(AiDecisionContext raw) { return chooseWithDiagnostics(raw).decision(); }
                public AiDecisionResult chooseWithDiagnostics(AiDecisionContext raw) {
                    var ctx = (StackResponseDecisionContext) raw;
                    if (ctx.legalActions().size() != 1 || !ctx.legalActions().get(0).sourceName().equals(name)
                            || !ctx.stackItems().get(0).objectType().equals("ACTIVATED_ABILITY")) throw new IllegalStateException("Missing exact activated counter offer");
                    return provider.chooseWithDiagnostics(raw);
                }
            }, true);
            var selected = c.chooseSpellAbilityToPlay();
            if (AiDecisionMetrics.stackResponseEvents().size() != 1) throw new IllegalStateException("No stack response call");
            var event = AiDecisionMetrics.stackResponseEvents().get(0);
            if (event.fallback() || event.staleOrRevalidationFailure()) throw new IllegalStateException("Failed live decision: " + event);
            boolean counterExecuted = selected != null;
            if (counterExecuted) {
                var action = selected.get(0);
                if (action.getTargets().getFirstTargetedSpell() != f.pending() || !ExternalAiAbilitySupport.stackResponseAllowed(action))
                    throw new IllegalStateException("Wrong activated target");
                if (!ComputerUtil.playStack(action, f.ai(), f.game())) throw new IllegalStateException("Counter payment failed");
                f.game().getStack().resolveStack();
                if (f.game().getStack().getInstanceMatchingSpellAbilityID(f.pending()) != null || f.ai().getLife() != 1
                        || f.rod().getZone().getZoneType() != ZoneType.Battlefield
                        || f.game().getCardsIn(ZoneType.Graveyard).stream().noneMatch(card -> card.getName().equals(name)))
                    throw new IllegalStateException("Counter did not resolve");
                executed++;
            }
            results.add(Map.ofEntries(Map.entry("card", name), Map.entry("seed", seed), Map.entry("decisionId", event.decisionId()),
                    Map.entry("selectedOption", event.selectedResponseId()), Map.entry("counterExecuted", counterExecuted),
                    Map.entry("passSelected", event.passSelected()), Map.entry("fallback", event.fallback()),
                    Map.entry("stale", event.staleOrRevalidationFailure()), Map.entry("targetType", "ACTIVATED_ABILITY"),
                    Map.entry("providerLatencyMs", event.providerLatencyMs())));
            AiDecisionMetrics.completeGame(f.game().getId(), null, true, 0, f.game().getPhaseHandler().getTurn());
        }
        Files.writeString(output.resolve("fixture-results.json"), new Gson().toJson(results));
        if (results.size() < 5 || executed < 3) throw new IllegalStateException("Coverage target not reached; inspect offers and PASS results");
        System.out.println("Stage 16.3 verified " + results.size() + " offers and " + executed + " legally resolved counters");
    }
}
