# Stage 16.3 — verified activated-ability counter recovery

Completed 10 October 2026. The existing ability-level registry now positively verifies **Stifle, Rust and Disallow against activated abilities only** through STACK_RESPONSE. No new decision surface, strategy, counter heuristic, prompt, ordering rule or provider reasoning setting was added.

## Outcome and inventory

The Stage 16 inventory contains 2,500 warning-eligible card records. Screening their ordinary Counter scripts for activated/triggered target declarations identified **six cards** for this cluster. **Three** satisfy the initial simple contract, **three** passed individual execution verification, **three** were registered, and **three** remain unverified/rejected. No triggered-target case was registered.

| Card | Contract outcome | Activated-target policy | Trigger-target policy |
| --- | --- | --- | --- |
| Stifle | Verified ordinary U counter | VERIFIED_SAFE | Excluded |
| Rust | Verified ordinary G artifact-source counter | VERIFIED_SAFE, artifact source only | Excluded by rules and policy |
| Disallow | Verified ordinary 1UU counter | VERIFIED_SAFE | Excluded |
| Interdict | Rejected: remembered target and follow-on activation-lock Effect/subability | Unverified | Excluded |
| Trickbind | Rejected: remembered target, follow-on activation-lock Effect/subability and special keyword behavior | Unverified | Excluded |
| Repudiate | Rejected from initial cluster: split-card face/cost preparation not independently verified | Unverified | Excluded |

`stage16/activated-counter-recovery.csv` records the exact inventory script for every candidate, warning retention and rejection reason. The split-card rejection is a deliberate verification boundary, not a claim that Forge cannot execute Repudiate.

## Stifle trace: the original gap was policy enforcement

1. `forge-gui/res/cardsfolder/s/stifle.txt` declares an ordinary instant with U mana cost and `SP$ Counter`, `ValidTgts$ Card,Emblem`, `TargetType$ Activated,Triggered`, and `AI:RemoveDeck:All`.
2. CounterEffect marks its target zone as Stack; native `CounterAi.checkApiLogic` considers the top stack ability, checks native target legality and prepares that target. Its strategic choices are unchanged.
3. `AiController.getPlayableCounters` collects Counter abilities before the ordinary host-card warning filter. `chooseCounterSpell` retains the native fallback through that separate path. As with the audited Swan Song exception, the warning does not universally block counters.
4. The generic `prepareStackResponseActions` pipeline makes a GameCopier copy, maps the counter and top stack ability, prepares targets, revalidates, copies cast choices and reverse-maps targets back into the live game.
5. Before Stage 16.3, this Counter path did **not** enforce ExternalAiAbilitySupport. Stifle could already be exposed against an activated top object even though the registry classified it as unsupported. Therefore this stage formalizes and hardens an existing partial path rather than claiming that all Stifle casts were formerly excluded.
6. Top-trigger STACK_RESPONSE routing was already bypassed by `shouldRouteExternalStackResponse`; that guard remains unchanged. Native Forge may still choose a trigger counter outside external routing. Stage 16.3 does not alter native behavior or add trigger-counter support.

The new guard runs on both the copied prepared counter and the reverse-mapped live counter before adding an option. It runs again on the selected prepared response before execution. The exact original native decision remains the fallback.

## Safe cluster contract and registry

Recovery requires an individually reviewed exact entry in the **same Stage 16.1/16.2 immutable registry**, keyed by source name, API and STACK_RESPONSE. Names are registry keys, not runtime rules-text heuristics. Each entry records exact script parameters, script/mana cost and verification note.

The candidate must be an ordinary root instant spell, fixed mana-only cost, exactly one stack-object target, no X, no modes, no alternate or optional cost, no splice, no optional keyword, no extra payment selection, no subability or unresolved nested callback. Changed or extra parameters fail closed. Existing pump entries remain in that registry and retain their COMBAT_ACTION-only surface.

A Counter registry match alone does not certify Stifle's dual activated/triggered script. The **actual prepared target** must:

- classify as a nonmana ACTIVATED_ABILITY using engine state;
- have a public visible source and an activating player in the same game;
- remain the exact SpellAbility of an extant stack instance, not its original ability template or a subsequent activation copy;
- remain counterable and legal for the prepared counter;
- satisfy the normal copied/live restrictions, target-number and payment checks.

Any Counter replacement definition anywhere in the game conservatively bypasses activated-counter recovery; preparation never invokes replacement-choice callbacks. This is intentionally broader than testing applicability and is recorded as a limitation below.

Disallow's legitimate ordinary-spell counter path remains separate and legal under existing rules. Stifle/Rust cannot use that path to target a spell. Mixed spell/trigger target lists cannot bypass the activated-target contract by putting a spell first, including when a type declaration is absent. Unverified ability-counter templates remain denied. The warning is not removed or treated as whole-card certification.

## Classification, projection and immutable mapping

`StackObjectType.classify` uses `isTrigger`, `isSpell`, `isActivatedAbility`, `isReplacementAbility`, `isManaAbility` and wrapper state. It returns SPELL, ACTIVATED_ABILITY, TRIGGERED_ABILITY or OTHER. Ambiguous wrappers, mana and replacement abilities are not recovered. Display text never determines the target type.

StackItemView now carries this immutable object type alongside the existing visible source, controller, API/effect description and public targets. The provider receives the type. Prepared action target summaries identify the exact visible stack position/type/source/controller; no raw engine ID or mutable object is exposed. Other hidden targets retain the existing privacy projection.

The stack fingerprint includes object type and the prepared public target summary. Opaque option IDs remain produced by the existing deterministic StackResponseOptionIds/presentation code; randomization/order settings are unchanged. Internal prepared mapping/heuristic ranking remains excluded from the provider serialization.

Old captured request contexts deserialize the newly added field as null. Serialization omits an absent type rather than adding a null property, preserving the existing Stage 15 assertion that disabled-memory historical request bytes are identical. Fresh engine projections carry the actual type. No prompt changes were required.

Request identity and option membership are still checked against the immutable pre-call presentation mapping. Live checks then reproject the existing prepared mapping and validate target presence/type/identity, current template, legality and costs. Candidate sets are not regenerated to validate the model's response. Wrong decision ID, fingerprint or option, provider failure, removed target, target-type change or lost mana uses the retained Forge fallback; model output is never repaired.

An important fixture finding: MagicStack copies activated abilities as they are placed on the stack. The verified identity is therefore the **actual stack instance's ability**, not the original permanent ability passed to activation. Execution tests pay Rod of Ruin's real three-mana/tap cost before capturing that instance.

## Execution and exclusion evidence

For all three registered spells, scripted providers verify opaque ID, exact activated target, legal payment and counter resolution, retained source permanent, unchanged protected player life, accepted PASS, provider failure, stale target, target-type mutation, mana loss, wrong decision ID, wrong fingerprint and unknown option. Fallback selection IDs match the recorded native heuristic IDs.

Triggered-top fixtures produce no external provider call and no prepared counter mapping. Direct prepared trigger targeting remains unsupported. Mixed stacks contain an ordinary spell, triggered ability and activated ability; only the legal activated top object is prepared for this recovery. The existing pipeline continues to prepare only the top object rather than enumerate lower-stack target alternatives.

Additional tests preserve all three card-wide deck warnings, deny surface mismatch and changed templates, reject Interdict/Trickbind/Repudiate, reject a removed or reactivated object, and bypass Counter replacements. Hidden opponent Black Lotus is absent from every model projection. Swan Song's real spell counter/token consequence and ordinary Counterspell behavior remain green in the existing tests.

## Live qwen3:8b validation

Successful run: `ai-eval/stage16_3/20261010-001502/`.

Nine coverage fixtures: Stifle, Rust and Disallow × seeds 16301/16302/16303. Rod of Ruin's opponent-controlled activated damage ability is legally paid and placed on the stack, targeting an AI at one life. This supplies a strategically clear ordinary public ability without changing native strategy. These are incomplete legal fixtures, not constructed win-rate decks or completed-game evidence.

- **9 verified activated-counter offers**.
- **9 selected and legally resolved counters**, including **3 Stifle selections**; three distinct registered cards.
- **PASS present in all nine exact structured enums**. The model selected action in all nine; the scripted per-card PASS tests separately prove accepted priority passing.
- **0 trigger targets exposed**, **0 fallback**, **0 stale/revalidation failure** in the successful run.
- Mean provider latency **1,939.44 ms**; first request 5,658 ms, remaining eight average **1,474.63 ms**.
- All captured request byte hashes and bodies verified. Model is qwen3:8b and `reasoning_effort=none`; transport and output cap remain unchanged.
- Offline verification correlates request decision ID/fingerprint, exact enum, public target type/source, provider acceptance, audit selection and legal fixture result. Opponent hidden hand and internal heuristic/mapping fields are absent.

The first manual startup attempt at `ai-eval/stage16_3/20261010-001203/` failed before any request because the reused fixture called TestNG Assert without that library on the manual classpath. The fixture guards were made independent of TestNG, and the manual main now exits reliably after reporting errors/results. That failed startup evidence is retained; it is not included as a provider or recovery failure.

From repository root in Windows PowerShell:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ai-evaluation\Run-Stage16_3ActivatedCounterRecovery.ps1 -Model 'qwen3:8b'
C:\Python312\python.exe .\tools\ai-evaluation\verify_stage16_3.py .\ai-eval\stage16_3\20261010-001502
```

Use the runner's newly printed output directory for a fresh verification. Default seeds are 16301/16302/16303; from an existing PowerShell session use `-Seeds @(16301,16302,16303)` to override them. `-Endpoint` overrides the compatible endpoint, and `-DryRun` prints the invocation. The runner uses PS 5.1-compatible hidden Start-Process and separate stdout/stderr redirection. It enables only STACK_RESPONSE in the fixture and creates a fresh timestamped evidence directory. No Ollama is needed for unit tests.

## Validation and preserved evidence

**351 focused Java tests passed**, with zero failures/errors/skips in 18 classes. The 43 new Stage 16.3 cases run alongside the previous 308-test suite: STACK_RESPONSE execution/opaque IDs/order/request validation, Swan Song, Stage 16 audit, Stage 16.1 Earthbrawn/Reinforce, Stage 16.2 pump cluster/native probes, prepared actions, privacy/projection, Stage 15 memory, main-phase, combat actions/blockers/damage order, Stage 14.2 blocker behavior and mulligan regressions.

`work/stage16_3/regressions-final.log` records the final suite. `test-results.json` records class counts. The final manual harness exit handling was compiled separately; the live run verifies that compiled harness. Only the desktop module and required dependencies were built; no full Maven reactor was run.

Hash preservation checks cover **39,245 baseline files**. Exactly five existing production files changed intentionally; no baseline files are missing. All **3,071 prior ai-eval files**, all **33,980 card scripts**, prior reports/CSV outputs, prompt text, strategic memory code and blocker strategy remain byte-identical. StackObjectType and the Stage 16.3 tests/tools/report/evidence are additions. `git diff --check` exits **0**; new source/report files also receive explicit whitespace/EOF checks.

## Files and registry impact

Existing production files changed:

- ExternalAiAbilitySupport.java: three exact verified entries and prepared activated-target guards.
- AiController.java: apply the guard at copied preparation, live mapping and selected-response revalidation; expose the package-level preparation seam for focused tests.
- MainPhaseStateProjector.java: public stack type/target projection and fingerprint coverage.
- decision/StackItemView.java: immutable type field with a compatible constructor.
- decision/external/OpenAiCompatibleDecisionProvider.java: serialize the visible type when present; transport/prompt unchanged.

Added StackObjectType.java; Stage16ActivatedCounterRecoveryTest.java; Stage16ActivatedCounterRecoverySmoke.java; Run-Stage16_3ActivatedCounterRecovery.ps1; verify_stage16_3.py; stage16/activated-counter-recovery.csv; this report and isolated evidence. No card-specific execution path or second registry was introduced.

## Limitations and next cluster

This proof covers the three exact spell templates against an ordinary artifact damage activation. It is not verification of every activated source/effect. Rust keeps its artifact-source restriction. Triggers, ambiguous wrappers, modes, alternate costs, split-card casting and remembered-target follow-on effects remain unverified. Recovery only considers the existing top-stack preparation seam. The Counter-replacement bypass can exclude an otherwise safe counter even when the replacement would apply only to another spell; narrow it only if real evaluation shows meaningful coverage loss, with separate applicability proof.

Exactly one next mechanic cluster from the Stage 16 audit: **pairwise conditional removal targeting**, starting with Dead Ringers. Its relational target condition needs copied preparation, exact paired mapping and live legality/execution verification through the existing MAIN_PHASE_ACTION path. That cluster was not implemented in this stage.

**A. ACTIVATED-ABILITY COUNTER RECOVERY SUCCESSFUL**
