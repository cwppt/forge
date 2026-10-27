# Unattended Commander batches

Run these commands in Windows PowerShell 5.1 from `C:\Users\Chris\Workspace\forge-commander-harness`.

```powershell
# 75 valid games: 25 per variant. Publication is explicitly enabled.
.\tools\ai-evaluation\Start-CommanderBatch.ps1 -GamesPerVariant 25 -Seed 2400 -PublishOnCompletion

# Read-only progress and final results (latest batch by default).
.\tools\ai-evaluation\Get-CommanderBatchStatus.ps1
.\tools\ai-evaluation\Get-CommanderBatchStatus.ps1 -Seed 2400 -Detailed

# Independent scheduled publication retry: no simulations.
.\tools\ai-evaluation\Retry-CommanderBatchPublication.ps1 -Seed 2400

# Revalidate and check publication readiness without copy, stage, commit, or push.
.\tools\ai-evaluation\Retry-CommanderBatchPublication.ps1 -Seed 2400 -DryRun

# Continue an interrupted batch after its previous worker and children are gone.
# Preserve its original target, attempt ceiling, and publication setting.
.\tools\ai-evaluation\Start-CommanderBatch.ps1 -GamesPerVariant 25 -Seed 2400 -PublishOnCompletion -Resume
```

`-MaxAttemptsPerVariant` defaults to three times the valid-game target. `-TimeoutSeconds` defaults to 180. Every attempt uses a separate Java process, with a 4 GB heap ceiling and two advertised processors; concurrency defaults to one and `-MaxParallelGames` accepts 1–5 for new batches. Existing serial batches retain their original worker. See [parallel runner notes](parallel-v2/README.md). The outer limit is the game timeout plus 120 seconds for startup and shutdown. A launch with no game evidence counts against the attempt budget. Task Scheduler also has a finite batch execution limit computed from these bounds. Actual process exit codes are recorded; nonzero exits exclude their results.

The task runs `pythonw.exe` directly under the existing user with Limited privileges and Interactive logon, without a console, password or administrator requirement. It has no periodic trigger. **The user must remain logged in and the PC must remain awake.** Locking the screen is fine; logging out, shutdown, and sleep can interrupt it. There is no reboot autostart. Use explicit resume after interruption. Task Scheduler access from the Codex sandbox requires approved execution outside the sandbox; disk status remains readable inside it. The previous PowerShell console wrapper was replaced after the seed2400 interruption investigation; see `work\commander-batches\seed2400\diagnostics\INTERRUPTION-DIAGNOSIS.md` for evidence and limits.

One scheduler task is maintained per harness path. Launch and worker file locks prevent duplicate workers; scheduler state prevents replacing an active action. Do not run unrelated simulations in parallel if the intended resource bound matters. No fallback attached child process is used if scheduling is unavailable.

Results are stored in `work\commander-batches\seed<Seed>\`. This is a separate experiment namespace: older seed2100/2101 reports stay intact, and old comparison or published seeds are rejected. Each variant has immutable raw `seed-N` directories, `attempts.jsonl`, `valid-games.jsonl`, `failed-games.jsonl`, and `cohort-summary.json`. Active game/seed/process and counts are in `status.json`; `heartbeat.json` updates every five seconds including during validation and publication. `worker.log` and `bootstrap-error.log` diagnose worker startup and exit failures. A stale heartbeat is reported without restarting anything.

Deck copies are frozen at launch and hashed. Land counts are verified from Forge card types: R2 36, R3 37, R3-38L 38. Fixed seats are candidate 0, Ganax/Far Traveler B2 1, Ganax/Acolyte G1 2, Lathliss V2.5 3. Each variant starts at the same base seed; replacement attempts increment it independently. Divergent deck RNG streams mean this is not a paired random experiment. Seat policy is recorded for future rotation, but rotation is not implemented.

The runner reuses `Run-ValidCommanderCohort.py` classification and ledger/summary functions; it launches Java directly to avoid its legacy hardcoded `pwsh` executable and to capture the actual Java exit code. `Run-CommanderCohort.ps1`, `Invoke-CommanderAttemptBatch.ps1`, and the broken v2 publishing script are unchanged. The telemetry validator's audit-coverage assertion now applies when audits are requested or present, so standard-AI exports do not require external decision audits. Metrics, game logs, role telemetry, and any decision exports are retained. Every accepted game is independently validated again at completion, with clean-runtime checks and zero provider attempts. Failed games remain forensic data and do not contribute to win rates.

`manifest.json` records the JAR hash, harness revision/dirty flag, input hashes, seeds, settings, seats, and validation results. `summary.json` contains valid games, wins, win rates, average durations, and failures. Replacement estimates are conditional on no detected engine failure. Script/JAR/deck hash changes block resume and publication revalidation; restore matching inputs or use a new seed rather than mixing experiments.

Publication creates an immutable local `publication-payload` and verifies its file hashes before use. It requires the private checkout's expected origin and main branch and refuses unrelated pending changes. Only `rakdos/forge-tests/reports/seed<Seed>/` is staged and committed, then the recorded commit is pushed without force. Existing destinations are rejected unless this batch owns them and all contents match. A failed push keeps the local reports and commit; retry pushes that same commit without rerunning games or committing again. A changed HEAD, divergent remote, pre-existing destination, interrupted partial copy, or ambiguous commit interruption requires manual reconciliation; the runner does not reset, merge, clean, or overwrite. Git operations have a 120-second timeout. Publication-only retry explicitly authorizes publication even if the original simulation was started without it.

Focused checks:

```powershell
C:\Python312\python.exe .\tools\ai-evaluation\test_valid_commander_cohort.py
C:\Python312\python.exe .\tools\ai-evaluation\test_commander_batch.py
```

The new tests cover nonzero exit exclusion, provider exclusion, empty launches and caps, hash changes, wrong counts, duplicate locks, interrupted resume, dry-run publication, destination protection, and network-failed push retry using mocks. Recovery now retains independently validated terminal games even if the worker never observed their process exit; that exit remains unknown, and interruption history is preserved separately. Duplicate seed/game identities block recovery. `runner-revisions.jsonl` permits explicitly reviewed infrastructure revisions without rewriting the frozen experiment request or relaxing simulation input checks. No real commit or push is made by tests.

New diagnostic files are `lifecycle.jsonl`, UTF-8 `worker-session-*.log`, per-launch task XML/metadata under `diagnostics/`, and `reconciliation.jsonl`. The status command distinguishes stopped scheduler state from stale RUNNING disk status. Console control signals, engine exits, process IDs and missing normal worker exits can be assessed separately. Hard termination may prevent any exit log, so the scheduler result and last heartbeat remain necessary evidence.

Implementation verification on 12 October 2026: 15 existing cohort tests and 12 new runner tests passed. Windows PowerShell 5.1 launch/status/retry commands were exercised. Two separate scheduled smoke batches, seed2390 and the final revision seed2391, each reached one valid game per variant with zero failed attempts and zero provider attempts. Each scheduler run exited with code 0 and each publication dry run passed against the real private checkout without committing or pushing. Seed2391 is the final-revision smoke result; the batches are not pooled. Duplicate launching was rejected while the task was active. The decklist checkout remained clean at `faef3ebdbdf1364184b756bbac8ce49cfed1774d`. Seed2400 was not launched.

Five-worker implementation verification on 13 October 2026 is recorded in `parallel-v2/README.md`. Seed2400 is currently running; the new version coexists without changing its worker, classifier, validator, JAR, task or results.

New batches now dispatch to `parallel-v3`, supporting1–16 games (default1). Existing requests retain their saved runner and concurrency. See [expanded concurrency notes](parallel-v3/README.md). No existing experiment is migrated automatically.

Future v3 batches now support opt-in `-AiProfile BoundedAdvisory` (Standard remains the default), and correct post-result console exception game indexing. Profile parameters are frozen and must be repeated on resume. See parallel-v3/README.md and the separate matched failed-seed assessment; seed2500 reports are unchanged.

New experiments now require an explicit `-ExperimentSpec` and dispatch to parallel-v4. Candidate selection, hashes, AI profile, concurrency and targets are printed and checked before launch. Existing saved experiments retain their original version. See [explicit experiment configuration](parallel-v4/README.md).
