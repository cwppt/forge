package forge.ai.decision;

import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Optional simulation-only observation. Never changes or suppresses an AI decision or exception. */
public final class SimulationFailureDiagnostics {
    // Evaluation workers inherit their own run state, so a late timed-out worker cannot poison the next game.
    private static final InheritableThreadLocal<State> CURRENT = new InheritableThreadLocal<>();

    private SimulationFailureDiagnostics() { }

    public record Exposure(int playerSeat, String playerIdentity, String deckIdentifier,
                           String card, String api, int firstTurn) { }

    public record Failure(String category, String message, String stage, Integer playerSeat,
                          String playerIdentity, String deckIdentifier, String card, String api,
                          int turnNumber, String stackTrace) { }

    public record Snapshot(List<Failure> failures, List<Exposure> candidateExposure) {
        public static Snapshot empty() { return new Snapshot(List.of(), List.of()); }
    }

    private static final class State {
        final Game game;
        final boolean collectExposure;
        final List<Failure> failures = new ArrayList<>();
        final LinkedHashMap<String, Exposure> exposure = new LinkedHashMap<>();
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        State(Game game, boolean collectExposure) { this.game = game; this.collectExposure = collectExposure; }
    }

    public static void begin(Game game) { begin(game, true); }
    public static void begin(Game game, boolean collectExposure) { CURRENT.set(new State(game, collectExposure)); }

    public static Snapshot finish() {
        State state = CURRENT.get();
        CURRENT.remove();
        if (state == null) return Snapshot.empty();
        synchronized (state) {
            return new Snapshot(List.copyOf(state.failures), List.copyOf(state.exposure.values()));
        }
    }

    public static void observe(Player player, SpellAbility ability) {
        State state = CURRENT.get();
        if (state == null || !state.collectExposure || player == null || ability == null || ability.getHostCard() == null) return;
        int seat = player.getGame().getRegisteredPlayers().indexOf(player);
        String card = ability.getHostCard().getName();
        synchronized (state) {
            state.exposure.putIfAbsent(seat + ":" + card, new Exposure(seat, player.getName(),
                    player.getRegisteredPlayer().getDeck().getName(), card,
                    ability.getApi() == null ? null : ability.getApi().name(),
                    state.game.getPhaseHandler().getTurn()));
        }
    }

    public static void capture(Throwable error, Player player, SpellAbility ability, String stage) {
        State state = CURRENT.get();
        if (state == null) return;
        Throwable root = error;
        Set<Throwable> chain = Collections.newSetFromMap(new IdentityHashMap<>());
        while (root.getCause() != null && chain.add(root)) root = root.getCause();
        synchronized (state) {
            if (!state.seen.add(root)) return;
            StringWriter stack = new StringWriter();
            error.printStackTrace(new PrintWriter(stack));
            state.failures.add(new Failure(root.getClass().getName(), root.getMessage(), stage,
                    player == null ? null : player.getGame().getRegisteredPlayers().indexOf(player),
                    player == null ? null : player.getName(),
                    player == null ? null : player.getRegisteredPlayer().getDeck().getName(),
                    ability == null || ability.getHostCard() == null ? null : ability.getHostCard().getName(),
                    ability == null || ability.getApi() == null ? null : ability.getApi().name(),
                    state.game.getPhaseHandler().getTurn(), stack.toString()));
        }
    }
}
