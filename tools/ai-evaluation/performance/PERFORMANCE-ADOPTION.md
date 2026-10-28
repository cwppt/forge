# Phase 7C: final cumulative source/JVM experiment

All four requested historical source candidates coexist: phase-1 block-cost filter,
phase-2 live cost-source filter, phase-3 invocation-local blocker-tax proof and
phase-5 unused requirement-snapshot elimination. No candidate is excluded for a
small historical gain. The abandoned EmptyTrigger revision is not included.

The isolated worktree is on D:, branch codex/commander-performance-adoption-v7c.
Exact historical input hashes and commits are in composition.json. The three
modified source classes are CardState, CostAdjustment and CombatUtil; four helper
classes implement opt-in switches/counters. No entire historical tree is rebuilt.

## Executable integrity and effective features

Original source controls compile instruction-equivalently to the observation
reference, including required local LamiaObservation diagnostics absent from an
older committed tree. All unaffected methods are checked. Generated nested
classes retain original bytes after instruction-equivalence checks. The overlay
changes/adds only seven JAR entries; every other entry is identical, including
363 critical AI/RNG/timeout/observer classes. Native AI budget patches are not
recompiled from incomplete historical source.

A/C use exact observation reference 5d5b2603...; B/D use combined 9107411b... .
A=all source flags false, normal JVM. B=all four true, normal JVM. C=all four
false, -XX:-DontCompileHugeMethods. D=all four true plus that JVM flag.
Supplemental control O validates the combined JAR with all source flags false;
it is not a fifth performance arm.

Test-class shadows are removed only from the new isolated test output and retained
as provenance. Runtime tests check class origins, actual feature fields and the
HotSpot option, and exercise each fast path with positive counter deltas. Accepted
JVM property strings alone are insufficient. Optional counters run only for
focused canaries; primary performance preserves normal game telemetry and logging.

## RNG safety and classification

The final assessment remains incomplete for production isolation: thread-local
ownership is not inherited, direct saved Random references can bypass it, and a
stable decision/game identity seed scheme and downstream advisory boundaries have
not been demonstrated. The existing fixture-local private seed is not adopted.
No RNG isolation, rule, heuristic or 500 ms / 2,000-work budget change is made.

These four-arm comparisons are diagnostics. A is the existing validated fallback,
not a newly certified RNG-deterministic engine. B/C/D remain experimental while
production RNG/comparability is unresolved, even if observed speed exceeds 10%.
A strict trajectory failure is retained, never sorted or waived; it is not
attributed to the JVM or RNG without evidence. No source merge/default change.

## Finite gates and measurements

Focused Java runs exercise all arms and O, including historical dynamic modifier
regressions, 10 RNG ownership tests and new feature interaction/provenance checks.
Four seven-mode native failure suites, independent validator tests and full
64-fixture legal/assignment/damage/trigger/stack/state parity run before timings.
The comparator is unchanged.

Synthetic measurements use four rotated JVM blocks, one warmup and three repeated
samples over four busy-board workloads and attack/block paths. Direct B/A, C/A,
D/A, D/C and D/B CPU/wall/allocation/GC comparisons and block-ratio ranges are
reported; historical percentages are not added. Small gains are reported without
an automatic rejection merely for missing a target. The historic 15% blocker
benchmark target is not redefined.

Then exactly three matched diagnostic seeds 573400--573402 use the three frozen
Phase 6 variants and common opponents. This is 12 games, not a deck-strength
cohort. Nine-seed extension is disabled because production RNG safety is not
established. All six pairwise trajectory comparisons are evaluated per seed,
including final outcomes and native role/decision records. Independent validation
and failure evidence are mandatory; failed games stop the run without replacements.

A 10% active whole-game elapsed reduction remains the numeric clear-pass target.
Its attainment is distinct from safety/adoption and cannot establish causal speed
when gameplay trajectories differ. CPU is read from the supervisor's own engine
and validator child processes; unavailable counters remain null. Kernel working
set and turn-boundary heap/GC coverage limitations are explicit. Active time
includes admission, startup, game, independent validation and analysis. Per-game
publication time is separate, including any later recovery publication.

## Detached operation and recovery

After building and compiling tests, run adoption_run.py <output> --initial, then
--freeze and --verify-only. Launch without an option as a hidden detached process.
The existing global lock, one-worker memory admission, 3 GiB reserve on lexical and
resolved junction volumes, timeout/result protections and local exclusive lock
remain mandatory. Original workspaces, outcomes and raw evidence are preserved.

Explicit --resume is restricted to a completed synthetic/cohort boundary. It
requires all 16 timing JVM checkpoints and prior correctness gates, rechecks every
frozen input and every existing native validation proof, preserves a pre-resume
checkpoint and refuses any incomplete/failed native attempt. Completed games are
reused, not replayed; no failed seed is replaced. Interrupted earlier stages require
manual evidence review instead of blind restart. The one-shot start marker and
locks refuse duplicate supervisors. Recovery downtime is present in total cohort
wall time, not invented as active simulation time.

Two inactive Chainer archives were hash-verified onto D: with their original paths
preserved by junctions to restore the reserve. Storage manifests are retained in
this run and D:/ForgeData/archived-harness-work. No evidence was discarded.

The report is 20261028-native-performance-adoption-v7c. It publishes checkpoints
independently and stops after the bounded comparison. No paid AI, automatic merge,
promotion, new optimisation phase or production deck cohort. Next priority is
Lathliss, Rivaz and Gruul testing on the best validated configuration.
