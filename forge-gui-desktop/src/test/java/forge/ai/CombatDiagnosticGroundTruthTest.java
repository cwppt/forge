package forge.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import forge.ai.decision.external.OpenAiCompatibleDecisionProvider;
import forge.ai.decision.external.OpenAiCompatibleProviderConfig;
import forge.ai.decision.external.OpenAiResponseFormatMode;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/** Independently verify the offline diagnostic goldens with real damage and state-based actions. */
public class CombatDiagnosticGroundTruthTest extends AITest {
    @DataProvider(name = "combatGoldens")
    public Object[][] combatGoldens() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("tools/ai-evaluation/fixtures/blocker-judgment.json"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot locate diagnostic fixture file");
        String json = Files.readString(root.resolve("tools/ai-evaluation/fixtures/blocker-judgment.json"), StandardCharsets.UTF_8).replace("\uFEFF", "");
        List<Object[]> cases = new ArrayList<>();
        for (var item : JsonParser.parseString(json).getAsJsonArray()) {
            JsonObject fixture = item.getAsJsonObject();
            if (!fixture.get("Order").getAsString().equals("NoBlockFirst")) continue;
            for (var truth : fixture.getAsJsonArray("ExpectedOutcomes")) cases.add(new Object[]{fixture, truth.getAsJsonObject()});
        }
        Assert.assertEquals(cases.size(), 16);
        return cases.toArray(Object[][]::new);
    }

    @Test(dataProvider = "combatGoldens")
    public void engineAgreesWithOfflineOutcome(JsonObject fixture, JsonObject expected) {
        Game game = initAndCreateGame();
        Player attackingPlayer = game.getPlayers().get(0), defendingPlayer = game.getPlayers().get(1);
        attackingPlayer.setLife(20, null);
        fillLibrary(attackingPlayer, 5); fillLibrary(defendingPlayer, 5);
        JsonObject state = fixture.getAsJsonObject("Payload").getAsJsonObject("state");
        int life = state.getAsJsonArray("players").get(0).getAsJsonObject().get("life").getAsInt();
        defendingPlayer.setLife(life, null);
        Card attacker = addCard("Hill Giant", attackingPlayer), blocker = addCard("Grizzly Bears", defendingPlayer);
        JsonObject a = state.getAsJsonArray("attackers").get(0).getAsJsonObject().getAsJsonObject("permanent");
        JsonObject b = state.getAsJsonArray("blockers").get(0).getAsJsonObject().getAsJsonObject("permanent");
        attacker.setBasePower(a.getAsJsonObject("card").get("power").getAsInt());
        attacker.setBaseToughness(a.getAsJsonObject("card").get("toughness").getAsInt());
        blocker.setBasePower(b.getAsJsonObject("card").get("power").getAsInt());
        blocker.setBaseToughness(b.getAsJsonObject("card").get("toughness").getAsInt());
        for (var keyword : a.getAsJsonArray("keywords")) {
            String text = keyword.getAsString();
            attacker.addIntrinsicKeyword(Character.toUpperCase(text.charAt(0)) + text.substring(1));
        }
        attacker.setSickness(false); blocker.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, attackingPlayer);
        Combat combat = new Combat(attackingPlayer); game.getPhaseHandler().setCombat(combat);
        combat.addAttacker(attacker, defendingPlayer);
        boolean blocks = !expected.get("optionId").getAsString().equals("NO_BLOCK");
        if (blocks) combat.addBlocker(attacker, blocker);
        combat.setBlocked(attacker, blocks);
        combat.orderBlockersForDamageAssignment();
        combat.orderAttackersForDamageAssignment();
        for (boolean firstStrike : List.of(true, false)) {
            game.getPhaseHandler().devModeSet(firstStrike ? PhaseType.COMBAT_FIRST_STRIKE_DAMAGE : PhaseType.COMBAT_DAMAGE, attackingPlayer, false, 8);
            if (combat.assignCombatDamage(firstStrike)) combat.dealAssignedDamage();
            game.getAction().checkStateEffects(true);
            combat.removeAbsentCombatants();
            if (game.isGameOver()) break;
        }
        String label = fixture.get("Case").getAsString() + " " + expected.get("optionId").getAsString();
        Assert.assertEquals(life - defendingPlayer.getLife(), expected.get("defenderDamage").getAsInt(), label + " player damage");
        Assert.assertEquals(!attacker.isInPlay(), expected.get("attackerDies").getAsBoolean(), label + " attacker death");
        Assert.assertEquals(!blocker.isInPlay(), expected.get("blockerDies").getAsBoolean(), label + " blocker death");
        Assert.assertEquals(defendingPlayer.getLife() <= 0, expected.get("defenderLoses").getAsBoolean(), label + " lethal");
    }

    @Test
    public void keepingPlatinumAngelIsMandatoryNoBlockControl() throws Exception {
        JsonObject exported = new JsonObject(); JsonArray outcomes = new JsonArray();
        for (boolean blocks : List.of(false, true)) {
            Game game = initAndCreateGame();
            Player attackingPlayer = game.getPlayers().get(0), defendingPlayer = game.getPlayers().get(1);
            attackingPlayer.setLife(20, null); fillLibrary(attackingPlayer, 5); fillLibrary(defendingPlayer, 5);
            Card attacker = addCard("Ankle Biter", attackingPlayer), angel = addCard("Platinum Angel", defendingPlayer);
            attacker.setSickness(false); angel.setSickness(false);
            defendingPlayer.setLife(0, null); game.getAction().checkStateEffects(true);
            Assert.assertFalse(game.isGameOver(), "Angel must protect the zero-life defender");
            game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, attackingPlayer, false, 8);
            Combat combat = new Combat(attackingPlayer); game.getPhaseHandler().setCombat(combat);
            combat.addAttacker(attacker, defendingPlayer);
            var prepared = ExternalCombatBlockers.prepare(defendingPlayer, combat, "probe-protective-blocker", 4);
            Assert.assertNotNull(prepared);
            var selected = prepared.context().options().stream().filter(o -> blocks == !o.assignments().isEmpty()).findFirst().orElseThrow();
            if (!blocks) {
                ExternalCombatBlockers.choose(defendingPlayer, combat, c -> {
                    throw new AssertionError("Loss-protected defender must keep the existing declaration");
                }, 4);
                Assert.assertTrue(combat.getBlockers(attacker).isEmpty());
                var provider = new OpenAiCompatibleDecisionProvider(new OpenAiCompatibleProviderConfig(true,
                        URI.create("http://localhost:11434/v1/chat/completions"), "qwen3:8b", null, Duration.ofSeconds(60), OpenAiResponseFormatMode.JSON_SCHEMA));
                var render = Arrays.stream(OpenAiCompatibleDecisionProvider.class.getDeclaredMethods()).filter(m -> m.getName().equals("requestBody")).findFirst().orElseThrow();
                render.setAccessible(true);
                JsonObject wire = JsonParser.parseString((String) render.invoke(provider, prepared.context(), null)).getAsJsonObject();
                JsonObject payload = JsonParser.parseString(wire.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
                exported.add("ProviderRequest", wire);
                exported.add("Payload", payload); exported.addProperty("ExpectedOptionId", "NO_BLOCK");
                exported.addProperty("BlockOptionId", prepared.context().options().stream().filter(o -> !o.assignments().isEmpty()).findFirst().orElseThrow().optionId());
                exported.addProperty("Case", "protective-blocker-zero-life");
            }
            if (blocks) combat.addBlocker(attacker, angel);
            combat.setBlocked(attacker, blocks); combat.orderBlockersForDamageAssignment(); combat.orderAttackersForDamageAssignment();
            game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE, attackingPlayer, false, 8);
            if (combat.assignCombatDamage(false)) combat.dealAssignedDamage();
            game.getAction().checkStateEffects(true);
            Assert.assertEquals(defendingPlayer.hasLost(), blocks, "Only sacrificing Angel should lose the game");
            Assert.assertEquals(angel.isInPlay(), !blocks);
            JsonObject outcome = new JsonObject(); outcome.addProperty("optionId", blocks ? exported.get("BlockOptionId").getAsString() : "NO_BLOCK");
            outcome.addProperty("defenderDamage", -defendingPlayer.getLife());
            outcome.addProperty("attackerDies", !attacker.isInPlay()); outcome.addProperty("blockerDies", !angel.isInPlay());
            outcome.addProperty("defenderLoses", defendingPlayer.hasLost()); outcomes.add(outcome);
        }
        exported.add("ExpectedOutcomes", outcomes);
        String export = System.getProperty("forge.ai.diagnostic.angelFixture");
        if (export != null) {
            Files.writeString(Path.of(export), new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(exported));
            var saved = JsonParser.parseString(Files.readString(Path.of(export))).getAsJsonObject();
            Assert.assertEquals(saved.getAsJsonObject("Payload").getAsJsonArray("options").size(), 2);
            Assert.assertEquals(saved.getAsJsonArray("ExpectedOutcomes").size(), 2);
        }
    }
}


