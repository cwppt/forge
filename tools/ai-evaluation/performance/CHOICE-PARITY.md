# Phase 7A: Atsushi mode divergence

Original Phase 7 remains `failed-preserved`. Token 1699 is the first structured
difference; the preceding token stream and last public boundary state match.
Hidden zones and RNG state were not captured in the original, so this does not
prove an identical complete pre-choice state. No original game is replaced,
rescored or rerun in its historical directory. The JVM candidate remains off.

The native Charm path shuffles available modes with `MyRandom`, evaluates their
native willingness in that order, then uses mandatory-trigger fallback passes if
necessary. `TokenAi` also has a profile-based random acceptance check; `DigAi`
checks library, hand, phase and related restrictions. A choice difference can
therefore arise from RNG consumption before the trigger even with unchanged
public state. It does not by itself identify superior play or budget exhaustion.
Source and actual frozen bytecode are inspected together.

The dedicated `choice-probe` javaagent observes only:

* Atsushi controller/Charm entry and exit, chosen modes and shuffled options.
* Native submode willingness in `canPlayWithSubs` (mandatory fallback return
  details are not separately instrumented).
* Read-only 48-bit `java.util.Random` seed/provider state within those scopes.
  No RNG replacement, reseeding, draws or result substitution occurs.
* Existing active evaluation-budget fields and selected ordered zone/card state.
* RNG/budget endpoints around turn 40, seat 1, combat begin: the first meaningful
  differing original scope (baseline 513.6 ms / 1,630 work / 106 fallbacks versus
  candidate 264.5 ms / 2,003 work / 3 fallbacks). Up to 64 RNG-provider call sites
  are captured within that scope; the per-window cap is explicit.
* Global `getRandom` provider-request counts per thread, not RNG draw counts.

The observer records major hidden zones only in local evidence. Published
summaries contain snapshot hashes, relevant hand/top-library cards, options,
native checks and budget/RNG evidence. Snapshots are not full engine checkpoints:
continuous-effect internals, every attachment/dependency and entire past RNG
history are not reconstructed. Logging overhead can affect wall-clock budgets.
The raw records remain ordered; the comparator is unchanged.

The TestNG fixture uses the real Atsushi script, native trigger mode selection,
16 controlled RNG seeds repeated twice, and an explicitly constructed local
state. It is **not** a replay of seed 573307's hidden state. The 32 choices must
match across unobserved/observed baseline/candidate configurations before native
diagnostics may run. Observer transformation, error and event-cap checks must
also pass. Both modes appeared (22 Dig, 10 Token choices per configuration).
A second focused method verifies that the budget observer does not advance or
reseed the native RNG. Both methods pass under all four configurations. The first
observer version and its initial fixture evidence remain separately preserved.

The finite six-game diagnostic plan, all at seed 573307 with frozen original
decklists/pod/preferences/budgets, is:

1. Observed baseline 0, observed candidate 0.
2. Observed baseline 1, observed candidate 1.
3. Uninstrumented baseline 0 and baseline 1.

This supports baseline/baseline, candidate/candidate and cross-configuration
comparisons, with an independent check for observer-free baseline nondeterminism.
Every game uses the existing single-worker global lock, memory/disk admission,
native timeout/process safeguards and independent result validation. No failed
game replacement, engine promotion, performance scoring or Phase 8 is permitted.
An ambiguous interrupted child blocks continuation; the supervisor has a
one-shot marker and does not automatically replay completed diagnostic work.

Preparation and focused fixture execution:

```powershell
python -B tools/ai-evaluation/performance/choice_run.py <fresh-output> --prepare
python -B tools/ai-evaluation/performance/choice_run.py <same-output> --fixtures
python -B tools/ai-evaluation/performance/choice_run.py <same-output> --freeze
python -B tools/ai-evaluation/performance/choice_run.py <same-output> --verify-only
```

Then run the same command without an option as a hidden detached supervisor.
It verifies all original evidence hashes, emits bounded diagnostic checkpoints
independently to `20261028-native-rules-parity-v7a`, and stops after six games.
Original native bytecode and performance acceptance gates are not edited.

Outcome B is reported only if unchanged uninstrumented baselines independently
diverge; whether their Atsushi choice matches the original discrepancy is stated
separately. If the original mechanism cannot be established, outcome C remains
explicit even when useful mode/RNG observations are available. A genuine defect
requires evidence of its dependency, not merely association with faster execution.
Controlled fixture repeatability is not a full-game reproducibility guarantee.

The follow-up strategy is to identify the earliest same-engine RNG/action drift,
record reproducible decision-boundary state and isolate game RNG from hypothetical
evaluation RNG where justified. Any future implementation is a separate reviewed
change with strict rules/parity validation. Do not sort or suppress differences
to make the original candidate pass, and do not infer either Atsushi mode is
optimal without the actual game state and heuristic conditions.
