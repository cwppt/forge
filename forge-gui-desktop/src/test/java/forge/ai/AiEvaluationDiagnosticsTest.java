package forge.ai;

import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.Random;
import java.util.concurrent.FutureTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class AiEvaluationDiagnosticsTest extends AITest {
    @Test public void cooperativeCancellationFinishesBeforeReturning() throws Exception {
        CountDownLatch started=new CountDownLatch(1);
        FutureTask<Void> future=new FutureTask<>(()->{started.countDown();while(!Thread.currentThread().isInterrupted())Thread.onSpinWait();return null;});
        Thread worker=new Thread(future);worker.setDaemon(true);worker.start();started.await();
        AiEvaluationDiagnostics.cancelAndAwait(future,worker,1000,new RuntimeException("test"));
        Assert.assertFalse(worker.isAlive());Assert.assertTrue(future.isCancelled());
    }
    @Test public void uncooperativeCancellationAbortsInsteadOfContinuing() throws Exception {
        AtomicBoolean release=new AtomicBoolean();CountDownLatch started=new CountDownLatch(1);
        FutureTask<Void> future=new FutureTask<>(()->{started.countDown();while(!release.get())Thread.onSpinWait();return null;});
        Thread worker=new Thread(future);worker.setDaemon(true);worker.start();started.await();
        try {Assert.expectThrows(IllegalStateException.class,()->AiEvaluationDiagnostics.cancelAndAwait(future,worker,10,new RuntimeException("test")));}
        finally {release.set(true);worker.join(1000);}
        Assert.assertFalse(worker.isAlive());
    }
    @Test public void boundedPredictionDoesNotMutateGameOrRandomState() throws Exception {
        String previous=System.getProperty("forge.ai.evalWorkLimit");
        System.setProperty("forge.ai.evalWorkLimit","1");
        try {
            var game=initAndCreateGame();var player=game.getPlayers().get(1);
            var card=addCardToZone("Steel Hellkite",player,ZoneType.Hand);
            MyRandom.setRandom(new Random(741));
            try(var diagnostics=AiEvaluationDiagnostics.begin(player,1)) {
                Assert.assertFalse(AiEvaluationDiagnostics.prediction("first"));
                Assert.assertFalse(ComputerUtilCard.doesSpecifiedCreatureAttackAI(player,card));
                Assert.assertTrue(AiEvaluationDiagnostics.usedFallback());
                Assert.assertTrue(player.getCardsIn(ZoneType.Hand).contains(card));
                Assert.assertTrue(game.getStack().isEmpty());
                Assert.assertEquals(MyRandom.getRandom().nextLong(),new Random(741).nextLong());
                Thread other=new Thread(()->Assert.assertFalse(AiEvaluationDiagnostics.usedFallback()));
                other.start();other.join();
            }
            Assert.assertFalse(AiEvaluationDiagnostics.usedFallback());
        } finally {
            if(previous==null)System.clearProperty("forge.ai.evalWorkLimit");else System.setProperty("forge.ai.evalWorkLimit",previous);
        }
    }
    @Test public void disabledBudgetDoesNotApproximatePredictions() {
        Assert.assertFalse(AiEvaluationDiagnostics.prediction("no-active-evaluation"));
        Assert.assertFalse(AiEvaluationDiagnostics.usedFallback());
    }
}
