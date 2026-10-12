# Stage 13.1 — targeted COMBAT_ACTION coverage

Stage 13.1 preserves the Stage 13 decision surface, provider protocol, prepared action pipeline, native strategic heuristics, option IDs, fingerprint rules, live revalidation, defaults and Stage 12 integration. Only independently reproduced replacement-guard false exclusions were corrected.

## Demonstrated gaps and fixes

1. **Crimson Acolyte outside combat suppressed its own legal protection ability.** Its ordinary protection-from-red replacement effect caused the board-wide guard to reject combat. The failing fixture had an opposing red Hill Giant attacking a Runeclaw Bear, a noncombat Crimson Acolyte, and payable white mana. Allowing ordinary keyword protection on noncombat permanents matches the existing protection support on combatants. The fixture now routes, prepares its target, pays, resolves and gives the Bear protection from red.
2. **Already-settled self-entry effects suppressed unrelated combat actions.** Foul Orchard's self-only battlefield-entry replacement hid Blossoming Defense. The real cohort audit then identified the same problem on Gnarlid Pack and Skitter of Lizards, which enter with counters. Additional failing fixtures proved that a settled Gnarlid Pack outside combat hid Doom Blade and a settled Gnarlid Pack blocking hid Giant Growth. Exact self-only battlefield-entry replacements on permanents already in play are now exempt in both replacement checks. They do not participate in the current combat. Newly cast flash creatures with replacements remain unsupported.

Before-fix evidence: `work/stage13-1-coverage-before.log` (Crimson Acolyte), `work/stage13-1-tapped-land-before.log` (Foul Orchard), `work/stage13-1-self-entry-creature-before.log` and `work/stage13-1-self-entry-combatant-before.log` (Gnarlid Pack). Global damage and counter replacements remain conservatively excluded; dedicated Furnace of Rath and Winding Constrictor fixtures verify that.

## Deterministic validation

`CombatActionCoverageTest` supplies 19 cases without Ollama. They exercise the real `PlayerControllerAi.chooseSpellAbilityToPlay` / `AiController.getSpellAbilityToPlay` seam, copied-game preparation, identity validation, live revalidation, native payment, stack execution and spell resolution.

| Coverage | Fixture |
|---|---|
| After attackers, before blockers | Doom Blade targeting the declared attacker |
| After blockers, before damage | Doom Blade, Giant Growth, Rootwalla, Crimson Acolyte, Blossoming Defense |
| After first-strike damage, before normal damage | Double-striking Hill Giant deals three actual Forge-assigned damage to Giant Spider; Doom Blade then routes and resolves before normal damage |
| PASS | A real prepared Giant Growth option exists; scripted provider chooses literal PASS; passing is accepted |
| Unsupported/global replacements retained | Winding Constrictor and Furnace of Rath still bypass |
| Eligibility diagnostics | No candidates, unpayable action, unrelated Opt, unresolved color-choice protection, prepared action outside supported windows, opt-in output, distinct deduplication and bounding counts |

One exploratory first-strike fixture was rejected by Forge's existing removal-target heuristic, which predicted the target would die anyway and did not produce a complete prepared target. That strategic behavior was retained. The final first-strike fixture uses actual damage assignment, initialized blocker order, and a surviving double striker; it proves the intended route and execution without changing heuristics.

## Opt-in eligibility evidence

`-RequestDiagnostics` on `Run-Stage13CombatActions.ps1` now also writes `<matchup>-<seed>.eligibility.jsonl`. Direct JVM callers can set `-Dforge.ai.combatAction.eligibilityAudit=<absolute-path>`.

This separate local diagnostic stream records window, source/API, timing/restriction and cost checks, legal target availability, combat target availability, supported-template status, prepared/relevant/exposed counts, deduplication and bounding counts, retained replacement blockers, and routing outcomes. Outcomes distinguish unsupported combat, excluded window, stack-response ownership, nonempty stack, no supported template, no prepared combat action, unchanged priority and provider invocation. Accepted PASS/action results remain in the established decision audit.

Probes run on copied state under an isolated random stream. No eligibility data is supplied to the model. The stream is absent by default and does not add permanent decision metrics. Diagnostics intentionally cost extra computation and should be enabled for coverage investigations, not ordinary latency benchmarks.

`TIMING_COST_AND_TARGETS_AVAILABLE` is preliminary engine evidence, not a claim that arbitrary modes, X values, target combinations or nested choices can safely be prepared. Complete prepared and revalidated actions remain the conclusive execution evidence. Probe limits and unprobed counts are explicit. Counts represent repeated priority observations, not unique decisions.

## Validation commands

From the repository root:

```powershell
mvn -o -pl forge-gui-desktop -am '-Dtest=CombatActionCoverageTest,CombatActionExternalDecisionTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

& .\tools\ai-evaluation\Run-Stage13CombatActions.ps1 -Matchup All -Seeds @(1301,1302,1303,1304,1305,1306) -Model 'qwen3:8b' -RequestDiagnostics -PassThru

git diff --check
```

The focused regression selection is the established Stage 13 set plus `CombatActionCoverageTest`: attacker/blocker/damage-order tests, integrated combat, prepared actions, provider/factory/seat selection, stack-response, main-phase and mulligan. No full Maven reactor test suite is required.

## Results and interpretation

**301 tests passed, zero failures/errors/skips**, in `work/stage13-1-regressions-final.log`. This comprises the 282 established focused regressions and 19 new coverage cases. `git diff --check` passed. The evaluation script's Windows PowerShell 5.1 dry run also passed.

Final real qwen3:8b replay: `ai-eval/stage13/20261008-223150-1c897a3d/`, with both original matchups and seeds 1301–1306. The earlier diagnostic replay is retained at `ai-eval/stage13/20261008-222238-8bab4374/`; it identified the settled-creature exclusion before that fix was completed. All pre-existing evaluation outputs were preserved.

| Final replay measure | Result |
|---|---:|
| Completed games | 12 |
| Games with decisions | 10 |
| Accepted combat actions | 22 |
| PASS selections | 0 |
| Forge agreements | 17 |
| Different action selections | 4 |
| Forge PASS / external action | 1 |
| Fallbacks / stale or revalidation failures | 0 / 0 |
| Average / maximum provider latency | 2,992.6 / 7,676 ms |
| Supplied non-PASS options | 26 |
| Exact wire requests verified | 22 |

The supplied options comprised 11 Giant Growth, eight Rootwalla and seven Blossoming Defense options. All actual model calls remained after blockers. All 22 requests passed SHA-256 checks, exact enum checks including PASS, the four-action bound, the 512-token cap and disabled-thinking verification. All accepted choices belonged to the original snapshot, and all audit events had matching eventual combat correlation.

The 1,584 eligibility observations included 509 with no nonmechanical candidate and 508 with no engine-playable candidate. These are repeated observations, not unique decisions. There were no prepared actions removed by the final option bound, 17 duplicate variants, zero unprobed candidates, and no observed safely prepared combat actions in excluded routing windows. Stack response was disabled in this cohort; stack precedence remains covered by the existing focused regression.

**The answer is mixed: B was demonstrated and fixed, while sampling and conservative preparation still account for missing categories.** Ordinary protection and irrelevant settled self-entry replacements were genuine exposure failures, demonstrated by failing-before/passing-after fixtures. The final real replay now supplies protection options. No routing defect was found in any of the three intended windows, and scripted PASS is accepted with a real prepared alternative.

Real removal was still not selected. The final audit identifies a concrete preparation boundary in Removal seed 1303: at turn nine, Doom Blade had legal combat targets but no complete prepared action was returned; at turn eleven, a prepared Doom Blade action existed but was not combat-relevant. No option bound removed a prepared combat removal action. This is not evidence of a broken priority route. Native target selection was retained rather than forcing a new target policy. The deterministic fixtures independently prove that a safely prepared removal action routes, pays and resolves in all three windows.

No real PASS or first-strike selections occurred in the cohort. Their availability and execution semantics are proven by fixtures, not claimed as observed model behavior. The wider global replacement exclusions, unsupported costs/choices, bounded candidate policy and default-disabled setting remain. This coverage investigation does not measure playing-strength improvement or justify enabling the feature by default.

Files changed for Stage 13.1: `ExternalCombatActions.java`, `AiController.java`, new `CombatActionEligibilityAudit.java`, new `CombatActionCoverageTest.java`, `Run-Stage13CombatActions.ps1`, and this document. The final cohort contains `run.json`, per-game logs/CSV/decision JSONL/request captures/eligibility JSONL, `evaluation-analysis.json`, and `coverage-analysis.json`.
