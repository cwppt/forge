package forge.ai;

import forge.ai.decision.AiDecisionMetrics;
import forge.ai.decision.external.OpenAiCompatibleDecisionProvider;
import forge.ai.decision.external.OpenAiCompatibleProviderConfig;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/** Explicit manual fixture runner; never part of the network-free unit suite. */
public final class CombatActionOllamaSmoke extends AITest {
    public static void main(String[] args) throws Exception {
        new CombatActionOllamaSmoke().run(args);
    }
    private void run(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("audit-file model endpoint required");
        initializeModel();
        var game = initAndCreateGame(); var ai = game.getPlayers().get(1); var enemy = game.getPlayers().get(0);
        var attacker = addCard("Hill Giant", enemy); var blocker = addCard("Runeclaw Bear", ai);
        attacker.setSickness(false); blocker.setSickness(false);
        addCards("Forest", 3, ai); addCardToZone("Giant Growth", ai, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, enemy);
        var combat = new Combat(enemy); game.getPhaseHandler().setCombat(combat);
        combat.addAttacker(attacker, ai); combat.addBlocker(attacker, blocker); game.getPhaseHandler().setPriority(ai);
        var provider = new OpenAiCompatibleDecisionProvider(new OpenAiCompatibleProviderConfig(true,
                URI.create(args[2]), args[1], null, Duration.ofSeconds(60)));
        var controller = new PlayerControllerAi(game, ai, ai.getLobbyPlayer(), provider, false, false, 3);
        ai.dangerouslySetController(controller); controller.getAi().configureExternalCombatActions(true, 4);
        AiDecisionMetrics.configureDecisionAudit(Path.of(args[0])); AiDecisionMetrics.observeCombatSequences(game);
        var selected = controller.chooseSpellAbilityToPlay();
        var events = AiDecisionMetrics.combatActionEvents();
        if (events.size() != 1 || events.get(0).optionCount() < 1 || events.get(0).fallback())
            throw new IllegalStateException("Smoke did not produce an accepted prepared combat decision: " + events);
        if (selected != null && (selected.size() != 1 || !selected.get(0).getTargets().contains(blocker)))
            throw new IllegalStateException("Prepared targets were lost");
        // Fixture verifies the priority seam only; no winner or completed combat outcome is fabricated.
        AiDecisionMetrics.completeGame(game.getId(), null, true, 0, game.getPhaseHandler().getTurn());
        System.out.println(AiDecisionMetrics.combatActionSummary());
        System.out.println("Stage 13 fixture smoke accepted: " + events.get(0).selectedOptionId());
    }
}
