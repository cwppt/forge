package forge.view;

import forge.ai.AITest;
import forge.game.GameEndReason;
import forge.game.spellability.SpellAbility;
import java.nio.file.Files;
import java.nio.file.Path;

/** Launched in a disposable JVM by test_integrity.py; halt must never kill the test runner. */
public final class SimulationWorkerProbe extends AITest {
    private static void mutate(Path path) {
        while (true) {
            try { Files.writeString(path, "tick\n", java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND); Thread.sleep(20); }
            catch (InterruptedException ignored) { /* deliberately uncooperative regression fixture */ }
            catch (Exception e) { throw new RuntimeException(e); }
        }
    }
    public static void main(String[] args) throws Exception {
        String mode=args[0]; Path dir=Path.of(args[1]);
        Runnable work=()->{};
        if (mode.equals("uncooperative")) work=()->mutate(dir.resolve("mutations"));
        if (mode.equals("timeout")) work=()->{try {Thread.sleep(60000);} catch(InterruptedException ignored){}};
        if (mode.equals("engine")) work=()->{throw new IllegalStateException("engine failure evidence");};
        if (mode.equals("stack") || mode.equals("draw")) {
            var fixture=new SimulationWorkerProbe();fixture.initializeModel();var game=fixture.initAndCreateGame();
            if (mode.equals("draw")) work=()->{new forge.game.ability.effects.GameDrawEffect().resolve(
                    new SpellAbility.EmptySa(fixture.addCard("Divine Intervention",game.getPlayers().get(0))));
                if (!game.isGameOver() || !game.getOutcome().isDraw()) throw new AssertionError("Natural draw");};
            else {
                var host=fixture.addCard("Shivan Dragon",game.getPlayers().get(0));
                var ability=new SpellAbility.EmptySa(host) {
                    @Override public void resolve() {
                        try {Files.writeString(dir.resolve("resolution-entered"),"true");}
                        catch(Exception e){throw new RuntimeException(e);}
                        mutate(dir.resolve("mutations"));
                    }
                };
                work=()->{game.getStack().add(ability);game.getStack().resolveStack();};
            }
        }
        if (mode.equals("interrupted")) Thread.currentThread().interrupt();
        SimulationWorker.run(work,1,dir.resolve("failure.jsonl"),1,0,123L);
        Files.writeString(dir.resolve("final-outcome"),mode);
        System.exit(0);
    }
}
