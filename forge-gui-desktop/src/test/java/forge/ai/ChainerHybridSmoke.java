package forge.ai;

import com.google.gson.GsonBuilder;
import forge.ai.decision.*;
import forge.ai.decision.external.*;
import forge.game.card.*;
import forge.game.zone.ZoneType;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Five fixed-state, paid-resolution probes per controller; never a game/win benchmark. */
public final class ChainerHybridSmoke {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]); Files.createDirectory(out);
        List<Object> rows = new ArrayList<>();
        var test = new ChainerHybridDecisionTest(); test.initializeModel();
        for (String arm : List.of("native-balanced-v2", "existing-hybrid-choice-control", "extended-qwen")) {
            System.setProperty(ExternalGraveyardChoices.ENABLED, String.valueOf(arm.equals("extended-qwen")));
            System.setProperty(ExternalGraveyardChoices.AUDIT, out.resolve(arm + "-choices.jsonl").toString());
            System.setProperty("forge.ai.externalRequestCapture", out.resolve(arm + "-transport.jsonl").toString());
            System.setProperty("forge.ai.ollama.noThinking", "true");
            AiDecisionProvider provider = arm.equals("native-balanced-v2") ? null : new OpenAiCompatibleDecisionProvider(
                    new OpenAiCompatibleProviderConfig(true, URI.create("http://127.0.0.1:11434/v1/chat/completions"),
                    "qwen3:30b", null, Duration.ofSeconds(30), OpenAiResponseFormatMode.JSON_SCHEMA));
            for (String scenario : List.of("discard-dragon", "reanimate-with-tempest", "tutor-answer", "preserve-engine", "graveyard-vs-hand")) {
                forge.util.MyRandom.setRandom(new Random(20261022));
                var f = test.fixture(); test.controller(f, provider); long started = System.nanoTime();
                MainPhaseAiState before; Object result;
                switch (scenario) {
                    case "discard-dragon": {
                        test.mana(f, 6, 0); var a = test.chainer(f);
                        test.addCardToZone("Mountain", f.p(), ZoneType.Hand); test.addCardToZone("Shivan Dragon", f.p(), ZoneType.Hand);
                        test.addCardToZone("Reanimate", f.p(), ZoneType.Hand); test.addCardToZone("Raging Goblin", f.p(), ZoneType.Graveyard);
                        before = MainPhaseStateProjector.project(f.p(), "SMOKE", List.of()).state();
                        var original = new CardCollection(f.p().getCardsIn(ZoneType.Graveyard)); test.play(f, a);
                        result = f.p().getCardsIn(ZoneType.Graveyard).stream().filter(c -> !original.contains(c)).map(Card::getName).toList(); break;
                    }
                    case "reanimate-with-tempest": {
                        test.mana(f, 0, 1); test.ready(f, "Dragon Tempest");
                        var d = test.addCardToZone("Shivan Dragon", f.p(), ZoneType.Graveyard);
                        test.addCardToZone("Scourge of Valkas", f.p(), ZoneType.Graveyard);
                        var a = test.spell(f, "Reanimate"); a.getTargets().add(d);
                        // Let the unchanged native policy select the incumbent before the optional live retarget.
                        a.resetTargets();
                        if (((PlayerControllerAi)f.p().getController()).getAi().canPlaySa(a) != AiPlayDecision.WillPlay)
                            throw new IllegalStateException("Native AI did not prepare Reanimate");
                        before = MainPhaseStateProjector.project(f.p(), "SMOKE", List.of()).state(); test.play(f, a);
                        result = Map.of("creatures", f.p().getCreaturesInPlay().stream().map(Card::getName).toList(), "selfLife", f.p().getLife(), "opponentLife", f.o().getLife()); break;
                    }
                    case "tutor-answer": {
                        test.mana(f, 1, 1); test.ready(f, "Chainer, Nightmare Adept");
                        f.p().getZone(ZoneType.Library).removeAllCards(true);
                        for (String name : List.of("Chaos Warp", "Shivan Dragon", "Dragon Tempest", "Mountain")) test.addCardToZone(name, f.p(), ZoneType.Library);
                        test.addCard("Rest in Peace", f.o()); f.g().getAction().checkStateEffects(true);
                        var a = test.spell(f, "Demonic Tutor"); before = MainPhaseStateProjector.project(f.p(), "SMOKE", List.of()).state(); test.play(f, a);
                        result = f.p().getCardsIn(ZoneType.Hand).stream().map(Card::getName).toList(); break;
                    }
                    case "graveyard-vs-hand": {
                        // Establish the same resolved permission without spending a model call on setup.
                        test.controller(f, null); test.mana(f, 6, 3); var activate = test.chainer(f);
                        test.addCardToZone("Mountain", f.p(), ZoneType.Hand); test.addCardToZone("Chaos Warp", f.p(), ZoneType.Hand);
                        test.addCardToZone("Doomed Necromancer", f.p(), ZoneType.Graveyard); test.addCardToZone("Shivan Dragon", f.p(), ZoneType.Graveyard);
                        test.play(f, activate); test.addCardToZone("Hill Giant", f.p(), ZoneType.Hand);
                        f.g().getPhaseHandler().devModeSet(forge.game.phase.PhaseType.MAIN2, f.p()); f.p().setLandsPlayedThisTurn(1);
                        var hybrid = new PlayerControllerAi(f.g(), f.p(), f.p().getLobbyPlayer(), provider, false, provider != null, 8);
                        f.p().dangerouslySetController(hybrid);
                        before = MainPhaseStateProjector.project(f.p(), "SMOKE", List.of()).state();
                        var actions = hybrid.chooseSpellAbilityToPlay();
                        if (actions == null || actions.isEmpty()) throw new IllegalStateException("No legal follow-through action");
                        var chosen = actions.get(0); String origin = chosen.getHostCard().getZone().getZoneType().name();
                        String name = chosen.getHostCard().getName(); test.play(f, chosen);
                        result = Map.of("selected", name, "origin", origin, "creatures", f.p().getCreaturesInPlay().stream().map(Card::getName).toList()); break;
                    }
                    default: {
                        test.mana(f, 1, 1); test.ready(f, "Chainer, Nightmare Adept"); test.ready(f, "Raging Goblin");
                        var a = test.spell(f, "Diabolic Edict"); a.getTargets().add(f.p());
                        before = MainPhaseStateProjector.project(f.p(), "SMOKE", List.of()).state(); test.play(f, a);
                        result = f.p().getCreaturesInPlay().stream().map(Card::getName).toList(); break;
                    }
                }
                Map<String,Object> row = new LinkedHashMap<>(); row.put("arm", arm); row.put("scenario", scenario);
                row.put("input", before); row.put("result", result); row.put("runtimeMs", AiDecisionResult.elapsedMillis(started));
                row.put("category", "fixed-state-paid-resolution-smoke"); rows.add(row);
                Files.writeString(out.resolve("results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(rows));
                System.out.println(arm + " " + scenario + " => " + result);
            }
        }
        System.exit(0);
    }
}
