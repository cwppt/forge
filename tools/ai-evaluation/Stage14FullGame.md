# Stage 14 full-game evaluation

`AI_EXTERNAL_FULL_GAME_ENABLED=false` is an optional preset for exactly the seven current surfaces. Each retains its own request, options, validation, live revalidation and fallback. No prompt history, strategic memory, new decision surface, guard, or strategy tuning is added.

Preference-only activation respects explicitly stored individual enable/disable settings. The command-line `--external-ai-full-game-enabled` explicitly enables all seven, then individual command-line disables and max-option settings override the preset. Existing max-option defaults are unchanged. Material and race-aware combat guards are forced off in full-game mode. The Stage 12 integrated flag stays independent. Individual flags still work without the preset.

Damage-order decisions require Forge's existing legacy combat-order rules. The evaluator explicitly selects those same rules for both native and external games; the preset itself does not change game rules. A surface may make no calls if a game never reaches an eligible supported choice.

## Audit

The evaluation-only `forge.ai.fullGame.audit=true` property installs a passive observer on the actual game, never on preparation copies. It introduces no provider calls or engine mutations. Correlation never enters model projections. SHA-256 derives `externalAiGameId` from the supplied run namespace, game index, seed and engine game number. `turnSequenceId` appends the actual turn number, including the opponent's turns; mulligans use turn zero. IDs have no timestamps or RNG. Replaying an identical logical run produces the same correlation ID.

Additional JSONL records:

- `FULL_GAME_DECISION`: chronological ordinal, decision reference, surface, game/turn/phase/seat, fingerprint, acceptance/failure, selection/Forge agreement, provider latency, special selections, duplicate flag and optional numeric token usage. No complete request body is duplicated.
- `TURN_SEQUENCE`: resources immediately before first main phase for both registered seats (public life, hand **size**, battlefield count, floating mana, untapped lands), then ordered decision references. Second main and defensive decisions share that turn. These are review evidence, not automatic mistake judgments.
- `FULL_GAME_SUMMARY`: both original seats/decks, final public resources, outcome, turns/duration, references, overall and per-surface reliability, latency distributions, agreement and selection counts. Eliminated players remain represented at their original seats.

Existing individual audit records receive the same game/turn correlation fields. Mulligans, previously logged outside JSONL, are covered by the compact decision records. Existing Stage 12 metrics and CSV columns keep their meaning.

The seven `externalFullGame*` budget fields cover calls, cumulative provider milliseconds, mean calls/latency per game, p50/p95 game latency and maximum call latency. Within a single game, game-total percentiles equal that game's total; the analyzer computes cohort percentiles across external game totals. Per-call mean/p50/p95/max and per-surface totals remain separate. Provider latency measures blocking request time, not all game duration. Optional usage captures only nonnegative prompt/completion/total token counts; no reasoning or secrets. Local default-port Qwen3 uses `reasoning_effort: none` and bounded structured output across all seven surfaces; other providers retain their previous transport policy.

Duplicate detection compares surface, turn, phase/window, fingerprint and canonical option-ID set. It reports repeats without adding suppression or modifying existing surface guards. Failure categories include timeouts, provider exceptions, malformed/empty responses, identity mismatches and stale/revalidation failures. A fallback remains local to its decision.

## Run

From the repository root, after compiling desktop classes:

```powershell
# Four games: two verified matchups, matched native/external, seat zero.
.\tools\ai-evaluation\Run-Stage14FullGame.ps1 -Model qwen3:8b -Seeds 1400 -ExternalSeats 0 -RequestDiagnostics

# Forty games: five seeds x two matchups x two external seat orientations x two modes.
.\tools\ai-evaluation\Run-Stage14FullGame.ps1 -Model qwen3:8b -Seeds 1401,1402,1403,1404,1405 -ExternalSeats 0,1 -RequestDiagnostics

# Reanalyze existing evidence without running games.
.\tools\ai-evaluation\Analyze-Stage14FullGame.ps1 -Path .\ai-eval\stage14\RUN_DIRECTORY
```

Windows PowerShell 5.1 supported. Java runs hidden with separately redirected stdout/stderr; no native stderr pipeline is used. Default model `qwen3:8b`, provider timeout 60 seconds, game timeout 1800 seconds. A process watchdog stops only the launched process if the game exceeds its limit plus shutdown allowance. The runner preserves partial evidence and stops on an engine crash or game timeout so it can be reviewed before expanding the cohort. Ordinary individual decision fallbacks do not stop a game or cohort.

`-Mode Both|Control|External`, `-Matchup All|Gatecrasher|Aurochs`, `-ExternalSeats 0,1`, `-Seeds`, `-Model`, `-Endpoint`, `-RequestDiagnostics`, `-DryRun` and `-PassThru` are supported. Mode Both is the default. Control explicitly disables the preset, integrated combat and all seven surfaces; external enables the preset for exactly one selected seat. Each seed/seat pair uses the same deck order and rules. Seat 0 is the first deck; seat 1 is the second. The repeated native control is intentional: each external orientation has a corresponding matched baseline.

Verified preconstructed decks under `forge-gui/res/quest/precons`: Gatecrasher / Air Forces and Aurochs Stampede / Selesnya Surge. Outputs are under `ai-eval/stage14/<run>/`: manifest, per-game process metadata, native logs, unchanged metrics CSV, audits, optional sanitized wire requests, aggregate JSON, report Markdown, and review index. Python analysis uses only the standard library.

Five seeds rather than twenty per matchup keeps the initial mirrored native/external cohort at the requested forty-game lower bound. Outcomes are descriptive; no statistical superiority is asserted. The review index identifies high-activity turns, multiple Forge disagreements, PASS, fallbacks and combat interaction without labeling choices as mistakes. Review these sequences before adding features or tuning strategy.
