package forge.ai;

import forge.ai.decision.AiDecisionMetrics;
import forge.ai.decision.AiDecisionType;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ExternalCombatDiagnosticIdsTest extends AITest {
    @Test public void allSurfaceIdsAreRepeatableIndependentAndUnique() {
        String previous = System.getProperty(ExternalCombatDiagnosticIds.ALL_SURFACES_PROPERTY);
        try {
            System.setProperty(ExternalCombatDiagnosticIds.ALL_SURFACES_PROPERTY, "true");
            AiDecisionMetrics.reset();
            var a = initAndCreateGame(); var b = initAndCreateGame();
            var pa = a.getPlayers().get(0); var pb = b.getPlayers().get(0);
            AiDecisionMetrics.registerGame(a.getId(), 0, 102L);
            AiDecisionMetrics.registerGame(b.getId(), 0, 102L);
            String attackA = ExternalCombatDiagnosticIds.next(pa, AiDecisionType.COMBAT_ATTACKERS, "fingerprint", "ordinary");
            String attackRepeat = ExternalCombatDiagnosticIds.next(pa, AiDecisionType.COMBAT_ATTACKERS, "fingerprint", "ordinary");
            String blockA = ExternalCombatDiagnosticIds.next(pa, AiDecisionType.COMBAT_BLOCKERS, "fingerprint", "ordinary");
            String damageA = ExternalCombatDiagnosticIds.next(pa, AiDecisionType.COMBAT_DAMAGE_ORDER, "fingerprint", "ordinary");
            // Reverse surface call order: counters must remain independent.
            Assert.assertEquals(ExternalCombatDiagnosticIds.next(pb, AiDecisionType.COMBAT_DAMAGE_ORDER, "fingerprint", "ordinary"), damageA);
            Assert.assertEquals(ExternalCombatDiagnosticIds.next(pb, AiDecisionType.COMBAT_BLOCKERS, "fingerprint", "ordinary"), blockA);
            Assert.assertEquals(ExternalCombatDiagnosticIds.next(pb, AiDecisionType.COMBAT_ATTACKERS, "fingerprint", "ordinary"), attackA);
            Assert.assertNotEquals(attackRepeat, attackA);
            Assert.assertEquals(java.util.Set.of(attackA, blockA, damageA).size(), 3);
            Assert.assertEquals(ExternalCombatDiagnosticIds.next(pa, AiDecisionType.MAIN_PHASE_ACTION, "fingerprint", "ordinary"), "ordinary");
            System.clearProperty(ExternalCombatDiagnosticIds.ALL_SURFACES_PROPERTY);
            Assert.assertEquals(ExternalCombatDiagnosticIds.next(pa, AiDecisionType.COMBAT_ATTACKERS, "fingerprint", "ordinary"), "ordinary");
            System.setProperty(ExternalCombatDiagnosticIds.ALL_SURFACES_PROPERTY, "true");
            AiDecisionMetrics.reset();
            Assert.assertEquals(ExternalCombatDiagnosticIds.next(pa, AiDecisionType.COMBAT_DAMAGE_ORDER, "fingerprint", "ordinary"), "ordinary");
        } finally {
            if (previous == null) System.clearProperty(ExternalCombatDiagnosticIds.ALL_SURFACES_PROPERTY);
            else System.setProperty(ExternalCombatDiagnosticIds.ALL_SURFACES_PROPERTY, previous);
            AiDecisionMetrics.reset();
        }
    }
}
