# Phase 7C recovery: optional benchmark counter dependency

## Confirmed root cause

Original full-parity-A exited 1 because BusyBoardBenchmark line 119 directly
referenced forge.game.combat.RequirementSnapshotMetrics. The validated reference
JAR never contained that optional phase-5 counter helper. Previous successful
control runs supplied it from test-classes. Phase 7C removed engine-package test
shadows correctly, exposing the benchmark's mandatory linkage. TestNG's actual
trace is NoClassDefFoundError/ClassNotFoundException, not a failing rules assertion.
No measured BUSY_BENCH result was produced before failure.

The original command, stdout, exit record, XML and partial trace remain unchanged
in validation-01/full-parity-A. The full child environment was not persisted;
command/runtime isolation and frozen helper hashes are available. Explicit -cp
makes the missing-class cause independent of an ambient CLASSPATH setting.

## Minimal test-only correction

BenchmarkOptionalMetrics reads counters only when enabled. An absent provider
returns unavailable null, never fabricated zero; malformed or failing present
providers still fail. The benchmark's board cases, seeds, action execution,
assertions, trace generation and strict comparator remain unchanged. No helper is
added to forge.game on the control classpath and no engine is rebuilt.

The corrected A command differs in output/classpath roots and corrected test
bytecode, not engine flags, workloads, budgets or resource limits. Corrected A
passed all 64 workloads and strict comparison against the prior successful
unchanged control. Six adapter compatibility methods passed under A/B/C/D.
Twelve recovery/comparator tests prove exact optional-metrics-only source edits,
unchanged comparator/engine source and the existing negative parity cases.

All source optimisation implementations and binaries are unchanged:

* Control: 5d5b2603a4796a6d0b5281e18554908fffa9fe987028bf1616944d9112fe0803
* Combined: 9107411b42f66408f0e9ffae47fd095210095bf4484d88a34b6fbd2a30193dd4

Only BusyBoardBenchmark.class and new benchmark-adapter/test classes are compiled.
Every other copied test class retains its frozen hash; engine-shadow intersections
are rejected. The original four source features and JVM-option activation were
already verified by 64 focused Java tests per arm; all four failure suites and
18-method independent validator passed. Those complete stages are reused by
immutable evidence hashes, not replayed.

## Separate finite continuation

Local recovery output is validation-01/recovery-01. Publication is under the
existing Phase 7C report's recovery-01 subdirectory, with a link to the immutable
original failure. No parent checkpoint, result or report is overwritten.

adoption_recovery.py <output> --prepare compiles only the test repair, --adapter-tests
runs the six compatibility methods in four configurations, and --control runs
corrected full A plus prior strict parity. After committing, --freeze and
--verify-only protect original and corrected evidence. Launch without an option
as a hidden detached supervisor.

The continuation checks B/C/D and the supplemental disabled-source O against
corrected A, then runs the original four rotated synthetic JVM blocks and exactly
three matched diagnostic seeds 573400--573402 per configuration where gates pass.
All B/A, C/A, D/A, D/C and D/B comparisons remain direct observations; publication
time remains separate from active elapsed. No historical percentages are added.
Games retain timeout/cancellation safeguards and independent validation. Shared
speculative RNG ownership remains incomplete: no isolation flag or faster-JVM
promotion is introduced, and divergent gameplay is never a pure-speed claim.

The existing global worker lock, one-worker memory admission, 3 GiB reserve,
exclusive local lock, one-shot marker and guarded cohort recovery remain in force.
Failed/incomplete attempts block blind replay. New classification metadata uses
VALIDATED for the unchanged reference and EXPERIMENTAL for insufficiently validated
replacements; no defaults change or automatic promotion. A required-gate failure
stops the finite run and preserves evidence. Synthetic results remain published
if a later game gate blocks whole-game measurements.

No additional optimisation exploration or candidates. After this finite comparison,
return to Lathliss, Rivaz and Gruul testing on the best validated engine. No deck
cohort launches automatically from this recovery.
