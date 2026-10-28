# Cost-source filtering follow-up

This resumes the interrupted local edits on `codex/commander-cost-performance-v2`.
The original harness, validated correctness JAR and completed combat-v1 evidence
are preserved. No profiling restart or larger cohort is needed.

## Correctness boundary

Both CostAdjustment overloads retain their original ordered snapshot of **all**
battlefield, stack and command-zone cards plus the evaluated host. Each source is
checked individually. The host need not have any modifier for another card's
increase, reduction or minimum cost to apply. Cost copying, condition checks,
choice/order of reductions, intrinsic costs, Commander tax and minimum-cost
application are unchanged. There is no per-card, per-decision or cross-turn cache.

CardState's live source proof checks printed and cached keyword statics. Any
changed traits, text traits, original split state or custom Card subclass causes
the original static-list rebuild. Land/type trait rebuilding can remove statics,
not create a missing mode; newly gained keyword or trait sources are checked on
every invocation. The new fast path is off unless enabled by
`-Dforge.cost.liveSourceFilter=true`. Optional counters use
`-Dforge.cost.sourceMetrics=true`; primary timings do not enable counters/JFR.

The earlier blocker-v1 classes remain in source ancestry with their default-off
switch. They are **not** overlaid into this engine. The candidate overlays only
CardState, CostAdjustment, their generated classes and CostSourceMetrics. All
other baseline JAR entries remain byte-identical.

## Preserved local baseline dependency

The initial preparation correctly refused a bytecode mismatch: the frozen JAR's
CostAdjustment included LamiaObservation.start/record calls absent from committed
source. These calls were reconstructed in the isolated source and control copy.
Preparation then required the entire control class's normalised javap output to
match the frozen class exactly, including diagnostic calls. Existing Lamia classes
are retained from the frozen engine, never rebuilt or removed. This preserves
logging fidelity and prevents comparing against an inadvertently changed engine.

The first Java regression attempt found a test-fixture error on the **baseline**:
a synthetic hand-host modifier had the default battlefield-only activation zone.
The fixture now explicitly activates in Hand. Failed preparation/test evidence is
preserved in the parent run and validation-02; it was not overwritten or scored.

## Validation and reproduction

Eight focused cost tests cover external Sphere of Resistance and Helm of
Awakening, Thorn of Amethyst conditions, dynamic text/layer traits, keyword-added
modifiers, live removal/suppression, split states, the hand host, cost-copy
ownership, minimum-cost ordering with Trinisphere, intrinsic costs and Commander
tax. They supplement the prior 26 Java regressions on each engine.

Use Python 3.12 on the existing Windows host, a fresh output directory on D:,
and the frozen correctness engine/resources/decks:

```
cost_performance.py <output> prepare
cost_performance.py <output> initial
```

Commit all source, then freeze and start the finite supervisor:

```
cost_run.py <output> --freeze
pythonw.exe -B cost_run.py <output>
```

Launch using PowerShell Start-Process with WindowStyle Hidden and verify started,
heartbeat and publication records once. Do not duplicate an active supervisor.
The existing global worker lock, one-worker memory admission and 3 GiB actual
volume reserve are retained. An ambiguous interrupted run requires evidence
review; it is not silently replayed. No external model calls are enabled.

The reused supervisor first runs failure/outcome/report/recovery regressions and
40 canonical workload comparisons. It then records bounded JFR/counter profiles,
three alternating-order JVM timing blocks (two warmups, four observations each),
the ten-fixture suite and three matched seeds per engine. Unexpected rules or
trajectory differences stop subsequent work. Percentiles and exploratory block
bootstrap statistics have limited sample size; no performance claim is automatic.

Report: `cwppt/MTG-deck-lists/forge-performance/reports/20261028-native-cost-performance-v2/`.
It updates only files owned by this new run. Source/input/helper hashes are frozen
and checked before each worker. No merge, production promotion or large cohort.
