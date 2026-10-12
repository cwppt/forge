# Stage 16.2 â€” verified pump/protection ability recovery

Completed 9 October 2026. This stage expands the existing Stage 16.1 registry only. COMBAT_ACTION, strategy, prompts, ordering, provider transport, native warning behavior and the other decision surfaces are unchanged.

## Result and scope

Screened all **2,500 warning-eligible card records** in the Stage 16 inventory. Of these, **74 relevant ordinary instant abilities** were examined in detail. **Five** met the structural screen; **four** passed individual end-to-end verification and are registered, including Earthbrawn from Stage 16.1. There are **three new entries**. Nine candidates received native preparation probes; five were not admitted. Within the five structurally eligible candidates, one (Dizzy Spell) failed the tested preparation gate.

Four is intentionally below the approximate 5â€“10 target. The fifth structural candidate did not produce a prepared action. None of the warned protection candidates met the safe contract. Rouse executed natively but carries an alternate-cost mechanic; the three stat-switch spells were outside the reviewed effect shape and also produced no prepared candidate in the tested combat. No card was added to inflate the count.

## Capability contract and registry

The screen requires an ordinary root instant spell, Pump/Protection/PreventDamage API, a fixed mana-only cost without X, exactly one creature target, fixed numeric effect values, and only the reviewed simple effect/keyword parameters. It excludes subabilities, triggers, replacements, alternate or optional costs, optional keywords, splice, extra payment choices, variable targeting, modes, distributions and resolution choices. Static AlternativeCost mechanics are rejected as well as already selected alternate costs. Reviewed keyword shapes are fixed grants such as First Strike/Indestructible, rather than hidden switch/death mechanics.

**Passing this screen never grants support.** Warned-card abilities remain denied unless the existing immutable registry contains the exact card/API/COMBAT_ACTION key and exact reviewed script parameters and mana cost. Unknown parameters or a changed template fail closed. Unmarked cards retain existing DEFAULT policy. The structural screen is a discovery/contract guard, not a new runtime strategy or automatic protection whitelist.

Entries carry an exact template, mana/script cost and verification note. Lookup remains deterministic and uses no heuristics, rules-text inference, live randomness or runtime option position. The production change is confined to `forge-ai/src/main/java/forge/ai/ExternalAiAbilitySupport.java`; no second registry or card-specific execution path was introduced.

## Individually admitted abilities

| Card | Ordinary ability | Paid cost | Verified result on 2/2 Runeclaw Bear | Unsafe siblings |
| --- | --- | --- | --- | --- |
| Earthbrawn (existing) | One creature gets +3/+3 | 1G | 5/5 | Reinforce excluded |
| Resounding Roar (new) | One creature gets +3/+3 | 1G | 5/5 | Cycling and cycling-trigger effect excluded |
| Righteousness (new) | One blocking creature gets +7/+7 | W | 9/9 | Only the exact blocking-creature spell template admitted |
| Hundred-Talon Strike (new) | One creature gets +1/+0 and First Strike | W | 3/2 with actual FIRST_STRIKE keyword | Splice-modified variants excluded |

Every entry was tested through real Forge preparation, COMBAT_ACTION routing, opaque option mapping, retained exact target, immutable request/provider selection, live revalidation, payment, stack placement and resolution. Scripted PASS, provider failure and target-stale fallback were tested for each. All non-COMBAT_ACTION surfaces remain unsupported for these warned abilities.

Hundred-Talon Strike's resolved keyword is visible in the subsequent COMBAT_ACTION projection and in blocker and damage-order request projections. The latter fixture projects the resolved creature into a synthetic subsequent attacking combat in the same turn; it does not claim native pre-block preparation for this spell. The attempted pre-block fixture offered no action, so native preparation and combat heuristics were not changed to force an offer. Existing routing-window regressions remain green.

## Rejections and protection limits

| Native-probed candidate not admitted | Reason |
| --- | --- |
| Dizzy Spell | Structurally eligible ordinary debuff, but no prepared option in the tested combat; no positive routing/execution proof. Transmute remains unverified. |
| About Face | Hidden power/toughness-switch keyword outside reviewed simple grants; no prepared option in the tested combat. |
| Strange Inversion | Same switch shape and preparation outcome. |
| Transmutation | Same switch shape and preparation outcome. |
| Rouse | Native ordinary spell prepared, paid and resolved to 4/2, but the card defines an alternate life-cost mechanic outside this contract. |

These preparation failures are fixture observations, not claims that the engine can never prepare those spells. They remain denied without changing strategic heuristics.

The full CSV records all 74 screened abilities and each structural exclusion reason. In particular:

- Bathe in Light requires a color choice and Radiance; Faith's Shield adds a color/conditional broader effect.
- Embolden/Remedy use broader or multiple targets/prevention allocation.
- Indestructible Aura uses an Effect/remember/replacement shape outside the existing safe option summary contract.
- Undying Evil grants a death/reanimation mechanic outside the reviewed keyword shape.
- X-cost Blazing Shoal, modal Ivory Charm, multi-target Triton Tactics and Radiance Wojek Siren remain excluded.

No warned protection entry was added. An **unmarked Withstand Death control** verifies existing prepared indestructible execution and DEFAULT policy; it is not counted as recovered support. This establishes that safe protection execution remains functional without falsely certifying warned protection mechanics.

## Warning, privacy and surface safety

Deck warnings remain intact for all four registered cards. All 33,980 card scripts are byte-identical to the pre-stage baseline. Recovery remains ability-scoped: Reinforce, Cycling, triggered cycling effects and splice-modified actions are not externally admitted. A specifically modified Hundred-Talon Strike splice copy is rejected.

Provider options contain ordinary COMBAT_ACTION data only. They disclose neither registry status nor warning/support labels. Tests include a hidden opponent Black Lotus and reject its presence in the model projection. Real captured requests contain no Reinforce/Cycling/Splice/VERIFIED_SAFE data. Forge remains authoritative for targets, payment, resolution and fallback; the provider selects only a supplied opaque option or literal PASS.

## Live qwen3:8b evidence

Run: `ai-eval/stage16_2/20261009-235351/`.

Eight deterministic coverage fixtures: four registered cards Ã— seeds 16201/16202. Each fixture presents a legal blocked 2/2 against a 3/3 and the necessary fixed mana. These are deliberately incomplete legal fixtures, not win-rate games or tuned decks. Righteousness is present in three existing quest precons: Army of Justice, Flyover and Totem Power. The other three were verified from their existing Stage 16 audited card scripts rather than inventing deck names.

- **8 offers, 8 legal external selections/resolutions, 4 distinct cards**.
- **0 fallback and 0 stale/revalidation failures**.
- PASS appears in all eight exact structured enums; the live model chose action in all eight. Four scripted per-card PASS cases separately prove accepted PASS behavior.
- No unsupported sibling exposed.
- Mean provider latency **3,468.75 ms**, including the first request at 8,351 ms; the remaining seven average **2,771.29 ms**.
- All eight captured request byte hashes and decoded bodies verified; `model=qwen3:8b`, `reasoning_effort=none`. Provider telemetry retains the actual model.
- Request identity/fingerprint, selected enum membership, audit correlation and ACTION_VALID outcomes verified offline.

The runner uses Windows PowerShell 5.1-compatible Start-Process with a hidden window and separate stdout/stderr files. It enables only the existing COMBAT_ACTION fixture path, reuses provider settings and writes a fresh timestamped directory. It does not change production defaults or evaluate playing strength.

From repository root, after focused test compilation:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ai-evaluation\Run-Stage16_2PumpProtectionRecovery.ps1 -Model 'qwen3:8b'
C:\Python312\python.exe .\tools\ai-evaluation\verify_stage16_2.py .\ai-eval\stage16_2\20261009-235351
```

The default seeds are 16201/16202. Use the directory printed by the runner for a fresh verification. From an existing PowerShell session, `-Seeds @(16201,16202)` can override the seed list. `-DryRun` prints the Java fixture invocation; `-Endpoint` permits another compatible provider URL. No Ollama is required for the unit tests.

## Validation and preserved evidence

**308 focused Java tests passed**, zero failures/errors/skips across 17 classes:

| Area | Tests |
| --- | ---: |
| Stage 16.2 routed contract/execution and native probes | 32 + 9 |
| Stage 16.1 Earthbrawn/Reinforce | 14 |
| Stage 16 support audit, including Swan Song real counter/token execution | 8 |
| COMBAT_ACTION decisions/coverage | 67 + 19 |
| Blocker decisions and damage order | 22 + 14 |
| STACK_RESPONSE decisions/opaque IDs/ordering | 12 + 3 + 4 |
| MAIN_PHASE_ACTION | 19 |
| Prepared actions | 17 |
| Projection consistency/privacy | 4 |
| Stage 14.2 blocker tactical behavior | 18 |
| Stage 15 memory integrity | 30 |
| Mulligan providers | 16 |

The initial focused run's unsupported pre-block fixture was corrected to test native post-block execution plus downstream projections; no production routing or heuristic change was made. Final focused recovery/Earthbrawn run: 46 tests green. The broader final run above includes all final Java changes and smoke-fixture compilation. No full Maven reactor was run; only the desktop module and its required dependencies.

Evidence: `work/stage16_2/regressions.log`, `test-results.json`, `preservation.json`, native probe outputs and the live run's `verification.json`. Baseline hash validation covers **39,154 files**: only the intended existing registry file changed, no files missing; **3,043 prior ai-eval files** and all card scripts preserved. Prompts, ordering, transport, Stage 14.2 and Stage 15 production files remain byte-identical. `git diff --check` exits **0**; new files were also checked for trailing whitespace and malformed EOF.

## Outputs and scalability

New assets: the 74-row `stage16/recovered-abilities.csv`, inventory screening script `audit_stage16_2.py`, native preparation and routed execution tests, `Stage16PumpClusterRecoverySmoke.java`, PS runner, offline request verifier, this report and isolated Stage 16.2 evidence directories. Existing audit and evaluation outputs were preserved.

The cluster is reusable: all four admitted spells share the existing prepared-action pipeline, without card-specific execution logic. A future assisted workflow can screen script structure, generate a focused fixture, execute/revalidate/resolve it, check downstream projections and propose an exact registry entry for review. Structural eligibility alone is insufficient: Dizzy Spell demonstrates the gap between syntactic shape and native preparation; protection choices demonstrate why effects need separate proof. This stage does not implement autonomous registry updates. Future test-generation tooling should report skipped/unprepared candidates and require reviewed evidence before production admission.

One next mechanic cluster from the Stage 16 audit: **counters targeting nontrigger activated abilities through existing STACK_RESPONSE**, starting with the audit's Stifle candidate. Verify native targeting, exact stack mapping, payment/resolution and triggered-sibling exclusions before any registry entry. This cluster was not implemented here.

**A. CLUSTER RECOVERY SUCCESSFUL â€” safe to expand verified registry**
