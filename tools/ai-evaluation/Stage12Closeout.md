# Stage 12 closeout

8 October 2026

Stage 12 is complete as an experimental integration of independent attacker, blocker and damage-order decisions. The blocking regression was investigated, a narrower opt-in policy was tested, and a predeclared broader comparison was completed. External combat and both material policies remain opt-in; this closeout does not claim a general strength improvement over Forge.

## 1. Blocking counterexample

In Gatecrasher seed 1001 with external Seat1, the defending Air Forces player had 17 life, the opponent had 6, and a tapped 6/6 first-strike Knight could pressure the opponent on the next turn. The original material guard refused a model-selected sacrifice of a smaller Knight against Llanowar Behemoth. The observed attack dealt five damage. Later attacks changed, and the original guard turned a control win into a loss. Exact captured requests and returned choices matched at the first override, making this a useful policy counterexample. Preserving a creature is not sufficient justification for giving up life and tempo in a race.

## 2. Targeted revision and pilot

The RaceAwareV2 experiment retains the original narrow material check but declines to override the model when either the estimated remaining life cannot absorb another attack of the same estimated size, or the other defending creatures have potential unblocked return damage at least equal to the opponent life total. It excludes the proposed sacrificed blocker from return damage. This is an uncertainty bound using public-board native estimates, not exact future simulation or a guaranteed lethal prediction. Existing legality, survival, trigger and trade safeguards remain in place.

Six new policy controls cover the captured race, an ordinary losing block, both sides of the life-buffer boundary, a trade and disabled behavior. The expanded external-AI regression suite passes 210 tests.

| Pilot arm | Games | External wins | Calls | Material vetoes | Other fallbacks |
|---|---:|---:|---:|---:|---:|
| Control | 8 | 3 | 58 | 0 | 0 |
| Material | 8 | 3 | 60 | 1 | 0 |

The pilot restored the Gatecrasher-1001 Seat1 win, with 0 improved, 0 worsened and 8 unchanged outcomes against control. One material veto still occurred elsewhere. All 55 shared requests had identical bytes and identical returned choices. The pilot references eight earlier control games and eight new candidate games; it is not sixteen newly run games.

## 3. Broader comparison

Before seeing the pilot result, the follow-up was fixed to Gatecrasher seeds 1010–1013 and Aurochs seeds 121–124. These seeds were absent from the earlier cohorts. The 40 new games comprise eight Forge-only baselines, sixteen external-control games and sixteen RaceAwareV2 games. Both external seats were evaluated for each seed; candidate/control first-run order alternated across consecutive seat pairs. Both external arms used repeatable combat IDs, exact request capture, the same model, endpoint, prompts and option caps.

| Broader arm | Games | External wins | Calls | Material vetoes | Other fallbacks |
|---|---:|---:|---:|---:|---:|
| Control | 16 | 4 | 99 | 0 | 0 |
| Material | 16 | 3 | 107 | 3 | 0 |

Against control: 0 improved, 1 worsened, 15 unchanged paired outcomes. All 206 captured requests were verified. Shared identities: 91; different bytes at shared identities: 0; different returned choices on identical requests: 0.

| External arm versus matching Forge outcome | Improved | Worsened | Unchanged |
|---|---:|---:|---:|
| Control | 0 | 4 | 12 |
| Material | 0 | 5 | 11 |

Disposition: reject RaceAwareV2 as a strength improvement. The original race exception fixes its motivating example but does not generalize: Aurochs-121 Seat1 changed from a control win to a candidate loss. Its first decision, a turn-4 blocker choice, had identical captured bytes and the same returned block choice in both arms; the candidate applied NO_BLOCK instead. A later second veto occurred in the same game. The only other changed trajectory was Aurochs-122 Seat0, which remained a loss. Both policies stay disabled, and the two adverse cases are preserved as observed-game counterexamples rather than universal per-position truth labels.

These are two deck pairings and eight seed pairs, with sparse damage-order calls. Do not treat a small observed difference as a general strength result or pool this cohort with earlier protocols. If the revised guard does not trigger in a game, a matching outcome provides no evidence of its strategic benefit.

## 4. Final review and supported configuration

- 210 Java regressions passed, including earlier mulligan, main-phase and stack-response paths, all combat surfaces, routing, partial fallbacks, protected defenders, interrupted/replaced/lethal combats, capture and identity controls.
- The diagnostic harness passed 26 offline controls in both PowerShell 5.1 and 7; exact-byte transport, strict final-response and invalid UTF-8 controls also passed in both.
- The policy comparator accepted a valid pair and rejected three deliberately corrupted scratch copies. The CLI routed RaceAwareV2 correctly and left material guards off by default.
- Broader cohort: 0 audit/public-snapshot issues, 0 provider failures and 0 native safety fallbacks. Forge-only baselines made zero model calls.
- Critical routing, immutable option maps, live validation, observer lifecycle, strict supplied-ID acceptance, request capture and comparison paths were reviewed. Combat sequences remain telemetry-only; no shared model strategy state was introduced.
- Existing uncommitted staged work and all prior ai-eval evidence were preserved. No commit, reset, model replacement or default-policy promotion was performed.

Recommended configuration: ordinary Forge combat for normal play. External integrated combat remains available for explicit experiments. Keep LegacyV1 and RaceAwareV2 material guards disabled by default. Seeded IDs and request capture are evaluation tools and default off. Noncombat surfaces remain independent.

Remaining strategic research is a documented follow-up, not unfinished Stage 12 integration: improve model combat judgment, especially the life/material/tempo tradeoff; test any new proposal with fresh seed pairs and the captured-request protocol. Neither a locally losing block nor a local material gain proves the best game outcome.

## Evidence and reproduction

- `tools/ai-evaluation/Stage12IntegratedCombat.md`: current scope, switches, limits and policy descriptions.
- `tools/ai-evaluation/Run-Stage12IntegratedCombat.ps1`: independent cohort runner; `-PassThru` returns its exact output directory.
- `tools/ai-evaluation/Analyze-Stage12IntegratedCombat.ps1`: per-run coherence and public-snapshot validation.
- `tools/ai-evaluation/Compare-Stage12CombatPolicies.ps1`: exact-byte policy comparison with `-ExpectedMaterialPolicy RaceAwareV2`.
- `work/stage12-final-plan.json`: predeclared seeds, pilot gate and broad-cohort scope.
- `work/stage12-final-summary.json`: final machine-readable result, including individual run directories.
- `work/stage12-final-regressions.log`, `work/stage12-final-source-review.json`, `work/stage12-final-comparator-controls.log`, `work/stage12-final-cli-controls.log`: validation and review evidence.
- `work/Run-Stage12FinalEvaluation.ps1`: resumable orchestration, preserving completed entries; the exact same manifest is not a fresh independent cohort.
- Raw per-game logs, CSVs, decision/sequence audits and request captures remain in their unique directories under `ai-eval/stage12/`.

The earlier 90-game baseline comparison remains separate evidence: 22 external wins in 60 external-seat games, with four improved and twelve worsened outcomes versus matching Forge games. Invalid forwarding-proxy experiments remain marked invalid and excluded. Offline mocked controls are not live-model results.
