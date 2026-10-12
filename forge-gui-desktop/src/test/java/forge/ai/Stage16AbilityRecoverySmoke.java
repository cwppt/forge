package forge.ai;

import com.google.gson.Gson;
import forge.ai.decision.AiDecisionMetrics;
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
public final class Stage16AbilityRecoverySmoke extends AITest {
    public static void main(String[] args) throws Exception { new Stage16AbilityRecoverySmoke().run(args); }
    private void run(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("output-directory model endpoint comma-separated-seeds required");
        initializeModel(); Path output = Path.of(args[0]); Files.createDirectories(output);
        var results = new ArrayList<Map<String, Object>>(); int executed = 0;
        for (String seedText : args[3].split(",")) {
            long seed = Long.parseLong(seedText); MyRandom.setRandom(new Random(seed)); AiDecisionMetrics.reset();
            String prefix = output.resolve("earthbrawn-" + seed).toString();
            System.setProperty(CombatActionEligibilityAudit.PROPERTY, prefix + ".eligibility.jsonl");
            System.setProperty(OpenAiCompatibleDecisionProvider.COMBAT_REQUEST_CAPTURE_PROPERTY, prefix + ".requests.jsonl");
            var game = initAndCreateGame(); var ai = game.getPlayers().get(1); var enemy = game.getPlayers().get(0);
            ai.getRegisteredPlayer().getDeck().getMain().add(FModel.getMagicDb().getCommonCards().getCard("Earthbrawn"), 1);
            var attacker = addCard("Hill Giant", enemy); var blocker = addCard("Runeclaw Bear", ai);
            attacker.setSickness(false); blocker.setSickness(false); addCards("Forest", 2, ai);
            var earthbrawn = addCardToZone("Earthbrawn", ai, ZoneType.Hand);
            addCardToZone("Black Lotus", enemy, ZoneType.Hand);
            game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, enemy, false);
            var combat = new Combat(enemy); game.getPhaseHandler().setCombat(combat);
            combat.addAttacker(attacker, ai); combat.addBlocker(attacker, blocker); combat.setBlocked(attacker, true);
            game.getPhaseHandler().setPriority(ai);
            var provider = new OpenAiCompatibleDecisionProvider(new OpenAiCompatibleProviderConfig(true,
                    URI.create(args[2]), args[1], null, Duration.ofSeconds(60)));
            var controller = new PlayerControllerAi(game, ai, ai.getLobbyPlayer(), raw -> {
                var context = (CombatActionDecisionContext) raw;
                if (context.options().size() != 1 || !context.passAvailable()
                        || !context.options().get(0).sourceName().equals("Earthbrawn"))
                    throw new IllegalStateException("Missing exact ordinary pump/PASS options");
                String json = new Gson().toJson(context);
                if (json.contains("Reinforce") || json.contains("Black Lotus") || json.contains("VERIFIED_SAFE"))
                    throw new IllegalStateException("Unsafe model projection");
                return provider.choose(context);
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
                if (blocker.getNetToughness() != 5 || game.getCardsIn(ZoneType.Graveyard).stream()
                        .noneMatch(card -> card.getName().equals("Earthbrawn")))
                    throw new IllegalStateException("Pump did not resolve");
                executed++;
            }
            results.add(Map.of("seed", seed, "selectedOption", event.selectedOptionId(),
                    "pumpExecuted", pumpExecuted, "passSelected", event.passSelection(), "hostWarning", true,
                    "reinforceExposed", false, "fallback", event.fallback(), "stale", event.staleOrRevalidationFailure(),
                    "providerLatencyMs", event.providerLatencyMs(), "liveRevalidation", event.liveRevalidationOutcome()));
            // Incomplete legal fixtures: deliberately no winner/completed-game statistic.
            AiDecisionMetrics.completeGame(game.getId(), null, true, 0, game.getPhaseHandler().getTurn());
        }
        Files.writeString(output.resolve("fixture-results.json"), new Gson().toJson(results));
        if (executed == 0) throw new IllegalStateException("No real-provider pump executed; inspect PASS results");
        System.out.println("Stage 16.1 live fixture verified: " + results.size() + " calls, " + executed + " pumps executed");
    }
}
