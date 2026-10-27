package forge.ai;

import forge.ai.decision.SimulationFailureDiagnostics;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

public class SimulationFailureDiagnosticsTest extends AITest {
    @Test
    public void disabledDiagnosticsDoNotCollect() {
        SimulationFailureDiagnostics.finish();
        SimulationFailureDiagnostics.capture(new IllegalStateException("ignored"), null, null, "test");
        Assert.assertTrue(SimulationFailureDiagnostics.finish().failures().isEmpty());
    }

    @Test
    public void inheritedWorkerCapturesExactCardAndDeduplicatesWrapper() throws Exception {
        Game game = initAndCreateGame();
        Player player = game.getPlayers().get(1);
        Card card = addCardToZone("Guardian Archon", player, ZoneType.Battlefield);
        var ability = card.getSpellAbilities().stream().filter(sa -> sa.getApi() != null).findFirst().orElseThrow();
        SimulationFailureDiagnostics.begin(game);
        try {
            NumberFormatException original = new NumberFormatException("ChosenPlayerUID");
            Thread worker = new Thread(() -> {
                SimulationFailureDiagnostics.observe(player, ability);
                SimulationFailureDiagnostics.observe(player, ability);
                SimulationFailureDiagnostics.capture(original, player, ability, "ability-evaluation");
                SimulationFailureDiagnostics.capture(new ExecutionException(original), player, null, "outer");
            });
            worker.start(); worker.join();
            var snapshot = SimulationFailureDiagnostics.finish();
            Assert.assertEquals(snapshot.failures().size(), 1);
            Assert.assertEquals(snapshot.candidateExposure().size(), 1);
            var failure = snapshot.failures().get(0);
            Assert.assertEquals(failure.card(), "Guardian Archon");
            Assert.assertEquals(failure.playerSeat().intValue(), 1);
            Assert.assertEquals(failure.category(), "java.lang.NumberFormatException");
            Assert.assertEquals(failure.message(), "ChosenPlayerUID");
            Assert.assertTrue(failure.stackTrace().contains("NumberFormatException"));
        } finally { SimulationFailureDiagnostics.finish(); }
    }

    @Test
    public void lateWorkerCannotContaminateNextGame() throws Exception {
        Game first = initAndCreateGame();
        SimulationFailureDiagnostics.begin(first);
        AtomicReference<Thread> late = new AtomicReference<>();
        late.set(new Thread(() -> SimulationFailureDiagnostics.capture(
                new IllegalStateException("late failure"), null, null, "old-worker")));
        Assert.assertTrue(SimulationFailureDiagnostics.finish().failures().isEmpty());
        SimulationFailureDiagnostics.begin(initAndCreateGame());
        try {
            late.get().start(); late.get().join();
            Assert.assertTrue(SimulationFailureDiagnostics.finish().failures().isEmpty());
        } finally { SimulationFailureDiagnostics.finish(); }
    }
}
