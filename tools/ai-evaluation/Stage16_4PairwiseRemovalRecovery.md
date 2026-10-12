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
