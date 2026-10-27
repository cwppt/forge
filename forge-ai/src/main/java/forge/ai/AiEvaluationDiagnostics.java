package forge.ai;

import com.google.gson.Gson;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/** Request-local diagnostics and an optional budget for advisory combat predictions. */
public final class AiEvaluationDiagnostics implements AutoCloseable {
    private static final ThreadLocal<AiEvaluationDiagnostics> CURRENT = new ThreadLocal<>();
    private static final AtomicLong IDS = new AtomicLong();
    private static final Gson JSON = new Gson();
    private final long start = System.nanoTime();
    private final long budgetMs = Long.getLong("forge.ai.evalBudgetMs", 0L);
    private final long workLimit = Long.getLong("forge.ai.evalWorkLimit", 0L);
    private final Map<String,Object> data = new LinkedHashMap<>();
    private final Map<String,Long> counts = new LinkedHashMap<>();
    private long work;
    private long fallbackCalls;
    private int advisoryDepth;
    private long predictionAborts;
    private long candidateStart;
    private String candidate;
    private String candidateApi;
    private double longestCandidateMs;
    private String longestCandidate;

    private AiEvaluationDiagnostics(Player player, int candidates) {
        data.put("id",IDS.incrementAndGet()); data.put("seat",player.getGame().getRegisteredPlayers().indexOf(player));
        data.put("turn",player.getGame().getPhaseHandler().getTurn());
        data.put("phase",player.getGame().getPhaseHandler().getPhase().name());
        data.put("candidates",candidates); data.put("battlefield",player.getGame().getCardsIn(forge.game.zone.ZoneType.Battlefield).size());
        data.put("budgetMs",budgetMs); data.put("workLimit",workLimit);
    }
    public static AiEvaluationDiagnostics begin(Player player, int candidates) {
        if (!Boolean.getBoolean("forge.ai.evalDiagnostics") && Long.getLong("forge.ai.evalBudgetMs",0L)<=0
                && Long.getLong("forge.ai.evalWorkLimit",0L)<=0) return null;
        AiEvaluationDiagnostics d=new AiEvaluationDiagnostics(player,candidates); CURRENT.set(d); return d;
    }
    /** Bound advisory predictions outside the decision worker, without resetting an enclosing budget. */
    public static AiEvaluationDiagnostics beginIfAbsent(Player player, int candidates, String context) {
        if (CURRENT.get() != null) return null;
        AiEvaluationDiagnostics diagnostics = begin(player, candidates);
        if (diagnostics != null) {
            diagnostics.data.put("context", context);
            diagnostics.result("ADVISORY_PREDICTION");
        }
        return diagnostics;
    }
    public static boolean prediction(String stage) {
        AiEvaluationDiagnostics d=CURRENT.get(); if(d==null) return false;
        d.counts.merge(stage,1L,Long::sum); d.work++;
        if ((d.workLimit>0 && d.work>d.workLimit) || (d.budgetMs>0 && System.nanoTime()-d.start>=d.budgetMs*1_000_000L)) {
            d.fallbackCalls++;
            if (d.advisoryDepth > 0) throw PredictionBudgetExceeded.INSTANCE;
            return true;
        }
        return false;
    }
    /** Discard an unfinished hypothetical search; never used around actual rules execution. */
    public static <T> T boundedPrediction(Supplier<T> prediction, T conservativeFallback) {
        AiEvaluationDiagnostics diagnostics = CURRENT.get();
        if (diagnostics == null) return prediction.get();
        diagnostics.advisoryDepth++;
        try {
            return prediction.get();
        } catch (PredictionBudgetExceeded exhausted) {
            diagnostics.predictionAborts++;
            return conservativeFallback;
        } finally {
            diagnostics.advisoryDepth--;
        }
    }
    /** Check expensive mock-search operations without approximating actual combat or legality checks. */
    public static void advisoryCheckpoint(String stage) {
        AiEvaluationDiagnostics diagnostics = CURRENT.get();
        if (diagnostics != null && diagnostics.advisoryDepth > 0) prediction(stage);
    }
    private static final class PredictionBudgetExceeded extends RuntimeException {
        private static final PredictionBudgetExceeded INSTANCE = new PredictionBudgetExceeded();
        private PredictionBudgetExceeded() { super(null, null, false, false); }
    }
    public static boolean usedFallback() {
        AiEvaluationDiagnostics d=CURRENT.get(); return d!=null && d.fallbackCalls>0;
    }
    public static void cancelAndAwait(Future<?> future, Thread worker, long waitMs, Throwable cause) {
        future.cancel(true);
        try {
            worker.join(waitMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting AI evaluation cancellation",ex);
        }
        if(worker.isAlive()) throw new IllegalStateException("AI evaluation remained active after cancellation",cause);
    }
    public static void candidateStarted(SpellAbility sa) {
        AiEvaluationDiagnostics d=CURRENT.get(); if(d==null) return;
        d.candidate=sa.getHostCard().getName(); d.candidateApi=sa.getApi()==null?"none":sa.getApi().name(); d.candidateStart=System.nanoTime();
        if(Boolean.getBoolean("forge.ai.evalVerbose")) {
            System.out.println("AI_EVAL_START "+JSON.toJson(Map.of("id",d.data.get("id"),"card",d.candidate,"api",d.candidateApi)));
        }
    }
    public static void candidateFinished(String opinion) {
        AiEvaluationDiagnostics d=CURRENT.get(); if(d==null) return;
        double ms=(System.nanoTime()-d.candidateStart)/1_000_000.0;
        if(ms>d.longestCandidateMs){d.longestCandidateMs=ms;d.longestCandidate=d.candidate;}
        if(ms>=100 || d.fallbackCalls>0) {
            Map<String,Object> event=new LinkedHashMap<>();event.put("id",d.data.get("id"));event.put("card",d.candidate);
            event.put("api",d.candidateApi);event.put("elapsedMs",ms);event.put("opinion",opinion);
            System.out.println("AI_EVAL_CANDIDATE "+JSON.toJson(event));
        }
    }
    public void result(String result) {data.put("result",result);}
    @Override public void close() {
        data.put("elapsedMs",(System.nanoTime()-start)/1_000_000.0);data.put("counts",counts);data.put("work",work);
        data.put("predictionAborts",predictionAborts);
        data.put("fallbackCalls",fallbackCalls);data.put("longestCandidate",longestCandidate);data.put("longestCandidateMs",longestCandidateMs);
        CURRENT.remove();System.out.println("AI_EVAL_DECISION "+JSON.toJson(data));
    }
}
