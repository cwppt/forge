# Stage 16.1: selective recovery of verified abilities

Earthbrawn's ordinary instant pump now enters COMBAT_ACTION when relevant, using existing copied preparation, bounded options, opaque IDs, PASS, immutable request validation and live execution checks. Its Reinforce ability remains excluded. Card scripts, warning metadata and native heuristic decisions were not changed.

## Original exclusion chain

`forge-gui/res/cardsfolder/e/earthbrawn.txt` contains `AI:RemoveDeck:All`. CardRules parses this into CardAiHints.getRemAIDecks. The deck warning remains `Deck.getUnplayableAICards -> AiController.complainCardsCantPlayWell -> Match -> revealUnplayableByAI`.

For priority decisions, `ComputerUtilAbility.getSpellAbilities` collects the ordinary Pump spell and keyword-generated Reinforce ability. `AiController.chooseSpellAbilityToPlay` removes abilities whose **host card** satisfies `ComputerUtilCard.isCardRemAIDeck`, before main/combat preparation. This is host metadata exclusion, not a Pump API failure. Direct Stage 16 copied preparation already proved ordinary Pump works. Reinforce carries a distinct activated ability/API/cost and was never verified.

## Ability-level capability layer

`ExternalAiAbilitySupport` is one isolated, immutable registry with `DEFAULT`, `VERIFIED_SAFE`, `UNSUPPORTED` statuses. Its stable key is source name + actual ability API + decision surface; each reviewed entry also specifies its exact script parameter template and mana cost. It does not use object identity, candidate index, RNG or timestamps.

There is exactly one entry: Earthbrawn's ordinary Pump, COMBAT_ACTION only. The entry requires an instant root spell, exact +3/+3 and Creature target template, mana-only `{1}{G}`, no subability, no extra script parameters (apart from matching Cost), no alternate/optional cost, no optional keyword payment and no announced X. The existing safeTemplate check additionally rejects unsafe timing, mechanics and splice state. Any changed template defaults to unsupported.

An explicit registry is necessary because the audit verified one ability, not every Pump card. API/shape similarity alone does not certify keyword-generated alternate abilities, extra costs, triggers or nested callbacks. The name is isolated in the registry, not scattered through execution code. Costs/templates are entry data, enabling future individually reviewed entries without a global Earthbrawn-shaped cost rule. This is deny-unless-verified admission for warned cards; DEFAULT leaves unmarked abilities under existing checks.

## Routing and fallback

AiController preserves its **original native candidate list** and decision. Only with COMBAT_ACTION enabled, a provider present and an existing empty-stack combat priority window does it collect verified abilities from the pre-filter list. Native host-marker filtering then proceeds unchanged. Verified abilities are appended only to the external combat candidate list, after Forge's original fallback has been chosen.

Nothing is appended to MAIN_PHASE_ACTION or STACK_RESPONSE. Reinforce has no registry entry and is denied for every surface. Swan Song's independent counter enumeration/STACK_RESPONSE preparation is untouched. No new feature flag or decision surface is added: existing `AI_EXTERNAL_COMBAT_ACTION_ENABLED=false` default and max-actions bounds apply.

SafeTemplate checks the same capability policy again before external preparation/snapshot mapping. This prevents a changed or sibling ability from entering through the direct combat snapshot seam. Prepared targets, costs, copied choices, combat relevance and source/window identity remain governed by existing code; no special casting path or validation bypass exists.

Forge's original choice remains the fallback, including its original PASS when Earthbrawn was excluded. Scripted PASS is accepted. Invalid IDs (including attempts to select an invented Reinforce ID), missing responses, changed target incarnation or lost payability use the original fallback. Unchanged-priority deduplication is retained.

## Audit and privacy

The existing opt-in `forge.ai.combatAction.eligibilityAudit` JSONL gains `ABILITY_SUPPORT` events for warned candidates: game/window/surface, source, broad-warning flag, actual API, spell-vs-activated identity, description and support status. In the fixture, the ordinary spell is VERIFIED_SAFE and Reinforce is UNSUPPORTED. Existing preparation/decision audits supply the exact exposed option and live-validation result; the smoke's results file confirms actual payment/resolution.

Support classifications never enter provider DTOs. Existing observer-safe combat projection is unchanged. Tests and real requests confirm no opponent Black Lotus hand sentinel, Reinforce description or VERIFIED_SAFE marker is exposed. No raw script/engine serialization is added.

## Validation and live evidence

The required focused suites cover recovery, COMBAT_ACTION windows/candidates, STACK_RESPONSE, opaque stack IDs/order, prepared actions, Stage 16 audit probes (including Swan Song), and full-game observer-safe projection. Final counts and preservation verification are in `work/stage16_1/validation.json`.

New tests prove: warning retention; ordinary spell admission, preparation, exact target, payment and +3/+3 resolution; PASS; native disabled behavior/fallback; stale target and mana failure; no sibling mapping; surface-specific eligibility; unverified About Face exclusion; unmarked Giant Growth DEFAULT; changed template/X/optional-cost denial; private information absence; internal admission/rejection audit. The Stage 16 pump probe now explicitly checks native/disabled behavior; its historical Stage 16 report/inventory remain unchanged.

Live smoke uses a deterministic legal post-block fixture, not a full match-strength experiment: Hill Giant attacks, Runeclaw Bear blocks, deciding AI holds Earthbrawn with two untapped Forests. Earthbrawn is also recorded in that AI's registered deck. The fixture uses existing repository cards and production card scripts. Seeds 16101, 16102, 16103 each expose exactly the ordinary spell plus PASS; every qwen3:8b selection pays and resolves the pump. Reinforce is rejected and never mapped. No winner is fabricated for these incomplete fixtures.

Initial live evidence: `ai-eval/stage16_1/20261009-233017/` — 3 requests, 3 legally resolved pumps, 0 PASS selections, 0 fallbacks, 0 stale/revalidation failures. Latencies: 8,077 / 2,089 / 2,107 ms (first call slower; subsequent calls about 2.1 seconds). Request byte hashes, exact schema enums, no hidden hand/unsupported sibling, and Qwen `reasoning_effort=none` are verified in `verification.json`. PASS is proven by scripted tests; these three real calls demonstrate mechanical coverage, not improved win rate or natural-deck frequency.

Final-source live evidence: `ai-eval/stage16_1/20261009-233412/` — **3 requests, 3 legally resolved pumps, 0 PASS selections, 0 fallbacks and 0 stale/revalidation failures**. Latencies: 2,740 / 2,794 / 2,795 ms, averaging **2,776 ms**. `verify_stage16_1.py` independently verifies all three raw request hashes, exact pump/PASS schema enums, ordinary admission and Reinforce rejection, private-hand absence, disabled Qwen reasoning and the execution results. The initial smoke is preserved separately; this is a repeat of the same three controlled seeds, not six independent games.

Final tests: **148 passed; 0 failures, errors or skips**, including 14 new recovery cases, 86 combat-action cases, 19 stack-response cases, 17 prepared-action cases, 8 Stage 16 audit cases and 4 observer-safe projection cases. Swan Song's existing real preparation/payment/counter/token regression passes. `git diff --check` passes; existing LF/CRLF notices are not whitespace errors. Hash verification preserves all **33,980 production card scripts, 3,017 pre-existing evaluation files and all seven historical Stage 16 report/output files**. Only the three intended existing production Java files changed; all other existing production Java files remain byte-identical. The Stage 16 test's native/disabled probe was the only modified pre-existing test file. No full Maven reactor was run.

## Changed files

- `forge-ai/src/main/java/forge/ai/ExternalAiAbilitySupport.java` — registry/capability policy.
- `forge-ai/src/main/java/forge/ai/AiController.java` — separate recovered combat list, native fallback unchanged.
- `forge-ai/src/main/java/forge/ai/ExternalCombatActions.java` — policy check at existing safety/mapping boundary.
- `forge-ai/src/main/java/forge/ai/CombatActionEligibilityAudit.java` — opt-in internal support evidence.
- `forge-gui-desktop/src/test/java/forge/ai/Stage16AbilityRecoveryTest.java` — focused scripted checks.
- `forge-gui-desktop/src/test/java/forge/ai/Stage16CardSupportAuditTest.java` — retain native/disabled historical probe.
- `forge-gui-desktop/src/test/java/forge/ai/Stage16AbilityRecoverySmoke.java` — explicit manual live fixture runner.
- `tools/ai-evaluation/Run-Stage16_1AbilityRecovery.ps1` — PowerShell 5.1-compatible runner; hidden process and separate stdout/stderr redirection; preserves outputs under ai-eval/stage16_1.
- `tools/ai-evaluation/verify_stage16_1.py` — offline request/support/execution evidence verifier for the qwen3:8b fixture.
- This report and new validation/evaluation artifacts.

## Manual commands

From the repository root:

```powershell
mvn -o -pl forge-gui-desktop -am "-Dtest=Stage16AbilityRecoveryTest,CombatActionExternalDecisionTest,CombatActionCoverageTest,StackResponseExternalDecisionTest,StackResponseOpaqueOptionTest,StackResponseActionOrderTest,PreparedAiActionTest,Stage16CardSupportAuditTest,FullGameProjectionConsistencyTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\tools\ai-evaluation\Run-Stage16_1AbilityRecovery.ps1 -Model 'qwen3:8b' -Seeds @(16101,16102,16103)
C:\Python312\python.exe tools/ai-evaluation/verify_stage16_1.py ai-eval/stage16_1/20261009-233412
git diff --check
```

## Limits and final classification

Only the ordinary reviewed Earthbrawn template is recovered. Reinforce, generic modal/X/alternate-cost/nested-choice recovery, other warned cards and other surfaces remain outside this change. Runtime mutations, special combat, nonempty stack ownership and stale mappings still pass through existing conservative exclusions/fallback. Future entries need the same per-ability preparation/payment/resolution/privacy evidence; adding a name alone is insufficient.

**A. ABILITY-LEVEL RECOVERY WORKS — ready to generalise**

Next reusable cluster: individually verified **fixed-cost, single-target combat pump/protection spells on broadly marked cards**, using the same ability/surface registry and existing preparation. That cluster has not been implemented here.
