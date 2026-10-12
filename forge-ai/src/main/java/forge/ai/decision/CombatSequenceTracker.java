package forge.ai.decision;

import com.google.common.eventbus.Subscribe;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.event.*;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Observes live games only. No strategy, provider calls, prepared options, or engine mutations. */
final class CombatSequenceTracker {
    record Correlation(String combatSequenceId, int turnNumber, int activePlayerSeat, Integer defendingPlayerSeat) { }
    private static final Map<Integer, CombatSequenceTracker> TRACKERS = new ConcurrentHashMap<>();
    private static long generation;
    private final Game game;
    private final long installedGeneration;
    private int ordinal;
    private Sequence current;
    private boolean sequenceAuditEnabled;
    private boolean closed;

    private CombatSequenceTracker(Game game) { this.game = game; installedGeneration = generation; }
    static void observe(Game game) {
        install(game).sequenceAuditEnabled = true;
    }
    private static CombatSequenceTracker install(Game game) {
        return TRACKERS.computeIfAbsent(game.getId(), id -> {
            CombatSequenceTracker tracker = new CombatSequenceTracker(game);
            game.subscribeToEvents(tracker); return tracker;
        });
    }
    static Correlation correlation(Game game, Combat combat) {
        Sequence sequence = install(game).ensure(combat);
        return new Correlation(sequence.id, sequence.turn, sequence.activeSeat, sequence.defenderSeat);
    }
    static void decision(CombatDecisionEvent event) {
        CombatSequenceTracker tracker = TRACKERS.get(event.gameId());
        if (tracker == null || tracker.current == null) return;
        Sequence sequence = tracker.current;
        if (!sequence.id.equals(event.combatSequenceId())) {
            sequence.issues.add("DECISION_COMBAT_SEQUENCE_CHANGED"); return;
        }
        if (sequence.decisions.stream().anyMatch(d -> d.decisionId().equals(event.decisionId())))
            sequence.issues.add("REUSED_REQUEST_ID");
        sequence.decisions.add(new CombatSequenceAuditRecord.DecisionReference(event.decisionId(), event.decisionSurface(),
                event.playerSeat(), event.defendingPlayerSeat(), event.heuristicOptionId(), event.selectedOptionId(),
                event.responseAccepted(), event.fallback(), event.failureReason(), event.providerAttempted(), event.providerLatencyMs()));
        // Capture the actual applied declarations after each call, including independent Forge fallbacks.
        if (sequence.combat != null) sequence.captureDeclarations();
    }
    static void completeGame(int gameId) {
        CombatSequenceTracker tracker = TRACKERS.remove(gameId);
        if (tracker != null) {
            tracker.finish(false, "GAME_COMPLETED_BEFORE_COMBAT_END");
            tracker.closed = true;
        }
    }
    static void reset() { TRACKERS.clear(); generation++; }
    private boolean active() { return !closed && installedGeneration == generation; }
    private Sequence ensure(Combat combat) {
        if (current != null && current.combat != null && current.combat != combat) finish(false, "COMBAT_REPLACED");
        if (current == null) current = new Sequence(combat == null ? game.getPhaseHandler().getPlayerTurn() : combat.getAttackingPlayer());
        if (current.combat == null) current.combat = combat;
        return current;
    }

    @Subscribe public void phase(GameEventTurnPhase event) {
        if (!active()) return;
        if (event.phase() == PhaseType.COMBAT_BEGIN) {
            if (current != null) finish(false, "NEXT_COMBAT");
            // This event precedes allocation of Combat. Bind its live instance at declaration time.
            current = new Sequence(game.getPhaseHandler().getPlayerTurn());
        } else if (event.phase() == PhaseType.COMBAT_END) {
            finish(true, "COMBAT_DAMAGE_COMPLETE");
        } else if (current != null && !PhaseType.PHASE_GROUPS.get(2).contains(event.phase())) {
            // End-combat/end-turn effects can jump straight to main phase or cleanup.
            // Do not attribute subsequent life loss or deaths to that interrupted combat.
            finish(false, "COMBAT_PHASES_INTERRUPTED");
        }
    }
    @Subscribe public void attackers(GameEventAttackersDeclared event) {
        if (active() && game.getCombat() != null) {
            Sequence sequence = ensure(game.getCombat()); sequence.captureDeclarations(); sequence.attackersLocked = true;
        }
    }
    @Subscribe public void blockers(GameEventBlockersDeclared event) {
        if (active() && game.getCombat() != null) {
            Sequence sequence = ensure(game.getCombat()); sequence.captureDeclarations(); sequence.blockersLocked = true;
        }
    }
    @Subscribe public void damage(GameEventPlayerDamaged event) {
        if (!active() || current == null || !event.combat()) return;
        if (event.target().getId() == current.activePlayer.getId()) current.activeDamage += event.amount();
        else if (current.defender != null && event.target().getId() == current.defender.getId()) current.defenderDamage += event.amount();
    }
    @Subscribe public void life(GameEventPlayerLivesChanged event) {
        if (!active() || current == null) return;
        int lost = Math.max(0, event.oldLives() - event.newLives());
        if (event.player().getId() == current.activePlayer.getId()) current.activeLifeLost += lost;
        else if (current.defender != null && event.player().getId() == current.defender.getId()) current.defenderLifeLost += lost;
    }
    @Subscribe public void zone(GameEventCardChangeZone event) {
        if (!active() || current == null) return;
        if (event.from() != null && event.from().zoneType() == ZoneType.Battlefield) {
            Creature creature = current.battlefieldCreatures.remove(event.card().getId());
            if (event.to() != null && event.to().zoneType() == ZoneType.Graveyard
                    && (creature != null || event.card().getCurrentState().isCreature())) {
                String name = creature == null ? (event.card().isFaceDown() ? "Face-down creature" : event.card().getCurrentState().getName()) : creature.name();
                int seat = creature == null ? current.seatOf(event.from().player().getId()) : creature.seat();
                current.deaths.add(new CombatSequenceAuditRecord.Death(name, seat));
            }
        }
        if (event.to() != null && event.to().zoneType() == ZoneType.Battlefield) current.captureBattlefield();
    }
    @Subscribe public void combatEnded(GameEventCombatEnded event) { if (active()) finish(true, "COMBAT_ENDED"); }
    @Subscribe public void gameOutcome(GameEventGameOutcome event) {
        if (active()) finish(current != null && current.lethal(), "GAME_ENDED_DURING_COMBAT");
    }
    private void finish(boolean damageCompleted, String reason) {
        if (current == null) return;
        Sequence sequence = current; current = null;
        AiDecisionMetrics.recordCombatSequence(sequence.snapshot(damageCompleted, reason), sequenceAuditEnabled);
    }

    private record Creature(Card card, long timestamp, int seat, String name) {
        boolean surviving(Game game) {
            return card.isInPlay() && card.getGameTimestamp() == timestamp && game.getCardState(card) == card;
        }
    }
    private final class Sequence {
        final String id;
        final int turn, activeSeat, defenderSeat;
        final Player activePlayer, defender;
        final CombatSequenceAuditRecord.BeforeState before;
        final List<CombatSequenceAuditRecord.DecisionReference> decisions = new ArrayList<>();
        final List<CombatSequenceAuditRecord.Death> deaths = new ArrayList<>();
        final List<String> issues = new ArrayList<>();
        final Map<Integer, Creature> battlefieldCreatures = new HashMap<>();
        final Map<Card, Creature> attackers = new LinkedHashMap<>(), blockers = new LinkedHashMap<>();
        List<CombatSequenceAuditRecord.Attacker> attackerViews = List.of();
        List<CombatSequenceAuditRecord.Blocker> blockerViews = List.of();
        List<CombatSequenceAuditRecord.Assignment> assignments = List.of();
        Combat combat;
        int activeDamage, defenderDamage, activeLifeLost, defenderLifeLost, attackerPower, blockedCount;
        boolean attackersLocked, blockersLocked;
        Sequence(Player activePlayer) {
            this.activePlayer = activePlayer;
            defender = game.getRegisteredPlayers().size() == 2 ? game.getPlayers().stream().filter(p -> p != activePlayer).findFirst().orElse(null) : null;
            activeSeat = game.getRegisteredPlayers().indexOf(activePlayer); defenderSeat = defender == null ? -1 : game.getRegisteredPlayers().indexOf(defender);
            turn = game.getPhaseHandler().getTurn();
            var run = AiDecisionMetrics.gameMetadata(game.getId());
            id = "G" + game.getId() + "-I" + (run == null ? 0 : run.gameIndex()) + "-T" + turn + "-P" + activeSeat + "-C" + ++ordinal;
            before = new CombatSequenceAuditRecord.BeforeState(activePlayer.getLife(), defender == null ? 0 : defender.getLife(),
                    activePlayer.getCreaturesInPlay().size(), defender == null ? 0 : defender.getCreaturesInPlay().size());
            captureBattlefield();
        }
        int seatOf(int playerId) {
            return game.getRegisteredPlayers().stream().filter(p -> p.getId() == playerId).findFirst()
                    .map(game.getRegisteredPlayers()::indexOf).orElse(-1);
        }
        Creature creature(Card card) {
            return new Creature(card, card.getGameTimestamp(), game.getRegisteredPlayers().indexOf(card.getController()),
                    card.isFaceDown() ? "Face-down creature" : card.getName());
        }
        void captureBattlefield() {
            for (Player player : game.getPlayers()) for (Card card : player.getCreaturesInPlay())
                battlefieldCreatures.put(card.getId(), creature(card));
        }
        void captureDeclarations() {
            // Do not overwrite declaration-time state once damage starts removing combatants.
            PhaseType phase = game.getPhaseHandler().getPhase();
            if (phase != PhaseType.COMBAT_DECLARE_ATTACKERS && phase != PhaseType.COMBAT_DECLARE_BLOCKERS) return;
            if (blockersLocked) return;
            boolean captureAttackers = !attackersLocked;
            if (captureAttackers) attackers.clear();
            blockers.clear();
            Map<Card, String> attackerRefs = new LinkedHashMap<>(), blockerRefs = new LinkedHashMap<>();
            List<CombatSequenceAuditRecord.Attacker> av = captureAttackers ? new ArrayList<>() : new ArrayList<>(attackerViews);
            if (!captureAttackers) {
                int index = 0;
                for (Card card : attackers.keySet()) attackerRefs.put(card, av.get(index++).ref());
            }
            List<CombatSequenceAuditRecord.Blocker> bv = new ArrayList<>();
            List<CombatSequenceAuditRecord.Assignment> as = new ArrayList<>();
            if (captureAttackers) attackerPower = 0;
            blockedCount = 0;
            for (Card attacker : combat.getAttackers()) {
                String ref = attackerRefs.get(attacker);
                if (ref == null) {
                    ref = "A" + (av.size() + 1); attackerRefs.put(attacker, ref);
                    Creature creature = creature(attacker); attackers.put(attacker, creature);
                    av.add(new CombatSequenceAuditRecord.Attacker(ref, creature.name(), attacker.getNetPower()));
                    attackerPower += attacker.getNetPower();
                }
                if (!combat.getBlockers(attacker).isEmpty()) blockedCount++;
                for (Card blocker : combat.getBlockers(attacker)) {
                    if (!blockerRefs.containsKey(blocker)) {
                        String blockerRef = "B" + (bv.size() + 1); blockerRefs.put(blocker, blockerRef);
                        Creature blockerCreature = creature(blocker); blockers.put(blocker, blockerCreature);
                        bv.add(new CombatSequenceAuditRecord.Blocker(blockerRef, blockerCreature.name()));
                    }
                    as.add(new CombatSequenceAuditRecord.Assignment(blockerRefs.get(blocker), ref));
                }
            }
            attackerViews = List.copyOf(av); blockerViews = List.copyOf(bv); assignments = List.copyOf(as);
            if (phase == PhaseType.COMBAT_DECLARE_BLOCKERS) attackersLocked = true;
        }
        boolean lethal() {
            return (activeDamage > 0 && (activePlayer.getLife() <= 0 || activePlayer.hasLost()))
                    || (defenderDamage > 0 && defender != null && (defender.getLife() <= 0 || defender.hasLost()));
        }
        CombatSequenceAuditRecord snapshot(boolean complete, String reason) {
            int activeLife = activePlayer.getLife(), defendingLife = defender == null ? 0 : defender.getLife();
            var after = new CombatSequenceAuditRecord.AfterState(activeLife, defendingLife,
                    activeLifeLost, defenderLifeLost, deaths,
                    (int) attackers.values().stream().filter(c -> c.surviving(game)).count(),
                    (int) blockers.values().stream().filter(c -> c.surviving(game)).count(), activeDamage, defenderDamage, lethal());
            var run = AiDecisionMetrics.gameMetadata(game.getId());
            return new CombatSequenceAuditRecord("COMBAT_SEQUENCE", id, game.getId(), run == null ? null : run.gameIndex(),
                    run == null ? null : run.runSeed(), turn, activeSeat, defenderSeat, before, attackerViews, blockerViews, assignments,
                    attackerViews.size(), attackerPower, blockerViews.size(), blockedCount, attackerViews.size() - blockedCount, after, decisions,
                    (int) decisions.stream().filter(CombatSequenceAuditRecord.DecisionReference::providerAttempted).count(),
                    (int) decisions.stream().filter(CombatSequenceAuditRecord.DecisionReference::fallback).count(),
                    decisions.stream().mapToLong(CombatSequenceAuditRecord.DecisionReference::providerLatencyMs).sum(), complete, reason, issues);
        }
    }
}
