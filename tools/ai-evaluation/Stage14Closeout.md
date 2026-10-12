# Stage 14 closeout

**Classification: B — mechanically reliable but strategically inconclusive.** The matched cohort completed reliably; external seats won 8/20 versus 10/20 for their matched native seats. This small sample does not establish strategic superiority or underperformance. No strategic prompts, option ordering, preparation rules, material guards or memory were tuned.

## Implementation

- `AI_EXTERNAL_FULL_GAME_ENABLED=false` / `--external-ai-full-game-enabled` enables the seven stable surfaces independently. Individual CLI overrides and option bounds take precedence; preference-only activation respects explicitly stored individual settings. Experimental material/race guards stay disabled. The preset does not change combat rules or enable the Stage 12 integrated flag.
- Audit-only deterministic SHA-256 game identity and turn identity correlate all seven surfaces, including defensive turns. They never enter model requests. Compact chronological decision references and before-main public resource snapshots accompany an aggregate game summary. Eliminated players retain their original seats and deck identities.
- Full-game and per-surface metrics cover provider calls/latency, game-total averages and p50/p95, maximum call latency, acceptance, fallback reasons, agreement, PASS/no-attack/no-block and combat-action selections. Safe numeric token usage is recorded when returned. Existing Stage 12 metrics and CSV definitions remain unchanged.
- Duplicate detection observes matching surface, turn/window, fingerprint and canonical option set; it adds no suppression. Individual failures still use the existing Forge fallback.
- Full-game wire capture extends optional request diagnostics to older surfaces. Inspection demonstrated that the previous thinking-disabled transport was scoped only to combat. Local Qwen3 now receives thinking-disabled, bounded-output requests for all seven stable surfaces. Other provider transport behavior is preserved.
- Windows PowerShell 5.1 runner supports matched control/external modes, selected/mirrored seats, seeds, models, endpoints, matchup presets and optional diagnostics. Hidden Java processes redirect stdout/stderr separately. Controls explicitly disable all external surfaces. Both cohorts use the same legacy combat-order rules so damage-order evaluation remains eligible when a suitable combat occurs.

## Files changed for Stage 14

Production: `AbstractPreferences.java`, `ForgePreferences.java`, `GamePlayerUtil.java`, `AiDecisionProviderFactory.java`, `SimulateMatch.java`, `AiDecisionMetrics.java`, `OpenAiCompatibleDecisionProvider.java`; new `FullGameExternalAiAudit.java`.

Tests: new `FullGameConfigurationTest.java`, `FullGameExternalAiAuditTest.java`, `FullGameProjectionConsistencyTest.java`; updated `OpenAiCompatibleDecisionProviderTest.java`.

Evaluation: new `Run-Stage14FullGame.ps1`, `Analyze-Stage14FullGame.ps1`, `analyze_stage14.py`, `test_stage14_analysis.py`, `Stage14FullGame.md` and this closeout. Existing Stage 12/13 implementation and evaluation outputs were preserved.

## Validation

- **314 Java tests passed**, zero failures/errors/skips, across 22 focused test classes. This includes all previous external surface groups, physical spell-resolution projection checks, game/turn identity, configuration precedence, audit aggregation, duplicate detection, hidden-information isolation and eliminated-player summaries. Log: `work/stage14-regressions-final.log`.
- **3 evaluator tests passed**: matched/mirrored outcome accounting, percentiles and wire/audit leak detection. Log: `work/stage14-analysis-tests.log`.
- `git diff --check`: passed. Windows PowerShell **5.1.26100.9444**: runner dry run and actual completed-cohort analysis passed. The full Maven reactor test suite was not run.
- Completed smoke: `ai-eval/stage14/20261008-225908-770c06dd/`: **4/4 games**, **19/19 accepted calls**, zero fallbacks/stale failures/duplicates; **19/19 captures verified**. A preliminary attempt was deliberately stopped to correct transport scope; its retained validation note distinguishes that interruption from an engine crash. Smoke review also caught and fixed the eliminated-player summary defect before the main cohort.

## Matched cohort

Evidence: `ai-eval/stage14/20261008-230210-574219ba/` (`report.md`, `aggregate.json`, `request-verification.json`, `review-index.json`, manifests, logs and original audits).

Verified existing decks: **Gatecrasher / Air Forces**, **Aurochs Stampede / Selesnya Surge**. Five seeds × two matchups × two seat orientations × native/external = **40 games**, **20 matched pairs**. Corresponding native controls are repeated for each external seat orientation; they are not 20 independent random controls.

**40/40 games completed.** Zero crashes, game/provider timeouts, provider exceptions, malformed responses, identity failures, fallbacks, stale/live revalidation failures or duplicate requests. Native controls made **zero provider calls**. All **158/158 captured requests verified**, and no individual audit records lacked game/turn correlation.

| Surface | Calls / accepted | Forge agreements / disagreements | Mean provider latency |
|---|---:|---:|---:|
| Mulligan | 20 / 20 | 15 / 5 | 1.881 s |
| Main phase | 25 / 25 | 24 / 1 | 2.484 s |
| Stack response | 7 / 7 | 5 / 2 | 2.805 s |
| Attackers | 80 / 80 | 52 / 28 | 2.719 s |
| Blockers | 21 / 21 | 5 / 16 | 2.862 s |
| Damage order | 0 / 0 | 0 / 0 | — |
| Combat action | 5 / 5 | 4 / 1 | 2.730 s |

Total **158 accepted calls**, **105 agreements / 53 disagreements**, **7.90 calls/external game**. Provider latency total **410.659 s**, mean **20.533 s/game**, game-total p50/p95 **21.221 / 29.962 s**. Per-call mean/p50/p95/max **2.599 / 2.680 / 3.300 / 3.570 s**. Mean game duration excluding JVM startup: external **23.972 s**, native **2.459 s**. Mean turns: external **18.7**, native **19.3**.

Outcomes: external **8 wins / 12 losses / 0 draws**, matched native seats **10 / 10 / 0**. Both external seat orientations finished **4 wins / 6 losses**. Token usage returned for all 158 requests: **310,715 prompt + 19,286 completion = 330,001 total tokens** (provider-reported accounting).

PASS, no-attack and no-block selections were all **zero**. Five combat actions were spells: two **Benalish Knight** flash casts before blockers, two **Chorus of Might** casts after blockers, and one **Resize** after blockers. Damage order was enabled but made no calls in these sampled games; its correctness and post-pump state projection remain covered by the focused fixtures. This cohort does not supply live evidence for damage-order execution or external PASS behavior.

## Five review sequences

The automatic index points to original state/options and decision references. These are observations for review, not mistake labels:

1. `Gatecrasher-1401-seat1-External.jsonl`, **turn 17**, `GAME_845719fb3e3b97926b9fb466_T17`: stack response → Benalish Knight before blockers → blocker choice differing from Forge.
2. `Aurochs-1405-seat1-External.jsonl`, **turn 13**, `GAME_dac5a9dc2a200e65dfe5a3e9_T13`: attacker selection → Chorus of Might; Forge preferred PASS for the combat action.
3. `Gatecrasher-1402-seat1-External.jsonl`, **turn 13**, `GAME_d8bed3db5cb7b3e7b4d1967d_T13`: Benalish Knight resolves, then appears as an eligible blocker in the next request; the external block differs from Forge's no-block choice.
4. `Aurochs-1401-seat1-External.jsonl`, **turn 13**, `GAME_ea82e4882fe5195b24ebeace_T13`: attacker selection → Chorus of Might, both agreeing with Forge.
5. `Aurochs-1404-seat0-External.jsonl`, **turn 24**, `GAME_a43837b069bd8c5ed9a33be4_T24`: attacker selection → Resize after blockers, both agreeing with Forge.

Additional useful sequence: Gatecrasher seed 1402, external seat 1, turn 9: external Disrupt responds to Incinerate targeting Serrated Biskelion while Forge preferred PASS.

**Next step:** review these sequences, especially the 16/21 blocker disagreements and the complete absence of PASS selections, before tuning strategy. Close the remaining live damage-order/PASS coverage gaps with targeted evaluation if needed. No new strategic feature is recommended yet.

## Reproduce

```powershell
.\tools\ai-evaluation\Run-Stage14FullGame.ps1 -Model qwen3:8b -Seeds 1400 -ExternalSeats 0 -RequestDiagnostics
.\tools\ai-evaluation\Run-Stage14FullGame.ps1 -Model qwen3:8b -Seeds 1401,1402,1403,1404,1405 -ExternalSeats 0,1 -RequestDiagnostics
.\tools\ai-evaluation\Analyze-Stage14FullGame.ps1 -Path .\ai-eval\stage14\20261008-230210-574219ba
```
