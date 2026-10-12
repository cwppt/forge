package forge.ai;

import com.google.gson.Gson;
import forge.ai.decision.*;
import forge.ai.decision.external.*;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import forge.game.phase.PhaseType;
import forge.game.card.Card;
import forge.util.MyRandom;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

public final class Stage16PairwiseRemovalRecoverySmoke extends AITest {
    public static void main(String[] args) {
        try { new Stage16PairwiseRemovalRecoverySmoke().run(args); }
        catch (Throwable error) { error.printStackTrace(); System.exit(1); }
        System.exit(0);
    }

    private void run(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("output-directory model endpoint comma-separated-seeds required");
        initializeModel(); Path output = Path.of(args[0]); Files.createDirectories(output);
        System.setProperty("forge.ai.fullGame.audit", "true");
        List<Map<String, Object>> results = new ArrayList<>();
        AtomicInteger recoveredExecutions = new AtomicInteger(0);

        for (String scenario : List.of("MAIN1_BURN", "MAIN2_BURN", "MAIN2_CREATURE")) {
            PhaseType window = scenario.startsWith("MAIN1") ? PhaseType.MAIN1 : PhaseType.MAIN2;
            boolean creatureAlternative = scenario.equals("MAIN2_CREATURE");
            String nativeName = creatureAlternative ? "Grizzly Bears" : "Shock";
            for (String seedText : args[3].split(",")) {
                long seed = Long.parseLong(seedText);
                MyRandom.setRandom(new Random(seed));
                AiDecisionMetrics.reset();

                String prefix = output.resolve(scenario + "-" + seed).toString();
                System.setProperty(OpenAiCompatibleDecisionProvider.COMBAT_REQUEST_CAPTURE_PROPERTY, prefix + ".requests.jsonl");
                AiDecisionMetrics.configureDecisionAudit(Path.of(prefix + ".decisions.jsonl"));

                Game game = initAndCreateGame();
                Player ai = game.getPlayers().get(1);
                Player enemy = game.getPlayers().get(0);
                int startingLife = window == PhaseType.MAIN2 && !creatureAlternative ? 2 : 20;
                enemy.setLife(startingLife, null);
                game.getPhaseHandler().devModeSet(window, ai);
                game.getPhaseHandler().setPriority(ai);

                addCards("Swamp", 5, ai);
                addCards(creatureAlternative ? "Forest" : "Mountain", 2, ai);
                Card source = addCardToZone("Dead Ringers", ai, ZoneType.Hand);
                addCardToZone(nativeName, ai, ZoneType.Hand);
                Card first = addCardToZone("Runeclaw Bear", enemy, ZoneType.Battlefield);
                Card second = addCardToZone("Grizzly Bears", enemy, ZoneType.Battlefield);
                addCardToZone("Black Lotus", enemy, ZoneType.Hand);

                var provider = new OpenAiCompatibleDecisionProvider(new OpenAiCompatibleProviderConfig(true, URI.create(args[2]), args[1], null, Duration.ofSeconds(60)));

                AtomicInteger calls = new AtomicInteger();
                var wrapped = new AiDecisionProvider() {
                    public Optional<AiDecision> choose(AiDecisionContext raw) { return chooseWithDiagnostics(raw).decision(); }

                    public AiDecisionResult chooseWithDiagnostics(AiDecisionContext raw) {
                        var ctx = (MainPhaseDecisionContext) raw;

                        if (ctx.legalActions().size() != 2) {
                            throw new IllegalStateException("Expected exactly 2 options");
                        }

                        var deadRingers = ctx.legalActions().stream()
                            .filter(a -> a.sourceName().equals("Dead Ringers"))
                            .findFirst()
                            .orElseThrow();

                        if (deadRingers.targets().size() != 2 || !deadRingers.targets().stream().allMatch(t -> t.contains("colors="))) {
                            throw new IllegalStateException("Dead Ringers targets invalid");
                        }

                        var shock = ctx.legalActions().stream()
                            .filter(a -> a.sourceName().equals(nativeName))
                            .findFirst()
                            .orElseThrow();

                        calls.incrementAndGet();

                        return provider.chooseWithDiagnostics(raw);
                    }
                };

                var controller = new PlayerControllerAi(game, ai, ai.getLobbyPlayer(), wrapped, false, true, 4);
                ai.dangerouslySetController(controller);

                var selected = controller.chooseSpellAbilityToPlay();

                if (AiDecisionMetrics.actionEvents().size() != 1 || calls.get() != 1) {
                    throw new IllegalStateException("Metrics mismatch at " + window + ": " + AiDecisionMetrics.mainPhaseRoutingDiagnostics());
                }

                var event = AiDecisionMetrics.actionEvents().get(0);
                if (event.fallback() || event.staleOrRevalidationFailure()) {
                    throw new IllegalStateException("Failed live decision: " + event);
                }

                if (selected == null || selected.size() != 1) {
                    throw new IllegalStateException("No valid action selected");
                }

                var action = selected.get(0);
                boolean recovered = action.getHostCard() == source;

                if (recovered) {
                    if (action.getTargets().size() != 2 || !action.getTargets().getTargetCards().contains(first) || !action.getTargets().getTargetCards().contains(second)) {
                        throw new IllegalStateException("Invalid targets");
                    }

                    if (!ExternalAiAbilitySupport.preparedPairwiseAllowed(action)) {
                        throw new IllegalStateException("Pairwise not allowed");
                    }

                }
                if (!ComputerUtil.playStack(action, ai, game)) {
                    throw new IllegalStateException("Action payment failed");
                    }

                game.getStack().resolveStack();

                    ZoneType expectedSourceZone = !recovered && creatureAlternative ? ZoneType.Battlefield : ZoneType.Graveyard;
                    if (game.getCardState(action.getHostCard(), null).getZone().getZoneType() != expectedSourceZone) {
                        throw new IllegalStateException("Source did not resolve to expected zone");
                    }

                if (recovered) {
                    if (game.getCardState(first, null).getZone().getZoneType() != ZoneType.Graveyard || game.getCardState(second, null).getZone().getZoneType() != ZoneType.Graveyard) {
                        throw new IllegalStateException("Targets not in Graveyard");
                    }

                    recoveredExecutions.incrementAndGet();
                }

                if (!game.getStack().isEmpty()) {
                    throw new IllegalStateException("Stack is not empty");
                }

                results.add(Map.ofEntries(
                    Map.entry("window", window.name()),
                    Map.entry("seed", seed),
                    Map.entry("enemyStartingLife", startingLife),
                    Map.entry("scenario", scenario),
                    Map.entry("nativeAlternative", nativeName),
                    Map.entry("decisionId", event.decisionId()),
                    Map.entry("selectedActionId", event.selectedActionId()),
                    Map.entry("sourceName", event.sourceName()),
                    Map.entry("recovered", recovered),
                    Map.entry("fallback", event.fallback()),
                    Map.entry("stale", event.staleOrRevalidationFailure()),
                    Map.entry("providerLatencyMs", event.providerLatencyMs()),
                    Map.entry("offerCount", 2)
                ));
                Files.writeString(output.resolve("fixture-results.json"), new Gson().toJson(results));
                AiDecisionMetrics.completeGame(game.getId(), null, true, 0, game.getPhaseHandler().getTurn());
            }
        }

        Files.writeString(output.resolve("fixture-results.json"), new Gson().toJson(results));
        System.out.println("Stage 16.4 verified " + results.size() + " offers and " + recoveredExecutions.get() + " recovered executions");
    }
}
