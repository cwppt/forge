# Phase 7B: speculative RNG ownership

This worktree is isolated on D:, branch codex/commander-rng-isolation-v7b. No engine
class, production flag, AI budget, comparator or historical result is modified.

## Proven native mechanism

boundedPrediction manages advisory depth and budget exits, not RNG ownership.
Hypothetical blocker assignment calls ComputerUtilCombat.lifeInDanger, which uses
nextInt(80) and profile-dependent nextInt(100) draws. Without a thread-local scope,
these advance the same seeded MyRandom provider later used by gameplay choices.

Ten Java fixture methods count actual protected Random.next(bits) operations,
not provider lookups. One native prediction uses one global draw; five use five.
The next committed int differs with unchanged controlled combat context. The first
mismatched fixture consumer is ordinal two: one search commits, the other is still
speculating. This is not the first proven original Atsushi draw.

The recorder preserves java.util.Random sequences over controlled seeds. The small
vanilla-card fixture fingerprints ordered zones, identity/control/tap/PT/damage/
counters, life, combat inputs and relevant AI-profile thresholds. It does not
reconstruct the original hidden game state or prove arbitrary state equivalence.

Normal completion, real 500 ms timeout, 2,000-work exhaustion, exception, nesting,
worker boundaries and cooperative cancellation are tested. Global draws already
consumed are not rolled back. Cancellation tests await both acknowledgement and
executor termination before asserting no more draws. cancel(true) alone is never
termination proof; existing process-failure regressions remain mandatory.

## Ownership and isolation limits

SimulateMatch replaces initial SecureRandom with a process-wide Random(seed).
Unscoped threads share it. withRandom installs a current-thread override and
restores its predecessor in finally, including nested failures; it is not inherited
by worker threads. Existing copied-action evaluation scopes some RNG access using
candidate-derived seeds, but native bounded prediction is not uniformly covered.
The alternate SpellAbilityPicker source saves/replaces providers and draws a seed
from the original provider; it requires a separate audit before a general patch.
Its source presence is not proof it ran in the original standard-AI experiment.

The only prototype uses the EXISTING withRandom API inside test fixtures with an
explicit private seed, including explicit installation in workers. It preserves
the committed draw across tested depths and exits. No production flag is added:
a stable decision-identity seed scheme, advisory boundaries, direct retained Random
references and every worker handoff are not yet proven safe. Rewinding a shared
RNG would be unsafe. Real Magic shuffles/targets/effects must remain outside any
private advisory scope. Future AI behaviour changes require a separate written
validation policy, not a silently relaxed comparator.

Controlled Atsushi histograms are reported, not forced to show a change. This
constructed state produced 16 Dig choices in each of four configurations; no
claim about optimality or general unchanged AI behaviour follows.

## Historical evidence and finite execution

Phase 7A's baseline/candidate window endpoints imply 60/92 primitive LCG advances
from the same start. They are interval deductions, not actual operation recordings
or exclusive-thread proof. Original draw origin/Atsushi causality stays unresolved.
All six old diagnostics remain immutable and are not repeated.

The detached plan adds two fixture JVM repeats, 38 existing Java regression methods,
seven native failure checks, 18 independent-validator tests and unchanged-engine
64-fixture structured parity. Zero full games or JVM speed comparisons. The
historical 12.8% result is not newly validated. Fixture timing includes bounded
actual-draw tracing and intentional waits; no production allocation or throughput
overhead is claimed.

Use rng_run.py <fresh-output> --prepare, --initial, --freeze, --verify-only, then
launch without an option as a hidden detached supervisor. It uses the existing
global lock, one-worker memory admission, 3 GiB disk reserve and native timeout
safeguards. Its exclusive local lock and one-shot marker prevent duplicates.
Interrupted or ambiguous child evidence blocks blind replay and requires review.
It publishes finite checkpoints independently to 20261028-native-rng-isolation-v7b.
No paid AI, source merge, RNG policy adoption, JVM promotion or Phase 8.
