# Failure-aware Commander diagnostic cohorts

Use `Run-CommanderCohort.ps1 -ValidGames 25 -MaxAttempts 75 -ReplacementBatchSize 4`
with the existing launcher, fixed deck files, timeout and external-AI parameters.
`SeedStart` supplies the first batch seed; each subsequent batch increments it.
The batch size is limited by both the remaining valid-game target and attempt
budget, so completed valid games are never over-collected. The legacy
`GamesPerSeed`/`SeedCount` mode remains available when `ValidGames` is zero.

## Output and inclusion

Each batch preserves its original four telemetry exports and console log in
`seed-N/`. The Java game results now include `valid`, `simulationStatus`,
`engineFailures` and `candidateExposure`. A caught AI exception marks the game
as `ENGINE_FAILED` even when Forge continues to a recorded winner. Timeouts
are `TIMEOUT`. The existing `winner` and `draw` in the raw game file and decision
audits are observed engine outcomes for forensic joins, not eligible outcomes.
The Java evaluation summary excludes detected failed games.

The runner also scans complete console exception blocks as a safety net for
other printed failures. It merges these with structured captures without
double-counting the wrapper and cause. Unknown card/controller attribution
remains explicitly `UNATTRIBUTED`; it is never guessed from the last card event.
Expected provider connection failures are fallback decisions, not engine
exceptions. Malformed or inconsistent telemetry is also excluded from valid
results, under `TELEMETRY_FAILED` rather than being blamed on a card.

`attempts.jsonl` is the authoritative inclusion ledger. Every started attempt
has a unique `attemptIndex`, seed, native game index, game ID when available,
status, failure details, raw-directory link and observed engine result.
Its top-level winner/draw are null for failures. `valid-games.jsonl` contains
only eligible results; `failed-games.jsonl` retains the excluded attempts.
`cohort-summary.json` reports valid/attempted/failed counts, engine failures,
timeouts, telemetry failures, process-launch failures, failure rate and Wilson
95% interval, valid-only wins/draws/rates, and root-cause/card/deck breakdowns.
Exception occurrence counts and failed game counts are separate: thirteen
exceptions in one game are one failed simulation.

State is atomically replaced after every batch. Existing cohort output is not
overwritten. Replacements stop at `MaxAttempts` (default three times the target)
or after three consecutive launches with no started-game evidence. An outer
wall-time limit stops a hung launcher/process tree. The saved summary remains
`INCOMPLETE` if the target is not reached; no success is implied by stopping.

## Diagnostics and unchanged AI behavior

`SimulationFailureDiagnostics` observes the existing ability-evaluation path
and already caught engine/candidate errors. It preserves the exact throwable,
card, evaluating controller/seat/deck, API, current raw turn, stage and stack.
Exceptions still propagate or are handled by their original catch blocks.
No card substitutions, RNG calls, AI recommendations or legal actions change.
The simulation's `--capture-simulation-failures` option additionally collects
unique evaluated-card exposure for bias inspection. These are omniscient
simulation diagnostics; they are not added to model-visible decision contexts.
An inherited per-game state isolates late timeout workers from later games.
Exception records deduplicate a forwarded throwable and its wrapper. Some JVM
optimized repeated exceptions may have a null message or omit stack frames;
preserve those limitations and do not infer an exact root cause from them.
Failure-game counts are the primary rate measure, not raw throw frequency.

## Bias interpretation

Card exposure means at least one ability on that card reached evaluation, or
an explicit failed-candidate context named it for that controller in that attempt,
including copied candidate evaluations. It is not
every drawn card, and exposure can depend on game duration and survival.
The summary compares failed/valid exposure counts, attributable failed games,
failure rates with/without exposure, a Wilson interval for exposed failures,
and an exploratory two-sided Fisher exact association. Mapping exceptions are
grouped by the failed copying operation without process-specific object hashes;
`failuresByImplicatedCard` separately records the object named in that exception.
An evaluated source card is not automatically a causal card attribution. These are descriptive,
with no multiple-comparison correction or causal/non-bias guarantee.

All four decks are in every game and retain their seat order, so deck-level
failure attribution does not estimate an independent matchup failure rate.
Distinguish evaluating controller/deck from card ownership if control changes.
Missing card attribution and crash/timeout censoring reduce confidence.
Replacement sampling estimates outcomes conditional on absence of detected
failure; frequent card-specific failures require investigation or an explicitly
authorized separate quarantine experiment before deck-quality conclusions.

Tests: the focused Java suite includes exact attribution, wrapper deduplication,
worker isolation and failed-result export. Run
`python tools/ai-evaluation/test_valid_commander_cohort.py` for runner exclusion,
replacement, cap, crash, timeout and corruption tests. Run the existing telemetry
validator on each completed batch for an independent cross-check; do not use its
strict clean-runtime option to reject a deliberately failure-aware diagnostic.

Interrupted local cohorts can be continued with `Run-ValidCommanderCohort.py --resume` and the original config/output/target. Stop the previous runner and its child processes first. Recovery retains the saved ledger, preserves unfinished seed directories, marks started games in those directories `INTERRUPTED`, and chooses the next unused seed. Interrupted games are excluded from win rates and counted separately from engine failures. A seed directory containing no started-game evidence records a launch failure rather than inventing a game attempt. Keep the jar, decks and config identical; any increased attempt ceiling must be disclosed in the report.
