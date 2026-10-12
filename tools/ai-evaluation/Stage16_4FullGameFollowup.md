# Stage 16.4 full-game follow-up

The recovery work remains unchanged. This follow-up adds an optional `-FullGame` switch to `Run-Stage16_4NaturalGames.ps1` and evaluates Burial with the seven existing stable external surfaces enabled. The default stays MAIN_PHASE_ACTION only. Strategic memory, Stage 12 integrated combat and experimental material/race guards remain disabled. Main-phase candidate bound remains three. No engine, prompt, heuristic, card or deck changes were made.

Qwen 14B drafted the runner changes. Its branch-edit attempts failed, so review corrected the small configuration branch before validation.

## Evidence and results

External evidence: `ai-eval/stage16_4/fullgame-20261012-165319-48969978/`. Matched native controls: `ai-eval/stage16_4/natural-20261012-160344-b69e3200/`. All runs use qwen3:8b, seeds 16411/16412, Burial seat 0 and the existing Aurochs Stampede / Selesnya Surge decks. Controls are reused from the earlier pilot; this is not a fresh randomized comparison.

| Opponent / seed | Native turns | Full-game turns | Accepted decisions | Disagreements |
| --- | ---: | ---: | ---: | ---: |
| Aurochs Stampede / 16411 | 14 | 20 | 5 | 2 |
| Aurochs Stampede / 16412 | 16 | 17 | 7 | 2 |
| Selesnya Surge / 16411 | 17 | 15 | 4 | 2 |
| Selesnya Surge / 16412 | 11 | 11 | 4 | 1 |

All four external games completed. Burial lost all four, as it did in the four native controls. There were 20 accepted decisions, 13 agreements, seven disagreements, zero fallbacks, zero stale/revalidation failures and zero duplicate requests. Mean provider latency was 3,589.5 ms. Differences in game length do not establish strategic benefit or causation.

Observed calls: mulligan 4; main phase 3; stack response 1; attackers 7; blockers 5. Damage order and combat action were configured but produced no provider calls in this sample. All three main-phase requests contained native creature choices, with no Dead Ringers option. Earlier natural recovery evidence remains valid; this sample adds no recovery execution evidence.

## Review of the seven disagreements

- Aurochs 16411 turn 5: attacks with Maggot Carrier 1/1 into untapped Bull Aurochs 2/1 instead of NO_ATTACK. This permits an even creature trade. Soul Link is visible, so card interactions should be checked before judging value.
- Aurochs 16411 turn 7: attacks with Maggot Carrier 3/3 into untapped Woolly Mammoths 3/2 instead of NO_ATTACK. This also permits a trade; Soul Link remains visible. These snapshots alone do not prove a bad attack.
- Aurochs 16412 turn 9: blocks Gorilla Shaman 1/1 with Grave Defiler 2/1 at 13 life instead of NO_BLOCK. The supplied projection correctly reports one creature lost on each side, one damage prevented and 12 remaining life versus 11 without blocking. This is a card-value trade, not the previously guarded one-sided losing block.
- Aurochs 16412 turn 16: attacks with Llanowar Dead and Maggot Carrier for three into a currently tapped opposing board at one life, opponent ten, instead of NO_ATTACK. The pressure is nonlethal and leaves no creature blockers; its value requires the next-turn context.
- Selesnya 16411 turn 6: attacks with Maggot Carrier 1/1 into untapped Centaur Courser 3/3 instead of NO_ATTACK. No aura or other nonland permanent is present. On visible combat alone the opponent can kill the Carrier without losing the Courser. This is the clearest replay candidate for avoidable attacker loss.
- Selesnya 16411 turn 13: chumps Phantom General with Llanowar Dead, projecting two life and loss of the blocker, rather than killing Seller of Songbirds while preserving Llanowar Dead at one life. Both options prevent current lethal. The model trades board preservation for one extra life. No Phyrexian Arena is present in this position. The snapshot does not establish the best future line.
- Selesnya 16412 turn 9: chumps a Centaur token with Grave Defiler, projecting six life and no enemy loss, rather than trading with Seller of Songbirds at four life. Both options are marked near-lethal protection. Again this exchanges enemy removal for immediate life, rather than demonstrating an exposure defect.

No captured choice demonstrates broken routing or illegal execution. No strategic guard or default was changed. The next bounded investigation should replay the Selesnya turn-6 attacker position with scripted decisions and actual combat resolution, then test model repeatability on the identical snapshot before proposing a prompt/projection or guard change. Secondary checks should account for imminent Phyrexian Arena life loss where it is present, including Selesnya seed 16412 turn 9. Four games are insufficient for a win-rate claim.

## Validation

All 20 captured requests passed raw UTF-8 SHA-256 and JSON round-trip checks, exact option enum/order checks, audit identity/fingerprint and selected-option membership checks, accepted-seat checks, absent opponent hidden zones, and thinking-disabled transport checks. Verification is saved beside the evidence as `fullgame-verification-results.json` with the reproducible local `verify.py`. Fingerprints were correlated to audit values, not independently recomputed from engine state.

Default and full-game dry runs each generated eight commands; only the four external full-game commands enable the full-game switch. Control commands and the default main-only path remain unchanged. Windows PowerShell 5.1 dry-run compatibility passed. Real execution additionally validates the full-game external command path. `git diff --check` passed. No Maven rerun was needed for this runner/report-only change; the earlier 374 focused production regression tests remain the latest engine validation.

Repeat the external sample from the repository root:

```powershell
& .\tools\ai-evaluation\Run-Stage16_4NaturalGames.ps1 -FullGame -Mode External -RequestDiagnostics -PassThru
```

Use `-FullGame -Mode Both` for fresh paired controls (eight games). Omit `-FullGame` to retain the existing main-phase-only evaluation.
