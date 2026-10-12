package forge.view;

import forge.ai.AITest;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.*;

public class FullGameConfigurationTest extends AITest {
    @Test public void cliPresetEnablesExactlySevenSurfacesAndDisablesGuards() {
        var prefs = FModel.getPreferences(); Map<FPref,String> saved = new EnumMap<>(FPref.class);
        ForgePreferences.EXTERNAL_STABLE_SURFACES.forEach(p -> saved.put(p, prefs.getPref(p)));
        saved.put(FPref.AI_EXTERNAL_FULL_GAME_ENABLED,prefs.getPref(FPref.AI_EXTERNAL_FULL_GAME_ENABLED));
        String guard = System.getProperty("forge.ai.external.combat.materialGuard");
        String race = System.getProperty("forge.ai.external.combat.materialGuardRaceAware");
        try {
            System.setProperty("forge.ai.external.combat.materialGuard","true");
            System.setProperty("forge.ai.external.combat.materialGuardRaceAware","true");
            SimulateMatch.configureExternalAi(Map.of("external-ai-full-game-enabled", List.of()));
            Assert.assertEquals(ForgePreferences.EXTERNAL_STABLE_SURFACES.size(),7);
            ForgePreferences.EXTERNAL_STABLE_SURFACES.forEach(p -> Assert.assertTrue(prefs.externalSurfaceEnabled(p),p.name()));
            Assert.assertEquals(System.getProperty("forge.ai.external.combat.materialGuard"),"false");
            Assert.assertEquals(System.getProperty("forge.ai.external.combat.materialGuardRaceAware"),"false");
            SimulateMatch.configureExternalAi(Map.of("external-ai-full-game-enabled",List.of(),"external-ai-combat-action-disabled",List.of()));
            Assert.assertFalse(prefs.externalSurfaceEnabled(FPref.AI_EXTERNAL_COMBAT_ACTION_ENABLED));
            Assert.assertTrue(prefs.externalSurfaceEnabled(FPref.AI_EXTERNAL_MAIN_PHASE_ENABLED));
        } finally {
            saved.forEach(prefs::setPref);
            if(guard==null)System.clearProperty("forge.ai.external.combat.materialGuard"); else System.setProperty("forge.ai.external.combat.materialGuard",guard);
            if(race==null)System.clearProperty("forge.ai.external.combat.materialGuardRaceAware"); else System.setProperty("forge.ai.external.combat.materialGuardRaceAware",race);
        }
    }
    @Test public void preferencePresetRespectsExplicitIndividualsAndMaxima() {
        var prefs = new ForgePreferences(); prefs.reset();
        Assert.assertFalse(prefs.getPrefBoolean(FPref.AI_EXTERNAL_FULL_GAME_ENABLED));
        String cap = prefs.getPref(FPref.AI_EXTERNAL_COMBAT_ACTION_MAX_ACTIONS);
        prefs.setPref(FPref.AI_EXTERNAL_FULL_GAME_ENABLED,true);
        ForgePreferences.EXTERNAL_STABLE_SURFACES.forEach(p -> Assert.assertTrue(prefs.externalSurfaceEnabled(p)));
        prefs.setPref(FPref.AI_EXTERNAL_COMBAT_BLOCKERS_ENABLED,false);
        Assert.assertFalse(prefs.externalSurfaceEnabled(FPref.AI_EXTERNAL_COMBAT_BLOCKERS_ENABLED));
        Assert.assertEquals(prefs.getPref(FPref.AI_EXTERNAL_COMBAT_ACTION_MAX_ACTIONS), cap);
        prefs.setPref(FPref.AI_EXTERNAL_FULL_GAME_ENABLED,false);
        prefs.setPref(FPref.AI_EXTERNAL_COMBAT_ACTION_ENABLED,true);
        Assert.assertTrue(prefs.externalSurfaceEnabled(FPref.AI_EXTERNAL_COMBAT_ACTION_ENABLED));
        Assert.assertFalse(prefs.externalSurfaceEnabled(FPref.AI_EXTERNAL_MAIN_PHASE_ENABLED));
    }
}
