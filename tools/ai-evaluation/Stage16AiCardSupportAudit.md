# Stage 16: Forge AI card support audit

This is a discovery audit. No production Java, card scripts, decision surfaces, heuristics, warning settings or existing evaluation outputs were changed. A warning is evidence of metadata, not proof that a card's rules are broken or that an LLM can recover it.

## Main finding

`AI:RemoveDeck:All` produces the poor-AI deck warning **and excludes ordinary non-counter priority actions before the existing external surfaces receive candidates**. Choosing better options cannot recover an option that never reaches preparation. Exceptions matter: counter responses, land/mana planning, mandatory triggers, permanent combat and full simulation have different paths. In particular, the scripted Swan Song probe demonstrates a warned card already executing through STACK_RESPONSE, including its token consequence.

Earthbrawn's ordinary pump can already be prepared, paid and resolved in a controlled combat fixture, but the card-wide marker prevents external routing. Reinforce is a separate, unverified ability. This is the most concrete new recovery opportunity. Dizzy Spell's comment suggests a similar broad marker, but its probe produced no prepared debuff; it is not given Earthbrawn's rating.

## Reproduction and scope

Run from the repository root:

```powershell
C:\Python312\python.exe tools/ai-evaluation/audit_stage16.py
C:\Python312\python.exe tools/ai-evaluation/test_audit_stage16.py
mvn -o -pl forge-gui-desktop -am "-Dtest=Stage16CardSupportAuditTest,PreparedAiActionTest,StackResponseExternalDecisionTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
git diff --check
```

The inventory scans all 33,980 `forge-gui/res/cardsfolder/**/*.txt` files. Its unit is a script record identified by the first `Name`, with additional face names preserved. It covers explicit RemoveDeck markers, AILogic/UnlessAI Never, AICheckSVar, unsupported default variants, and explicitly unsupported/unmapped script APIs. It does not pretend to identify every strategic weakness of every card. Keyword-generated and implicit permanent abilities are not fully expanded by the static parser; unresolved cases retain that limitation explicitly.

No edition-specific support differences were demonstrated: scripts are shared across printings. Deck impact counts retain printing/section entry distinctions. Names absent from the database can produce unsupported placeholders dynamically; an unbounded universe of nonexistent card names cannot be enumerated as shipped card scripts.

Generated artifacts:

- `stage16/ForgeAiCardSupportAudit.csv`: one primary category, score and evidence reason per affected script record; exact markers/lines, source hash, API/AI mapping, refusal and preparation scope.
- `stage16/ForgeAiCardSupportAbilities.csv`: exact source lines for every extracted ability and nested/variant definition belonging to those records. The card-level primary category is inherited; this does **not** claim every ability on a card has the same defect.
- `stage16/ForgeAiSupportCodeEvidence.csv`: conditional refusals, named exceptions, metadata filters and TODO/unsupported evidence in AI Java. Context must be inspected; a return-false or TODO alone is not a missing mechanic.
- `stage16/ForgeAiDeckSupportAudit.csv`: bounded shipped quest-precon impact population.
- `stage16/summary.json`: authoritative regenerated counts and overlapping mechanic clusters.

## Warning sources and exact triggers

| Source | Trigger and meaning |
| --- | --- |
| `forge-gui/res/languages/en-US.properties:2083`, `lblAICantPlayCards` | Display text: **AI can't play these cards well**. The property has a doubled apostrophe for MessageFormat; translations use the same key, not separate support mechanisms. |
| `forge-core/src/main/java/forge/card/CardRules.java`, Reader RemoveDeck parser | Case-insensitive values `All`, `Random`, `NonCommander`; cumulative booleans. Unknown values do not establish a support flag. Card/face metadata feeds CardAiHints. |
| `forge-core/src/main/java/forge/deck/Deck.java:717`, `getUnplayableAICards` | Collects PaperCard entries with `getRemAIDecks()`. Cached per section; `inMainDeck` counts entries, not copies. Does not remove them. |
| `forge-ai/src/main/java/forge/ai/AiController.java:2859`, `complainCardsCantPlayWell` | Returns the deck list unless full simulation is enabled. `PlayerControllerAi:1627` delegates after auto-profile setup; base PlayerController returns no complaints. This is a deck/startup warning, not runtime classification. |
| `forge-game/src/main/java/forge/game/Match.java:326–356` | Collects complaints on first game, after zones have been prepared. Reveals only when complaints exist, format is not card-pool-limited, and `GameRules.warnAboutAICards()` is true (default true). |
| `forge-game/src/main/java/forge/game/GameAction.java`, `revealUnplayableByAI` | Broadcasts to controllers. `forge-gui/src/main/java/forge/player/PlayerControllerHuman.java:2569` presents section/card lists; mobile skips sideboard display. No deck rejection here. |
| `forge-core/src/main/java/forge/card/CardRules.java:882`, `getUnsupportedCardNamed` | **This card is not supported by Forge. Whenever you start a game with this card, it will be bugged.** Synthetic placeholder with `unsupported=true`; distinct rules/data failure. `CardDb.getRulesOrElseUnsupported` and `createUnsupportedCard` construct it. |
| `forge-core/src/main/java/forge/deck/Deck.java:134`, `getValid` | Removes entries whose rules report `isUnsupported()`, returns removed list. Match separately calls `revealUnsupported`; Human controller labels this **Removed**. This is actual removal, unlike the poor-AI warning. |
| English properties `lblWarnUnknownCardMsg`, `lblGuideNoImportCard` | **Unknown Card or Unsupported in Forge**, **Invalid or Unsupported Card that cannot be imported.** Import/database validity, not AI strategy. `lblErrUnsupportedCard=Not allowed in {0}` is format legality, not an AI support diagnosis. Inventory removal labels are likewise distinct. |
| `forge-ai/src/main/java/forge/ai/SpellApiToAi.java:230` | Runtime stderr **No AI assigned for API: ...**, then fallback to CannotPlayAi. `get(SpellAbility)` rejects non-API abilities with **SA is not api-based, this is not supported yet**. Ability-level evaluator diagnostics, not deck warnings. |
| `AiController.java:833` | **Warning: unsupported mana reservation phase specified for reserveManaSources: ...** is AI configuration/phase metadata, not proof the card rules are unsupported. |

No second independent generic “AI cannot play card well” warning source was found. Unsupported transport/provider messages, invalid deck URLs and format errors have different meanings and are not counted as poor-AI cards.

## Support-marker taxonomy

| Mechanism | Verified consequence | Audit interpretation |
| --- | --- | --- |
| RemoveDeck All | Warning; `CardRulesPredicates.IS_KEPT_IN_AI_DECKS` excludes generated AI decks; ordinary AiController priority list removes host cards at lines 1628–1632. | Broad AI refusal, not missing rules. Full simulation exits earlier at line 1422. `PlayBeforeLandDrop` and land handling are also earlier paths; do not assume a global engine ban. |
| RemoveDeck Random | Random-deck filtering; `CardAiHints.getRemRandomDecks`; UI question-mark status. | No poor-AI startup warning by itself. Often deck-construction/synergy metadata; H absent a specific demonstrated defect. |
| RemoveDeck NonCommander | `IS_KEPT_IN_AI_LIMITED_DECKS` excludes it alongside All. | Context-specific deck construction; no generic poor-AI warning by itself. |
| DeckHints, DeckNeeds, DeckHas | Parsed deck synergy requirements; CardRanker, themed builders/generators use metadata. | Constraints and preferences, not evidence of missing rules/preparation. Their presence alone does not add an affected card. |
| CardAiHints/UI status | `getAiStatusComparable` gives All+Random=3, All=4, Random=2, otherwise=1. `ColumnDef` renders X?, X, ?, or blank. | Status indicators, not separate diagnosis. NonCommander is not represented by that comparator. |
| API-to-AI mappings | Explicit CannotPlayAi mappings or absent mapping reject ordinary API evaluation/drawback; mandatory trigger handling must be inspected independently. | Ability-local gap. An effect class/mapping existing does not prove reliable resolution. |
| AILogic Never / UnlessAI Never | `SpellAbilityAi.checkAiLogic:159` refuses action; `willPayUnlessCost:409` refuses that payment. | Scoped refusal. UnlessAI is a resolution payment rule, not necessarily a ban on casting the card. |
| AICheckSVar; AIMaxAmount, AITgts; SVar AI preferences | Conditional activation/target/trigger preferences; `AiController` checks AICheckSVar at trigger and ordinary evaluation seams and AIMaxAmount at line 2772; CopySpellAbilityAi checks AITgts. | Contextual gates, not automatic unsupportedness; AICheckSVar records remain H unless other evidence dominates. |
| SpellAbility.isSkip | Removed from the ordinary priority list after metadata filtering. | Runtime candidate skip flag; not a static registry of broken cards. |
| API evaluator CantPlayAi/CantPlaySa and callback return-false | Per-class timing, target, cost, strategic and mechanic branches. | Exact context is essential: many are sensible strategic refusals. The evidence index preserves source/line for review. |
| Named exceptions / TODOs / unsupported comments | AI classes and SpecialAiLogic specialize individual cards; e.g. PhasesAi says it needs fleshing out. | No central exhaustive bad-card registry found. Named handling can improve support; comments cannot certify failure or recovery. |
| Secondary metadata filters | `CopySpellAbilityAi:76`, CopyPermanentAi, CloneAi, ControlGainAi, ControlExchangeAi, ChangeZoneAi; `CopyPermanentEffect:175` and `PlayEffect:145` restrict AI candidates. | Can block copying, stealing, tutoring or playing marked cards even if ordinary generation were extended. Game effects contain AI-specific filtering, not a blanket human rules failure. |
| Sideboard/generation/avatar filters | PlayerControllerAi sideboard, CardRanker, themed/limited construction and Vanguard lobby filters consult metadata. | Deck usability changes can occur before play; distinguish generated decks from user-loaded decks. |
| Unsupported default variant / unsupported placeholder | Everythingamajig, Garbage Elemental, Sly Spy advertise Unsupported Variant for default script; implemented variant definitions are separate. Placeholder `isUnsupported` removes nonexistent/unimplemented entries. | F only for evidenced scope. Default variant text does not imply every variant is broken. |

The explicit CannotPlayAi API mappings are ControlPlayer, ControlSpell, ExchangeControlVariant, GainOwnership, GameDrawn, ManaReflected, MultiplePiles, PlayLandVariant, RemoveCounterAll and InternalIgnoreEffect. **ManaReflected is not treated as a missing mana mechanic:** `ComputerUtilMana:990` handles reflected color payment independently (also lines 444/1681). InternalIgnoreEffect is mechanical. Both are excluded from automatic API-gap card classification, while remaining visible in the mapping taxonomy.

## Inventory and classification

At this audit revision: **4,842 affected script records; 2,500 warning-eligible records**. There are 2,501 All marker lines because markers can repeat within a script, 2,383 Random lines and 70 NonCommander lines; these populations overlap. Other scoped markers add 27 lines. Do not add marker counts to obtain a card count.

| Primary category | Records | Evidence standard |
| --- | ---: | --- |
| A STRATEGY_ONLY | 0 | No sample proved that strategic weakness alone explains its warning. |
| B CANDIDATE_SELECTION | 1 | Earthbrawn plain pump: direct preparation and resolution proven; ordinary enumeration blocks it. |
| C PREPARATION_GAP | 7 | Reviewed target/cost/nested-choice cases; reasoning and fixture scope retained. |
| D HEURISTIC_REFUSAL | 2,472 | Explicit All/Never refusal is primary observable mechanism. Rules/preparation remain unverified unless specifically tested; D does not certify every script works. |
| E MECHANICAL_AI_GAP | 164 | Script API has explicit CannotPlayAi/unmapped evaluator, excluding the native mana/internal exceptions. Other abilities on those cards may still work. |
| F RULES_OR_SCRIPT_GAP | 3 | Explicit unsupported **default** variants. |
| G WARNING_ONLY / STALE_METADATA | 1 | Swan Song's warning is broader than demonstrated stack support; not proof its strategic play is optimal. |
| H UNKNOWN | 2,194 | Random/NonCommander/conditional metadata without a demonstrated runtime defect. |

Recoverability: **HIGH 1; MEDIUM 1; LOW 4,670; NONE 170**. LOW on unstudied records is explicitly provisional: it means no evidence of safe exposure, not an estimate that every one needs major engineering. NONE applies to current external architecture/affected mechanic, not necessarily the card's basic cast or the native engine. Among the 2,500 warning-eligible records: D 2,456; E 34; C 7; B 1; F 1; G 1. Recoverability: HIGH 1, MEDIUM 1, LOW 2,460, NONE 38. These are also included separately in summary.json.

## False-negative / broad-warning candidates

1. **Swan Song — HIGH, G, STACK_RESPONSE, already exposed.** `getPlayableCounters:448` enumerates Counter API before ordinary All filtering. Stack candidates rebuild that counter list at line 1650. `prepareStackResponseActions:2176` copies the game, targets the visible top spell, revalidates costs/targets and reverse-maps choices. Scripted provider selects the exact opaque option; request identity and live validation accept it; payment, countering Divination and opponent Bird creation succeed. Own source and visible stack suffice; opponent Black Lotus hand sentinel is absent from captured context. No modes, X or optional costs exist on this action; deterministic Token subability survives. Do not extrapolate to every counterspell or unsupported trigger window.
2. **Earthbrawn ordinary Pump — MEDIUM, B, COMBAT_ACTION, not exposed.** Provider remains uncalled with combat actions enabled. Direct copied preparation returns a targeted legal action; live revalidation, payment and resolution turn a 2/2 blocker into 5/5. No X/modes/optional cost is involved on this ordinary spell. Reinforce is untested and must remain excluded from any future ability-scoped recovery. Missing normal enumeration prevents HIGH.
3. **Dizzy Spell — LOW, D, unproven.** Its source comment says ordinary use works and transmute does not, but the controlled direct combat probe supplies no prepared action. This is a hypothesis for further diagnosis, not a verified false negative. The static comment does not override runtime evidence.

No blanket marker removal is justified. One safe ability does not certify a card's other abilities, costs, triggers or resolution callbacks.

## Representative traces (18 cards)

Every All trace reaches `CardRules -> CardAiHints -> Deck.getUnplayableAICards -> AiController.complain -> Match -> reveal` for the warning. “Ordinary exclusion” below means AiController:1628, before main/combat preparation; execution is `PlayerControllerAi.playChosenSpellAbility` / `ComputerUtil.playStack` with AiCostDecision, followed by API effect resolution. Static traces are not runtime success claims.

| Card/script (`forge-gui/res/cardsfolder/`) | API / AI and path | Primary finding |
| --- | --- | --- |
| Swan Song `s/swan_song.txt` | Counter / CounterAi -> dedicated counter enumeration -> copied stack preparation -> scripted external selection -> payment -> CounterEffect -> TokenEffect. | G/HIGH; exact runtime success demonstrated. |
| Earthbrawn `e/earthbrawn.txt` | Pump / PumpAi, Reinforce keyword adds another ability -> ordinary exclusion -> direct combat preparation -> target blocker -> payment/PumpEffect. | B/MEDIUM for ordinary pump; resolution tested, Reinforce unverified. |
| Dizzy Spell `d/dizzy_spell.txt` | Pump / PumpAi plus Transmute keyword -> ordinary exclusion -> direct combat preparation yields zero. | D/LOW; broad marker/comment, no exact action proof. |
| Stalking Yeti `s/stalking_yeti.txt` | Implicit PermanentCreature / PermanentCreatureAi; triggered DealDamage / DealDamageAi -> reciprocal damage subability; activated ChangeZone / ChangeZoneAi with snow cost. | C/LOW; empty opposing-board cast preparation refuses; ordinary route also excludes. ETB and return not executed in probe. |
| Sylvan Library `s/sylvan_library.txt` | ChooseCard / ChooseCardAi -> RepeatEach / RepeatEachAi -> ChangeZone / ChangeZoneAi -> per-card UnlessCost PayLife. | C/NONE for current selection surfaces; private drawn-card resolution callbacks cannot be selected merely by casting the enchantment. |
| Strionic Resonator `s/strionic_resonator.txt` | CopySpellAbility / CopySpellAbilityAi -> own triggered stack target -> optional new targets. Ordinary exclusion; own-trigger priority is not owned by external STACK_RESPONSE. | C/NONE; retargeting and routing blockers both remain. |
| Fire Covenant `f/fire_covenant.txt` | DealDamage / DealDamageAi -> variable life payment, any-number targets, divided allocation. Ordinary exclusion; AiCostDecision/DealDamageEffect execute downstream. | C/LOW; fixed legal targets alone do not freeze X, life payment and allocation. |
| Firestorm `f/firestorm.txt` | DealDamage / DealDamageAi -> Discard<X> cost tied to targeted-object count. Ordinary exclusion; discard payment remains downstream. | C/LOW; coupled discard/X/target snapshot missing proof. |
| Blazing Shoal `b/blazing_shoal.txt` | Pump / PumpAi -> alternative-cost expansion via ComputerUtilAbility -> announce X/exile red card cmcEQX. Ordinary exclusion. | C/LOW; alternative payment needs exact immutable choice verification. |
| Artillerize `a/artillerize.txt` | DealDamage / DealDamageAi -> sacrifice artifact/creature in AiCostDecision before stack resolution. Ordinary exclusion. | C/LOW; exact sacrifice choice must be supplied by Forge, not model text. |
| Cabal Coffers `c/cabal_coffers.txt` | Mana / ManaAi; Count Swamp X -> native ComputerUtilMana and payment planning, not external action candidates. Land-drop path precedes ordinary filter. | D/NONE for external action recovery; warning does not prove land/mana unusable. |
| Mindslaver `m/mindslaver.txt` | ControlPlayer -> explicit CannotPlayAi -> cannot prepare ordinary activated action; ordinary All exclusion also applies. ControlPlayer effect entails another player's turn/private choices. | E/NONE; selecting one activation cannot supply a controlled turn's callbacks safely. |
| Everythingamajig `e/everythingamajig.txt` | Default unsupported Oracle; variant C includes FlipCoin/Mana/Discard/Animate and other variants define different APIs. Warning All. | F/NONE default only; not an assertion implemented variants fail. |
| Survival of the Fittest `s/survival_of_the_fittest.txt` | ChangeZone / ChangeZoneAi with AILogic SurvivalOfTheFittest and AIPreference DiscardCost; native library/hand choice. Random marker only; no poor-AI warning. | H/LOW provisional; tutor + discard proof absent, despite specialized handling. |
| Orcish Lumberjack `o/orcish_lumberjack.txt` | Mana / ManaAi -> Forest sacrifice + combo colors -> native mana/cost planner. Creature cast normally hits All exclusion. | D/LOW provisional; native mana and ordinary creature cast differ; no recovery runtime proof. |
| Tinder Wall `t/tinder_wall.txt` | Mana / ManaAi sacrifice native path; DealDamage / DealDamageAi targets Creature.blockedBySourceLKI and sacrifices source. Ordinary action exclusion. | D/LOW provisional; useful combat interaction requires LKI target and sacrifice preparation proof. |
| Vodalian Illusionist `v/vodalian_illusionist.txt` | Phases / PhasesAi -> phasesPrefTargeting and threat checks; class says support needs fleshing out. Ordinary exclusion. | D/LOW provisional; current combat-relevance whitelist also needs specific phasing review; cannot assume supported by pump/removal coverage. |
| Dead Ringers `d/dead_ringers.txt` | Destroy / DestroyAi -> two nonblack targets with same-color relational condition -> ordinary exclusion -> DestroyEffect if cast. | D/LOW provisional; pairwise target condition must survive copied preparation/live validation, not just two individually legal targets. |

## Hard blockers and safe architecture boundaries

- **No candidate:** All filter is upstream of current main/combat option sets. No provider selection can repair that. Counter rebuilding is an existing exception, not permission to bypass the filter for everything.
- **No prepared object/mapping:** AiController:2249+ copies/reverse-maps candidates; uncopiable abilities are skipped. Unsupported clone/alternate/nested choices cannot become valid through string construction.
- **Strategic refusal versus missing choice:** `prepareStrategicallyRejectedAction:2419` admits only named strategic rejection reasons and then revalidates. CantPlayAi/CantPlaySa are not blanket accepted; Dizzy Spell's empty set illustrates why a simple-looking spell is not enough evidence.
- **Cost callbacks are still native:** `ComputerUtil.playStack:226` checks payability then `CostPayment.payComputerCosts(new AiCostDecision(...))`. Sacrifice/discard/variable life selection is not automatically frozen because a prepared DTO names a target. Treat coupled costs as unverified until explicit choice-copy/payment tests exist.
- **Resolution callbacks:** Sylvan Library, copy retargeting, tutors and player-control mechanics require downstream native choices. Existing request identity and target validation do not validate hypothetical arbitrary future subchoices.
- **Ownership/window constraints:** `shouldRouteExternalStackResponse:1698` requires opposing visible nontrigger/nonreplacement/nonmana top item; excludes combat begin/damage/end. Own triggers, mandatory machinery and mana belong to native paths. COMBAT_ACTION primarily owns empty-stack combat; it must not double-call stack routing.
- **Privacy:** Public targets and the deciding player's own candidate sources are safe projections. Unknown opponent hand/library decisions must stay out of requests; the Swan Song probe checks a hidden hand sentinel. Draw/library private-choice recovery cannot be inferred from a public cast action.
- **Validation remains authoritative:** request snapshot identity precedes live source/ability/timing/payability/target checks; no candidate regeneration or validation relaxation is recommended. The existing regression suite checks failures/fallbacks alongside these audit probes.

## Deck impact

Scope: all **505 shipped quest precons** under `forge-gui/res/quest/precons`, excluding duplicate adventure sets and unrelated constructed collections. **321** contain warning-eligible cards. Means across all 505: **2.15 distinct warned names** and **2.79 warned copies** per deck. **166** have at least four warned copies. Four copies is an exposure threshold only, not measured “severe AI” or win-rate loss; a mana card's warning differs materially from an uncastable spell.

| Existing evaluation deck | Warned names / copies | Names |
| --- | --- | --- |
| Aurochs Stampede | 6 / 8 | Bounty of the Hunt, Giant Trap Door Spider, Orcish Lumberjack, Rimehorn Aurochs, Stalking Yeti, Tinder Wall |
| Air Forces | 3 / 7 | Debt of Loyalty, Ophidian, Vodalian Illusionist |
| Gatecrasher | 2 / 3 | Dragon Mask, Maraxus of Keld |

User-loaded warned cards remain in decks and zones. Ordinary exclusion can leave spells/actions unused; native land/mana/trigger exceptions mean warning counts cannot be interpreted as dead-card counts. Generated AI decks and tutor/copy/control candidate sets may filter them out instead. Only genuine unsupported placeholders are removed by Deck.getValid. Poor-AI warnings alone do not reject a whole deck. No new match run or playing-strength conclusion was needed for this discovery audit.

## Reusable mechanic clusters

Counts overlap and identify script syntax among affected cards, **not the number proved broken**. The second number is warning-eligible members. Full machine-readable counts remain in summary.json.

| Cluster | Affected / warned | Limitation to investigate; likely seam |
| --- | ---: | --- |
| Modal/choice syntax | 298 / 201 | Exact mode and nested choices may differ; copied prepared MAIN_PHASE_ACTION/STACK_RESPONSE only after choice proof. LOW pending per-ability study. |
| X/cost coupling | 183 / 147 | X, costs and target counts must agree; action preparation and payment-copy seam. LOW. |
| Sacrifice/discard costs | 764 / 502 | Payability does not prove exact payment choice; AiCostDecision/SpellAbilityChoiceCopier. LOW; staged explicit cost-choice proof needed. |
| Copy/clone | 66 / 34 | Copy targets and subsequent retargeting; native copy callbacks and prepared stack ownership. LOW/NONE for unresolved callbacks. |
| Tutor/library selection | 222 / 83 | Search/discard/resolution choices, hidden/private information boundaries; native ChangeZone callbacks. LOW/NONE for current external selection alone. |
| Name/type/color choice | 126 / 95 | Not inferred from action ID; native choice callbacks remain authoritative. LOW/NONE until deterministically prepared. |
| Graveyard selection | 326 / 170 | Exact zone/incarnation/selection and costs; copied action mapping plus resolution callback. LOW. |
| Alternate costs | 37 / 28 | Announced X/payment card must survive choice-copy/revalidation; ComputerUtilAbility alternate expansion. LOW. |
| Damage distribution | 19 / 15 | Allocations must remain complete and legal; target/allocation snapshot, payment and DealDamageEffect. LOW. |
| Replacement definitions | 243 / 90 | Mechanical and resolution choices, not a new external surface. NONE without appropriate existing native callback proof. |
| Blink/reanimation/change-zone | 941 / 487 | Broad ChangeZone cluster includes tutors, so not all are blink; origin/destination/live mapping and nested choices. LOW. |
| Nested repeat choices | 104 / 69 | RepeatEach/resolution decisions outlive cast snapshot; existing native callbacks. NONE for unprepared nested choices. |
| Control gain (including temporary control) | 76 / 44 | Exact gain-control target/duration and metadata target pruning; ControlGainAi and copied action mapping. LOW, unverified. |
| Pump abilities | 544 / 358 | Broad card flags can exclude a simple pump alongside harder abilities; COMBAT_ACTION preparation. MEDIUM only for the proved Earthbrawn spell, others unverified. |

The reusable opportunity demonstrated here is **ability-scoped admission of already-safe ordinary actions on broadly marked cards**, not LLM understanding of arbitrary mechanics. Only Earthbrawn proves that opportunity so far; counts above must not be represented as its guaranteed recovery population.

## Ranked recovery shortlist

This is a restrained shortlist, not fifteen unsupported promises. Complexity concerns a future proof/admission change, not estimated rules correctness. Existing surfaces only.

| Rank | Card / ability | Category / score | Surface; complexity | Reusable work and value |
| --- | --- | --- | --- | --- |
| 1 | Swan Song ordinary counter | G / HIGH | STACK_RESPONSE; S | Already recovered by existing route; preserve exact target and deterministic token-consequence regression. No recovery implementation required. |
| 2 | Earthbrawn ordinary +3/+3 spell | B / MEDIUM | COMBAT_ACTION; M | Capability-scoped admission of fixed-effect prepared spells while leaving Reinforce excluded. Concrete combat value and strongest new runtime evidence. |
| 3 | Dizzy Spell ordinary debuff | D / LOW | COMBAT_ACTION if prepared; M | Diagnose why target/preparation is empty before considering ability-scoped admission; transmute remains outside proof. |
| 4 | Stifle activated-ability counter | D / LOW (unprobed) | STACK_RESPONSE for opposing nontrigger abilities; S–M | Existing Counter enumeration may already expose it; activated-target/resolve/privacy fixtures required. Trigger top items remain outside stack ownership. |
| 5 | About Face | D / LOW (unprobed) | COMBAT_ACTION if prepared; M | Fixed single-target stat interaction; prove target/effect relevance and resolution before admission. |
| 6 | Dead Ringers | D / LOW (unprobed) | MAIN_PHASE_ACTION; M | Pairwise target-condition preparation/live validation reusable beyond this removal spell. |
| 7 | Tinder Wall combat damage ability | D / LOW (unprobed) | COMBAT_ACTION; L | Source-sacrifice cost plus blockedBySourceLKI target identity; useful but more demanding than fixed pump. |

No Mindslaver/Sylvan Library/Strionic Resonator recovery is recommended through current action-selection surfaces. Do not delete metadata wholesale, alter heuristics to force disagreement, expose raw targets/modes/X to the model, or change legality.

## Validation

Focused Java probes cover warning-vs-random distinction, entry/copy accounting and retained decks, unsupported placeholder distinction, Stalking Yeti refusal, two different warned-pump preparation outcomes, an ordinary Shock control, and fully routed/paid/resolved Swan Song. PreparedAiActionTest and StackResponseExternalDecisionTest run alongside them. Python probes cover marker combination, multiple faces, variant/nested APIs, conservative classification, scoped Never payments and reviewed exceptions. No Ollama or full Maven reactor run is needed.

Final result: **37 Java tests passed** (8 new support probes, 17 prepared-action regressions, 12 stack-response regressions), **8 Python tests passed**, with zero failures/errors/skips. Inventory regeneration is byte-identical. Hash verification preserves **2,119 production Java files, 33,980 card scripts and 3,017 existing ai-eval files**, with no changed/missing baseline file. `git diff --check` exits 0; Git reports existing LF/CRLF notices, not whitespace errors. These results are recorded in `stage16/validation.json`. Initial optimistic fixture assumptions were corrected to the observed behavior; no production behavior was changed to make tests pass.

## Final classification and one next target

**B. RECOVERY IS POSSIBLE BUT MOST CASES NEED PREPARATION WORK**

This means proof and/or preparation/admission work is needed before broad recovery; it is not a claim all 2,500 warned cards have mechanically broken preparation. Exactly one next implementation target: **ability-scoped COMBAT_ACTION admission for Earthbrawn's already-prepared ordinary pump, preserving exclusion of its unverified Reinforce ability and all existing validation/fallback boundaries**. Stage 16.1 has not been implemented.
