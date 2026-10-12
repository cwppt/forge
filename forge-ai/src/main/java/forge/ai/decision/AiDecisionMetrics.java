package forge.ai.decision;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.tinylog.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AiDecisionMetrics {
    private static final List<CombatSequenceAuditRecord> COMBAT_SEQUENCES = new ArrayList<>();
    private static final List<MulliganDecisionEvent> EVENTS = new ArrayList<>();
    private static final List<MainPhaseDecisionEvent> ACTION_EVENTS = new ArrayList<>();
    private static final List<StackResponseDecisionEvent> STACK_EVENTS = new ArrayList<>();
    private static final List<PendingActionAudit> PENDING_ACTION_AUDITS = new ArrayList<>();
    private static final List<PendingStackAudit> PENDING_STACK_AUDITS = new ArrayList<>();
    private static final List<CombatDamageOrderDecisionEvent> DAMAGE_ORDER_EVENTS = new ArrayList<>();
    private static final List<PendingDamageOrderAudit> PENDING_DAMAGE_ORDER_AUDITS = new ArrayList<>();
    private static final List<CombatBlockersDecisionEvent> BLOCK_EVENTS = new ArrayList<>();
    private static final List<PendingBlockAudit> PENDING_BLOCK_AUDITS = new ArrayList<>();
    private static final List<CombatAttackersDecisionEvent> COMBAT_EVENTS = new ArrayList<>();
    private static final List<PendingCombatAudit> PENDING_COMBAT_AUDITS = new ArrayList<>();
    private static final Map<Integer, GameRunMetadata> GAME_METADATA = new ConcurrentHashMap<>();
    private static final Gson AUDIT_GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Gson MEMORY_AUDIT_GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final List<CombatActionDecisionEvent> COMBAT_ACTION_EVENTS = new ArrayList<>();
    private static final List<PendingCombatActionAudit> PENDING_COMBAT_ACTION_AUDITS = new ArrayList<>();
    private record PendingCombatActionAudit(CombatActionDecisionEvent telemetry, CombatActionDecisionContext modelVisibleDecision) { }
    private record CombatActionAudit(String decisionType, CombatActionDecisionContext modelVisibleDecision,
            CombatActionDecisionEvent telemetry, CombatSequenceAuditRecord eventualCombatResult,
            String finalWinner, boolean draw, int finalTurnCount) { }
    public static synchronized List<CombatActionDecisionEvent> combatActionEvents() { return List.copyOf(COMBAT_ACTION_EVENTS); }
    public static synchronized void record(CombatActionDecisionEvent event, CombatActionDecisionContext context) {
        FullGameExternalAiAudit.record(AiDecisionType.COMBAT_ACTION, event, context);
        COMBAT_ACTION_EVENTS.add(event);
        if (auditWriter != null) PENDING_COMBAT_ACTION_AUDITS.add(new PendingCombatActionAudit(event, context));
        Logger.info("combatActionDecision {}", AUDIT_GSON.toJson(event));
    }
    public static synchronized String combatActionSummary() {
        var events = COMBAT_ACTION_EVENTS;
        long accepted = events.stream().filter(e -> !e.fallback()).count();
        long agreements = events.stream().filter(e -> !e.fallback() && e.heuristicOptionId().equals(e.selectedOptionId())).count();
        return String.format(Locale.ROOT, " combatActionDecisions=%d combatActionProviderCalls=%d combatActionOptions=%d "
                + "combatActionPassSelections=%d combatActionAgreements=%d combatActionDisagreements=%d combatActionFallbacks=%d "
                + "combatActionStaleOrRevalidationFailures=%d combatActionAvgProviderLatencyMs=%.2f combatActionAvgTotalLatencyMs=%.2f "
                + "combatActionForgePassExternalAction=%d combatActionForgeActionExternalPass=%d combatActionDifferentAction=%d",
                events.size(), events.size(), events.stream().mapToLong(CombatActionDecisionEvent::optionCount).sum(),
                events.stream().filter(CombatActionDecisionEvent::passSelection).count(), agreements, accepted - agreements,
                events.stream().filter(CombatActionDecisionEvent::fallback).count(),
                events.stream().filter(CombatActionDecisionEvent::staleOrRevalidationFailure).count(),
                events.stream().mapToLong(CombatActionDecisionEvent::providerLatencyMs).average().orElse(0),
                events.stream().mapToLong(CombatActionDecisionEvent::totalLatencyMs).average().orElse(0),
                events.stream().filter(e -> "FORGE_PASS_EXTERNAL_ACTION".equals(e.disagreementCategory())).count(),
                events.stream().filter(e -> "FORGE_ACTION_EXTERNAL_PASS".equals(e.disagreementCategory())).count(),
                events.stream().filter(e -> "DIFFERENT_ACTION".equals(e.disagreementCategory())).count());
    }
    private static BufferedWriter auditWriter;
    private static long mainPhaseWindowsConsidered;
    private static long mainPhaseNotActivePlayer;
    private static long mainPhaseStackNonempty;
    private static long mainPhaseEnumerationFailures;
    private static long mainPhaseZeroSafeActions;
    private static long mainPhaseOneSafeAction;
    private static long mainPhaseTwoOrMoreSafeActions;
    private static long mainPhaseRawCandidates;
    private static long mainPhaseEvaluatedCandidates;
    private static long mainPhaseAcceptedCandidates;
    private static long mainPhaseExposedCandidates;

    private AiDecisionMetrics() {
    }

    private static void writeAuditRecord(Object record) throws IOException {
        var json = record instanceof com.google.gson.JsonObject object ? object : AUDIT_GSON.toJsonTree(record).getAsJsonObject();
        auditWriter.write(MEMORY_AUDIT_GSON.toJson(FullGameExternalAiAudit.decorate(json)));
    }

    private static void writeAuditRecord(Object record, AiDecisionContext context) throws IOException {
        var json = AUDIT_GSON.toJsonTree(record).getAsJsonObject();
        json.addProperty("memoryEnabled", context.memorySnapshot() != null);
        if (context.memorySnapshot() != null) json.add("memory", MEMORY_AUDIT_GSON.toJsonTree(context.memorySnapshot()));
        writeAuditRecord(json);
    }

    public static synchronized void record(MulliganDecisionEvent event) {
        FullGameExternalAiAudit.record(AiDecisionType.MULLIGAN_KEEP, event, null);
        EVENTS.add(event);
        Logger.info("aiDecision decisionId={} fingerprint={} gameId={} gameIndex={} runSeed={} player={} "
                        + "seat={} deck={} provider={} model={} source={} heuristicOption={} finalOption={} "
                        + "latencyMs={} fallback={} fallbackReason={} cardsToReturn={} openingHandSize={}",
                event.decisionId(), event.stateFingerprint(), event.gameId(), value(event.gameIndex()),
                value(event.runSeed()), event.playerIdentity(), event.playerSeat(), value(event.deckIdentifier()),
                value(event.provider()), value(event.model()), event.source(),
                event.heuristicOption(), event.finalOption(), event.latencyMs(), event.fallback(),
                value(event.fallbackReason()), event.cardsToReturn(), event.openingHandSize());
    }

    public static void registerGame(int gameId, int gameIndex, Long runSeed) {
        GAME_METADATA.put(gameId, new GameRunMetadata(gameIndex, runSeed));
    }

    public static synchronized void record(MainPhaseDecisionEvent event) {
        ACTION_EVENTS.add(event);
        Logger.info("aiActionDecision decisionId={} fingerprint={} gameId={} gameIndex={} runSeed={} player={} "
                        + "seat={} deck={} provider={} model={} source={} candidates={} heuristicAction={} "
                        + "selectedAction={} category={} actionSource={} providerLatencyMs={} totalLatencyMs={} "
                + "fallback={} fallbackReason={} staleOrRevalidationFailure={} rejectedActionsExposed={} selectedRejectedAction={}",
                event.decisionId(), event.stateFingerprint(), event.gameId(), value(event.gameIndex()),
                value(event.runSeed()), event.playerIdentity(), event.playerSeat(), value(event.deckIdentifier()),
                value(event.provider()), value(event.model()), event.source(), event.acceptedCandidateCount(),
                event.heuristicActionId(), event.selectedActionId(), event.actionCategory(), event.sourceName(),
                event.providerLatencyMs(), event.totalLatencyMs(), event.fallback(), value(event.fallbackReason()),
                event.staleOrRevalidationFailure(), event.rejectedActionsExposed(), event.selectedRejectedAction());
    }

    public static synchronized void record(MainPhaseDecisionEvent event, MainPhaseDecisionContext context) {
        FullGameExternalAiAudit.record(AiDecisionType.MAIN_PHASE_ACTION, event, context);
        record(event);
        if (auditWriter != null) {
            PENDING_ACTION_AUDITS.add(new PendingActionAudit(event, context));
        }
    }

    public static synchronized void record(StackResponseDecisionEvent event,
            StackResponseDecisionContext context) {
        FullGameExternalAiAudit.record(AiDecisionType.STACK_RESPONSE, event, context);
        STACK_EVENTS.add(event);
        Logger.debug("aiStackResponse decisionId={} gameId={} player={} actions={} heuristic={} selected={} "
                        + "pass={} fallback={} reason={} providerLatencyMs={} totalLatencyMs={}",
                event.decisionId(), event.gameId(), event.playerIdentity(), event.responseActionCount(),
                event.heuristicResponseId(), event.selectedResponseId(), event.passSelected(),
                event.fallback(), value(event.fallbackReason()), event.providerLatencyMs(), event.totalLatencyMs());
        if (auditWriter != null) {
            PENDING_STACK_AUDITS.add(new PendingStackAudit(event, context));
        }
    }

    public static synchronized void record(CombatAttackersDecisionEvent event, CombatAttackersDecisionContext context) {
        FullGameExternalAiAudit.record(AiDecisionType.COMBAT_ATTACKERS, event, context);
        COMBAT_EVENTS.add(event);
        CombatSequenceTracker.decision(event);
        Logger.info("aiCombatAttackers decisionId={} options={} heuristic={} selected={} fallback={} reason={} providerLatencyMs={} totalLatencyMs={}",
                event.decisionId(), event.optionCount(), event.heuristicOptionId(), event.selectedOptionId(),
                event.fallback(), event.failureReason(), event.providerLatencyMs(), event.totalLatencyMs());
        if (auditWriter != null) PENDING_COMBAT_AUDITS.add(new PendingCombatAudit(event, context));
    }

    public static synchronized List<CombatAttackersDecisionEvent> combatAttackerEvents() { return List.copyOf(COMBAT_EVENTS); }

    public static synchronized void record(CombatBlockersDecisionEvent event, CombatBlockersDecisionContext context) {
        FullGameExternalAiAudit.record(AiDecisionType.COMBAT_BLOCKERS, event, context);
        BLOCK_EVENTS.add(event);
        CombatSequenceTracker.decision(event);
        Logger.info("aiCombatBlockers decisionId={} options={} heuristic={} selected={} fallback={} reason={} providerLatencyMs={} totalLatencyMs={}",
                event.decisionId(), event.optionCount(), event.heuristicOptionId(), event.selectedOptionId(),
                event.fallback(), event.failureReason(), event.providerLatencyMs(), event.totalLatencyMs());
        if (auditWriter != null) PENDING_BLOCK_AUDITS.add(new PendingBlockAudit(event, context));
    }

    public static synchronized List<CombatBlockersDecisionEvent> combatBlockerEvents() { return List.copyOf(BLOCK_EVENTS); }

    public static synchronized void record(CombatDamageOrderDecisionEvent event, CombatDamageOrderDecisionContext context) {
        FullGameExternalAiAudit.record(AiDecisionType.COMBAT_DAMAGE_ORDER, event, context);
        DAMAGE_ORDER_EVENTS.add(event);
        CombatSequenceTracker.decision(event);
        Logger.info("aiCombatDamageOrder decisionId={} options={} heuristic={} selected={} fallback={} reason={} providerLatencyMs={} totalLatencyMs={}",
                event.decisionId(), event.optionCount(), event.heuristicOptionId(), event.selectedOptionId(),
                event.fallback(), event.failureReason(), event.providerLatencyMs(), event.totalLatencyMs());
        if (auditWriter != null) PENDING_DAMAGE_ORDER_AUDITS.add(new PendingDamageOrderAudit(event, context));
    }

    public static synchronized List<CombatDamageOrderDecisionEvent> combatDamageOrderEvents() { return List.copyOf(DAMAGE_ORDER_EVENTS); }

    public record CombatCorrelation(String combatSequenceId, int turnNumber, int activePlayerSeat, Integer defendingPlayerSeat) { }

    public static void observeCombatSequences(forge.game.Game game) { CombatSequenceTracker.observe(game); }

    public static CombatCorrelation combatCorrelation(forge.game.Game game, forge.game.combat.Combat combat, AiDecisionType surface) {
        var correlation = CombatSequenceTracker.correlation(game, combat);
        return new CombatCorrelation(correlation.combatSequenceId(), correlation.turnNumber(),
                correlation.activePlayerSeat(), correlation.defendingPlayerSeat());
    }

    static synchronized void recordCombatSequence(CombatSequenceAuditRecord sequence, boolean auditEnabled) {
        COMBAT_SEQUENCES.add(sequence);
        Logger.debug("aiCombatSequence sequenceId={} calls={} fallbacks={} providerLatencyMs={} lethal={}",
                sequence.combatSequenceId(), sequence.providerCallCount(), sequence.fallbackCount(),
                sequence.totalProviderLatencyMs(), sequence.afterState().lethal());
        if (auditEnabled && auditWriter != null) {
            try { auditWriter.write(AUDIT_GSON.toJson(sequence)); auditWriter.newLine(); auditWriter.flush(); }
            catch (IOException ex) { Logger.error(ex, "Unable to write combat sequence audit"); }
        }
    }

    public static synchronized List<CombatSequenceAuditRecord> combatSequences() { return List.copyOf(COMBAT_SEQUENCES); }

    public static synchronized IntegratedCombatMetrics integratedCombatMetrics() {
        long count = COMBAT_SEQUENCES.size();
        long attacker = COMBAT_SEQUENCES.stream().filter(s -> hasSurface(s, AiDecisionType.COMBAT_ATTACKERS)).count();
        long blocker = COMBAT_SEQUENCES.stream().filter(s -> hasSurface(s, AiDecisionType.COMBAT_BLOCKERS)).count();
        long order = COMBAT_SEQUENCES.stream().filter(s -> hasSurface(s, AiDecisionType.COMBAT_DAMAGE_ORDER)).count();
        long all = COMBAT_SEQUENCES.stream().filter(s -> hasSurface(s, AiDecisionType.COMBAT_ATTACKERS)
                && hasSurface(s, AiDecisionType.COMBAT_BLOCKERS) && hasSurface(s, AiDecisionType.COMBAT_DAMAGE_ORDER)).count();
        long calls = COMBAT_SEQUENCES.stream().mapToLong(CombatSequenceAuditRecord::providerCallCount).sum();
        long fallbacks = COMBAT_SEQUENCES.stream().mapToLong(CombatSequenceAuditRecord::fallbackCount).sum();
        long[] latency = COMBAT_SEQUENCES.stream().mapToLong(CombatSequenceAuditRecord::totalProviderLatencyMs).sorted().toArray();
        return new IntegratedCombatMetrics(count, attacker, blocker, order, all, calls,
                COMBAT_SEQUENCES.stream().filter(s -> s.fallbackCount() > 0).count(), fallbacks,
                count == 0 ? 0 : (double) calls / count,
                count == 0 ? 0 : (double) COMBAT_SEQUENCES.stream().mapToLong(CombatSequenceAuditRecord::totalProviderLatencyMs).sum() / count,
                percentile(latency, 0.50), percentile(latency, 0.95), COMBAT_SEQUENCES.stream().filter(s -> s.afterState().lethal()).count());
    }
    private static boolean hasSurface(CombatSequenceAuditRecord sequence, AiDecisionType surface) {
        return sequence.decisions().stream().anyMatch(d -> d.surface() == surface && d.providerAttempted());
    }
    private static long percentile(long[] sorted, double fraction) {
        return sorted.length == 0 ? 0 : sorted[(int) Math.ceil(sorted.length * fraction) - 1];
    }

    /** Enables optional JSONL auditing. Existing files are replaced for a new simulation cohort. */
    public static synchronized void configureDecisionAudit(Path path) throws IOException {
        closeAuditWriter();
        if (path != null) {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            auditWriter = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        }
    }

    /** Flushes the decisions for one completed game with joinable, non-causal outcome information. */
    public static synchronized void completeGame(int gameId, String winner, boolean draw,
            long durationMs, int finalTurnCount) {
        CombatSequenceTracker.completeGame(gameId);
        var fullGameRecords = FullGameExternalAiAudit.completeGame(gameId, winner, draw, durationMs, finalTurnCount);
        forge.ai.ExternalAiStrategicMemory.release(gameId);
        if (auditWriter != null) try {
            for (var record : fullGameRecords) { writeAuditRecord(record); auditWriter.newLine(); }
            auditWriter.flush();
        } catch (IOException ex) { Logger.error(ex, "Unable to write full game audit"); }
        if (auditWriter != null) {
            try {
                for (var pending : PENDING_COMBAT_ACTION_AUDITS.stream().filter(p -> p.telemetry().gameId() == gameId).toList()) {
                    var eventual = COMBAT_SEQUENCES.stream().filter(c -> c.combatSequenceId().equals(pending.telemetry().combatSequenceId())).findFirst().orElse(null);
                    writeAuditRecord(new CombatActionAudit("COMBAT_ACTION", pending.modelVisibleDecision(),
                            pending.telemetry(), eventual, winner, draw, finalTurnCount), pending.modelVisibleDecision()); auditWriter.newLine();
                }
                auditWriter.flush();
            } catch (IOException e) { throw new IllegalStateException("Unable to write combat action audit", e); }
        }
        PENDING_COMBAT_ACTION_AUDITS.removeIf(p -> p.telemetry().gameId() == gameId);
        if (auditWriter == null) {
            FullGameExternalAiAudit.release(gameId);
            return;
        }
        try {
            for (PendingActionAudit pending : PENDING_ACTION_AUDITS.stream()
                    .filter(p -> p.event().gameId() == gameId).toList()) {
                MainPhaseDecisionEvent e = pending.event();
                MainPhaseDecisionContext context = pending.context();
                MainPhaseDecisionAuditRecord record = new MainPhaseDecisionAuditRecord(
                        "MAIN_PHASE_ACTION", e.gameId(), e.gameIndex(), e.runSeed(), e.playerIdentity(),
                        e.playerSeat(), e.deckIdentifier(), e.turnNumber(), e.phase(), e.decisionId(),
                        e.stateFingerprint(), e.configuredMaxActions(), e.rawCandidateCount(),
                        e.evaluatedCandidateCount(), e.acceptedCandidateCount(), context.legalActions().size(),
                        e.copiedGameCount(), e.enumerationNanos(), e.candidateSetTruncated(),
                        e.heuristicActionId(), e.selectedActionId(),
                        e.heuristicActionId().equals(e.selectedActionId()), e.provider(), e.model(),
                        e.providerLatencyMs(), e.totalLatencyMs(), e.source(), e.fallback(), e.fallbackReason(),
                        context.state(), context.legalActions(), e.returnedDecisionId(), e.returnedFingerprint(),
                        e.returnedOptionId(), e.responseAccepted(), winner,
                        !draw && e.playerIdentity().equals(winner), draw, durationMs, finalTurnCount);
                writeAuditRecord(record, context);
                auditWriter.newLine();
            }
            PENDING_ACTION_AUDITS.removeIf(p -> p.event().gameId() == gameId);
            for (PendingStackAudit pending : PENDING_STACK_AUDITS.stream()
                    .filter(p -> p.event().gameId() == gameId).toList()) {
                StackResponseDecisionEvent e = pending.event();
                StackResponseDecisionContext context = pending.context();
                StackResponseDecisionAuditRecord record = new StackResponseDecisionAuditRecord(
                        "STACK_RESPONSE", e.gameId(), e.gameIndex(), e.runSeed(), e.playerIdentity(),
                        e.playerSeat(), e.deckIdentifier(), e.turnNumber(), e.phase(), e.decisionId(),
                        e.stateFingerprint(), e.responseActionCount(), context.visibleState(), context.stackItems(),
                        context.legalActions(), true, e.heuristicResponseId(), e.selectedResponseId(),
                        e.heuristicResponseId().equals(e.selectedResponseId()), e.provider(), e.model(),
                        e.providerLatencyMs(), e.totalLatencyMs(), e.source(), e.fallback(),
                        e.fallbackReason(), e.returnedDecisionId(), e.returnedFingerprint(),
                        e.returnedOptionId(), e.responseAccepted(), winner,
                        !draw && e.playerIdentity().equals(winner), draw, durationMs, finalTurnCount,
                        context.randomizedOrder(), context.internalActionMapping().stream()
                                .map(mapping -> mapping.withHeuristic(e.heuristicResponseId())).toList());
                writeAuditRecord(record, context);
                auditWriter.newLine();
            }
            PENDING_STACK_AUDITS.removeIf(p -> p.event().gameId() == gameId);
            for (PendingCombatAudit pending : PENDING_COMBAT_AUDITS.stream().filter(p -> p.event().gameId() == gameId).toList()) {
                CombatAttackersDecisionEvent e = pending.event();
                List<CombatAttackersDecisionAuditRecord.Mapping> mapping = new ArrayList<>();
                for (int i = 0; i < pending.context().options().size(); i++) {
                    CombatAttackOptionView option = pending.context().options().get(i);
                    mapping.add(new CombatAttackersDecisionAuditRecord.Mapping(option.optionId(), i,
                            option.optionId().equals(e.heuristicOptionId()), option.attackers()));
                }
                CombatAttackersDecisionAuditRecord record = new CombatAttackersDecisionAuditRecord("COMBAT_ATTACKERS",
                        pending.context(), mapping, e, e.heuristicOptionId().equals(e.selectedOptionId()), winner,
                        !draw && e.playerIdentity().equals(winner), draw, durationMs, finalTurnCount);
                writeAuditRecord(record, pending.context());
                auditWriter.newLine();
            }
            PENDING_COMBAT_AUDITS.removeIf(p -> p.event().gameId() == gameId);
            for (PendingBlockAudit pending : PENDING_BLOCK_AUDITS.stream().filter(p -> p.event().gameId() == gameId).toList()) {
                CombatBlockersDecisionEvent e = pending.event();
                List<CombatBlockersDecisionAuditRecord.Mapping> mapping = new ArrayList<>();
                for (int i = 0; i < pending.context().options().size(); i++) {
                    CombatBlockOptionView option = pending.context().options().get(i);
                    mapping.add(new CombatBlockersDecisionAuditRecord.Mapping(option.optionId(), i,
                            option.optionId().equals(e.heuristicOptionId()), option.assignments()));
                }
                CombatBlockersDecisionAuditRecord record = new CombatBlockersDecisionAuditRecord("COMBAT_BLOCKERS",
                        pending.context(), mapping, e, e.heuristicOptionId().equals(e.selectedOptionId()), winner,
                        !draw && e.playerIdentity().equals(winner), draw, durationMs, finalTurnCount);
                writeAuditRecord(record, pending.context());
                auditWriter.newLine();
            }
            PENDING_BLOCK_AUDITS.removeIf(p -> p.event().gameId() == gameId);
            for (PendingDamageOrderAudit pending : PENDING_DAMAGE_ORDER_AUDITS.stream().filter(p -> p.event().gameId() == gameId).toList()) {
                CombatDamageOrderDecisionEvent e = pending.event();
                List<CombatDamageOrderDecisionAuditRecord.Mapping> mapping = new ArrayList<>();
                for (int i = 0; i < pending.context().options().size(); i++) {
                    CombatDamageOrderOptionView option = pending.context().options().get(i);
                    mapping.add(new CombatDamageOrderDecisionAuditRecord.Mapping(option.optionId(), i,
                            option.optionId().equals(e.heuristicOptionId()), option.orderedRecipientIds()));
                }
                CombatDamageOrderDecisionAuditRecord record = new CombatDamageOrderDecisionAuditRecord("COMBAT_DAMAGE_ORDER",
                        pending.context(), mapping, damageOrder(pending.context(), e.heuristicOptionId()),
                        damageOrder(pending.context(), e.returnedOptionId()), damageOrder(pending.context(), e.selectedOptionId()),
                        e, e.heuristicOptionId().equals(e.selectedOptionId()), winner,
                        !draw && e.playerIdentity().equals(winner), draw, durationMs, finalTurnCount);
                writeAuditRecord(record);
                auditWriter.newLine();
            }
            PENDING_DAMAGE_ORDER_AUDITS.removeIf(p -> p.event().gameId() == gameId);
            auditWriter.flush();
        } catch (IOException ex) {
            Logger.error(ex, "Unable to write external AI decision audit");
        } finally {
            FullGameExternalAiAudit.release(gameId);
        }
    }

    public static GameRunMetadata gameMetadata(int gameId) {
        return GAME_METADATA.get(gameId);
    }

    public static synchronized void recordMainPhaseWindowConsidered() {
        mainPhaseWindowsConsidered++;
    }

    public static synchronized void recordMainPhaseNotActivePlayer() {
        mainPhaseNotActivePlayer++;
    }

    public static synchronized void recordMainPhaseStackNonempty() {
        mainPhaseStackNonempty++;
    }

    public static synchronized void recordMainPhaseEnumerationFailure() {
        mainPhaseEnumerationFailures++;
    }

    public static synchronized void recordMainPhaseEnumeration(
            int raw, int evaluated, int accepted, int exposed) {
        mainPhaseRawCandidates += raw;
        mainPhaseEvaluatedCandidates += evaluated;
        mainPhaseAcceptedCandidates += accepted;
        mainPhaseExposedCandidates += exposed;
        if (accepted == 0) {
            mainPhaseZeroSafeActions++;
        } else if (accepted == 1) {
            mainPhaseOneSafeAction++;
        } else {
            mainPhaseTwoOrMoreSafeActions++;
        }
    }

    public static synchronized MainPhaseRoutingDiagnostics mainPhaseRoutingDiagnostics() {
        return new MainPhaseRoutingDiagnostics(mainPhaseWindowsConsidered, mainPhaseNotActivePlayer,
                mainPhaseStackNonempty, mainPhaseEnumerationFailures, mainPhaseZeroSafeActions,
                mainPhaseOneSafeAction, mainPhaseTwoOrMoreSafeActions, mainPhaseRawCandidates,
                mainPhaseEvaluatedCandidates, mainPhaseAcceptedCandidates, mainPhaseExposedCandidates);
    }

    public static synchronized void reset() {
        FullGameExternalAiAudit.reset();
        forge.ai.ExternalAiStrategicMemory.reset();
        COMBAT_ACTION_EVENTS.clear(); PENDING_COMBAT_ACTION_AUDITS.clear();
        COMBAT_SEQUENCES.clear();
        CombatSequenceTracker.reset();
        EVENTS.clear();
        ACTION_EVENTS.clear();
        STACK_EVENTS.clear();
        PENDING_ACTION_AUDITS.clear();
        PENDING_STACK_AUDITS.clear();
        DAMAGE_ORDER_EVENTS.clear();
        PENDING_DAMAGE_ORDER_AUDITS.clear();
        BLOCK_EVENTS.clear();
        PENDING_BLOCK_AUDITS.clear();
        COMBAT_EVENTS.clear();
        PENDING_COMBAT_AUDITS.clear();
        GAME_METADATA.clear();
        mainPhaseWindowsConsidered = 0;
        mainPhaseNotActivePlayer = 0;
        mainPhaseStackNonempty = 0;
        mainPhaseEnumerationFailures = 0;
        mainPhaseZeroSafeActions = 0;
        mainPhaseOneSafeAction = 0;
        mainPhaseTwoOrMoreSafeActions = 0;
        mainPhaseRawCandidates = 0;
        mainPhaseEvaluatedCandidates = 0;
        mainPhaseAcceptedCandidates = 0;
        mainPhaseExposedCandidates = 0;
        closeAuditWriter();
    }

    public static synchronized List<MulliganDecisionEvent> events() {
        return List.copyOf(EVENTS);
    }

    public static synchronized List<MainPhaseDecisionEvent> actionEvents() {
        return List.copyOf(ACTION_EVENTS);
    }

    public static synchronized List<StackResponseDecisionEvent> stackResponseEvents() {
        return List.copyOf(STACK_EVENTS);
    }

    public static synchronized AiDecisionMetricsSnapshot snapshot(long games) {
        long attempts = EVENTS.stream().filter(MulliganDecisionEvent::providerAttempted).count();
        long accepted = EVENTS.stream().filter(e -> e.source() == AiDecisionSource.EXTERNAL_PROVIDER).count();
        long agreements = EVENTS.stream().filter(e -> e.source() == AiDecisionSource.EXTERNAL_PROVIDER
                && e.heuristicOption().equals(e.finalOption())).count();
        long disagreements = accepted - agreements;
        long rejected = EVENTS.stream().filter(e -> e.providerAttempted()
                && isRejectedResponse(e.fallbackReason())).count();
        long failures = EVENTS.stream().filter(e -> e.providerAttempted()
                && isProviderFailure(e.fallbackReason())).count();
        long timeouts = EVENTS.stream().filter(e -> e.fallbackReason() == AiDecisionFailureReason.TIMEOUT).count();
        long fallbacks = EVENTS.stream().filter(MulliganDecisionEvent::fallback).count();
        long keeps = EVENTS.stream().filter(e -> MulliganDecisionContext.KEEP_OPTION_ID.equals(e.finalOption())).count();
        long mulligans = EVENTS.size() - keeps;
        List<Long> latencies = EVENTS.stream().filter(MulliganDecisionEvent::providerAttempted)
                .map(MulliganDecisionEvent::latencyMs).sorted().toList();
        double average = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        double finalHand = EVENTS.stream()
                .filter(e -> MulliganDecisionContext.KEEP_OPTION_ID.equals(e.finalOption()))
                .mapToInt(e -> Math.max(0, e.openingHandSize() - e.cardsToReturn())).average().orElse(0);
        return new AiDecisionMetricsSnapshot(EVENTS.size(), attempts, accepted, rejected, failures, timeouts,
                fallbacks, keeps, mulligans, average, percentile(latencies, 0.50), percentile(latencies, 0.95),
                games > 0 ? (double) mulligans / games : 0, finalHand, agreements, disagreements,
                accepted == 0 ? 0 : (double) disagreements / accepted);
    }

    public static synchronized void writeCsv(Path path) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("decisionId,stateFingerprint,provider,model,source,option,latencyMs,providerAttempted,"
                + "fallback,fallbackReason,cardsToReturn,openingHandSize,gameId,gameIndex,runSeed,"
                + "playerIdentity,playerSeat,deckIdentifier,heuristicOption,finalOption,decisionType,"
                + "candidateCount,rawCandidateCount,evaluatedCandidateCount,copiedGameCount,enumerationNanos,"
                + "totalLatencyMs,staleOrRevalidationFailure,actionCategory,actionSource,rejectedActionsExposed,"
                + "selectedRejectedAction,fallbackAfterRejectedAction,selectedForgeRecommendation");
        EVENTS.stream().sorted(Comparator.comparing(MulliganDecisionEvent::decisionId)).forEach(e -> lines.add(
                csv(e.decisionId()) + "," + csv(e.stateFingerprint()) + "," + csv(e.provider()) + ","
                        + csv(e.model()) + "," + e.source() + "," + e.option() + "," + e.latencyMs() + ","
                        + e.providerAttempted() + "," + e.fallback() + "," + value(e.fallbackReason()) + ","
                        + e.cardsToReturn() + "," + e.openingHandSize() + "," + e.gameId() + ","
                        + value(e.gameIndex()) + "," + value(e.runSeed()) + "," + csv(e.playerIdentity()) + ","
                        + e.playerSeat() + "," + csv(e.deckIdentifier()) + "," + e.heuristicOption() + ","
                        + e.finalOption() + ",MULLIGAN_KEEP,,,,,,,,,,,,,"));
        ACTION_EVENTS.stream().sorted(Comparator.comparing(MainPhaseDecisionEvent::decisionId)).forEach(e -> lines.add(
                csv(e.decisionId()) + "," + csv(e.stateFingerprint()) + "," + csv(e.provider()) + ","
                        + csv(e.model()) + "," + e.source() + "," + e.selectedActionId() + ","
                        + e.providerLatencyMs() + "," + e.providerAttempted() + "," + e.fallback() + ","
                        + value(e.fallbackReason()) + ",,," + e.gameId() + "," + value(e.gameIndex()) + ","
                        + value(e.runSeed()) + "," + csv(e.playerIdentity()) + "," + e.playerSeat() + ","
                        + csv(e.deckIdentifier()) + "," + e.heuristicActionId() + "," + e.selectedActionId()
                        + ",MAIN_PHASE_ACTION," + e.acceptedCandidateCount() + "," + e.rawCandidateCount() + ","
                        + e.evaluatedCandidateCount() + "," + e.copiedGameCount() + "," + e.enumerationNanos()
                        + "," + e.totalLatencyMs() + "," + e.staleOrRevalidationFailure() + ","
                        + csv(e.actionCategory()) + "," + csv(e.sourceName()) + ","
                        + e.rejectedActionsExposed() + "," + e.selectedRejectedAction() + ","
                        + e.fallbackAfterRejectedAction() + "," + csv(e.selectedForgeRecommendation())));
        STACK_EVENTS.stream().sorted(Comparator.comparing(StackResponseDecisionEvent::decisionId)).forEach(e -> lines.add(
                csv(e.decisionId()) + "," + csv(e.stateFingerprint()) + "," + csv(e.provider()) + ","
                        + csv(e.model()) + "," + e.source() + "," + e.selectedResponseId() + ","
                        + e.providerLatencyMs() + "," + e.providerAttempted() + "," + e.fallback() + ","
                        + value(e.fallbackReason()) + ",,," + e.gameId() + "," + value(e.gameIndex()) + ","
                        + value(e.runSeed()) + "," + csv(e.playerIdentity()) + "," + e.playerSeat() + ","
                        + csv(e.deckIdentifier()) + "," + csv(e.heuristicResponseId()) + ","
                        + csv(e.selectedResponseId()) + ",STACK_RESPONSE," + e.responseActionCount()
                        + ",,,,," + e.totalLatencyMs() + "," + e.staleOrRevalidationFailure()
                        + ",,,,,"));
        COMBAT_EVENTS.stream().sorted(Comparator.comparing(CombatAttackersDecisionEvent::decisionId)).forEach(e -> lines.add(
                csv(e.decisionId()) + "," + csv(e.stateFingerprint()) + "," + csv(e.provider()) + "," + csv(e.model())
                        + "," + e.source() + "," + csv(e.selectedOptionId()) + "," + e.providerLatencyMs() + ","
                        + e.providerAttempted() + "," + e.fallback() + "," + value(e.failureReason()) + ",,," + e.gameId()
                        + "," + value(e.gameIndex()) + "," + value(e.runSeed()) + "," + csv(e.playerIdentity()) + ","
                        + e.playerSeat() + "," + csv(e.deckIdentifier()) + "," + csv(e.heuristicOptionId()) + ","
                        + csv(e.selectedOptionId()) + ",COMBAT_ATTACKERS," + e.optionCount() + ",,,,,"
                        + e.totalLatencyMs() + "," + e.staleOrRevalidationFailure() + ",,,,,,"));
        BLOCK_EVENTS.stream().sorted(Comparator.comparing(CombatBlockersDecisionEvent::decisionId)).forEach(e -> lines.add(
                csv(e.decisionId()) + "," + csv(e.stateFingerprint()) + "," + csv(e.provider()) + "," + csv(e.model())
                        + "," + e.source() + "," + csv(e.selectedOptionId()) + "," + e.providerLatencyMs() + ","
                        + e.providerAttempted() + "," + e.fallback() + "," + value(e.failureReason()) + ",,," + e.gameId()
                        + "," + value(e.gameIndex()) + "," + value(e.runSeed()) + "," + csv(e.playerIdentity()) + ","
                        + e.playerSeat() + "," + csv(e.deckIdentifier()) + "," + csv(e.heuristicOptionId()) + ","
                        + csv(e.selectedOptionId()) + ",COMBAT_BLOCKERS," + e.optionCount() + ",,,,,"
                        + e.totalLatencyMs() + "," + e.staleOrRevalidationFailure() + ",,,,,,"));
        DAMAGE_ORDER_EVENTS.stream().sorted(Comparator.comparing(CombatDamageOrderDecisionEvent::decisionId)).forEach(e -> lines.add(
                csv(e.decisionId()) + "," + csv(e.stateFingerprint()) + "," + csv(e.provider()) + "," + csv(e.model())
                        + "," + e.source() + "," + csv(e.selectedOptionId()) + "," + e.providerLatencyMs() + ","
                        + e.providerAttempted() + "," + e.fallback() + "," + value(e.failureReason()) + ",,," + e.gameId()
                        + "," + value(e.gameIndex()) + "," + value(e.runSeed()) + "," + csv(e.playerIdentity()) + ","
                        + e.playerSeat() + "," + csv(e.deckIdentifier()) + "," + csv(e.heuristicOptionId()) + ","
                        + csv(e.selectedOptionId()) + ",COMBAT_DAMAGE_ORDER," + e.optionCount() + ",,,,,"
                        + e.totalLatencyMs() + "," + e.staleOrRevalidationFailure() + ",,,,,,"));
        for (var e : COMBAT_ACTION_EVENTS.stream().sorted(Comparator.comparing(CombatActionDecisionEvent::decisionId)).toList()) {
            List<String> row = new ArrayList<>(java.util.Collections.nCopies(34, ""));
            row.set(0, csv(e.decisionId())); row.set(1, csv(e.stateFingerprint())); row.set(2, csv(e.provider())); row.set(3, csv(e.model()));
            row.set(4, e.fallback() ? "FORGE_HEURISTIC" : "EXTERNAL_PROVIDER"); row.set(5, csv(e.selectedOptionId()));
            row.set(6, Long.toString(e.providerLatencyMs())); row.set(7, "true"); row.set(8, Boolean.toString(e.fallback()));
            row.set(9, value(e.failureReason())); row.set(12, Integer.toString(e.gameId())); row.set(16, Integer.toString(e.playerSeat()));
            var run = gameMetadata(e.gameId());
            if (run != null) { row.set(13, Integer.toString(run.gameIndex())); row.set(14, value(run.runSeed())); }
            row.set(18, csv(e.heuristicOptionId())); row.set(19, csv(e.selectedOptionId())); row.set(20, "COMBAT_ACTION");
            row.set(21, Integer.toString(e.optionCount())); row.set(26, Long.toString(e.totalLatencyMs()));
            row.set(27, Boolean.toString(e.staleOrRevalidationFailure())); lines.add(String.join(",", row));
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    public static String summary(long games) {
        AiDecisionMetricsSnapshot s = snapshot(games);
        return String.format(Locale.ROOT,
                "games=%d mulliganDecisions=%d providerCalls=%d accepted=%d rejected=%d providerFailures=%d "
                        + "timeouts=%d fallbacks=%d fallbackRate=%.4f avgLatencyMs=%.2f p50LatencyMs=%d "
                        + "p95LatencyMs=%d avgMulligans=%.4f avgFinalOpeningHandSize=%.2f "
                        + "externalAgreements=%d externalDisagreements=%d disagreementRate=%.4f "
                        + "mainPhaseDecisions=%d mainPhaseProviderCalls=%d mainPhaseExternal=%d "
                        + "mainPhaseDisagreements=%d mainPhaseEligibleWindows=%d mainPhaseTwoCandidates=%d "
                        + "mainPhaseThreeCandidates=%d mainPhaseTruncated=%d mainPhaseFallbacks=%d "
                        + "mainPhaseFallbackRate=%.4f mainPhaseDisagreementRate=%.4f "
                        + "mainPhaseAvgProviderLatencyMs=%.2f mainPhaseP50ProviderLatencyMs=%d "
                        + "mainPhaseP95ProviderLatencyMs=%d mainPhaseAvgTotalLatencyMs=%.2f selectedPositions=%s "
                        + "selectedCategories=%s mainPhaseWindowsConsidered=%d mainPhaseNotActivePlayer=%d "
                        + "mainPhaseStackNonempty=%d mainPhaseEnumerationFailures=%d "
                        + "mainPhaseZeroSafeActions=%d mainPhaseOneSafeAction=%d "
                        + "mainPhaseTwoOrMoreSafeActions=%d mainPhaseRawCandidates=%d "
                        + "mainPhaseEvaluatedCandidates=%d mainPhaseAcceptedCandidates=%d "
                        + "mainPhaseExposedCandidates=%d mainPhaseRejectedActionsExposed=%d "
                        + "mainPhaseRejectedSelections=%d mainPhaseFallbackAfterRejected=%d "
                        + "stackResponseDecisions=%d stackResponseProviderCalls=%d stackResponsePasses=%d "
                        + "stackResponseAgreements=%d stackResponseDisagreements=%d stackResponseFallbacks=%d",
                games, s.mulliganCallbacks(), s.providerAttempts(), s.acceptedExternalDecisions(),
                s.rejectedExternalDecisions(), s.providerFailures(), s.timeouts(), s.heuristicFallbacks(),
                s.providerAttempts() == 0 ? 0 : (double) s.heuristicFallbacks() / s.providerAttempts(),
                s.averageLatencyMs(), s.p50LatencyMs(), s.p95LatencyMs(), s.averageMulligansPerGame(),
                s.averageFinalOpeningHandSize(), s.externalAgreements(), s.externalDisagreements(),
                s.externalDisagreementRate(), ACTION_EVENTS.size(),
                ACTION_EVENTS.stream().filter(MainPhaseDecisionEvent::providerAttempted).count(),
                ACTION_EVENTS.stream().filter(e -> e.source() == AiDecisionSource.EXTERNAL_PROVIDER).count(),
                ACTION_EVENTS.stream().filter(e -> e.source() == AiDecisionSource.EXTERNAL_PROVIDER
                        && !e.heuristicActionId().equals(e.selectedActionId())).count(),
                ACTION_EVENTS.size(), countCandidates(2), countCandidates(3),
                ACTION_EVENTS.stream().filter(MainPhaseDecisionEvent::candidateSetTruncated).count(),
                actionFallbacks(), rate(actionFallbacks(), actionProviderCalls()),
                rate(actionDisagreements(), actionExternalDecisions()), averageActionLatency(true),
                percentile(actionLatencies(), 0.50), percentile(actionLatencies(), 0.95),
                averageActionLatency(false), selectedPositions(), selectedCategories(),
                mainPhaseWindowsConsidered, mainPhaseNotActivePlayer, mainPhaseStackNonempty,
                mainPhaseEnumerationFailures, mainPhaseZeroSafeActions, mainPhaseOneSafeAction,
                mainPhaseTwoOrMoreSafeActions, mainPhaseRawCandidates, mainPhaseEvaluatedCandidates,
                mainPhaseAcceptedCandidates, mainPhaseExposedCandidates,
                ACTION_EVENTS.stream().mapToLong(MainPhaseDecisionEvent::rejectedActionsExposed).sum(),
                ACTION_EVENTS.stream().filter(MainPhaseDecisionEvent::selectedRejectedAction).count(),
                ACTION_EVENTS.stream().filter(MainPhaseDecisionEvent::fallbackAfterRejectedAction).count(),
                STACK_EVENTS.size(), STACK_EVENTS.stream().filter(StackResponseDecisionEvent::providerAttempted).count(),
                STACK_EVENTS.stream().filter(e -> e.source() == AiDecisionSource.EXTERNAL_PROVIDER
                        && e.passSelected()).count(),
                STACK_EVENTS.stream().filter(e -> e.source() == AiDecisionSource.EXTERNAL_PROVIDER
                        && e.heuristicResponseId().equals(e.selectedResponseId())).count(),
                STACK_EVENTS.stream().filter(e -> e.source() == AiDecisionSource.EXTERNAL_PROVIDER
                        && !e.heuristicResponseId().equals(e.selectedResponseId())).count(),
                STACK_EVENTS.stream().filter(StackResponseDecisionEvent::fallback).count()) + combatAttackerSummary() + combatBlockerSummary() + combatDamageOrderSummary() + " " + integratedCombatMetrics() + combatActionSummary();
    }

    private static String combatAttackerSummary() {
        return String.format(Locale.ROOT, " combatAttackerDecisions=%d combatAttackerProviderCalls=%d combatAttackerOptions=%d "
                        + "combatAttackerNoAttackSelections=%d combatAttackerAgreements=%d combatAttackerDisagreements=%d "
                        + "combatAttackerFallbacks=%d combatAttackerStaleOrRevalidationFailures=%d combatAttackerAvgProviderLatencyMs=%.2f combatAttackerAvgTotalLatencyMs=%.2f",
                COMBAT_EVENTS.size(), COMBAT_EVENTS.stream().filter(CombatAttackersDecisionEvent::providerAttempted).count(),
                COMBAT_EVENTS.stream().mapToLong(CombatAttackersDecisionEvent::optionCount).sum(),
                COMBAT_EVENTS.stream().filter(CombatAttackersDecisionEvent::noAttackSelected).count(),
                COMBAT_EVENTS.stream().filter(e -> e.responseAccepted() && e.heuristicOptionId().equals(e.selectedOptionId())).count(),
                COMBAT_EVENTS.stream().filter(e -> e.responseAccepted() && !e.heuristicOptionId().equals(e.selectedOptionId())).count(),
                COMBAT_EVENTS.stream().filter(CombatAttackersDecisionEvent::fallback).count(),
                COMBAT_EVENTS.stream().filter(CombatAttackersDecisionEvent::staleOrRevalidationFailure).count(),
                COMBAT_EVENTS.stream().mapToLong(CombatAttackersDecisionEvent::providerLatencyMs).average().orElse(0),
                COMBAT_EVENTS.stream().mapToLong(CombatAttackersDecisionEvent::totalLatencyMs).average().orElse(0));
    }

    private static String combatBlockerSummary() {
        return String.format(Locale.ROOT, " combatBlockerDecisions=%d combatBlockerProviderCalls=%d combatBlockerOptions=%d "
                        + "combatBlockerNoBlockSelections=%d combatBlockerAgreements=%d combatBlockerDisagreements=%d "
                        + "combatBlockerFallbacks=%d combatBlockerStaleOrRevalidationFailures=%d combatBlockerAvgProviderLatencyMs=%.2f combatBlockerAvgTotalLatencyMs=%.2f",
                BLOCK_EVENTS.size(), BLOCK_EVENTS.stream().filter(CombatBlockersDecisionEvent::providerAttempted).count(),
                BLOCK_EVENTS.stream().mapToLong(CombatBlockersDecisionEvent::optionCount).sum(),
                BLOCK_EVENTS.stream().filter(CombatBlockersDecisionEvent::noBlockSelected).count(),
                BLOCK_EVENTS.stream().filter(e -> e.responseAccepted() && e.heuristicOptionId().equals(e.selectedOptionId())).count(),
                BLOCK_EVENTS.stream().filter(e -> e.responseAccepted() && !e.heuristicOptionId().equals(e.selectedOptionId())).count(),
                BLOCK_EVENTS.stream().filter(CombatBlockersDecisionEvent::fallback).count(),
                BLOCK_EVENTS.stream().filter(CombatBlockersDecisionEvent::staleOrRevalidationFailure).count(),
                BLOCK_EVENTS.stream().mapToLong(CombatBlockersDecisionEvent::providerLatencyMs).average().orElse(0),
                BLOCK_EVENTS.stream().mapToLong(CombatBlockersDecisionEvent::totalLatencyMs).average().orElse(0));
    }

    private static String combatDamageOrderSummary() {
        return String.format(Locale.ROOT, " combatDamageOrderDecisions=%d combatDamageOrderProviderCalls=%d combatDamageOrderOptions=%d "
                        + "combatDamageOrderAgreements=%d combatDamageOrderDisagreements=%d "
                        + "combatDamageOrderFallbacks=%d combatDamageOrderStaleOrRevalidationFailures=%d combatDamageOrderAvgProviderLatencyMs=%.2f combatDamageOrderAvgTotalLatencyMs=%.2f",
                DAMAGE_ORDER_EVENTS.size(), DAMAGE_ORDER_EVENTS.stream().filter(CombatDamageOrderDecisionEvent::providerAttempted).count(),
                DAMAGE_ORDER_EVENTS.stream().mapToLong(CombatDamageOrderDecisionEvent::optionCount).sum(),
                DAMAGE_ORDER_EVENTS.stream().filter(e -> e.responseAccepted() && e.heuristicOptionId().equals(e.selectedOptionId())).count(),
                DAMAGE_ORDER_EVENTS.stream().filter(e -> e.responseAccepted() && !e.heuristicOptionId().equals(e.selectedOptionId())).count(),
                DAMAGE_ORDER_EVENTS.stream().filter(CombatDamageOrderDecisionEvent::fallback).count(),
                DAMAGE_ORDER_EVENTS.stream().filter(CombatDamageOrderDecisionEvent::staleOrRevalidationFailure).count(),
                DAMAGE_ORDER_EVENTS.stream().mapToLong(CombatDamageOrderDecisionEvent::providerLatencyMs).average().orElse(0),
                DAMAGE_ORDER_EVENTS.stream().mapToLong(CombatDamageOrderDecisionEvent::totalLatencyMs).average().orElse(0));
    }

    private static List<String> damageOrder(CombatDamageOrderDecisionContext context, String optionId) {
        return context.options().stream().filter(o -> o.optionId().equals(optionId))
                .map(CombatDamageOrderOptionView::orderedRecipientIds).findFirst().orElse(List.of());
    }

    private static long countCandidates(int count) {
        return ACTION_EVENTS.stream().filter(e -> e.acceptedCandidateCount() == count).count();
    }

    private static long actionProviderCalls() {
        return ACTION_EVENTS.stream().filter(MainPhaseDecisionEvent::providerAttempted).count();
    }

    private static long actionExternalDecisions() {
        return ACTION_EVENTS.stream().filter(e -> e.source() == AiDecisionSource.EXTERNAL_PROVIDER).count();
    }

    private static long actionDisagreements() {
        return ACTION_EVENTS.stream().filter(e -> e.source() == AiDecisionSource.EXTERNAL_PROVIDER
                && !e.heuristicActionId().equals(e.selectedActionId())).count();
    }

    private static long actionFallbacks() {
        return ACTION_EVENTS.stream().filter(MainPhaseDecisionEvent::fallback).count();
    }

    private static List<Long> actionLatencies() {
        return ACTION_EVENTS.stream().filter(MainPhaseDecisionEvent::providerAttempted)
                .map(MainPhaseDecisionEvent::providerLatencyMs).sorted().toList();
    }

    private static double averageActionLatency(boolean providerOnly) {
        return ACTION_EVENTS.stream().filter(e -> !providerOnly || e.providerAttempted())
                .mapToLong(providerOnly ? MainPhaseDecisionEvent::providerLatencyMs
                        : MainPhaseDecisionEvent::totalLatencyMs).average().orElse(0);
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private static Map<Integer, Long> selectedPositions() {
        Map<Integer, Long> positions = new java.util.TreeMap<>();
        ACTION_EVENTS.forEach(e -> {
            String id = e.selectedActionId();
            if (id != null && id.startsWith("ACTION_")) {
                try {
                    positions.merge(Integer.parseInt(id.substring(7)), 1L, Long::sum);
                } catch (NumberFormatException ignored) {
                    // Invalid provider IDs are recorded as fallbacks, not as candidate positions.
                }
            }
        });
        return positions;
    }

    private static Map<String, Long> selectedCategories() {
        Map<String, Long> categories = new java.util.TreeMap<>();
        ACTION_EVENTS.forEach(e -> categories.merge(value(e.actionCategory()), 1L, Long::sum));
        return categories;
    }

    public record GameRunMetadata(int gameIndex, Long runSeed) {
    }

    public record MainPhaseRoutingDiagnostics(
            long windowsConsidered,
            long notActivePlayer,
            long stackNonempty,
            long enumerationFailures,
            long zeroSafeActions,
            long oneSafeAction,
            long twoOrMoreSafeActions,
            long rawCandidates,
            long evaluatedCandidates,
            long acceptedCandidates,
            long exposedCandidates) {
    }

    private record PendingActionAudit(MainPhaseDecisionEvent event, MainPhaseDecisionContext context) {
    }

    private record PendingStackAudit(StackResponseDecisionEvent event, StackResponseDecisionContext context) {
    }
    private record PendingDamageOrderAudit(CombatDamageOrderDecisionEvent event, CombatDamageOrderDecisionContext context) { }
    private record PendingBlockAudit(CombatBlockersDecisionEvent event, CombatBlockersDecisionContext context) { }

    private record PendingCombatAudit(CombatAttackersDecisionEvent event, CombatAttackersDecisionContext context) { }

    private static void closeAuditWriter() {
        if (auditWriter != null) {
            try {
                auditWriter.close();
            } catch (IOException ex) {
                Logger.warn(ex, "Unable to close external AI decision audit");
            }
            auditWriter = null;
        }
    }

    private static boolean isRejectedResponse(AiDecisionFailureReason reason) {
        return reason == AiDecisionFailureReason.EMPTY_RESPONSE
                || reason == AiDecisionFailureReason.MALFORMED_RESPONSE
                || reason == AiDecisionFailureReason.INVALID_OPTION
                || reason == AiDecisionFailureReason.DECISION_ID_MISMATCH
                || reason == AiDecisionFailureReason.FINGERPRINT_MISMATCH;
    }

    private static boolean isProviderFailure(AiDecisionFailureReason reason) {
        return reason == AiDecisionFailureReason.TIMEOUT
                || reason == AiDecisionFailureReason.CONNECTION
                || reason == AiDecisionFailureReason.HTTP_ERROR
                || reason == AiDecisionFailureReason.PROVIDER_EXCEPTION;
    }

    private static long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    private static String csv(Object value) {
        String text = value(value).replace("\"", "\"\"");
        return "\"" + text + "\"";
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }
}
