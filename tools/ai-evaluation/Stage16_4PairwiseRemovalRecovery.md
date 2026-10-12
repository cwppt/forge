# Stage 16.4: narrow Dead Ringers recovery

Dead Ringers can be supplied as an additional prepared MAIN_PHASE_ACTION option when its exact verified script is present and Forge prepares a useful matching-color pair. Existing Forge fallback and all other decision surfaces retain their previous behavior.

## Eligibility and execution

- External main-phase selection must be enabled with an installed provider.
- Two-player game, deciding AI's MAIN1 or MAIN2, AI priority, empty stack.
- At least one native prepared action and a spare slot within the existing action bound are required. Native options are prepared first and retain their order and IDs; recovered actions are appended.
- Only the exact Dead Ringers Destroy script and fixed 4B mana cost are registered. No subability, trigger, replacement ability, alternative/optional costs, X, splicing or modified script is admitted.
- Both prepared targets must be distinct, current, visible opposing battlefield creatures, legally targetable and exactly equal in current color set. Colorless and multicolor matching pairs are covered by preparation/resolution probes.
- Forge chooses both targets through the existing copied-game preparation and live mapping pipeline. No new pair search, target combinations or strategic heuristic is added.
- Dead Ringers' equal-color condition is a resolution condition, not a target-legality restriction. Mismatched pairs remain legal under ordinary engine targeting but are excluded from this recovery path.
- The model-visible target summaries include current colors. Existing SHA-256 state fingerprinting therefore detects color changes even when both targets change to another matching color set.
- Frozen request decision ID, fingerprint and supplied action ID are validated before live projection/revalidation. The pair guard is checked again before execution, alongside existing source, target, restrictions and payment checks.
- Invalid responses use the native prepared preference; stale requests return to existing native evaluation. Recovered actions never become the native fallback.

## Configuration, audit and limits

No new configuration, provider transport, decision surface or metrics were introduced. Existing external main-phase settings, option IDs, audit events and telemetry apply. Existing defaults remain disabled. COMBAT_ACTION and STACK_RESPONSE do not admit Dead Ringers through this registry entry. The original removed-from-AI card warning/filter remains intact for native behavior.

Recovery intentionally skips cases without a native fallback, with a full native option bound, with unsafe or mismatched prepared targets, or where the existing Forge preparation does not find a useful pair. It does not claim broad pairwise-removal coverage or measured strategic improvement. No real-game evaluation was run for this change.

## Validation

On 2026-10-12, 374 focused tests passed with zero failures, errors or skips (20 test classes). This includes 11 Dead Ringers preparation probes and 12 recovery tests, plus the existing 351 external-AI regressions. Covered recovery behavior includes MAIN1/MAIN2 selection, real payment and resolution, preserved target identity, disabled behavior, mismatching colors, exact-script rejection, wrong request identity, post-request target/color/source/priority changes, lost mana, required native fallback and candidate limits.

`git diff --check` passed. Newly added files were separately checked for trailing whitespace and terminal blank lines. No tracked ai-eval outputs or card scripts changed.

Repeat the focused recovery tests from the repository root:

```powershell
mvn -B -pl forge-gui-desktop -am test '-Dtest=DeadRingersPreparationProbeTest,PairwiseRemovalRecoveryTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DfailIfNoTests=false'
```

Local Aider with Ollama Qwen 14B drafted the implementation excerpts and tests. Codex reviewed the changes, corrected mechanical API/syntax and fixture mistakes, restored a removed test, and ran validation. A temporary loopback relay enforced `think:false` for the local coding worker; Forge's established provider transport was not changed.

## Live Ollama coverage validation (12 October 2026)

Aider/Qwen 14B drafted `Stage16PairwiseRemovalRecoverySmoke.java` and adapted the existing safe PowerShell launcher into `Run-Stage16_4PairwiseRemovalRecovery.ps1`. Review rejected invalid engine API calls and corrected imports, callback-count scope, exact target identity checks, execution of every selected action, source destination checks and audit flushing. Production code, provider prompts, reasoning settings and strategy were not changed.

The final real-provider run is `ai-eval/stage16_4/20261012-132205/`. It contains nine controlled positions, not nine complete games, using qwen3:8b and seeds 16401, 16402 and 16403:

| Position | Starting opponent life | Native alternative | Offers | Model choice | Verified resolution |
| --- | ---: | --- | ---: | --- | --- |
| MAIN1 burn | 20 | Shock | 3 | Shock (3) | Paid and resolved (3) |
| MAIN2 burn control | 2 | Shock | 3 | Shock (3) | Paid and resolved (3) |
| MAIN2 creature | 20 | Grizzly Bears | 3 | Dead Ringers (3) | Paid and destroyed both prepared creatures (3) |

All nine requests supplied Dead Ringers and the native alternative. All nine responses were accepted; zero fallbacks or stale/revalidation failures occurred. Mean provider latency was **1,314 ms**. The provider response was passed through without substitution or forced selection. Existing native ACTION_0 and recovered ACTION_1 ordering was retained.

Independent inspection verified all nine captured request byte hashes, JSON round-trips, supplied-option enums and returned identity/fingerprint/option mapping against the decision audit and execution results. Both exact prepared target names and GREEN color summaries were present. The opponent's hidden Black Lotus was absent. Requests retained `reasoning_effort: none` through the established local default-port Qwen transport. Results are saved in `fixture-results.json` and `verification-results.json` alongside requests, decision audits and process logs.

Earlier exploratory runs are preserved. The first attempted run (`20261012-131824`) accepted three MAIN1 decisions but stopped when the initial MAIN2 fixture produced no external offer. The burn-only control run (`20261012-132022`) completed six valid decisions, all selecting Shock. This prompted an additional ordinary creature-alternative fixture rather than a heuristic or prompt change. The fixture starts at 20 life; its distinct comparison allowed the real model to select Dead Ringers. This demonstrates supplied-action coverage and execution, not natural-game frequency or strategic superiority.

The final harness compiled with **23 focused offline recovery/probe tests passing**, zero failures, errors or skips. The existing **374-test production regression result** remains applicable: this follow-up changes only manual validation tooling and these notes. Whitespace checks passed for tracked and newly added files; the launcher dry run also passed under Windows PowerShell 5.1.

Repeat after compiling the test classes, from the repository root:

```powershell
powershell.exe -NoProfile -File .\tools\ai-evaluation\Run-Stage16_4PairwiseRemovalRecovery.ps1 -Model qwen3:8b
```

The launcher defaults to the three seeds above, writes a fresh timestamped directory under `ai-eval/stage16_4/`, and uses hidden Start-Process execution with separate stdout/stderr files. It supports Model, Endpoint, Seeds and DryRun. No broader card recovery or routing fix is justified by these results. Natural-game frequency and whether the option improves play remain open evaluation questions.

## Natural-game coverage pilot (12 October 2026)

`Run-Stage16_4NaturalGames.ps1` reuses the Stage 14 process, quoting, redirection, watchdog and completion checks. Qwen 14B drafted the runner; review corrected an invalid strategic-memory flag, removed unrelated combat diagnostics, added seat validation and made the expected game count reflect selected parameters. Only MAIN_PHASE_ACTION is enabled for the designated external seat. Its existing three-action bound is retained; all other surfaces, integration flags and memory are explicitly disabled. No decks, production behavior, heuristics or provider prompts changed.

Evidence: `ai-eval/stage16_4/natural-20261012-160344-b69e3200/`, including run configuration, per-game completion records, metrics, full-game audit, captured requests and `natural-verification-results.json`.

The existing Burial precon contains two Dead Ringers. Eight games used unmodified Burial against verified existing Aurochs Stampede and Selesnya Surge precons, seeds 16411/16412, with paired Forge-only controls and external Burial seat 0.

| Matchup | Seed | Control winner / turns | External winner / turns | External provider calls |
| --- | ---: | --- | --- | ---: |
| Burial vs Aurochs Stampede | 16411 | Aurochs / 14 | Aurochs / 14 | 0 |
| Burial vs Aurochs Stampede | 16412 | Aurochs / 16 | Aurochs / 17 | 2 |
| Burial vs Selesnya Surge | 16411 | Selesnya / 17 | Selesnya / 17 | 0 |
| Burial vs Selesnya Surge | 16412 | Selesnya / 11 | Selesnya / 11 | 1 |

All eight games completed: zero crashes, timeouts or draws. All three external calls were accepted, agreed with Forge and had zero fallbacks or stale/revalidation failures. Mean provider latency was **4,253 ms**, including the local model-loading/state-size conditions of this run. The selected sources were Phyrexian Reaper, Llanowar Dead and Phyrexian Reaper; all three calls occurred in MAIN2. Every captured request passed byte-hash, thinking-disabled, exact schema enum, response identity/fingerprint/option mapping, audit and hidden-opponent-hand checks. Summaries confirmed no provider calls from other surfaces or control games.

The four external games considered 153 main-phase windows: 82 were not the AI's active turn, 56 yielded zero safe actions, 12 yielded one, and three yielded multiple actions. Those three requests exposed seven options in total. Dead Ringers appeared in neither the supplied options nor the AI's own hand in those three captured request states.

**Interpretation:** this pilot demonstrates sparse ordinary decision coverage, not a Dead Ringers exposure defect. It does not establish whether Dead Ringers was drawn or safely preparable in unrecorded no-call windows; existing aggregate eligibility metrics do not capture their full hands or candidate rejection reasons. The earlier nine-position smoke remains positive proof of exact exposure and real selected execution. No implementation or strategic fix is justified by the natural pilot. Burial lost all four controls and all four external games; this tiny sample, with zero recovered offers, cannot estimate the strategic benefit of recovery or attribute those losses to it.

The runner passed an eight-command isolation check and a dry run under Windows PowerShell 5.1. No Java change required rerunning the previous focused tests. Tracked and added-file whitespace checks passed. The game limit is ten minutes, with the existing watchdog allowing an additional two minutes for process shutdown. Repeat from the repository root after compiling the desktop classes:

```powershell
powershell.exe -NoProfile -File .\tools\ai-evaluation\Run-Stage16_4NaturalGames.ps1 -RequestDiagnostics -PassThru
```

The next useful coverage investigation, if needed, is a wider predeclared seed sample or temporary read-only observation of no-call recovery opportunities. Do not increase disagreements, relax safeguards or redesign targeting to manufacture positive results.

## Wider natural-game sample (12 October 2026)

The unchanged runner was reused for a predeclared extension: seeds **16421 through 16426**, both existing matchups, external Burial seat 0 only. This adds twelve ordinary games without changing decks, bounds, provider prompts, heuristics or production code. No new local-worker edit was necessary. It is an exposure study, not a matched win-rate comparison.

Evidence: `ai-eval/stage16_4/natural-20261012-163544-74500b1a/`, including `natural-verification-results.json` and all per-game request, audit, metrics and completion records.

All **12 games completed**, with zero crashes, timeouts or draws. They produced **11 accepted main-phase provider calls**, nine Forge agreements and two disagreements. Both disagreements selected the recovered Dead Ringers option. There were **zero fallbacks or stale/revalidation failures**; mean provider latency was **3,267 ms**. All eleven request UTF-8 byte hashes and JSON round-trips, thinking-disabled settings, bounded schema enums, returned request identity/fingerprint/option mapping, audit correlation, external seat and hidden-opponent-hand checks passed. Game summaries confirmed that other external decision surfaces remained inactive.

| Natural offer | Window | Prepared targets | Selected option | Subsequent evidence |
| --- | --- | --- | --- | --- |
| Aurochs, seed 16424 | Turn 10 MAIN2 | Woolly Mammoths; Bull Aurochs, both GREEN | ACTION_1, Dead Ringers | Turn 12 projection adds Dead Ringers to own graveyard and both target names to opponent graveyard |
| Aurochs, seed 16426 | Turn 11 MAIN2 | Aurochs; Bull Aurochs, both GREEN | ACTION_2, Dead Ringers | Turn 13 projection adds Dead Ringers and increases both target-name graveyard counts |

These ordinary-game selections confirm that the recovered option is exposed under both two-option and three-option native bounds. Later public graveyard-count changes corroborate resolution; the earlier smoke and focused tests provide exact prepared target identity, payment and execution verification. The natural request projection is name-based, so this report does not claim a separate engine-incarnation trace from those later snapshots.

Dead Ringers appeared in four captured hands. It was exposed and selected in two; the other two snapshots are consistent with the conservative relational-target guard:

- Seed 16424, turn 12: only Boreal Druid remained as an opposing creature, so two distinct opposing creature targets were unavailable.
- Seed 16424, turn 14: the opposing creatures were Boreal Druid (G) and Earthen Goo (2R). Their existing card definitions give different colors, so the pair does not satisfy Dead Ringers' equal-color condition. No color-changing effect was identified in that captured battlefield.

Across the twelve new games, aggregate eligibility counted 509 main-phase windows: 258 outside the AI's active turn, 198 with zero safe prepared actions, 42 with one, and eleven with multiple actions. Those eleven requests exposed 25 options in total. No evidence here demonstrates a missed safe prepared recovery action. No instrumentation or implementation fix was added.

Burial lost all twelve extension games. Combined with the first pilot, natural evaluation now covers **16 external games and four Forge controls**, with fourteen accepted provider decisions and two recovered offers/selections. The observed recovery frequency is two of sixteen external games in this particular sample; it is not a general rate estimate. The extension has no new paired controls and cannot establish strategic improvement or explain the losses. Coverage is confirmed, while broader strategic benefit remains unproven.

Repeat the extension from a PowerShell prompt at the repository root:

```powershell
& .\tools\ai-evaluation\Run-Stage16_4NaturalGames.ps1 -Mode External -Seeds @(16421,16422,16423,16424,16425,16426) -RequestDiagnostics -PassThru
```

Use an array from a PowerShell prompt or `-Command`; Windows PowerShell's `-File` argument binding does not accept a comma-separated string as this typed seed array. This extension required no software change or test rerun. Report whitespace checks passed; all earlier evidence and implementation remain intact.
