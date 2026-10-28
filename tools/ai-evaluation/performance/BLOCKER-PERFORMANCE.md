# Phase 3: blocker requirement enumeration

Functional baseline: correctness commit 418dac868f3f48349ff0b5d35e9197c65008d6e6,
frozen engine SHA256 0fb7ec0758c186ee8d1e85efc6e1fe0a7754f50ff0e5a8433366f484651fc274.
Structured observer is cherry-picked from e821e2b1030. Earlier cost-adjustment
and block-tax fast paths are absent from production sources here and explicitly
disabled in launch flags. Original harness and historical experiments are untouched.

## One optimisation and proof lifetime

Saved JFR evidence repeatedly enters CombatUtil.getBlockCost from
mustBlockAnAttacker, inside canBlock/getPossibleBlockers. The original requirement
loop repeats a board-wide cost scan for each attacker even if no blocking-cost
source exists. This compounds attacker/blocker enumeration rather than changing
which meaningful assignments are considered.

The candidate performs one complete static-source scan per requirement-query
invocation. If no ability has CantBlockUnless mode, all subsequent cost lookups
in that invocation are guaranteed null. If any potential source exists, including
inactive/suppressed/conditional sources, every original lookup remains in its
original order. The proof uses original live getStaticAbilities rebuilds, including
text/layer/keyword changes. No result survives the method call; no persistent cache
or game-state invalidation machinery is introduced. Specific must-block-card checks,
legal candidate order, predictions, budgets and AI policy remain unchanged.

The random-tax regression deliberately uses Count$Random.1.6 to ensure potential
taxes retain original RNG consumption. Dynamic external tax addition/removal,
suppression, trait gains, lure, satisfied requirements, forced blockers, flying,
reach and menace are covered. Six additional deterministic rule boards exercise
first/double strike, deathtouch/trample, menace, lure and restrictions; verification
advances the actual first-strike and regular damage phases before resolution.

## Shared reference and candidate

The same diagnostic JAR is used in both arms. Baseline startup flag:
`-Dforge.blocker.hoistCostPresence=false`; candidate: `true`. Default is false.
Both share identical observer/counter code and all other flags. Controls for every
recompiled baseline class must match frozen JAR bytecode before overlaying anything;
all other entries remain identical. The frozen AiBlockController has extra local
virtual-budget checkpoints absent from committed source. Its failed control check
is preserved, and that class is never recompiled/overlaid. Frozen ComputerUtilCombat
cache/prediction dependencies are also retained. A complete Maven rebuild is not a
substitute for this verified overlay workflow.

Optional `-Dforge.blocker.metrics=true` counts legal-pair queries and accept/reject,
requirement candidates, tax lookups and tax/proof static-source visits. It also
measures inclusive legality-query elapsed time. These are not exhaustive multi-block
assignment or equivalent-position counts. Exact hypothetical-combat, game-copy and
duplicate-position counts are unavailable; no zero counts are fabricated. Counters
and bounded JFR are enabled only in separate diagnostic passes, never primary timing.

## Finite validation and acceptance

Preparation/initial validation runs 26 Java methods per arm, then compares a large
board to the unmodified frozen engine and between both comparison modes. Extended
gates use the ten-board suite plus six rule boards, all four action paths, and
structured traces. Eight strict comparator tests and four performance-gate tests
supplement process-level timeout/failure/draw/recovery and independent validator
regressions. Structured comparison preserves trigger/resolution/state order and
only permits consecutive delivery permutations within explicit simultaneous combat
damage batches. Raw events are never globally sorted.

Predeclared performance gate: both primary blocker workloads improve median latency
by >=15%; all three alternating-order JVM blocks improve, exploratory block-bootstrap
upper ratio <1; primary p95 and allocations regress no more than 5%; no focused
blocker median regresses >5%. Target 20% is desirable, not assumed. Focused timings
use identical seeds/fixtures, two excluded warmups and four observations per JVM
block, 3 GiB heap and two advertised CPUs. The old generic 240-operation full timing
suite is not replayed. New controlled A/B measurements are required; historical
3,762/4,055 ms medians are not substituted for this baseline.

Only if correctness and performance pass, run three matched native four-player
seeds (572200–572202), independently validate all outcomes and compare full structured
trajectories. No production cohort or automatic merge/promotion. If the single
candidate fails a gate, preserve findings without trying another optimisation.

The reused supervisor retains the global worker lock, one-worker/7 GiB admission,
3 GiB free-space reserve on lexical and actual output volumes, bounded watchdogs,
failure journals, frozen source/test/engine/deck/helper hashes and no external model
calls. Duplicate starts and blind replay of ambiguous work are refused.

On the configured Windows host, use a fresh D: output directory:

```
blocker_performance.py <output> --prepare
blocker_performance.py <output> --initial
```

Commit source, then `blocker_run.py <output> --freeze`. Launch the same command
without --freeze via pythonw.exe and Start-Process -WindowStyle Hidden. Check the
initial heartbeat, child and GitHub publication once, then close Codex.

Source branch: codex/commander-blocker-performance-v3. Report destination:
cwppt/MTG-deck-lists/forge-performance/reports/20261028-native-blocker-performance-v3/.
