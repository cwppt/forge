# Incremental programme v1 — Phase 5 candidate 01

One bottleneck, one opt-in candidate, measured history and an explicit stop decision.
Source is isolated from the dirty original harness. No prior experimental engine
changes are combined. Functional baseline 418dac868f3 and JAR 0fb7ec... remain frozen.
The permanent measurement reference is the previously verified structured-observer
derivative 5d5b2603...; all other native AI classes/budget/cache code remain untouched.
Preparation compares original CombatUtil bytecode before overlaying only that class
and RequirementSnapshotMetrics. Prior experiments stay in their original branches.

## Narrow proof

Phase 4 measured substantial makeGoodBlocks/makeTradeBlocks/makeGangBlocks work,
prediction calls and board access. It did not prove equivalent game states; this
candidate never caches predictions.

Their common requirement search supplies a newly constructed combat-blocker snapshot
to attackerLureSatisfied. That helper only reads the list for the `CARDNAME must be
blocked...` count requirements and `MustBeBlockedBy...` validity families. Each call
rechecks live prefixes (including dynamically gained/hidden keywords). If neither
family exists, the helper receives immutable CardCollection.EMPTY; none of its
branches can inspect the list. Otherwise it receives the original fresh snapshot.
The helper body, cost evaluations (including RNG expressions), candidate/assignment
order, rules and budgets are unchanged. The parameter becomes a read-only view.
No cache, saved result, cross-call lifetime or invalidation assumption exists.

Default-off switch: `-Dforge.blocker.lazyRequirementSnapshots=true`.
Aggregate counters: `-Dforge.blocker.snapshotMetrics=true` (diagnostic passes only).
No source AI controller or combat prediction class is recompiled from incomplete
source. The frozen Phase 4 observation agent is reused only for separate counts;
it is absent from primary timings and all cohort throughput measurements.

## Gates and permanent baseline

Initial validation runs 32 Java methods per arm and frozen/structured large-board
parity. A synthetic power-validity fixture failed on the unchanged baseline and was
replaced by a native Giant subtype fixture; the failed attempt remains preserved.
The finite runner repeats failure/result/recovery tests, 8 strict comparator tests,
6 programme-gate tests and 64 fixture/action comparisons, including strike/trample/
deathtouch/restriction boards. It also compares final canonical results for the
original unmodified functional engine across those fixtures.

Only after correctness passes, establish three independently validated native
four-player reference games with frozen decks/seeds 573100–573102. The immutable
`permanent-baseline.json` records artifact/flag/deck provenance and per-game hashes.
Whole-game profile, evaluation/fallback diagnostics, GC, reported heap peaks,
engine duration and elapsed cohort throughput are retained. Missing diagnostic
coverage is null, not a fabricated zero. Valid native outcomes remain valid if a
separate analysis/parity gate fails; those failure categories are reported apart.

Timing rotates functional-original / matched observation-reference / candidate
order over three JVM blocks. Two warmups precede four observations of each workload.
The same frozen fixtures, seed, 3 GiB heap, two CPUs, 500 ms/2,000 checkpoints and
normal diagnostics apply. Existing Phase 3/4 measurements are not replayed or
substituted for this new controlled comparison.

Predeclared gates:

- Experimental: >=2% median improvement on both primary blocker boards, improvement
  in all three JVM blocks and exploratory block-bootstrap upper ratio <1; no >5%
  median/p95/CPU/current-thread-allocation regression in measured combat workloads.
- Promotion consideration: additionally >=15% median gain on both primaries and
  all correctness/native gates. Never an automatic promotion.

Three JVM blocks provide limited statistical evidence. Smaller credible results
are experimental building blocks, not promotion success. Negligible/noisy results
stop this candidate without further refinement absent new profiling evidence.

Only after the experimental gate passes, run the candidate on the same three
seeds, reusing the completed reference games. Structured comparison permits only
consecutive delivery permutations inside explicit simultaneous combat damage
batches; assignments, amounts/recipients, triggers, stack and state remain exact.
No global sorting or silent discrepancy suppression is allowed.

## History and throughput

`performance-history.json` records the original functional engine, the permanent
observation reference, previous unadopted Phase 3 candidate (historical context only)
and this candidate. Synthetic incremental ratios use directly paired reference/
candidate observations. Cumulative ratios use directly measured functional-original/
candidate timings in this run; percentages from different experiments are never
added. Historical Phase 3 data are not a concurrent denominator or a combined gain.

Whole-game throughput compares identical structured-observer configurations and
matched decks/seeds. Elapsed wall time includes JVM startup, admission, isolation
and independent validation; GitHub publication occurs outside the measured cohort
interval. Synthetic gains do not imply throughput gains. Bare-original whole-game
throughput/cumulative gains are not claimed without a directly comparable run.
Three games per arm give descriptive throughput and failure data, not robust
population estimates. Synthetic CPU/allocation counts are current-thread only.

## Autonomous execution

Use `incremental_performance.py <fresh-D-output> --prepare`, then `--initial`.
Commit source; freeze with `incremental_run.py <output> --freeze`. Launch the same
command without --freeze through pythonw.exe / Start-Process -WindowStyle Hidden.
Verify startup, heartbeat, child and publication once, then close Codex.

The existing global worker lock, one-worker/7 GiB admission, actual-volume 3 GiB
reserve, watchdogs, validators and SHA-guarded publication are reused. Started
markers reject duplicates/blind replay; ambiguous interrupted work requires review.
Completed games are hashed and preserved, never replaced to reach a count. No
external/paid models, production cohort, merge, promotion or automatic second
candidate. Any meaningful rules/AI divergence rejects the experiment.

Source: codex/commander-incremental-performance-v5.
Report: forge-performance/reports/20261028-native-incremental-performance-v5-01/
in cwppt/MTG-deck-lists. The report exposes current stage, machine measurements,
versioned history, baseline/cohort provenance, engine hashes and final decision.
