# Stage 13: prepared instant-speed combat actions

Status: implemented, disabled by default, experimental. This adds only `COMBAT_ACTION`. Stage 12's trio, integrated flag, evaluation artifacts and disabled material guards are preserved. No strength improvement is claimed.

## Routing and execution

The exact seam is `AiController.getSpellAbilityToPlay()`, after Forge has computed `chosenAction` / `legacyStackResponse` and after existing stack-response routing. The caller is `PhaseHandler.mainLoopStep()` -> `PlayerControllerAi.chooseSpellAbilityToPlay()`; Forge has already performed the phase's turn-based declaration or first-strike damage action before granting priority.

Supported empty-stack priority phases:

- `COMBAT_DECLARE_ATTACKERS`: attackers have been declared, blockers have not.
- `COMBAT_DECLARE_BLOCKERS`: blockers and damage order have been declared, combat damage has not occurred.
- `COMBAT_FIRST_STRIKE_DAMAGE`: first-strike damage has occurred; normal damage has not.

The live priority holder must be the deciding AI, a supported two-player combat must exist, and at least one relevant prepared action must survive. There is no call for only PASS. Main phases, declaration callbacks themselves, damage-order callbacks, ordinary damage/end combat and non-empty stacks bypass this surface. Exact unchanged priority snapshots are deduplicated within a combat, including cached accepted PASS choices. Disabled routing adds a null check and no enumeration or provider work.

With an opposing non-trigger spell/ability on the stack, the existing enabled `STACK_RESPONSE` surface now owns these three combat priority phases; its original other exclusions remain. COMBAT_ACTION always bypasses a non-empty stack, including when stack responses are disabled. There is never a call to both surfaces for one priority choice.

Forge's original ability or PASS is retained before external work. Preparation uses the existing copied-game `prepareCandidateActions` / `SpellAbilityChoiceCopier` pipeline, including safe strategic rejections when Forge passes. Enumeration considers at most 24 whitelisted source templates, bounds supplied actions to 4 by default and 8 absolutely, retains a relevant Forge choice first, deduplicates equivalent outcomes, and presents options sorted by opaque ID. It does not enumerate target combinations.

The whitelist supports targeted pump/debuff, fixed protection, damage/removal, battlefield bounce/exile, targeted prevention/regeneration and safe self-only activated tricks. Simple flash creatures with no triggers/replacements can be supplied as potential blockers before blocker declaration. Targets must be current combatants. Public cost/legality and power/toughness/keyword static sources are included conservatively in the focused battlefield projection. Available public mana sources are checked on ability copies; floating mana reuses the existing safe DTO. Opponent hand/library cards are never included.

A one-use engine-internal prepared-choice handoff reaches `ComputerUtil.handlePlayingSpellAbility` through `PlayerControllerAi.playChosenSpellAbility`. It preserves the selected modes, targets, X and optional costs during actual payment and stack placement. Unchosen optional keyword additions/splice are declined; the normal mode/target callbacks cannot replace this prepared choice. Native execution retains its existing behavior. Forge remains responsible for costs, payment, legality, resolution and normal fallback.

PASS is always a legal supplied alternative at these priority seams. An accepted PASS returns no ability and is not a failure. Tests prove PASS, an alternate action and all disagreement categories independently of Ollama.

## Snapshot and safety

Provider DTOs contain only immutable records and copied lists. `CombatActionOptionView` supplies source reference/name, category, cost, exact target references, concise effects, modes, X and optional costs. It contains no rank, source index, live objects or raw engine identifiers.

`OPT_<SHA-256>` hashes the sanitized prepared option and canonical ability-parameter signature, including opaque source references. IDs do not use presentation rank, timestamps or live RNG. Forge incarnation timestamps are kept only in the internal live-validation guards. The fingerprint covers the canonical public combat projection, life, mana, relevant static effects, exact supplied options and their presentation order, targets/modes/X/cost summaries and PASS availability. Counters, keywords, combatants, assignments and targets are canonicalized.

After the synchronous provider call, request ID, fingerprint and membership in the original immutable mapping are checked first. Candidate sets are never regenerated for identity validation. Only then does live validation check the same combat/game/priority/window, source and target incarnations/controllers/zones, unchanged prepared signatures and projection, playability, costs and target legality. PASS requires only the original combat/window and valid passing priority. A stale state, invalid identity, unknown option, timeout, exception or malformed output uses the original Forge choice without repair.

Conservative bypasses include multiplayer, bands, face-down combatants, alternate/unusual damage assignment, wither, replacement-heavy boards, unresolved color/target/mode callbacks, splice, hidden-zone-dependent expressions, unsupported effects and non-mana/non-source-tap costs. Tapping effects are excluded because tapping an already declared combatant generally does not alter its combat assignment. Modal actions survive only when their chosen, linked subabilities are fully prepared and whitelisted. Flash creatures with ETB callbacks are excluded.

## Configuration and transport

| Preference | Default | Bound |
|---|---:|---:|
| `AI_EXTERNAL_COMBAT_ACTION_ENABLED` | `false` | independent of Stage 12 integrated combat |
| `AI_EXTERNAL_COMBAT_ACTION_MAX_ACTIONS` | `4` | 1–8 actions, plus PASS |

CLI: `--external-ai-combat-action-enabled`, `--external-ai-combat-action-max-actions N`. The evaluation script also uses an explicit disable flag and explicit existing-surface disable flags to avoid inherited saved settings. Provider/model/endpoint/timeout settings are reused. The neutral prompt requests only `decisionId`, `stateFingerprint`, `optionId`; the schema enum is precisely supplied IDs plus literal PASS. Ordinary output is capped at 512 tokens. Existing default-port Ollama Qwen3 `reasoning_effort=none` behavior is preserved and applied here.

## Telemetry and audit

The summary exports `combatActionDecisions`, `combatActionProviderCalls`, `combatActionOptions`, `combatActionPassSelections`, `combatActionAgreements`, `combatActionDisagreements`, `combatActionFallbacks`, `combatActionStaleOrRevalidationFailures`, `combatActionAvgProviderLatencyMs`, `combatActionAvgTotalLatencyMs`, plus Forge-PASS/external-action, Forge-action/external-PASS and different-action counts. Options counts exclude PASS. CSV uses the established 34-column schema.

JSONL records `COMBAT_ACTION`, the sanitized immutable request, all options, game/turn/window, `combatSequenceId`, Forge/returned/final selections, PASS/action, acceptance/fallback reason, explicit live-validation outcome, disagreement category and latency. At game completion the existing correlated combat outcome is joined as `eventualCombatResult`. Stage 13 events never increment the old Stage 12 trio provider-call metrics. Fixture outcomes are explicitly incomplete; no fixture winner is fabricated.

## Evaluation and manual commands

Run from `C:\Users\Chris\Workspace\forge`. `Run-Stage13CombatActions.ps1` is Windows PowerShell 5.1 compatible and uses hidden processes with redirected stdout/stderr. It writes unique runs below `ai-eval/stage13/`, manifests, metrics CSV, audit JSONL, logs and per-game summary JSON. RequestDiagnostics also captures exact UTF-8 requests with SHA-256 verification material.

Verified existing precon presets:

- **Tricks**: `Counter Surge` vs `Bestial Strength`. Counter Surge has four Blossoming Defense; Bestial Strength has Giant Growth and Rootwalla.
- **Removal**: `Brute Force` vs `Bestial Strength`. Brute Force has Lightning Bolt and Giant Growth; Bestial Strength has Doom Blade, Rootwalla and Vial of Poison. Unsupported effects remain native Forge choices.

Default mode enables COMBAT_ACTION only and disables mulligan/main/stack and all three Stage 12 surfaces. `-CombatTrio` independently enables attacker/blocker/damage order with legacy ordering; `-StackResponse` enables the existing response surface. Integrated combat remains off. `-Seeds`, `-Matchup`, `-MaxActions`, provider timeouts and `-RequestDiagnostics` are configurable. `-DryRun` prints the exact process arguments. `-FixtureSmoke` runs the explicit non-unit local provider fixture and requires compiled test classes.

```powershell
Set-Location C:\Users\Chris\Workspace\forge
mvn -o -pl forge-gui-desktop -am -DskipTests test-compile

# Guaranteed prepared option; actual local qwen3:8b request, no fabricated game result.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/ai-evaluation/Run-Stage13CombatActions.ps1 -FixtureSmoke -Model qwen3:8b -PassThru

# Verified real-game smoke seed.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/ai-evaluation/Run-Stage13CombatActions.ps1 -Matchup Removal -Seeds 1302 -Model qwen3:8b -RequestDiagnostics -PassThru

# Broader independent-surface evaluation, called directly from PowerShell.
.\tools\ai-evaluation\Run-Stage13CombatActions.ps1 -Matchup All -Seeds @(1301,1302,1303) -RequestDiagnostics
.\tools\ai-evaluation\Run-Stage13CombatActions.ps1 -Matchup Removal -Seeds @(1302) -CombatTrio -StackResponse -RequestDiagnostics

git diff --check
```

Focused regression command:

```powershell
$tests = 'CombatActionExternalDecisionTest,ExternalCombatDiagnosticIdsTest,CombatBlockersExternalDecisionTest,CombatDiagnosticGroundTruthTest,BasicBlockTests,BasicAttackTests,CombatAttackersExternalDecisionTest,CombatDamageOrderExternalDecisionTest,IntegratedCombatExternalDecisionTest,OpenAiCompatibleDecisionProviderTest,AiDecisionProviderFactoryTest,ExternalAiSeatSelectionTest,MulliganDecisionProviderTest,MainPhaseExternalDecisionTest,PreparedAiActionTest,StackResponseExternalDecisionTest,StackResponseActionOrderTest,StackResponseOpaqueOptionTest'
mvn -o -pl forge-gui-desktop -am "-Dtest=$tests" -Dsurefire.failIfNoSpecifiedTests=false test
```

This selects focused tests in the required local modules, not the full reactor's test suite. Ollama is not required by those tests. `CombatActionOllamaSmoke` is an explicit manual main program, not a unit test.

## Stage 13 file changes

New: `ExternalCombatActions.java`; decision `CombatActionDecisionContext.java`, `CombatActionOptionView.java`, `CombatActionDecisionEvent.java`; `CombatActionExternalDecisionTest.java`; `CombatActionOllamaSmoke.java`; this document; `Run-Stage13CombatActions.ps1`.

Updated: `AiController.java`, `ComputerUtil.java`, `PlayerControllerAi.java`, `LobbyPlayerAi.java`, `MainPhaseStateProjector.java`, `AiDecisionType.java`, `AiDecisionMetrics.java`, `OpenAiCompatibleDecisionProvider.java`, `ForgePreferences.java`, `AiDecisionProviderFactory.java`, `GamePlayerUtil.java`, `SimulateMatch.java`, `OpenAiCompatibleDecisionProviderTest.java`, `AiDecisionProviderFactoryTest.java`.

Existing uncommitted staged-architecture work and all earlier `ai-eval/` outputs are preserved. No commit, reset or full Maven reactor test run was performed.

## Evaluation limits

This is engineering validation, not a playing-strength result. Real seeds can expose no actions because preparation and relevance guards are deliberately conservative; replacement-heavy boards and rejected choices without complete targets are common bypasses. Real smoke calls have selected actions; PASS remains in the real wire schema and is exercised in deterministic tests, without claiming the real model chose PASS. Only broader evaluation showing a material coverage/strength problem should justify a Stage 13.1 change to these exclusions, alternative targeting or candidate diversity. Stage 12's material guards remain disabled.

## Verified closeout, 8 October 2026

- **282 focused/regression tests passed**, zero failures/errors/skips. This includes 67 Stage 13 fixture cases, structured provider/configuration cases, and all selected attacker/blocker/damage-order/integrated/main/stack/mulligan regressions.
- Actual execution tests verify selected modal targets/modes and X=3 through payment and stack placement.
- `git diff --check`: exit 0. Windows PowerShell 5.1 dry-run and real redirected process runs succeeded.
- Final game: `C:\Users\Chris\Workspace\forge\ai-eval\stage13\20261008-213847-786d4f59` — 4 accepted decisions, 4 actions, 0 PASS, 0 fallbacks, 0 stale/revalidation failures; average provider latency 1743.75 ms.
- Final fixture: `C:\Users\Chris\Workspace\forge\ai-eval\stage13\20261008-214152-10c4d3a3` — 1 accepted decisions, 1 actions, 0 PASS, 0 fallbacks, 0 stale/revalidation failures; average provider latency 1691.00 ms.
- All five final game/fixture request captures match their exact UTF-8 SHA-256 bytes, enumerate PASS plus supplied opaque IDs, cap output at 512 and disable Qwen thinking. Every action audit joins its existing combat sequence outcome. Real candidate sources include Giant Growth and Rootwalla.
- Earlier preserved runs include no-action seeds 1301/1303 and the Tricks preset seed 1302; these are coverage observations, not provider failures or evidence of improved playing strength.
- Full verification: `work/stage13-validation-summary.json`, `work/stage13-regressions.log`, `work/stage13-diff-check.log`; source hashes: `work/stage13-source-review.json`.
