package forge.ai;
import forge.game.ability.AbilityFactory;
import forge.game.phase.PhaseType;
import forge.util.MyRandom;
import java.util.*;
import org.testng.Assert;
import org.testng.annotations.Test;
/** Controlled native choice, not a reconstruction of seed 573307's hidden state. */
public final class AtsushiChoiceFixture extends AITest {
    @Test public void budgetObserverDoesNotAdvanceRng(){
        var g=initAndCreateGame();var p=g.getPlayers().get(0);g.getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN,p);
        MyRandom.setRandom(new Random(125));
        try(var budget=AiEvaluationDiagnostics.begin(p,0)){AiEvaluationDiagnostics.prediction("fixtureObservation");}
        Assert.assertEquals(MyRandom.getRandom().nextInt(),new Random(125).nextInt(),"Budget observer must not draw or reseed");
    }
    @Test public void repeatableNativeChoiceWithFixedRngAndState(){
        for(int index=0;index<16;index++){
            int seed=index*4096;
            String previous=null;
            for(int repeat=0;repeat<2;repeat++){
                var g=initAndCreateGame();var p=g.getPlayers().get(0);var card=addCard("Atsushi, the Blazing Sky",p);
                g.getAction().moveToLibrary(addCard("Mountain",p),null);g.getAction().moveToLibrary(addCard("Mountain",p),null);
                var sa=AbilityFactory.getAbility(card.getSVar("TrigCharm"),card);sa.setActivatingPlayer(p);sa.setTrigger(card.getTriggers().get(0));
                g.getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE,g.getPlayers().get(1));
                MyRandom.setRandom(new Random(seed));
                var choices=forge.game.ability.effects.CharmEffect.makePossibleOptions(sa);
                var chosen=p.getController().chooseModeForAbility(sa,choices,1,1,false);
                Assert.assertNotNull(chosen);Assert.assertEquals(chosen.size(),1);
                String mode=chosen.get(0).getApi().name();Assert.assertTrue(mode.equals("Dig")||mode.equals("Token"));
                if(previous!=null)Assert.assertEquals(mode,previous,"Fixed native state and RNG must repeat");previous=mode;
                System.out.println("ATSUSHI_FIXTURE "+new com.google.gson.Gson().toJson(Map.of("seed",seed,"repeat",repeat,"mode",mode)));
            }
        }
    }
}
