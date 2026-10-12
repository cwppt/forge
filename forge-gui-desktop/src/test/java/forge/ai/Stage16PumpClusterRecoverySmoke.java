package forge.ai;

import com.google.gson.Gson;
import forge.ai.decision.*;
import forge.game.keyword.Keyword;
import java.util.Optional;
import forge.ai.decision.CombatActionDecisionContext;
import forge.ai.decision.external.OpenAiCompatibleDecisionProvider;
import forge.ai.decision.external.OpenAiCompatibleProviderConfig;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import forge.model.FModel;
import forge.util.MyRandom;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;

/** Manual real-provider fixture; intentionally not a networked unit test. */
public final class Stage16PumpClusterRecoverySmoke extends AITest {
    public static void main(String[] args) throws Exception { new Stage16PumpClusterRecoverySmoke().run(args); }
    private void run(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("output-directory model endpoint comma-separated-seeds required");
        initializeModel(); Path output = Path.of(args[0]); Files.createDirectories(output);
        var results = new ArrayList<Map<String, Object>>(); int executed = 0;
        for (String name : new String[] {"Earthbrawn", "Resounding Roar", "Righteousness", "Hundred-Talon Strike"}) {
        for (String seedText : args[3].split(",")) {
            long seed = Long.parseLong(seedText); MyRandom.setRandom(new Random(seed)); AiDecisionMetrics.reset();
            String prefix = output.resolve(name.toLowerCase().replaceAll("[^a-z0-9]", "-") + "-" + seed).toString();
            System.setProperty(CombatActionEligibilityAudit.PROPERTY, prefix + ".eligibility.jsonl");
            System.setProperty(OpenAiCompatibleDecisionProvider.COMBAT_REQUEST_CAPTURE_PROPERTY, prefix + ".requests.jsonl");
            var game = initAndCreateGame(); var ai = game.getPlayers().get(1); var enemy = game.getPlayers().get(0);
            ai.getRegisteredPlayer().getDeck().getMain().add(FModel.getMagicDb().getCommonCards().getCard(name), 1);
            var attacker = addCard("Hill Giant", enemy); var blocker = addCard("Runeclaw Bear", ai);
            attacker.setSickness(false); blocker.setSickness(false); addCards("Forest", 2, ai); addCards("Plains", 2, ai);
            var candidate = addCardToZone(name, ai, ZoneType.Hand);
            addCardToZone("Black Lotus", enemy, ZoneType.Hand);
            game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, enemy, false);
            var combat = new Combat(enemy); game.getPhaseHandler().setCombat(combat);
            combat.addAttacker(attacker, ai); combat.addBlocker(attacker, blocker); combat.setBlocked(attacker, true);
            game.getPhaseHandler().setPriority(ai);
            var provider = new OpenAiCompatibleDecisionProvider(new OpenAiCompatibleProviderConfig(true,
                    URI.create(args[2]), args[1], null, Duration.ofSeconds(60)));
            var controller = new PlayerControllerAi(game, ai, ai.getLobbyPlayer(), new AiDecisionProvider() {
                public Optional<AiDecision> choose(AiDecisionContext raw) { return chooseWithDiagnostics(raw).decision(); }
                public AiDecisionResult chooseWithDiagnostics(AiDecisionContext raw) {
                var context = (CombatActionDecisionContext) raw;
                if (context.options().size() != 1 || !context.passAvailable()
                        || !context.options().get(0).sourceName().equals(name))
                    throw new IllegalStateException("Missing exact ordinary pump/PASS options");
                String json = new Gson().toJson(context);
                if (json.contains("Reinforce") || json.contains("Cycling") || json.contains("Splice") || json.contains("Black Lotus") || json.contains("VERIFIED_SAFE"))
                    throw new IllegalStateException("Unsafe model projection");
                return provider.chooseWithDiagnostics(context);
                }
            }, false, false, 3);
            ai.dangerouslySetController(controller); controller.getAi().configureExternalCombatActions(true, 4);
            AiDecisionMetrics.configureDecisionAudit(Path.of(prefix + ".decisions.jsonl"));
            AiDecisionMetrics.observeCombatSequences(game);
            var selected = controller.chooseSpellAbilityToPlay();
            if (AiDecisionMetrics.combatActionEvents().size() != 1) throw new IllegalStateException("No combat call");
            var event = AiDecisionMetrics.combatActionEvents().get(0);
            if (event.fallback() || event.staleOrRevalidationFailure()) throw new IllegalStateException("Decision failed: " + event);
            boolean pumpExecuted = selected != null;
            if (pumpExecuted) {
                if (selected.size() != 1 || !selected.get(0).isSpell() || !selected.get(0).getTargets().contains(blocker))
                    throw new IllegalStateException("Wrong prepared ability/target");
                controller.playChosenSpellAbility(selected.get(0));
                if (game.getStack().isEmpty()) throw new IllegalStateException("Payment failed");
                game.getStack().resolveStack();
                int power = name.equals("Righteousness") ? 9 : name.equals("Hundred-Talon Strike") ? 3 : 5;
                int toughness = name.equals("Righteousness") ? 9 : name.equals("Hundred-Talon Strike") ? 2 : 5;
                if (blocker.getNetPower() != power || blocker.getNetToughness() != toughness
                        || name.equals("Hundred-Talon Strike") && !blocker.hasKeyword(Keyword.FIRST_STRIKE) || game.getCardsIn(ZoneType.Graveyard).stream()
                        .noneMatch(card -> card.getName().equals(name)))
                    throw new IllegalStateException("Pump did not resolve");
                executed++;
            }
            results.add(Map.ofEntries(Map.entry("card", name), Map.entry("seed", seed),
                    Map.entry("selectedOption", event.selectedOptionId()), Map.entry("pumpExecuted", pumpExecuted),
                    Map.entry("passSelected", event.passSelection()), Map.entry("hostWarning", true),
                    Map.entry("siblingExposed", false), Map.entry("fallback", event.fallback()),
                    Map.entry("stale", event.staleOrRevalidationFailure()), Map.entry("providerLatencyMs", event.providerLatencyMs()),
                    Map.entry("liveRevalidation", event.liveRevalidationOutcome())));
            // Incomplete legal fixtures: deliberately no winner/completed-game statistic.
            AiDecisionMetrics.completeGame(game.getId(), null, true, 0, game.getPhaseHandler().getTurn());
        }
        }
        Files.writeString(output.resolve("fixture-results.json"), new Gson().toJson(results));
        if (results.size() < 5 || executed < 3) throw new IllegalStateException("Coverage target not reached; inspect offers/PASS results");
        System.out.println("Stage 16.2 live cluster verified: " + results.size() + " calls, " + executed + " pumps executed");
    }
}
