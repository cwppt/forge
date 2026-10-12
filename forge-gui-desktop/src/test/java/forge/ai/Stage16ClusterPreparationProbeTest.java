package forge.ai;

import forge.ai.decision.AiDecisionType;
import forge.game.combat.Combat;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Pre-registration probes: use native preparation/payment, never override production policy. */
public class Stage16ClusterPreparationProbeTest extends AITest {
    @DataProvider public Object[][] candidates() { return new Object[][] {
            {"Earthbrawn"}, {"Resounding Roar"}, {"Righteousness"}, {"Hundred-Talon Strike"},
            {"About Face"}, {"Strange Inversion"}, {"Transmutation"}, {"Dizzy Spell"}, {"Rouse"} }; }
    @Test(dataProvider="candidates") public void observeNativePreparationAndExecution(String name) throws Exception {
        var game = initAndCreateGame(); var ai = game.getPlayers().get(1); var enemy = game.getPlayers().get(0);
        var attacker = addCard("Hill Giant", enemy); var blocker = addCard("Runeclaw Bear", ai);
        attacker.setSickness(false); blocker.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, enemy, false);
        var combat = new Combat(enemy); game.getPhaseHandler().setCombat(combat);
        combat.addAttacker(attacker, ai); combat.addBlocker(attacker, blocker); combat.setBlocked(attacker, true);
        game.getPhaseHandler().setPriority(ai);
        addCards("Forest", 5, ai); addCards("Plains", 5, ai); addCards("Mountain", 5, ai); addCards("Swamp", 5, ai); addCards("Island", 5, ai);
        var source = addCardToZone(name, ai, ZoneType.Hand); var original = source.getFirstSpellAbility();
        var controller = (PlayerControllerAi) ai.getController();
        var prepared = controller.getAi().prepareCombatActionCandidates(List.of(original), 4);
        String outcome = "NO_PREPARED_ACTION";
        if (!prepared.actions().isEmpty()) {
            var action = prepared.actions().get(0);
            Assert.assertEquals(action.spellAbility().getTargets().size(), 1);
            Assert.assertTrue(controller.getAi().revalidatePreparedAction(action));
            Assert.assertTrue(ComputerUtil.playStack(action.spellAbility(), ai, game));
            game.getStack().resolveStack();
            outcome = "EXECUTED P=" + blocker.getNetPower() + " T=" + blocker.getNetToughness()
                    + " firstStrike=" + blocker.hasKeyword(Keyword.FIRST_STRIKE) + " keywords=" + blocker.getKeywords();
            if (name.equals("Hundred-Talon Strike")) Assert.assertTrue(blocker.hasKeyword(Keyword.FIRST_STRIKE));
        }
        String result = name + " | " + outcome + " | " + original.getMapParams() + " | cost="
                + original.getPayCosts().getCostMana().getMana() + " | policy="
                + ExternalAiAbilitySupport.status(original, AiDecisionType.COMBAT_ACTION) + "\n";
        System.out.print(result);
        String path = System.getProperty("forge.ai.stage16_2.probe");
        if (path != null) Files.writeString(Path.of(path), result, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
