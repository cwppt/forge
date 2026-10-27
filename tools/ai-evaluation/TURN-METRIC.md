# Commander telemetry turn metric

`finalTurnCount` in game-results JSONL and decision audits is the unmodified
`PhaseHandler.getTurn()` value at completion. This is the global, one-based
ordinal of the last started individual player turn (zero before play), including
extra turns. It is not a completed round or the number of turns taken by one
player. Eliminations and extra turns make division by the starting seat count
unsuitable. Both exports already receive the same value in `SimulateMatch`.

The last `TURN` game-log entry must agree with this value. Forge's existing
`GAME_OUTCOME` text uses `ceil(raw / 2)` as a legacy display convention, even
for four-player games. Preserve that engine formatter and verify its conversion
explicitly; do not treat its displayed number as the canonical metric.

The CSV records individual decisions and has no final winner, draw, or turn
columns. Join it by decision ID to audits and by `(runSeed, gameId, gameIndex)`
to results. Mulligans have metrics but deliberately no decision audits;
main-phase, stack-response and combat decisions require one audit each.

Telemetry consistency and runtime cleanliness are separate checks. A caught
engine exception may leave `simulationError` null. Always inspect the console
log as well; a passing telemetry join does not certify a clean game.
