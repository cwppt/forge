# Failure-success replay benchmark

Development only; never merges replay results into seed2500 or publishes. Original worker files, request, reports and ledgers are untouched. Copied decks/preferences, fixed seats, same JAR/Java limits and standard Forge AI. Source hashes are checked before each condition and after completion.

A copied classifier corrects console exception attribution: human Game1 corresponds to zero-based gameIndex0, including exceptions printed after the result. It no longer creates a phantom second attempt. A corrected classification still excludes a failed/timed-out game; no original result is retrospectively promoted.

Five selected failed attempts: R2/2502, R3/2870, R3-38L/3102 (game timeouts), R2/2558 and R3/2809 (AI evaluation timeouts). Three conditions: five workers/180seconds; five workers/300seconds; one worker/300seconds. Exactly15 attempts, with no replacements. Game limits differ only where recorded; the AI decision timeout remains the original5seconds.

The replay worker is windowless under a separate Scheduler task, with a two-hour ceiling and the harness global worker lock. It cannot overlap a new batch coordinator. Each condition/attempt has isolated profile/user/cache/temp/logs/exports. Interrupted outputs are preserved, and the tool refuses automatic overwrite/resume. This deliberately failure-enriched small sample tests hypotheses, not the full cohort's predicted success rate. Fixed seeds do not eliminate thread/scheduling sensitivity.

Outputs: work/commander-success-replays/20261013-121730, with status.json, append-only results.jsonl, per-condition summaries, detailed attempt exports and a final summary. The task is ForgeCommander-SuccessReplay-20261013-121730. No Git add/commit/push is executed.

Verification: 19 tests passed, including15 existing cohort cases, three post-result exception attribution cases and a mocked matched-seed/bounded-pool replay test.
