# Parallel unattended Commander runner

Public launcher dispatches new experiments here; existing requests without `runnerVersion=parallel-v2` stay on the original serial worker. Default concurrency 1; range 1–5. No migration of seed2400. Scheduler and global worker locks reject another batch while it is active. Publication retries and dry runs dispatch to the saved runner version.

From `C:\Users\Chris\Workspace\forge-commander-harness`:

```powershell
.\tools\ai-evaluation\Start-CommanderBatch.ps1 -GamesPerVariant 100 -Seed 2500 -MaxParallelGames 5 -PublishOnCompletion
.\tools\ai-evaluation\Get-CommanderBatchStatus.ps1 -Seed 2500 -Detailed
.\tools\ai-evaluation\Start-CommanderBatch.ps1 -GamesPerVariant 100 -Seed 2500 -MaxParallelGames 5 -PublishOnCompletion -Resume
```

One coordinator reserves attempts, accepts completions, writes aggregate ledgers and performs final publication. Pool slots include runtime preparation and independent validation. A variant's valid games plus reserved slots cannot exceed its target. Failure replacements consume fresh attempt ordinals; launch failures also consume budget. Concurrent jobs never share output files.

Seeds are deterministic and unique across all three variants: `baseSeed + variantIndex * maxAttempts + attemptOrdinal`. With target100/default budget300, seed2500 reserves R2 2500–2799, R3 2800–3099, R3-38L 3100–3399. This changes the old paired-seed policy; parallel completion order cannot affect seed attribution. Fixed seats and deck hashes remain unchanged.

Each attempt has a private working directory, Forge profile, preferences, cache, user directory, home and temp. Preferences are frozen and hashed at preparation and copied into each private profile. Shared custom profile overrides are rejected for review. Resource junctions point to the existing `forge-gui/res` tree used as input. Do not edit shared resources during any batch. Runtime trees and junctions are excluded from publication snapshots; frozen preferences are retained. Java option-injection environment variables are removed. Each engine retains 4 GiB maximum heap and two advertised processors: five processes can allocate up to 20 GiB heap, plus other JVM memory. Real CPU/memory and throughput have not been measured for this revision.

Task Scheduler executes `pythonw.exe` directly with no console, Limited privileges, Interactive logon, IgnoreNew and a finite conservative runtime bound. It survives the launcher exiting. Remain logged in and keep the machine awake; there is no reboot autostart. Explicit resume preserves target, attempt budget, concurrency, publication option and frozen inputs. Changed code/JAR/decks/preferences block resume.

Recovery first checks all recorded children have stopped, then checks reservation, engine command, seed range and ledger identity. Orphans block recovery without writes. Completed unledgered games pass independent telemetry validation and retain hashes; unobserved process exits remain null. Incomplete/empty reserved attempts become preserved INTERRUPTED entries and consume budget. Repeated recovery cannot count a completion twice. Promotion history remains in reconciliation.jsonl. Ambiguous or altered data blocks recovery instead of overwriting evidence.

Lifecycle logs distinguish reservation, engine launch/start/exit/cancellation, per-attempt errors and coordinator acceptance. Per-attempt command, PID, exit, validation and isolation records are preserved. Heartbeat continues during validation/publication. Read-only status shows concurrency, per-game variant/seed/PID/elapsed, overall and per-variant counts, heartbeat and Scheduler state. Failed counts exclude interrupted attempts, reported separately. Stopped workers show zero active games, retaining the last recorded in-flight list as evidence. Pending interrupted exports require reconciliation before their final inclusion counts are known.

Only after all targets and independent final validation pass is one immutable publication snapshot prepared. No per-game Git operation occurs. Existing origin/main checks, path-scoped staging, recorded commit retry and non-force push are retained. Publication dry run performs no Git mutation.

Verification on 13 October 2026: 46 Python fixture tests passed (34 existing, 12 added), including one/five-worker bounds, out-of-order completion, unique seeds/paths, exact targets, single failure replacement, attempt cap, multi-attempt recovery, duplicate rejection, live-orphan blocking, reservation crash recovery, runtime junction/profile isolation, validation rejection and frozen concurrency/preferences. Windows PowerShell 5.1 status and mocked active-task guards passed. A separate 20-second scheduled pythonw probe survived launcher exit, reported consoleWindow=0 and finished with exit0; its task was then removed and diagnostics retained under `work/parallel-v2-diagnostics`.

Real five-Forge smoke deferred because seed2400 remains active. Mocked tests reached five simultaneous jobs; no production speedup claim. No seed2500 experiment, full cohort, commit or push was launched during development. Original active worker/classifier/validator hashes were saved for comparison in protected-input-hashes.json; no branch changes, cleaning or result replacement.

```powershell
C:\Python312\python.exe -m unittest discover -s .\tools\ai-evaluation\parallel-v2 -p test_*.py -q
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\ai-evaluation\parallel-v2\Test-ParallelStatus.ps1
```

PID-reuse fix on13 October2026: recovery now verifies Windows executable/command/creation identity and batch-owned Java descendants, retaining legacy attempt attribution and all evidence. Seed2500 source changes have path-scoped reviewed provenance under runner-revisions.jsonl; its request remains frozen. Regression suite now55 tests. Detailed assessment is under its diagnostics/pid-reuse-fix-* directory.
