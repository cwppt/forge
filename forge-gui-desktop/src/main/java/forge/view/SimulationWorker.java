package forge.view;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Fail-closed boundary for one process-isolated simulation. Never reads a failed game's state. */
public final class SimulationWorker {
    private SimulationWorker() { }

    public static void run(Runnable game, long seconds, Path evidence, int gameId, int index, Long seed) {
        try {
            TimeLimitedCodeBlock.runWithTimeout(game, seconds, TimeUnit.SECONDS);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Simulation caller interrupted");
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            String status = cause instanceof TimeoutException ? "TIMEOUT"
                    : cause instanceof InterruptedException || Thread.currentThread().isInterrupted() ? "INTERRUPTED"
                    : error instanceof TimeLimitedCodeBlock.UnstoppedTaskException ? "UNSTOPPED_WORKER" : "ENGINE_FAILED";
            abort(status, error, evidence, gameId, index, seed);
        }
    }

    public static void abort(String status, Throwable error, Path evidence, int gameId, int index, Long seed) {
        // FileChannel is interruptible. Preserve the interrupt as data, then clear it so the
        // failure journal can be forced to disk before this process is halted.
        boolean callerInterrupted = Thread.interrupted();
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        String row = "{\"schemaVersion\":1,\"gameId\":" + gameId + ",\"gameIndex\":" + index
                + ",\"runSeed\":" + seed + ",\"callerInterrupted\":" + callerInterrupted + ",\"valid\":false,\"simulationStatus\":\"" + escape(status)
                + "\",\"winner\":null,\"draw\":null,\"stackTrace\":\"" + escape(trace.toString()) + "\"}\n";
        // Persist only failure metadata from immutable identifiers. Do not inspect or finalize the Game.
        System.err.print("SIMULATION_FAILURE " + row);
        try {
            if (evidence != null) {
                if (evidence.toAbsolutePath().getParent() != null)
                    java.nio.file.Files.createDirectories(evidence.toAbsolutePath().getParent());
                try (FileChannel channel = FileChannel.open(evidence, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    ByteBuffer bytes = StandardCharsets.UTF_8.encode(row);
                    while (bytes.hasRemaining()) channel.write(bytes);
                    channel.force(true);
                }
            }
        } catch (Throwable writeFailure) { writeFailure.printStackTrace(System.err); }
        System.out.flush();
        System.err.flush();
        // Exit hooks/finalizers must not race with an uncooperative game or evaluation thread.
        Runtime.getRuntime().halt(status.equals("INTERRUPTED") ? 130 : status.equals("TIMEOUT") ? 124 : 70);
        throw new AssertionError("Runtime.halt returned");
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c < 32) result.append(String.format("\\u%04x", (int)c));
            else result.append(c);
        }
        return result.toString();
    }
}
