# Expanded Commander concurrency

New batches use parallel-v3: `-MaxParallelGames` supports 1–16 and defaults to 1. Existing serial and parallel-v2 batches keep their saved runner. Seed2500 remains a v2 batch with concurrency5; this development does not change or resume it. Its frozen worker, pool, classifier, validator, inputs and results are untouched. The v2 launcher only gained recognition of the owned v3 Scheduler action so old batches can be resumed after a v3 batch completes.

The bounded coordinator, deterministic disjoint seed ranges, isolated profiles/cache/temp/output, independent validation, replacement budgets, exact-target reservations and single final publication are retained. Higher concurrency is a ceiling, not a recommended setting for every machine. Every game still allows 4 GiB heap and two advertised processors. Thus 8/10/16 games permit 32/40/64 GiB heap respectively, plus JVM and OS overhead. The launcher warns above5 and the request records maximumCombinedHeapGiB. Start benchmarking at8 on this machine; no real higher-concurrency benchmark has been launched.

From `C:\Users\Chris\Workspace\forge-commander-harness`, use a fresh unused experiment seed:

```powershell
# Example future batch; not launched during development.
.\tools\ai-evaluation\Start-CommanderBatch.ps1 -GamesPerVariant 100 -Seed 2600 -MaxParallelGames 8 -PublishOnCompletion
.\tools\ai-evaluation\Get-CommanderBatchStatus.ps1 -Seed 2600 -Detailed
.\tools\ai-evaluation\Start-CommanderBatch.ps1 -GamesPerVariant 100 -Seed 2600 -MaxParallelGames 8 -PublishOnCompletion -Resume
```

Resume retains the saved concurrency. Do not use a changed concurrency or new runner to continue a frozen experiment. An explicit reviewed migration is separate work. Same global Scheduler task and worker lock still prevent simultaneous batch coordinators; the new capacity is more games within one batch. Remain logged in and keep Windows awake. No graceful pause or live concurrency adjustment is added.

Future process records include the Windows creation FILETIME and executable path as well as PID. Recovery compares identity before treating a process as an orphan. A reused PID belonging to another process is left alone. Failure to inspect identity blocks recovery conservatively. Older records without identity retain the conservative PID-only check; this does not silently relax legacy recovery. The seed2500 diagnostic observed PID20704 now assigned to svchost.exe, demonstrating the risk of numeric-PID-only checks. Its existing failure state is not changed by this update.

Verification: 52 Python tests pass, including actual bounded thread-pool fixtures at1/5/8/10/16, unique seed/output attribution, out-of-order completion, replacement and caps, recovery/idempotency, exact targets, profile isolation, independent validation rejection, frozen inputs/concurrency, PID reuse/live-process/access-error handling and actual Windows identity roundtrip. PowerShell5.1 fixtures cover read-only stale status, public v3 dispatch,16-game parameter acceptance, active-task rejection and frozen v2 routing/bounds. Git publication tests are mocked dry runs: no commit/push, no new production experiment, no JAR rebuild or branch changes.

```powershell
C:\Python312\python.exe -m unittest discover -s .\tools\ai-evaluation\parallel-v3 -p test_*.py -q
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\ai-evaluation\parallel-v3\Test-ParallelStatus.ps1
```

For measured throughput compare the same test seed set/settings at5,8,10 workers, reporting validated games/hour, duration distributions, timeout/failure rate, peak CPU/RAM and sustained paging. Do not infer a speedup from fixture concurrency alone.


Success-rate development on13 October2026: future batches have the console exception attribution fix (Game1 means zero-based index0), and an opt-in `-AiProfile BoundedAdvisory`. The default remains Standard. BoundedAdvisory enables the JAR's existing advisory-search limits:500ms and2000 checkpoints, with diagnostics. The5second AI decision timeout, game rules, per-JVM4GiB heap/two advertised processors, external-provider exclusion and independent validation remain unchanged. Conservative advisory fallbacks can change AI choices and deck strength, so compare separately from Standard results. The profile and exact JVM flags are frozen in request/manifest metadata; resume must preserve the profile.

Use `-TimeoutSeconds 300 -AiProfile BoundedAdvisory` only for a fresh experiment after reviewing the replay assessment. Repeat the same profile on resume. Existing v2 batches cannot adopt this profile. No full cohort was launched or published during this development.

Matched failed-seed results:5 workers/180seconds0of5 valid;5 workers/300seconds1of5;1worker/300seconds2of5;5workers/300seconds/bounded advisory5of5. These are deliberately selected failures, not an estimated production success rate. The complete report is under work/commander-success-replays/20261013-121730/ASSESSMENT.md. Latest v3 Python regression suite:57 tests pass; benchmark coordinator/classifier19 tests, advisory replay20 tests. No Forge JAR rebuild was required.
