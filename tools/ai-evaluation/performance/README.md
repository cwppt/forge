# Isolated Commander correctness and busy-board validation

Run one game per worker JVM. `SimulationWorker` treats timeout, interruption and engine failure as process failures, writes and forces a separate `*.games.jsonl.failures.jsonl` journal, then halts the JVM without game-state cleanup or outcome export. Exit codes are 124 (timeout), 130 (interruption), and 70 (other failure). Existing partial logs remain evidence; they are not scored outcomes. Shutdown hooks are deliberately bypassed because an uncooperative game/evaluation thread can still be alive.

`TimeLimitedCodeBlock` cancels and waits for executor termination with a monotonic two-second grace period. It preserves the original cause and interruption state if the worker will not stop. The process boundary is the safety guarantee, rather than interruption alone. A natural draw still follows Forge's normal draw effect and completed-outcome path.

`Run-ValidCommanderCohort.py` in `parallel-v4` gives the failure journal precedence over a claimed outcome or exit code zero and checks missing/contradictory outcomes. Frozen historical runners and engines are intentionally not migrated automatically. New experiments must explicitly select the new engine and validator; older engine hashes retain the old timeout limitation.

Optional `-Dforge.simulation.profile=true` writes compact monotonic turn records beside game-results JSONL, with board counts, observed stack depth, resolved ability/trigger counts, heap and GC time. No full snapshots or extra AI evaluations are requested. The flag is off by default. Aborted workers leave partial telemetry and a failure journal, never a fabricated terminal turn or outcome.

## Reproduction

From this source checkout, create a fresh output directory, then run:

```powershell
C:\Python312\pythonw.exe -B tools\ai-evaluation\performance\validate.py <existing-new-output-directory>
```

The finite supervisor acquires the established global worker lock, queues behind an existing worker for up to six hours, preserves the 3 GiB disk reserve on lexical and resolved output locations, checks the existing 7 GiB admission allowance for one JVM, and uses existing isolated-runtime helpers. Run in the normal Windows user session because creating private resource junctions requires Windows filesystem access. It uses one worker at a time and no external AI provider.

The baseline is the frozen telemetry engine with SHA256 `2d82615d9310c0f2fb6f97736881ae2f05f7d958e03536c00a723ae05aed7ac6`. The overlay replaces only the simulation boundary/result/turn-observer classes. It verifies byte identity of all other JAR entries. Native AI, card and rules classes are unchanged. Do not replace an engine or settings under an existing experiment.

Stages: compile; seven disposable-process modes; reporting/validator/recovery tests; Forge JSONL, Throne and virtual-blocker regressions; ten board projections with three repetitions of main-phase, attacker, blocker and trigger-resolution workloads on each engine; bounded JFR recordings; four matched native Commander smoke games plus one profiling-enabled game. Failed stages stop subsequent stages. `status.json`, per-child command/process/execution files, durable raw outputs and `completion.json` are the checkpoints.

The fixture seed is 771230. Board projections use four players, Dragon libraries and high life totals to prevent early fixture termination. They use native AI and real stack/trigger resolution but are synthetic stress states, not exact historical-game replays or new deck comparisons. Main-thread allocation measurements exclude allocations on the native AI evaluation thread; JFR covers JVM-wide samples. Three repetitions cannot establish precise tail distributions.

## Recovery

For an interrupted smoke stage after the correctness and benchmark gates passed:

```powershell
C:\Python312\pythonw.exe -B tools\ai-evaluation\performance\validate.py <same-output-directory> --resume-smoke
```

Resume verifies engine/deck hashes, reclassifies terminal evidence, checks saved independent-validation hashes and skips valid completed games. Only a confirmed, exited `INTERRUPTED` worker may be retried on the same seed, in a new attempt directory, with a three-attempt ceiling. Timeout/engine failures, missing process-exit evidence, missing independent-validation proof, or changed completed evidence stop recovery for review. Nothing resets or overwrites raw attempts. `recovery.py` tests duplicate valid games, unresolved ownership, retry ceilings and safe same-seed selection.

## Report integrity

After the completed smoke gate, a real CLI timeout can be checked separately with
`validate.py <same-output-directory> --timeout-probe`. It uses a one-second game
limit, requires exit 124 and a durable TIMEOUT journal, and rejects any final game
outcome export. This negative test is excluded from all scored-game counts.

`variant-labels.json` is an explicit versioned mapping keyed by immutable run ID and variant ID. `report_integrity.py` checks manifest arms, frozen deck hashes, actual invocation decks and seeds, terminal outcomes, aggregate counts and comparison directions for all 150 games in either Lathliss experiment. It rejects duplicate JSON keys/arms, missing or wrong labels and mismatched statistics. Do not run Python with optimisation enabled.

The correction command writes into a new directory, preserving the original report, provenance hashes and original numerical intervals. It changes labels and comparison keys, without resampling or altering outcomes:

```powershell
C:\Python312\python.exe -B tools\ai-evaluation\performance\report_integrity.py <historical-experiment-root> <new-correction-output-directory>
```

The frozen historical generator is retired rather than edited in place: its request freezes that script's hash. Future report generation should use this manifest-bound validator and mapping, not the old positional label zip.
