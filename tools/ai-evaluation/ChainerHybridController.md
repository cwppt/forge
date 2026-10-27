# Chainer hybrid controller audit and bounded validation — 2026-10-22

## Result

An opt-in extension now lets the harness controller choose single-card discards, simple reanimation targets, authorized tutor results and single mandatory sacrifices. It also repairs enumeration of Chainer-granted graveyard spells. Forge still generates actions, checks restrictions and costs, pays costs, changes zones and resolves triggers. Existing native choices remain the fallback.

**Final regression gate: 164 passed, 0 failed, 0 skipped.** A five-state comparison completed all 15 controller/scenario resolutions, with five accepted local Qwen recommendations, no invalid replies, no fallbacks and no request timeouts. This validates narrow decision plumbing and paid execution, not autonomous Commander competence or a win-rate advantage. Larger comparisons are not yet justified by this sample alone.

Evidence directory: `work/chainer-hybrid-20261022/`. Machine-readable results: `summary.json`. Final binary: `build-06/engine-chainer-hybrid.jar`, SHA256 `89f6f0fcff9101f8439344cea523f4418254dbbb88fd93e089a4055050b5feb7`.

## Implementation audit

The requested checkout is `forge-commander-harness`, on `feature/commander-simulation-harness` at `8e25b040118`. The sibling `forge` checkout is on `feature/llm-ai-opponents` at `1d968fefa2d`. Both had substantial existing uncommitted changes. No checkout, reset, cherry-pick or commit was performed, and the sibling checkout was only read.

The harness branch already contains external mulligan, bounded main-phase action selection, stack responses, attacker alternatives, strict decision ID/fingerprint/option validation, OpenAI-compatible transport and native fallback. Candidate preparation uses copied games and deterministic candidate RNG; selected actions are checked again against the live game. Main-phase routing requires an active-player main phase, empty stack and at least two accepted candidates. Lands and some special choices bypass that router. Full-simulation AI also bypasses the ordinary main-phase route.

The newer LLM branch adds blockers, combat actions/damage order, strategic memory, wider rejected-ability recovery, request capture and desktop logging. Its existing `ExternalCardChoices` helper is restricted to particular flagged mechanics/control effects; it is not general Chainer discard/tutor/reanimation support. That later branch was inspected rather than merged wholesale into this dirty harness checkout.

Native Commander improvements largely live in frozen experimental companions rather than committed source. `work/rivaz-ai-fixes-20261020/REPORT.md` documents an opt-in Chainer activation/discard/recast policy, Rakdos Signet funding and Faithless Looting eligibility. Its Chainer policy deliberately favors surplus-land discards and already-affordable graveyard creatures; it does not plan speculative Dragon discards into an initially empty graveyard. `work/swash-balanced-v2-20261018/REPORT.md` documents BalancedV2's Swashbuckler Treasure/combat valuation. BalancedV2 is that experimental native policy, not a new universal Forge difficulty setting.

The final experiment extends the frozen Rivaz-fixes companion, SHA256 `ce0a4fce653646ac349dd19561b95233f5d42d9ddda58795d77d8a19015db575`, which already incorporates BalancedV2. The packaging adapter retains the frozen experimental `AiController` and `ComputerUtil` policies while applying only the new mapping/execution hooks. This matters because compiling those two files directly from the checkout loses experimental native hooks. All archive entries outside eight replaced and four added classes are byte-identical to the frozen baseline. Sources, hashes, commands, test results and an exact source snapshot are retained under `build-06/` and the final run folders.

## Decision coverage

Every row below is exposed by Forge. “Finite” means the LLM chooses only supplied options; it does not mean all strategically possible alternatives are enumerated. Native fallback exists in every intercepted row. Unintercepted paths simply remain native.

| Decision | Harness before this task | Current interception and information | Finite legal choices / fallback | Remaining gap |
|---|---|---|---|---|
| Mulligan | Implemented and tested | Existing keep/mulligan hook; own opening hand and public player metadata | KEEP/MULLIGAN, native fallback | London bottoming remains native; no new Chainer mulligan benchmark |
| Discard | Native only | Execution-time ordinary one-card discard costs, and own exact-one discard effects; own hand/public zones/rules | Filtered valid pool, card discardability recheck, native fallback | Multiple-card, random, whole-hand, source-only and correlated-name costs remain native; no universal discard ranking |
| Tutor | Native only | After delayed reveal, exact engine search pool for own-library single-card zone changes | Sorted authorized candidates, library ownership/zone checks, native fallback | Optional native decline is preserved; opponent-library searches, global search planning and every special tutor path are unsupported |
| Graveyard spell | Main-phase selection existed but Chainer alternatives were unmappable | Permission-derived spells regenerated by Forge inside each copied game; visible hand/graveyard and prepared actions | Existing action IDs, live cost/restriction checks and native fallback | Main-one holding policy can suppress options; native acceptance and caps limit exposure; lands/full-simulation routes differ |
| Reanimation target | Prepared native target was fixed | Live single-target Graveyard→Battlefield ChangeZone spells/abilities and ordinary queued/static triggers; source/target rules and public state | Forge target candidates and target recheck, native fallback | Multi-target, coupled-target and most subability chains remain native; Reanimate's non-targeted life-loss/cleanup suffix is explicitly supported |
| Activated abilities | Main-phase router could choose prepared activations | Existing router plus execution discard/reanimation hooks; paid Necromancer/Chainer sequence tested | Forge ability/cost/restriction checks | No general activated-ability recovery; Chainer initiation still depends on the native experimental policy or an already-exposed candidate |
| Sacrifice | Native only | Exact-one mandatory permanent sacrifice effects, using the supplied pool | Sacrifice legality/controller/zone checks, native fallback | Sacrifice costs, optional counts and multi-card sacrifices remain native. Necromancer's self-sacrifice is an engine cost, not a choice |
| Sequencing | Repeated independent decisions | Re-query after resolved actions; no macro bypass. Chainer→graveyard Necromancer→activation→Bladewing chain executes in a scripted fixture | Each action is separately paid/resolved; one-creature and once-per-turn limits tested | No LLM plan commitment, plan memory or autonomous end-to-end Chainer sequence benchmark |
| Combat | Duel attacker alternatives; normal native combat otherwise | Existing combat surfaces retained; Dragon Tempest/Scourge damage actually resolves in fixtures | Existing bounded combat candidates/native fallback | Harness attacker alternatives are restricted to supported two-player combat. Newer sibling branch is broader; no multiplayer combat competence claim |
| Removal/protection | Existing main-phase/stack-response hooks with native targets | Those hooks retained; tutor can choose a visible-board answer | Existing prepared actions/native fallback | No general retargeting for removal/protection; stack detail and tactical forecasting for card subchoices remain limited |

New card-choice contexts use the existing whitelist projector: own hand, public battlefield/graveyard/exile/command, life, phase, mana and rules text. They do not serialize engine objects, opponents' hands, unsearched library contents or library order. The tutor exception exposes only the search pool Forge has authorized for the chooser, sorted independently of library order. Controlled-player choices conservatively remain native. These records omit some strategically useful details, including explicit summoning-sickness and land-drop counters and detailed stack items. Legality remains engine-enforced even when strategic information is incomplete.

## Exact changes and hooks

Production source files changed for this task:

- `forge-ai/src/main/java/forge/ai/ExternalGraveyardChoices.java` — new bounded choice helper, immutable projection, exact metadata checks, live fingerprint recheck, legality predicates, native fallback and optional JSONL sidecar. Also regenerates permission-granted copied actions with matching permission-host identity; restores a missing effect-source reference only inside the copied game.
- `forge-ai/src/main/java/forge/ai/decision/CardChoiceDecisionContext.java` — new immutable options/context record.
- `forge-ai/src/main/java/forge/ai/decision/AiDecisionType.java` — CARD_CHOICE.
- `forge-ai/src/main/java/forge/ai/PlayerControllerAi.java` — own discard effects, exact-one sacrifice effects, authorized library searches, chosen-action retargeting and both queued/static trigger retargeting. Other player/control/variable-count cases retain native behavior.
- `forge-ai/src/main/java/forge/ai/AiCostDecision.java` — ordinary one-card discard selection, opt-in only when the execution caller enables it. Preview/desirability visitors never enable external choices.
- `forge-ai/src/main/java/forge/ai/ComputerUtil.java` — three real payment paths enable that execution-only visitor mode.
- `forge-ai/src/main/java/forge/ai/AiController.java` — when printed-ability mapping fails, request regeneration of the permission-granted alternative. The helper is disabled with the new feature flag.
- `forge-ai/src/main/java/forge/ai/decision/external/OpenAiCompatibleDecisionProvider.java` — CARD_CHOICE schema/payload/validation; optional prompt/response capture; opt-in reasoning suppression and response-token cap.

Tests and runner:

- `forge-gui-desktop/src/test/java/forge/ai/ChainerHybridDecisionTest.java` — 13 new targeted fixtures using real card scripts/costs/zone changes/triggers.
- `forge-gui-desktop/src/test/java/forge/ai/decision/external/OpenAiCompatibleDecisionProviderTest.java` — four additional HTTP/provider contract cases.
- `forge-gui-desktop/src/test/java/forge/ai/ChainerHybridSmoke.java` — five fixed states, three controller configurations, paid resolutions.
- `work/chainer-hybrid-20261022/Experiment.py` — isolated build/profile/test/smoke adapter; unique output names refuse overwrites; test timeout 180 seconds; smoke timeout 180 seconds; each provider call capped at 30 seconds.
- `work/chainer-hybrid-20261022/ProbeOllama.py` — two bounded compatibility probes plus read-only version/model metadata capture.

No decklist, card script, Magic rule, stable packaged binary, global Ollama setting or existing evaluation script was changed. Existing dirty edits to these files remain alongside the incremental changes; use the final source snapshots to inspect the exact experimental build.

## Test results

Final gate: `work/chainer-hybrid-20261022/regression-final-02/results/testng-results.xml` — **164/164 passing**, process exit 0, no skips. This includes new Chainer fixtures, existing mulligan/action/provider contracts and native Rivaz, BalancedV2, four-card support, rules and bounded-evaluation regressions. Some inherited fixtures overlap between test classes; these are test invocations, not independent gameplay observations.

The targeted fixtures execute actual payments and effect resolution for:

- Discarding Shivan Dragon, casting it through Chainer's real permission, paying mana and receiving haste.
- Choosing graveyard Doomed Necromancer over hand Hill Giant through the actual main-phase router, paying for it and consuming the one-creature permission. Copied permission references do not escape into the live action.
- Casting Doomed Necromancer from the graveyard, receiving Chainer haste, paying B/tap/self-sacrifice, returning Bladewing and resolving its Dragon-return trigger.
- Reanimate choosing Scourge over Shivan, paying the corresponding life loss, and excluding noncreature land targets.
- Bladewing's Dragon-only targeting, Dragon Tempest/Scourge damage and exclusion of Necromancer from that target pool.
- Demonic Tutor moving Chaos Warp into hand from an authorized search pool; an opposing Rest in Peace supplies the visible strategic context.
- An actual sacrifice effect preserving Chainer by sacrificing Raging Goblin.
- Invalid option/decision/fingerprint, provider exception/null/timeout, stale visible state, hidden hand/library boundaries, disabled/singleton/native-decline paths and preview paths that must not call the provider.

Inherited native fixtures include insufficient mana/wrong colors, early land preservation, existing Rivaz permission, Grafdigger's Cage, Drannith Magistrate, once-per-turn use and real paid reanimation restrictions. A sick Necromancer without haste cannot activate. These are legality and execution assertions, not checks of a model's written rationale.

Development failures remain preserved. Initial compilation needed explicit comparator typing and non-wildcard TestNG imports. The first targeted run was 104/106; it exposed missing queued-trigger interception and Reanimate suffix support. A later added sequencing fixture exposed permission-alternative mapping and an incorrect main-one casting expectation. The first broader gate was 143/164 because the experimental package had accidentally dropped BalancedV2 utility hooks; it was rejected and corrected before the final scored smoke. Earlier builds/tests/smokes are development evidence and are not pooled into the final comparison.

## Ollama/Qwen compatibility

Installed model: `qwen3:30b`, model ID `ad815644918f`; Ollama reported version `0.40.8`. The saved model metadata still reports thinking default true, and its template ends with an opening `<think>` block. The historical template concern is therefore relevant; suppression must be measured.

Observed locally:

- Native `/api/chat`, `think:false`, structured schema: valid decision, zero returned thinking characters, 11.47 seconds including model loading/runtime overhead.
- `/v1/chat/completions`, `reasoning_effort:"none"`, structured schema: valid decision, zero returned reasoning characters, 14.12 seconds.
- All five final provider responses: valid content JSON, zero returned thinking/reasoning characters.

This demonstrates these particular requests work on this installation; it does not prove that no internal computation occurs or that arbitrary future prompts/templates suppress thinking reliably. `/no_think` was not relied on or claimed verified. Capture contains exact request bodies, response bodies and safe model configuration. The new suppression switch is opt-in, with `max_tokens:512`; schema/metadata validation still rejects empty/truncated/tagged/malformed content and falls back. Separate reasoning fields are ignored when valid decision JSON is present in content; reasoning is never extracted as an action. HTTP tests cover valid content alongside reasoning and rejection of `<think>` text inside content.

No models were installed, no global configuration was changed, and no paid service was used.

## Final fixed-state comparison

Final scored evidence is exclusively `work/chainer-hybrid-20261022/smoke-final/`, using `build-06`. All five before-state fingerprints match across all three controller configurations. Opposing state, resources, source scripts and fixture seed are identical. Controllers are:

A. Frozen native companion with BalancedV2/four-card/Rivaz flags, new card choices disabled.

B. The existing hybrid routing path with local Qwen configured, new card choices/mapping repair disabled. It has no card-subchoice calls, and the old post-Chainer candidate mapping fails before exposing a main-phase request. This is a coverage control, not evidence of Qwen selecting those native outcomes.

C. Same native companion and flags, new card choices/mapping repair enabled, local Qwen3:30b, temperature 0, JSON_SCHEMA, reasoning_effort none, max_tokens 512, 30-second per-request timeout.

| Fixed state | Native / existing hybrid control | Extended Qwen | Supported interpretation |
|---|---|---|---|
| Chainer discard | Mountain | Mountain | Agreement; model did not demonstrate the Dragon-discard line in this sample |
| Reanimate with Dragon Tempest | Shivan; self 34, opponent 39 | Scourge; self 35, opponent 38 | Real extra immediate damage and one less life lost; resulting 4/4 versus 5/5 is a strategic tradeoff, not proven overall improvement |
| Tutor under Rest in Peace | Mountain | Chaos Warp | A relevant answer reached hand. Removal was not cast; only two lands were present, so answer timing versus mana development needs manual review |
| Mandatory sacrifice | Goblin; Chainer survives | Goblin; Chainer survives | Agreement; both preserve the engine |
| Post-Chainer graveyard versus hand | Cast Shivan from graveyard | Cast Shivan from graveyard | Agreement, but extended router actually exposed five candidates and accepted an LLM choice; old hybrid used native fallback without a model call |

Final model calls: **5 accepted / 5 attempted**, 0 invalid responses, 0 fallbacks, 0 request timeouts. Agreement 3, disagreement 2. Latencies: **16,959; 1,804; 1,378; 1,773; 2,142 ms**; median **1,804 ms**, total inference **24,056 ms**. The first call includes a large startup/cache effect; five observations cannot estimate sustained performance. All 15 fixture resolutions completed; this is not a full-game completion rate.

The model picked a different reanimation target in an earlier corrected development repeat despite temperature 0. Request IDs and contexts are saved, but deterministic model-output reproducibility is not established. Preserve alternatives for review. There is no natural-opportunity denominator for missed reanimations, tutor success frequency, avoidable sacrifices or sequencing mistakes; those metrics remain unmeasured rather than zero.

## Seven rounds, twelve rounds and full games

The existing evaluation launchers and CSV/game/decision/role telemetry were not altered. New card choices have their own JSONL sidecar because the legacy CSV and audit schemas do not understand this decision type. Join by run directory/game ID/player and inspect the retained request context; a robust parent-action/sidecar join is still future work. Main-phase routing retains its existing metrics/audit events. Do not assume existing summary scripts count the new subchoices.

`Run-Stage7Cohorts.ps1` denotes implementation Stage 7; it is not a verified seven-round stop rule. The inspected harness scripts did not establish a reusable seven- or twelve-round cutoff. Preserve any established development harness separately and verify its stopping rule before scoring. `TURN-METRIC.md` correctly defines `finalTurnCount` as the ordinal of an individual player turn, including extra turns, not completed rounds. Dividing by starting seats is invalid after extra turns/eliminations.

| Evaluation category | Readiness and interpretation |
|---|---|
| Seven-round development | Hooks can be used, but cutoff adapter, opportunity counters and sidecar aggregation still need a validated integration run. Measure engine access, sequencing and damage, never win rate |
| Twelve-round engine/closing | Not run. Use separate outputs and the same controller/deck hashes; validate round boundaries and stopping censoring |
| Full Commander games | Existing failure-aware runner can retain game outcomes, failures and timeouts. Natural Chainer coverage, multiplayer combat and inference budgets are not validated here. Zero full games were run in this task |

The repository includes Rivaz/Rakdos experiment inputs, but no verified standalone current Chainer physical-deck snapshot was located in the inspected deck-source/experiment inputs. No Rivaz-to-Chainer commander swap or new decklist was invented. This and incomplete autonomous/planning coverage make a natural Chainer win-rate comparison premature.

## Next experiment

First freeze the intended Chainer and Rivaz deck files without card changes and record hashes. Require the deterministic gate above and a correctly measured seven-round cutoff. Then run **two fixed seeds × two decks × two controller configurations = eight short development attempts**, against the same unchanged pod. Give both decks identical native flags, LLM model/endpoint/timeout, enabled decision surfaces and seat treatment; use the second seed with mirrored candidate seating. Keep original hybrid coverage controls separate if retained.

Use one local inference worker, per-request 30-second timeout, an explicit total inference/request budget per attempt, and a process watchdog. If a budget is reached, label the remainder native fallback and report it; do not silently call that an all-LLM comparison. Save deck/engine/model hashes, legal candidates, prompt/response pairs, sidecar choices, actual casts/discards/targets, inference time and engine failures. Report opportunity denominators: creature in graveyard, available permission, sufficient mana, eligible discard/tutor/target, and whether the action was exposed, proposed and executed. Review disagreement and multi-card lines manually.

Only after those eight clean development attempts show actual Chainer engine use should twelve-round closing probes be added in a separate cohort. Full games and actual wins/eliminations need their own failure-aware cohort and sample-size plan. Attribute problems separately to deck availability, insufficient resources, legal restrictions, candidate-generation/native policy, provider validity and strategic LLM choices.

## Reproduction

Run from the harness checkout, with a new output label each time:

```text
python work/chainer-hybrid-20261022/Experiment.py build build-next
python work/chainer-hybrid-20261022/Experiment.py test regression-next build-next
python work/chainer-hybrid-20261022/Experiment.py smoke smoke-next build-next
```

The runner uses the already-installed JDK/TestNG and existing frozen experiment sources; it does not fetch dependencies or alter preferences. Isolated test resource junctions require normal local filesystem permissions. Smoke alone requires local Ollama access. It executes at most five requests; it does not launch unbounded games.

For integration, `forge.ai.externalCardChoices=true` enables the new hooks only on controllers with an existing provider. Optional `forge.ai.externalCardChoiceAudit=<absolute JSONL path>` records projected options, native/returned/final IDs, latency and fallbacks. Optional `forge.ai.externalRequestCapture=<absolute JSONL path>` records exact prompts/configuration/responses without authorization headers. `forge.ai.ollama.noThinking=true` requests reasoning_effort none and the 512-token cap. These are JVM properties, set per run; omit them to retain the old behavior. Native experiment flags remain separate and must match between comparison arms.
