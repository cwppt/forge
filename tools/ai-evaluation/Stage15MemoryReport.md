# Stage 15: short-horizon strategic memory

Stage 15 is implemented and evaluated. It adds bounded history to five existing external decision surfaces. It adds no decision surface, strategy tuning, player profile, cross-game learning, or model-generated memory. Memory remains **disabled by default**.

The completed cohort is `ai-eval/stage15/20261009-151055/`: 20 control games and 20 memory-enabled games. Useful observed history reached later requests, with small local overhead. The cohort does **not** demonstrate stronger play: control won 4/20 games and memory won 2/20.

## Architecture and information safety

`ExternalAiStrategicMemory` keeps independent instances per observing AI and game. The registry uses weak game keys and each instance uses a weak observer reference. Fact entries contain immutable strings, turn numbers and flags; they retain no Card, Game, Player, SpellAbility or GameEvent objects. Bounded internal scalar card identities support invalidation and never appear in the model-visible DTO.

Collection uses the receiving AI controller's existing legal reveal/look callbacks and these native events:

- `GameEventCardChangeZone`: visible graveyard/exile observations and updates to previously known public cards.
- `GameEventSpellAbilityCast`: publicly visible, non-mana spells and activated abilities. Interaction labels come from the actual stack ability's API; no private targets, choices, modes or reasoning are stored.
- `GameEventAttackersDeclared` and `GameEventBlockersDeclared`: objective current-turn declaration counts.
- Turn, outcome, finish, restart and snapshot-restore events: expiry and lifecycle cleanup.

The reveal callback is the authority for information legally shown to its receiving controller. Public observations additionally require a visible, face-up card in a whitelisted public zone. Unknown hands, library identity/order, face-down identity and reveals received only by another controller are excluded. Lands are not accumulated as known-card history. `AiCardMemory` retains its previous behavior.

Known-card statuses distinguish `CURRENTLY_KNOWN`, `PUBLIC_ZONE_CONFIRMED`, and `PREVIOUSLY_REVEALED_LOCATION_UNKNOWN`. Legal library looks retain a previously observed name with unknown location, never its position. Any opponent hand exit invalidates all remembered hand certainty: private engine identity cannot prove which indistinguishable copy remains. Hidden moves invalidate known location without discovering a new identity. Unknown draws do not create known-card facts. Public snapshot refresh confirms location/name against visible state, including face-down transitions.

## Expiry, bounds and immutable view

Current-turn casts, ability/resource uses and combat events expire at turn end or a changed turn. Demonstrated interaction and safe card observations can last through the same game, subject to the fact bound. Game outcome/finish clears facts and stops collection; completion releases the registry entry. Restart and restored snapshots clear facts. Nothing is loaded from saved audits into a later game.

The default storage/request bound is 12 facts; the hard cap is 32, with a minimum of one. Semantic ties are ordered deterministically. Summary selection ranks current-turn facts, direct source/target relevance to supplied options, recency, then category importance. Bounded storage eviction uses observable recency/category information; engine identity is only a final tie breaker between semantically identical entries.

`StrategicMemoryView` contains four immutable lists:

```text
knownOpponentCards
interactionObserved
currentTurnFacts
recentStrategicEvents
```

Each selected fact contains category, SELF/OPPONENT source, turn, optional observed card name, conservative interaction label, optional knowledge status/location and a short authoritative observation. No raw engine identity, hidden location, wall-clock timestamp, model reasoning or inferred intention is included.

`StrategicMemorySnapshot` separately holds audit diagnostics: game/observer correlation, stored/selected counts, categories, expiry/invalidation counts, build time and view length. Only its `view` goes to the provider.

## Requests, fingerprints and configuration

Memory is supported in MAIN_PHASE_ACTION, STACK_RESPONSE, COMBAT_ATTACKERS, COMBAT_BLOCKERS and COMBAT_ACTION. MULLIGAN and COMBAT_DAMAGE_ORDER remain outside its scope. Combat projections filter unrelated known-card facts and counter history from attacker/blocker requests; counter history remains relevant to proactive combat spells.

Enabled requests receive a short neutral history instruction: observations come from this game; current visible state is authoritative; hidden cards and current availability must not be assumed from history. The model cannot write memory. Existing recommendations, blocker annotations, legal preparation, candidate ordering, option IDs, routing gates and fallback rules are unchanged.

The fingerprint wraps the original fingerprint and the exact canonical, model-visible summary in SHA-256. Fixed DTO fields, deterministic fact ordering and null fields are covered; audit timing/identities are excluded. Different relevant history changes the fingerprint. Option IDs and prepared mappings are finalized before memory is attached. Immutable request identity validation still precedes live revalidation, which includes the live memory summary where applicable. Disabled requests match five saved pre-Stage-15 request bodies exactly, including presentation order and structured enums.

| Setting | Default |
| --- | --- |
| `AI_EXTERNAL_MEMORY_ENABLED` | `false` |
| `AI_EXTERNAL_MEMORY_MAX_FACTS` | `12`, hard cap `32` |
| `--external-ai-memory-enabled` | Explicit opt-in |
| `--external-ai-memory-disabled` | Explicit control-mode override |
| `--external-ai-memory-max-facts N` | Configured bounded maximum |

The Stage 14 full-game preset does not automatically enable memory. Existing provider configuration is reused. All 321 live captures retained local Qwen's `reasoning_effort: none` and the existing output cap/schema.

## Audit and evaluation

The five detailed decision audits and full-game references record effective `memoryEnabled` and the immutable memory snapshot, including the exact supplied summary. Game summaries add `factsRecorded`, `factsExpired`, `factsInvalidated`, `factsEvicted`, `maxActiveFacts` and `requestsContainingMemory`. Fact insertions are counted separately from updates; expiry includes game-end disposal. The memory-mode cohort recorded 871 fact insertions, 868 expirations, three evictions and 15 certainty invalidations; maximum active storage was 12. There were 119 requests with nonempty memory.

`Run-Stage15Memory.ps1` is compatible with Windows PowerShell 5.1. It starts a hidden Python process with redirected stdout/stderr; the helper redirects Java output directly into files. It snapshots compiled classes/dependencies once, runs provider calls serially, alternates control/memory order, and verifies the runtime and earlier evidence hashes afterward. Only the memory toggle differs between modes. All seven Stage 14 surfaces are enabled for one external seat; integrated mode and experimental material guards remain disabled, and legacy damage ordering is retained.

Verified existing matchups are Aurochs Stampede vs Selesnya Surge, and Gatecrasher vs Air Forces. The first contains Balduvian Rage, Resize, Savage Surge and protection/interaction; the second contains Incinerate, activated damage and counters including Dissipate, Memory Lapse, Disrupt and Power Sink. Seeds 1501-1505 run in both external seats: 20 matched pairs, 40 games.

| Completed measurement | Control | Memory |
| --- | ---: | ---: |
| Games | 20 | 20 |
| External wins | 4 | 2 |
| Provider calls | 160 | 161 |
| Fallbacks | 3 | 1 |
| Prepared-action revalidation failures | 1 | 1 |
| Duplicate requests | 0 | 0 |
| Average provider latency | 2,718.81 ms | 2,758.98 ms |
| Average prompt tokens, whole cohort | 2,226.56 | 2,497.52 |

Control fallbacks were two existing blocker safety rejections and one Uktabi Orangutan main-phase revalidation failure. Memory's fallback was Bountiful Harvest main-phase revalidation. Both main-phase failures passed request identity and current fingerprint checks before the existing action guard rejected execution. There were **no stale-memory fingerprint mismatches, transport failures, malformed responses or identity failures**. All 321 captures passed audit joins, enum/fingerprint checks, thinking-disabled verification and applicable exact-view/bound checks.

Across memory's **136 supported requests**, 17 summaries were empty, 92 supplied plausibly relevant interaction/known-card/resource context, and 27 contained only declarations or other known-card context. These are context-availability classifications, not proof that history caused a choice. Nonempty-memory requests contained 40 accepted Forge/external disagreements, including 35 with plausibly relevant facts.

- Average selected facts per supported request: **3.15**; maximum **8**.
- Average added complete request size: **1,137 characters**; maximum **2,396**, including the history instruction and escaped DTO.
- Average measured summary build time: **0.61 ms**; maximum **4.76 ms**, excluding provider calls and final HTTP encoding.
- Provider-latency difference: **+40.18 ms / +1.48%** descriptively, not an isolated causal estimate.
- 57 matched groups had identical model-visible current state and options, excluding history/request identity. Eight had different recorded final option IDs. Their first control/memory pair averaged **246 additional prompt tokens**, median **254**; ordinary identifier tokenization also contributes. Fallback flags remain attached to comparisons. No hypothetical outcomes are generated.

Concrete observed examples in the memory JSONL/captures:

- Aurochs 1501, external seat 1, turn 14 COMBAT_ATTACKERS received the opponent's Balduvian Rage cast from turn 13 (`94f66123-e744-495f-b5d5-dc2870df6b4b`). It did not assert another copy was held.
- Gatecrasher 1504, external seat 0, turn 8 MAIN_PHASE_ACTION received the opponent's Dissipate cast from turn 6 (`f0ad9074-f36f-4ff2-b502-814f436098f0`).
- Gatecrasher 1504, external seat 1, turn 16 COMBAT_ACTION received the current-turn Fire Whip and Granger Guildmage activations (`ad9922a8-637c-4960-9a14-ae7abdb1700c`).

The interrupted initial attempt `20261009-150504` is preserved and excluded. It exposed audit null-field omission; exact-summary serialization was corrected and regression-tested before the completed cohort. Final analysis and effect comparisons are in `analysis.json` and `effect-analysis.json` under `20261009-151055`.

## Verification and changed files

**343 focused Java tests passed**, zero failures/errors/skips, including **30 Stage 15 cases**. All eight mandatory information-safety requirements pass: unknown hand, library identity/order, face-down identity, observer-visible reveal, another observer's private reveal, public graveyard/exile, hidden-transition invalidation and game reset. Additional coverage includes indistinguishable copies, unknown draws, resource/turn expiry, end-of-game disposal, multiplayer exclusion, immutable views, deterministic bounds/fingerprints, exact disabled wire compatibility, preserved nonempty prepared options/opaque mappings, known counter/trick fixtures, PASS execution, one-call routing and fallback/audit behavior.

Existing mulligan, main-phase, stack, attacker, blocker, damage-order, combat-action, Stage 12 integration, Stage 14 projection/audit/configuration, provider transport and native attack/block regressions pass. **14 Python tests passed**: nine existing Stage 14 evaluator tests and five new memory-analysis tests. The Windows PowerShell **5.1.26100.9444** parser reported zero errors. `git diff --check` passed; new Stage 15 files were also checked for trailing whitespace.

All **704** previously captured Stage 14/14.2 files remain byte-for-byte unchanged. Of 2,073 snapshotted existing Java sources, only the intended 18 request/configuration/audit files changed. `AiController`, `ComputerUtil`, `AiCardMemory`, `BlockerTacticalProjection` and the existing strategic guard/decision policies remain unchanged from the Stage 15 baseline.

Changed files, relative to the repository root:

- New Java: `forge-ai/src/main/java/forge/ai/ExternalAiStrategicMemory.java`; `forge-ai/src/main/java/forge/ai/decision/StrategicMemoryView.java` and `StrategicMemorySnapshot.java`; `forge-gui-desktop/src/test/java/forge/ai/ExternalAiStrategicMemoryTest.java`.
- Request wiring under `forge-ai/src/main/java/forge/ai/`: `MainPhaseStateProjector.java`, `ExternalCombatAttackers.java`, `ExternalCombatBlockers.java`, `ExternalCombatActions.java`, `PlayerControllerAi.java`, `LobbyPlayerAi.java`.
- Under its `decision/` directory: `AiDecisionContext.java`, `MainPhaseDecisionContext.java`, `StackResponseDecisionContext.java`, `CombatAttackersDecisionContext.java`, `CombatBlockersDecisionContext.java`, `CombatActionDecisionContext.java`, `AiDecisionMetrics.java`, `FullGameExternalAiAudit.java`, and `external/OpenAiCompatibleDecisionProvider.java`.
- Configuration: `forge-gui/src/main/java/forge/player/GamePlayerUtil.java`, `forge-gui/src/main/java/forge/localinstance/properties/ForgePreferences.java`, `forge-gui-desktop/src/main/java/forge/view/SimulateMatch.java`.
- Under `tools/ai-evaluation/`: `Run-Stage15Memory.ps1`, `evaluate_stage15.py`, `test_stage15_memory_evaluation.py`, `fixtures/stage15-disabled-wire.json`, and this report.

From the repository root, with current focused-build classes and local Ollama serving qwen3:8b:

```powershell
.\tools\ai-evaluation\Run-Stage15Memory.ps1 -Model qwen3:8b -Seeds 1501,1502,1503,1504,1505 -MaxFacts 12
```

Re-analyze saved evidence without model calls:

```powershell
C:\Python312\python.exe tools/ai-evaluation/evaluate_stage15.py --analyze ai-eval/stage15/20261009-151055
```

## Limitations

Interaction labels use conservative Forge APIs, not broad semantic card analysis. Resource facts describe observed uses at card-name level, not availability of every identical copy/current incarnation. Exact mana expenditures, private priority intentions and speculative hand contents are not inferred. Some stored public-card/declaration context duplicates current state or is weakly informative; the analysis identifies those requests separately. Uncertain hand transitions deliberately lose useful certainty. The small cohort establishes context delivery and bounded cost, not better choices or win rates. No Commander evaluation, multiplayer politics or persistent learning was added.

C. MEMORY PROVIDES USEFUL STRATEGIC CONTEXT
