# Stage 14.2 blocker fix

Validation date: 2026-10-09. Final evidence: `ai-eval/stage14-2/20261009-094931/`. The exact frozen requests correct **six of the seven** Stage 14.1 unnecessary losing blocks. The seventh remains deliberately unannotated because its public effects are outside the projection's safe scope. The revised full-game runs preserve lethal-saving blocks and have no fallback or stale-state failures; the small cohort shows no win-rate change.

## Cause and scope

All seven original decisions selected legal, correctly mapped blocks. Evasion, targets, option IDs and execution were working. Each selected blocker died, no attacker died, and the block saved only 1–3 life while NO_BLOCK left the defender safely alive. These were local exchange errors, rather than routing or legality failures. The original view provided creature statistics and assignments but did not quantify the losses, prevented damage and remaining life for each option. The model's internal reason is not observable; no chain of thought was requested. A controlled old/new comparison supports supplying those missing consequences.

Only the blocker option projection and blocker prompt change. No options are pruned, reordered or newly generated. No new decision surface, configuration, material guard, or other strategic prompt changes are introduced. Forge's existing safety check, fallback, immutable request validation and live revalidation remain unchanged. The pre-change compiled AI provides the control; these are original external-policy controls, not wholly native Forge controls.

## Projection and prompt

The immutable optional `TacticalConsequences` supplies creature losses on each side, damage prevented, life after combat, lethal prevention, near-lethal protection, unfavorable trade, chump block, board preservation, and the narrowly scoped `unnecessaryLosingBlock` annotation. It uses a scratch combat and Forge's combat helpers (`damageIfUnblocked`, `canDestroyBlocker`, `canDestroyAttacker`, `getEnoughDamageToKill`, `predictDamageTo`). Nine fixtures compare projections against actual Forge damage and creature deaths, including trample, first strike and deathtouch.

The basis explicitly assumes no further spells or activated abilities and describes combat damage, not future card value. No opponent hand or other hidden information is read. With incoming unblocked damage D, unblocked remaining life L and damage prevented P, near-lethal protection means `0 < L <= D` and positive prevention. An unnecessary losing block requires own creature loss, no opposing creature loss, no lethal/near-lethal protection, `L > D`, and `2P < L`. There is no fixed life-total cutoff. Any attacker death excludes the unnecessary classification; no card-value ranking is invented.

Uncertain projections are omitted for death/block/damage triggers, relevant replacements, prevention shields, hidden characteristics, unusual combat keywords/damage, commanders, attacks against planeswalkers or other non-player defenders, and ambiguous multiple-blocker/attacker ordering. Resolved attack pumps, upkeep-only triggers and entry-only effects do not by themselves prevent annotation. All existing legal options, including profitable multi-blocks and critical-permanent protection, remain available.

The blocker prompt adds:

> When life is safely above immediate lethal danger, avoid sacrificing valuable blockers merely to prevent small amounts of damage unless the block creates a meaningful trade or other tactical benefit.

And the evaluated survival correction adds:

> Choose a survival-preserving block when taking the damage would be lethal and a supplied block prevents it.

Life-saving and near-lethal blocks are not labeled unfavorable. A lethal NO_BLOCK option does not claim board preservation. Opaque IDs retain the exact old hash material; annotations enter the versioned `COMBAT_BLOCKERS_V2` fingerprint. Tests and all frozen exports verify unchanged option IDs and presentation order. There is no Forge recommendation in the added model-visible data. Existing audit/request capture automatically records the annotations; no new permanent telemetry is needed. Qwen3 thinking remains disabled.

## Exact seven-decision comparison

These are identical captured public requests, with the same IDs, mappings and option order. Original controls reproduce all seven bad selections. Life is defender/opponent before combat; losses are self/opponent for the original block. The Forge choice is NO_BLOCK in every row. Complete request IDs, opaque option IDs, requests, raw responses and annotations are in `frozen-results.json`.

| Game / seat / turn | Original external block | Revised external | Life | Losses | Prevented | Life after old block → revised choice | Avoided? |
|---|---|---|---|---|---|---|---|
| Aurochs 1401 / 0 / 9 | Earthen Goo → Centaur Courser | NO_BLOCK | 17/18 | 1/0 | 3 | 14 → 11 | Yes |
| Aurochs 1403 / 0 / 7 | Boreal Druid → Seller of Songbirds | NO_BLOCK | 20/20 | 1/0 | 1 | 18 → 17 | Yes |
| Aurochs 1403 / 1 / 10 | Centaur's Herald → Aurochs | NO_BLOCK | 19/20 | 1/0 | 1 | 12 → 11 | Yes |
| Aurochs 1405 / 0 / 11 | Aurochs → Centaur Courser | NO_BLOCK | 20/20 | 1/0 | 3 | 20 → 17 | Yes |
| Aurochs 1405 / 0 / 13 | Aurochs → Centaur Courser | NO_BLOCK | 20/20 | 1/0 | 3 | 14 → 11 | Yes |
| Gatecrasher 1401 / 0 / 12 | Grizzly Bears → Jamuraan Sentinel | Same block | 18/20 | 1/0* | 2* | 16 → 16* | No; projection omitted |
| Gatecrasher 1402 / 1 / 13 | Benalish Knight → Jamuraan Sentinel | NO_BLOCK | 16/20 | 1/0 | 2 | 16 → 14 | Yes |

\* Historical Stage 14.1 event evidence, not a new guaranteed projection. Abduction/Mistmoon Griffin effects make this state uncertain; the new annotation is null. This case was not forced into the simple model to improve the success count.

Across all 21 frozen blocker contexts: original NO_BLOCK **0/21**, revised **8/21 (38.1%)**. All 42 responses pass strict decision ID, fingerprint and option validation. The existing lethal-saving Aurochs 1405/seat 0/turn 15 block remains selected, as do the checked profitable Mammoths/Guildmage, Brushstrider/Bull, and attacker-killing exchanges. Both Griffin recursion blocks remain selected despite omitted annotations. The Savage Surge-supported Herald block changes to NO_BLOCK: holding the trick is plausible, but this projection cannot establish that the new choice is better. It is explicitly not counted as a corrected bad block.

## Full games and regression checks

| Metric | Targeted original | Targeted revised | Cohort original | Cohort revised |
|---|---:|---:|---:|---:|
| Games | 6 | 6 | 8 | 8 |
| Wins / losses | 0 / 6 | 0 / 6 | 3 / 5 | 3 / 5 |
| Provider calls | 42 | 39 | 67 | 73 |
| Blocker decisions | 14 | 10 | 7 | 9 |
| Selected unnecessary-losing annotation | 8 | 0 | 3 | 0 |
| Selected projection unknown | 3 | 1 | 3 | 6 |
| Blocker disagreements | 13 | 3 | 7 | 7 |
| NO_BLOCK | 0/14 (0%) | 4/10 (40%) | 0/7 (0%) | 2/9 (22.2%) |
| Lethal-saving blocks / available opportunities | 1/1 | 1/1 | 0/0 | 0/0 |
| Raw missed lethal prevention | 0 | 0 | 0 | 0 |
| Near-lethal protection selections | 0 | 2 | 0 | 0 |
| Attacker-killing selections in known projections | 1 | 2 | 0 | 0 |
| Fallback / stale / duplicate | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 |

All **221 full-game provider calls** have matching captured requests and audits. There are no timeout, invalid-response or revalidation fallbacks. The annotation count is conditional current-combat classification, not retrospective proof that each block was bad: future tricks can change the exchange. The reviewed seven-case replay is the primary quality measure. The cohort contains no annotated lethal-saving opportunity and cannot independently establish performance in that situation.

The six exact seed/matchup/seat pairs run first (12 games), followed by eight matched pairs (16 games) using the same two existing matchups and seeds 1401/1402 in both seats. Some cohort seeds overlap the targeted schedule; these are small descriptive checks, not independent win-rate evidence. All stable external surfaces are enabled for one seat, legacy combat ordering is retained, and integrated mode/experimental material guards remain disabled. Other strategy is unchanged. Full reruns can diverge before an original reviewed turn, so the seven-decision table uses frozen requests rather than pretending later boards stayed identical.

The first candidate's complete comparison (`20261009-092128`) corrected the same six cases but exposed a raw lethal NO_BLOCK at Aurochs 1403/seat 1/turn 14. Forge rejected it and applied a survival block. That candidate was not accepted: the conflicting unfavorable/preserves-board labels were corrected and survival guidance added before the final rerun. The exact captured mistake was rechecked after the adjustment: the first candidate again chose NO_BLOCK (projected life 0), while the adjusted candidate chose `OPT_7F74770D87CDF8AD158829D2` (projected life 1). Both responses passed strict identity validation; raw responses are retained in `critical-lethal-recheck.json`. The final full-game targeted run selects the available lethal-saving block and two near-lethal protection blocks, with no missed lethal prevention or safety fallback.

No-block states where every supplied option loses are distinguished from missed survival opportunities. Unknown projections remain unknown; zero classified bad blocks is not a claim that every unannotated block was good.

## Validation, provenance and reproduction

- **332 Java tests passed**, zero failures/errors/skips, including 18 new projection tests, existing blocker tests, Stage 12/13 combat, mulligan/main-phase/stack, provider transport, full-game audit/configuration and native attack/block regressions. Focused desktop dependency build only; no full reactor. Log: `work/stage14-2/regressions-final-verified.log`.
- **Nine Python evaluator/analysis tests passed**, including schema synchronization, strict unrepaired identity validation, and raw missed-survival accounting. PowerShell 5.1 parser reports zero errors.
- **`git diff --check` passed.** All 225 original Stage 14 evidence files are byte-for-byte unchanged. Of 2,653 Java files recorded before Stage 14.2, only the four intended existing files changed; 2,649 remain unchanged, plus the two new Java files. The evaluated runtime predates only the final non-player-defender omission; all evaluated blocker attacks target players, so that tested omission does not change these comparisons.

The early two-call attempt (`20261008-235715`) is excluded: a replay harness fingerprint/schema mismatch was fixed and tested without repairing a response. The later interrupted full-game attempt (`20261009-094416`) is also excluded from game statistics: a concurrent build replaced loaded class files. The final run uses isolated, hashed compiled classes, records exact Java commands and validates its runtime did not change. All attempts and raw evidence are preserved.

From the repository root, with Ollama serving qwen3:8b:

```powershell
.\tools\ai-evaluation\Run-Stage14_2BlockerFix.ps1 -Model qwen3:8b
```

The PowerShell 5.1 wrapper uses a hidden process with redirected stdout/stderr. It requires the preserved pre-change AI snapshot under `work/stage14-2/original-ai` and the Forge-exported frozen projections under `work/stage14-2/frozen-projections.json`; it refuses to silently compare revised code against itself. New evidence goes into a timestamped `ai-eval/stage14-2/` directory. Use `-FrozenOnly` for the 42-request comparison. The analysis tool consumes the live export produced by `BlockerTacticalProjectionTest#reviewedPublicStatesExportWithoutChangingIdsOrOrder` using its input/output properties.

Changed files: `BlockerTacticalProjection.java`, `ExternalCombatBlockers.java`, `CombatBlockOptionView.java`, the blocker prompt and its provider test, `BlockerTacticalProjectionTest.java`, `fixtures/stage14-2-blockers.json`, `evaluate_stage14_2.py`, `analyze_stage14_2.py`, their two Python test files, `Run-Stage14_2BlockerFix.ps1`, and this report. Broad strategy tuning and further stages are outside this change.

A. FIX SUCCESSFUL — keep change
