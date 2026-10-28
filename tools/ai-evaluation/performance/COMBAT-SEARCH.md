# Phase 4: observe actual frozen combat search

Phase 3 completed and failed its two-primary-workload 15% gate. Its optimisation
remains disabled and no smoke games ran. Preparation recomputes the saved Phase 3
comparison without replaying any benchmark operation.

This pass instruments the loaded native bytecode with an opt-in Java agent. It
never recompiles AiBlockController, ComputerUtilCombat or their incomplete-source
dependencies, replaces a result, swallows an original exception, changes budgets,
or prunes legal options. Byte Buddy 1.18.14 is taken from the existing local Maven
repository and frozen by hash. The engine is the shared Phase 3 reference JAR,
with every experimental fast path explicitly false, retaining the validated
correctness engine's actual caches and virtual-budget checkpoints. The separate
0fb7ec... functional baseline is also verified unchanged.

## Actual observations

The agent observes blocker assignment/filter stages, survival and damage predictions,
keyword forecasting, legal queries, creature scoring, cache lookups/clears,
GameCopier methods, LKI copying, last-state snapshots, source traversals and selected
Game/GameCopier/CardCopyService constructor endpoints. Method entries count exactly;
wrapper overloads and constructor chains must not be summed as independent copies
or predictions. Generated/accepted **filter pairs** are counted directly. Complete
multi-block combination enumeration is not measured.

Wall/CPU time and thread allocation are sampled every 32nd method invocation.
Inclusive nested samples overlap and contain observer overhead. Counter-only
traversal/constructor hooks have no latency samples. Log2 latency histograms remain
local; public per-window summaries preserve counts and raw-file SHA256 values.
Stage totals are explicitly overlapping, not an additive CPU-percent breakdown.
Plain/probed paired runs measure instrumentation overhead, not an optimisation gain.

Bounded signature proxies include the method, receiver and argument reference
identities, primitive/list values, thread, decision and observed mutation epoch.
Identity references are compared with ==, avoiding identity-hash assignment to game
keys. Storage caps are 1,024 signatures, 2,048 references, bounded lists/strings and
four changed-result examples per method/window. Every cap is reported. No result
is reused; signature observations are never a cache.

**Identical arguments and an unchanged observed event epoch do not prove identical
combat state.** Hidden zones, delayed/replacement effects, static/keyword changes,
RNG, AI budgets/caches and hypothetical mutations without game events are not all
encoded. Proven-equivalent repeat and exact distinct-state counts remain null.
Different results for the same proxy reject that proposed equivalence; the same
result does not establish safety. No speculative cache is authorised by this run.

## Bounded validation/execution

Preparation compiles only the agent and benchmark observer window hooks. Agent
self-tests verify unchanged returns, exception propagation, exact calls/filter
counts and signature caps. The finite supervisor runs 26 Java regressions in each
plain/probed mode, 64 structured fixture comparisons, seven process-level failure/
result/recovery tests and eight structured comparator tests. Agent journals use
unique process IDs for hard-halt probes; an absent shutdown footer after Runtime.halt
is expected forensic evidence, never a valid outcome.

After correctness passes, two alternating JVM blocks measure diagnostic overhead
on large four-player and Dragon-token blocking (one warmup, two observations).
One additional counter pass covers four busy boards. No Phase 3 timing series is
restarted, no candidate engine comparison is claimed, and no full-game cohort runs.

A future optimisation requires a concrete immutable subproblem with complete
equivalence and explicit decision-scoped lifetime. Its predeclared acceptance is
>=15% median improvement on both primary workloads, three paired JVM blocks,
p95/allocation regression <=5%, exact rules/structured parity, then only a small
matched smoke cohort. This profiling pass never writes such an optimisation based
on proxy counts alone.

The existing global worker lock, one-worker/7 GiB admission, resolved-volume 3 GiB
reserve, isolated native runtimes, bounded watchdogs, failure evidence and SHA-guarded
GitHub publication are reused. No paid/external model calls, merge or promotion.

Prepare with `search_run.py <new-D-output> --prepare`, commit source, then freeze
with `search_run.py <output> --freeze`. Launch the same command without --freeze
via pythonw.exe / Start-Process -WindowStyle Hidden. Verify startup and publication
once; do not continuously supervise with Codex. Duplicate starts are refused.

Source: codex/commander-combat-search-v4. Report:
cwppt/MTG-deck-lists/forge-performance/reports/20261028-native-combat-search-v4/.
