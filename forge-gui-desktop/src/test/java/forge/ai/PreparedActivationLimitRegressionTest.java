package forge.ai;

import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.List;

public class PreparedActivationLimitRegressionTest extends AITest {
    private Player ai(Game game) {
        Player ai = game.getPlayers().get(1);
        ai.setTeam(0);
        game.getPlayers().get(0).setTeam(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        return ai;
    }
    private AiController controller(Player ai) {
        return ((PlayerControllerAi) ai.getController()).getAi();
    }
    private SpellAbility draw(Player ai, String limit, String value) {
        addCards("Island", 8, ai);
        for (int i=0; i<8; i++) addCardToZone("Island", ai, ZoneType.Library);
        Card sailor = addCard("Spectral Sailor", ai);
        // Synthetic ability keeps the test independent of Sailor's end-step AI heuristic.
        SpellAbility original = AbilityFactory.getAbility("AB$ Draw | Cost$ 0 | NumCards$ 1 | Defined$ You"
                + (limit == null ? "" : " | " + limit + "$ " + value), sailor);
        original.setActivatingPlayer(ai);
        return original;
    }
    private SpellAbility prepared(Player ai, SpellAbility original) {
        List<PreparedAiAction> actions=controller(ai).prepareSpellAbilityActions(List.of(original),false);
        Assert.assertEquals(actions.size(),1,"fixture must produce a playable prepared action");
        return actions.get(0).spellAbility();
    }
    @Test public void executedPreparedActivationUpdatesSourceGameLimit() {
        Game game=initAndCreateGame();Player ai=ai(game);
        SpellAbility original=draw(ai,"GameActivationLimit","1");
        SpellAbility first=prepared(ai,original);
        Assert.assertNotSame(first,original);
        game.getStack().add(first);
        Assert.assertEquals(original.getActivationsThisGame(),1,"live source must see prepared activation");
        Assert.assertFalse(original.canPlay());
    }
    @Test public void secondPreparedActivationCannotBypassGameLimit() {
        Game game=initAndCreateGame();Player ai=ai(game);
        SpellAbility original=draw(ai,"GameActivationLimit","1");
        original.getHostCard().addAbilityActivated(prepared(ai,original));
        Assert.assertTrue(controller(ai).prepareSpellAbilityActions(List.of(original),false).isEmpty());
    }
    @Test public void perTurnLimitAlsoCountsPreparedActivationsAndResetsNormally() {
        Game game=initAndCreateGame();Player ai=ai(game);
        SpellAbility original=draw(ai,"ActivationLimit","1");
        original.getHostCard().addAbilityActivated(prepared(ai,original));
        Assert.assertEquals(original.getActivationsThisTurn(),1);
        Assert.assertFalse(original.canPlay());
        original.getHostCard().resetActivationsPerTurn();
        Assert.assertTrue(original.canPlay());
    }
    @Test public void unrestrictedAbilityRemainsUsableAfterActivation() {
        Game game=initAndCreateGame();Player ai=ai(game);
        SpellAbility original=draw(ai,null,null);
        original.getHostCard().addAbilityActivated(prepared(ai,original));
        Assert.assertTrue(original.checkRestrictions(ai));
        Assert.assertEquals(controller(ai).prepareSpellAbilityActions(List.of(original),false).size(),1);
    }
    @Test public void stalePreparedActivationFailsNormalLegalityRevalidation() throws ReflectiveOperationException {
        Game game=initAndCreateGame();Player ai=ai(game);
        SpellAbility original=draw(ai,"GameActivationLimit","1");
        PreparedAiAction stale=controller(ai).prepareSpellAbilityActions(List.of(original),false).get(0);
        original.getHostCard().addAbilityActivated(prepared(ai,original));
        var method=AiController.class.getDeclaredMethod("revalidatePreparedAction",PreparedAiAction.class);
        method.setAccessible(true);
        Assert.assertEquals(method.invoke(controller(ai),stale),false);
    }
    @Test public void activationHistoryDoesNotLeakBetweenDistinctSources() {
        Game game=initAndCreateGame();Player ai=ai(game);
        SpellAbility first=draw(ai,"GameActivationLimit","1");
        SpellAbility other=draw(ai,"GameActivationLimit","1");
        first.getHostCard().addAbilityActivated(prepared(ai,first));
        Assert.assertEquals(other.getActivationsThisGame(),0);
        Assert.assertTrue(other.canPlay());
        Assert.assertNotNull(prepared(ai,other));
    }
    @Test public void guardianRevealCostFallbackPreservesOnceOnlySourceIdentity() throws ReflectiveOperationException {
        Game game=initAndCreateGame();Player ai=ai(game);Player opponent=game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS,ai);
        Card guardian=addCard("Guardian Archon",ai);
        guardian.setChosenPlayer(opponent);
        guardian.setTurnInController(ai);
        fillLibrary(ai,20);
        // Preserve Guardian's actual reveal cost and once-only restriction; a deliberately
        // unmet heuristic criterion exercises the legal bounded-fallback preparation path.
        SpellAbility original=AbilityFactory.getAbility("AB$ Draw | Cost$ RevealChosen<Player> | Defined$ You | NumCards$ 1 | GameActivationLimit$ 1 | AICheckSVar$ 0 | AISVarCompare$ GE1",guardian);
        original.setActivatingPlayer(ai);
        // The heuristic criterion rejects this state, but the bounded fallback may legally prepare it.
        var method=AiController.class.getDeclaredMethod("prepareStrategicallyRejectedAction",SpellAbility.class,boolean.class,int.class);
        method.setAccessible(true);
        PreparedAiAction action=(PreparedAiAction)method.invoke(controller(ai),original,false,0);
        Assert.assertNotNull(action,"fixture must produce a legal fallback action");
        SpellAbility first=action.spellAbility();
        guardian.addAbilityActivated(first);
        Assert.assertEquals(original.getActivationsThisGame(),1);
        Assert.assertFalse(original.canPlay());
    }
}
