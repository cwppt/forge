# Bounded combat parity follow-up

Original experiment and frozen JARs remain under
`D:/ForgeData/forge-experiments/commander-cost-performance-v2-20261027/validation-03`.
No completed benchmark or scored smoke game is replayed for recovery.

The follow-up is implemented in a separate source checkout. The four hook classes
(GameAction, MagicStack, TriggerHandler and CommanderRoleTelemetry), including
their generated nested classes, must compile from unmodified controls to the same
normalised javap instructions as the frozen baseline before diagnostic engines
can be created. Both diagnostic derivatives receive identical observer classes.
All other engine entries remain identical to their respective frozen engine.
The derivatives are diagnostic instruments, not replacement benchmark engines.

`CombatParityTrace` is enabled only with `-Dforge.parity.trace=<new-file>` and the
existing role telemetry option. It observes native assignments, replacement-
adjusted damage maps, nested simultaneous damage batches, damage recipients and
amounts, trigger contexts, static dispatch, stack additions/resolution, zone
events and battlefield/life/counter state at relevant boundaries. It never
consumes RNG or evaluates extra actions. Copied AI games have no registered
observer. Default-off production behaviour is unchanged; any observer IO or
subscriber error makes the diagnostic trace fail rather than silently pass.

The comparator preserves event order. Only consecutive DAMAGE deliveries within
an explicitly identified simultaneous **combat** batch are compared as a
multiset. Ordered trigger creation/resolution, state, nested batches, requested/
replacement-adjusted damage, selected assignments and blocker order must match.
Noncombat damage remains ordered. Raw traces are retained in original order.
Unknown trigger context types prevent a completeness claim and stop the gate.
This is bounded observation of tested games, not a proof for all Commander rules.

Eight comparator tests reject altered assignments, blocker order, recipients,
amounts, triggers, resolution order, states and incomplete traces. A native Java
test verifies real simultaneous damage boundaries, amounts and resulting state.
The supervisor runs that test with the previous 34 Java regressions on each
diagnostic engine, plus both seven-mode process-integrity suites.

The finite stages are:

1. Three fresh JVM runs of original frozen baseline seed 572101, compared with
   preserved role/game logs and independently validated outcomes. These are
   explicitly labelled repeatability tests, not replacement scored attempts.
2. Two diagnostic-baseline and one diagnostic-candidate run of seed 572101.
   Structured comparisons and unchanged outcomes must pass, with all required
   telemetry present and no unexpanded trigger contexts.
3. Only then, one original frozen baseline/candidate pair for previously unrun
   seed 572102. Exact ordered role/game logs and independently validated outcomes
   must match; any new unresolved difference stops the run.

The supervisor holds the existing global worker lock, admits one worker with the
7 GiB memory allowance, checks the actual-volume 3 GiB free-space reserve, uses
bounded worker watchdogs and reaps expired children. It refuses duplicate starts
and never blindly restarts ambiguous work. Frozen source, engine, deck, helper,
test-class and original checkpoint hashes are checked before native execution.
No external model calls, production cohort, merge or promotion is included.

Prepare with `parity_run.py <new-output-directory> --prepare`, commit the source,
then `parity_run.py <output> --freeze`. Launch the same command without flags via
pythonw.exe / Start-Process -WindowStyle Hidden. Verify started/heartbeat/child and
GitHub publication once; ongoing Codex supervision is unnecessary. The report
follow-up and combat-parity.json update automatically at the existing cost-v2
report URL with optimistic SHA ownership checks. Original raw evidence,
measurements, report history and checkpoint journals remain preserved.

After parity is resolved, target blocker-assignment candidate counts and combat-
prediction/search complexity. Do not infer new performance gains from diagnostic
game timings, or further optimise cost adjustment without stronger profile data.
