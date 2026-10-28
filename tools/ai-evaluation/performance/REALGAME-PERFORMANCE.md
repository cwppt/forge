# Phase 6: real games and native evaluation coverage

This is an observation-only experiment. The frozen `5d5b2603...` observer engine is
used unchanged. Every prior experimental flag is false; normal 500 ms / 2,000 work
budgets, 3 GiB heap, two advertised processors, native four-player rules and existing
failure capture are retained. The original harness checkout is not edited.

The finite primary plan is nine seeds 573200–573208, rotating the three immutable
Lathliss deck files from the Roaming Throne manifest, against the same three frozen
opponents. File identifiers and hashes are reported; historical file names do not
relabel card contents. This deck-family engineering sample is not representative of
all Commander decks and cannot establish win rates.

After independently validating these games, at most one additional replay of the
slowest valid case receives a bounded 64 MiB JFR recording. It uses the same engine,
budget and seed, has independent validation, and is never scored in primary
throughput. Structured parity is checked using the existing strict comparator;
any rules-sensitive differences are preserved and reported. No global sorting or
event suppression is added. A single diagnostic replay does not prove deterministic
repeatability or isolate profiler overhead.

Each valid-game proof hashes commands, native result, validation, diagnostics and
structured trace. The existing exclusive global worker lock, 7 GiB available-memory
admission and 3 GiB disk reserve apply. Both lexical output paths and resolved
junction volumes are checked. Runtime failures stop the finite programme; no
replacement seed is selected. Zero process exit alone cannot validate a game.

Freeze using normal Windows access:

```powershell
python -B tools/ai-evaluation/performance/realgame_run.py <new-output-directory> --freeze
python -B tools/ai-evaluation/performance/realgame_run.py <same-output-directory> --verify-only
```

Run the same command without the final option in a hidden detached process. The
supervisor publishes each checkpoint independently to the Phase 6 report. For an
interrupted supervisor, inspect its heartbeat, child and failure evidence first.
Explicit `--resume` reuses complete proofs after rechecking every hash. Existing
incomplete attempts block continuation instead of being replayed. Valid games are
not repeated. The cumulative elapsed denominator includes any recovery downtime;
the active denominator includes admission/startup/game/validation/analysis and
excludes publication. Diagnostic time is excluded from primary timing.

Seven focused analysis tests plus the eight existing structured-comparator tests
must pass before the request is frozen. Tests cover call-versus-decision counts,
missing context, ambiguous time/work-limit evidence, nesting, malformed evidence,
empty observations and per-JVM diagnostic-ID reuse. Java correctness results are
inherited for the identical engine, not rerun or claimed as new results.

`fallbackCalls` is a count of checkpoint requests after a budget condition, not a
number of bad decisions. Existing diagnostics permit phase, scope result,
battlefield and scope-end work/time analysis. They do not permit precise per-call
reason attribution, exact total native decision counts, all combat elapsed time,
or quality/regret estimates. Candidate log events are selected slow/affected
events; associations are not causal attributions. No policy, budget or logging
change is made to fill these gaps. Scope time can overlap; JFR estimates sampled
CPU and allocation attribution separately.

Historical Phase 5 games are re-analysed read-only and never pooled. No candidate
is implemented by this supervisor. It publishes evidence and a conditional next
target, then stops. Any future candidate needs a demonstrated safe computation,
predeclared material/repeatability and regression gates, strict deterministic and
structured parity, directly matched synthetic and real-game measurements, and the
existing 15% primary synthetic target plus whole-game benefit before promotion
consideration. No engine is merged or promoted automatically and no historical
percentage improvements are added.
