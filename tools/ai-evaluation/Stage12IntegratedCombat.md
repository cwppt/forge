# Stage 12 integrated combat evaluation

Status: complete as an experimental integration. See [Stage12Closeout.md](Stage12Closeout.md) for the final review, broader comparison and supported configuration. No strength improvement has been demonstrated; both material policies remain disabled by default.

The staged architecture keeps attacker, blocker and damage-order choices independent. Combat sequence identifiers join audit events only; they are not model-visible strategy state. Noncombat decisions remain with Forge in these runs.

## Running and checking a cohort

```powershell
./tools/ai-evaluation/Run-Stage12IntegratedCombat.ps1 -Matchup All -ExternalMode Seat0
./tools/ai-evaluation/Analyze-Stage12IntegratedCombat.ps1 -RunDirectory 'ai-eval/stage12/<run>' -OutputPath 'work/stage12-analysis.json'
```

Repeat Forge, Seat0 and Seat1 with identical matchups/seeds for paired comparisons. `-Seeds` limits a run; the analyzer derives its expected game list from run.json, preserving compatibility with earlier full-cohort settings. Require zero missing games, audit issues and public-snapshot issues before interpreting outcomes. It also checks provider seat routing, legal supplied IDs, fallback mappings, sequence references, latency/count totals, hidden correlation metadata, and observed declarations. A zero process exit alone does not establish a valid game; the runner checks results, exports, exceptions and time-limit truncation.

## Current scope and gates

Stage 12 integration has passed focused regressions and earlier full normal-path cohorts. The earlier 60-game external-versus-Forge pilot scored 22 external wins and showed weaker play on these two matchups. It does not support enabling external combat as a strength improvement.

The October 8 follow-up adds a protected-defender bypass and a native combat-danger comparison for blocker choices. These are conservative safety boundaries, not exact general simulation. Safety fallbacks are expected, attributable behavior: count UNSAFE_COMBAT_CHOICE separately from provider/transport failures. See CombatBlockerDiagnostics.md for the offline engine-grounded controls and limits.

A targeted four-seed follow-up uses Gatecrasher 1001/1007 and Aurochs 102/104, each with Forge-only and either external seat, for twelve games. Seeds were selected from prior weaknesses and multi-surface examples. Report it as a diagnostic with all three arms, not an unbiased win-rate estimate. Improvement in this subset alone cannot establish general strength; a subsequent predeclared broader comparison is required before a strength claim.

Preserve all raw run directories. Do not pool results across different implementations as one cohort, count supplied fixture truths as model prediction accuracy, or interpret both-external winner counts as external-versus-Forge strength.

## Opt-in material-preservation trial

`Run-Stage12IntegratedCombat.ps1 -ExperimentalMaterialGuard` enables the JVM property `forge.ai.external.combat.materialGuard=true` for evaluation only. The default remains off, and run.json records the setting.

The guard retains the native no-block declaration if Forge's danger estimate does not predict a loss, the model chooses exactly one blocker, and Forge predicts that blocker will die without destroying the attacker. Combatants with triggers bypass this material check; gang blocks, trades and profitable blocks also bypass it. It records MATERIAL_COMBAT_CHOICE / COMBAT_MATERIAL_REJECTED through the existing fallback audit. Survival validation runs first.

This is a strategic preference experiment, not a legal-action or exact-safety rule. Triggered benefits elsewhere on the battlefield, future turns and other abilities are not fully simulated. Blocking to preserve nonlethal life can be strategically correct. The model's option set and prompt stay unchanged; any strength gain would belong to the combined native/model policy. Keep the flag disabled by default unless broader evaluation supports promotion.

October 8 trial result: eight matched external-seat games produced 3/8 wins versus 3/8 in the reference, with two material vetoes, one improved outcome and one worsened outcome. An improved game had no material veto; do not attribute that change to the guard. 114 tests passed and audit checks were clean. The material guard remains disabled by default; the pilot does not support promotion.

## Direct capture and repeatable blocker identities

Use `-CaptureCombatRequests` on the integrated runner to save `<game>.requests.jsonl` alongside the normal audit. It captures the exact combat request bytes before transmission, their SHA-256 hash and a parsed request. It does not capture headers, API credentials, responses, reasoning text or noncombat requests. It leaves the original endpoint unchanged. Capture errors log a warning and do not alter acceptance/fallback behavior. Default capture is off.

Avoid routing a control through a different port without checking inference settings: the existing Qwen3/Ollama compatibility check recognizes port 11434. A forwarding-proxy prototype on 11435 omitted reasoning_effort=none and produced truncated responses. Those runs are marked invalid and excluded; the prototype was archived under work/.

An actual captured Aurochs blocker request selected a block in six exact-byte replays. A fixed alternative decision ID selected NO_BLOCK in three interleaved replays. The inner content differed only in decisionId and the schema's matching enum. Earlier reconstructed fixtures did not expose this case; their negative result must not be generalized to all IDs or states. This establishes identity sensitivity for this request, not a general cause of win-rate changes.

Use `-SeededBlockerIds` for evaluation-only repeatable blocker IDs. Given simulation metadata, IDs derive from the run seed, game index, defender seat, unchanged state fingerprint and same-fingerprint occurrence count. Repeated occurrences receive distinct IDs within a game. The identity allocator is separate from strategic selection and does not consume the game's random-number stream. It uses weak game references. Normal play remains unchanged, and missing seed metadata retains ordinary IDs. Attacker and damage-order IDs are unchanged.

Treat seeded-ID mode as a distinct evaluation protocol. Candidate/control runs must use the same identity policy; do not credit an outcome change caused by a label substitution to a strategic guard. This setting improves repeatability of blocker labels, not the model's tactical understanding or immunity to arbitrary labels.

## Repeatable identities across combat stages

`-SeededCombatIds` enables `forge.ai.external.combat.seededIds=true` for attackers, blockers and damage ordering. It is opt-in and recorded in run.json. The older blocker-only switch remains supported and keeps its established blocker ID mapping. Missing simulation seed metadata retains ordinary IDs.

Each surface has independent occurrence counters, keyed by game, seed, game index, seat and unchanged public-state fingerprint. Reversing the order of calls across surfaces does not shift another surface's IDs. The allocator does not consume game randomness or carry strategic information. Repeated occurrences of the same state receive distinct IDs within a game; equivalent occurrences in matching seeded games receive matching IDs.

For a policy comparison, enable `-SeededCombatIds -CaptureCombatRequests` on both arms. Join captures and audit decisions within each individual game; identities intentionally repeat across matching games. Verify exact request hashes at shared identities and compare returned model choices separately from applied choices after safety or material fallbacks. Repeatable identities do not guarantee deterministic inference. Keep this protocol separate from earlier cohorts using ordinary or blocker-only IDs.

Damage-order transport preserves Stage 11's compact aliases (`S`, `C1`, `C2`, ...) and omits derived option summaries. Its canonical audit context retains permanent IDs and full option records. Reconstruct this existing projection when comparing public snapshots; compare captured bytes directly for candidate/control equality. Use capture order for chronological comparisons, because the audit export groups decisions by surface.

October 8 all-surface trial: 16 completed games (eight control, eight material-guard), 118 captured and verified requests, zero audit/public-snapshot issues and zero non-material fallbacks. Control won 3/8; material guard won 2/8, with zero improved, one worsened and seven unchanged paired outcomes. Both arms exercised all three surfaces. All 51 shared identities had identical request bytes and identical returned model choices. The two changed game trajectories first diverged at the guard's blocker veto, with identical requests and answers immediately before the override. Gatecrasher seed 1001 Seat1 changed from an external win to a loss; Seat0 remained a loss. This selected pilot provides no support for promoting the guard; it remains off. It does not establish general model determinism or general policy strength.

## Race-aware material-policy revision

`-ExperimentalRaceAwareMaterialGuard` enables the material experiment with `forge.ai.external.combat.materialGuardRaceAware=true`. The runner records `MaterialGuardPolicy=RaceAwareV2`; the original switch records `LegacyV1`, and the default records `Off`. Both experimental policies remain disabled by default. The race-aware switch also enables the underlying material guard; the race-aware JVM property alone does not enable it.

The revision declines a material veto when the estimated life remaining after no block is no greater than the estimated damage of the current attack, or when the remaining defending creatures' potential unblocked return damage reaches the opponent's life total. The latter excludes the blocker the model proposes to sacrifice, uses next-turn attack eligibility and the native damage estimator, and includes currently tapped creatures that may attack next turn. This is a conservative uncertainty bound. It does not assert a guaranteed lethal return attack, simulate future blocking, infer future draws, or establish a solved combat strategy. Existing survival, legality, trigger and trade checks still apply.

The motivating Gatecrasher-1001 Seat1 position had the defender at 17 life, the opponent at 6, a small 2/2 first-strike blocker facing Llanowar Behemoth, and a tapped 6/6 first-strike creature. Taking the attack dealt five damage in the observed game. The original guard preserved the small creature, altered subsequent attacks, and lost the race. The revision lets the model's block stand in this uncertain race. Engine-backed controls also cover an ordinary losing block away from a race, the life-buffer boundary, a trade and disabled behavior.

Use `Compare-Stage12CombatPolicies.ps1 -Manifest <runs.json> -Output <summary.json> -ExpectedPairs 8 -ExpectedMaterialPolicy RaceAwareV2` to verify policy comparisons. The manifest identifies `Control` and `Material` arms with individual run directories and analyzer summaries. The comparator checks protocol provenance, hashes, public audit projections, legal applied choices, fallback preservation and matching returned choices. Forge baseline entries may be included in a manifest and are excluded from the model-policy comparison. `-PassThru` on the integrated runner returns its exact run directory for orchestration without guessing from directory inventories.

Final result: RaceAwareV2 restored the original targeted win and matched control at 3/8 wins in the diagnostic pilot, with one veto and no worsened paired outcomes. The predeclared 40-game follow-up used eight new seeds, eight Forge-only baselines and sixteen games per external arm. Control won 4/16; RaceAwareV2 won 3/16, with zero improved, one worsened and fifteen unchanged outcomes. All 206 requests were verified; all 91 shared requests had identical bytes and returned choices. Three material vetoes occurred, with zero provider failures or audit/public-snapshot issues. Aurochs-121 Seat1 supplied a new adverse counterexample. The revision is retained only as an opt-in experiment; the broader result rejects promotion. The `fixtures/material-policy-counterexamples.json` fixture contains observed game outcomes, not universally correct tactical labels.


