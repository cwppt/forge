package forge.ai;

import com.google.gson.Gson;
import forge.ai.decision.*;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import java.util.*;

/** Cross-surface assertions use fresh projections of the actual resolved engine state. */
public class FullGameProjectionConsistencyTest extends AITest {
    @BeforeMethod public void resetMetrics() { AiDecisionMetrics.reset(); }
    private void resolve(Game game, Player player, String cardName, Card target) {
        Card card = addCardToZone(cardName, player, ZoneType.Hand);
        var ability = card.getFirstSpellAbility(); ability.setActivatingPlayer(player);
        if (target != null) ability.getTargets().add(target);
        var controller = new PlayerControllerAi(game, player, player.getLobbyPlayer());
        player.dangerouslySetController(controller); controller.playChosenSpellAbility(ability);
        Assert.assertNotNull(game.getStack().peekAbility()); game.getStack().resolveStack();
    }
    @Test public void resolvedMainPhaseCreatureAppearsInAttackProjection() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1), enemy = game.getPlayers().get(0);
        addCards("Forest", 4, ai);
        resolve(game,ai,"Runeclaw Bear",null);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS,ai,false);
        Combat combat = new Combat(ai); game.getPhaseHandler().setCombat(combat);
        var projection = ExternalCombatAttackers.project(ai,combat);
        Assert.assertTrue(projection.state().players().stream().filter(p -> p.self()).flatMap(p -> p.battlefield().stream())
                .anyMatch(p -> p.card().name().equals("Runeclaw Bear")));
        Assert.assertTrue(MainPhaseStateProjector.project(ai,"after",List.of()).state().players().stream()
                .filter(p -> p.self()).flatMap(p -> p.battlefield().stream()).anyMatch(c -> c.name().equals("Runeclaw Bear")));
    }
    @Test public void resolvedPreblockRemovalChangesBlockerAndLaterMainProjection() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1), enemy = game.getPlayers().get(0);
        Card attacker = addCard("Hill Giant",ai), blocker = addCard("Runeclaw Bear",enemy);
        addCard("Grizzly Bears",enemy); addCards("Swamp",4,ai); attacker.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS,ai,false);
        Combat combat = new Combat(ai); game.getPhaseHandler().setCombat(combat); combat.addAttacker(attacker,enemy);
        game.getPhaseHandler().setPriority(ai);
        Assert.assertEquals(ExternalCombatActions.state(ai,combat,List.of()).combatants().get(0).name(),"Hill Giant");
        resolve(game,ai,"Doom Blade",blocker);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS,ai,false);
        var prepared = ExternalCombatBlockers.prepare(enemy,combat,"block",4);
        Assert.assertNotNull(prepared);
        String projection = new Gson().toJson(prepared.context());
        Assert.assertFalse(projection.contains("Runeclaw Bear")); Assert.assertTrue(projection.contains("Grizzly Bears"));
        Assert.assertTrue(MainPhaseStateProjector.project(ai,"later",List.of()).state().players().stream()
                .filter(p -> !p.self()).flatMap(p -> p.graveyard().stream()).anyMatch(c -> c.name().equals("Runeclaw Bear")));
    }
    @Test public void postblockPumpIsReflectedInCombatActionAndDamageOrder() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1), enemy = game.getPlayers().get(0);
        game.getRules().setOrderCombatants(true);
        Card attacker=addCard("Hill Giant",enemy), first=addCard("Runeclaw Bear",ai),second=addCard("Grizzly Bears",ai);
        addCards("Forest",4,ai);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS,enemy,false);
        Combat combat = new Combat(enemy); game.getPhaseHandler().setCombat(combat);
        combat.addAttacker(attacker,ai); combat.addBlocker(attacker,first); combat.addBlocker(attacker,second); combat.setBlocked(attacker,true);
        game.getPhaseHandler().setPriority(ai);
        var before = ExternalCombatActions.state(ai,combat,List.of());
        Assert.assertEquals(before.combatants().stream().filter(p -> p.name().equals("Runeclaw Bear")).findFirst().orElseThrow().blocking().size(),1);
        resolve(game,ai,"Giant Growth",first);
        var after = ExternalCombatActions.state(ai,combat,List.of());
        Assert.assertEquals(after.combatants().stream().filter(p -> p.name().equals("Runeclaw Bear")).findFirst().orElseThrow().power(),Integer.valueOf(5));
        var damage = ExternalCombatDamageOrder.prepare(enemy,combat,attacker,List.of(first,second),true,"damage",6);
        Assert.assertNotNull(damage);
        Assert.assertTrue(damage.context().state().recipients().stream().anyMatch(c -> c.name().equals("Runeclaw Bear") && c.power()==5));
    }
    @Test public void auditObserverDoesNotChangeProjectionOrRevealHiddenCards() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1), enemy = game.getPlayers().get(0);
        addCardToZone("Doom Blade",enemy,ZoneType.Hand);
        var before = MainPhaseStateProjector.project(ai,"same",List.of());
        FullGameExternalAiAudit.observe(game);
        var after = MainPhaseStateProjector.project(ai,"same",List.of());
        Assert.assertEquals(after,before); Assert.assertFalse(new Gson().toJson(after).contains("Doom Blade"));
        Assert.assertFalse(new Gson().toJson(after).contains("externalAiGameId"));
    }
}
