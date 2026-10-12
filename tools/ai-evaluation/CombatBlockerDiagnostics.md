# Combat blocker diagnostics

These offline tools evaluate the local model on eight synthetic public combat positions. They do not change Forge's decision types, live requests, legal option generation, or fallback behavior. Every run creates a new directory under `ai-eval/stage12/`.

Run from the repository root:

```powershell
./tools/ai-evaluation/Test-CombatBlockerJudgment.ps1 -Mode None
./tools/ai-evaluation/Test-CombatBlockerJudgment.ps1 -Mode None -ResponseFormat JSON_OBJECT
./tools/ai-evaluation/Test-CombatBlockerJudgment.ps1 -Mode Thinking
```

`None` uses a 512-token response limit and `reasoning_effort=none`. `Thinking` requests `reasoning_effort=low` with a 2,048-token limit. It stops after three response failures. The requested effort does not imply a model-specific fixed reasoning budget. Both modes use temperature zero and validate the returned identity, fingerprint and option ID.

The four families cover first strike, deathtouch, trample and a profitable block. Each family has a high-life and immediately lethal counterpart. Both option orders are tested, giving sixteen choices. The high-life no-block grades are material-preservation preferences, not proven strategic optima. Lethal-defense and no-loss profitable-block checks are reported separately in `GradeType`.

To inspect fixtures without calling a model:

```powershell
./tools/ai-evaluation/Test-CombatBlockerJudgment.ps1 -DryRun
```

Use a completed judgment run's fixture directory to evaluate immediate outcomes:

```powershell
./tools/ai-evaluation/Test-CombatBlockerOutcomes.ps1 -FixtureDirectory 'ai-eval/stage12/<judgment-run>'
```

The outcome tool checks player damage, attacker death, blocker death and immediate loss. It then asks for a choice using the model's own predictions, without exposing expected answers. Named block/no-block fields are the default; `-OutcomeLayout Array` tests opaque-option association separately. These diagnostic predictions must not be used as authoritative live engine results.

Each run stores its prompt/configuration and results; judgment runs also store fixtures. Error messages and truncation are retained. Test another already available model with `-Model`, or another compatible endpoint with `-Endpoint`, before spending time on full cohorts. Passing this small diagnostic does not establish improved play: any candidate still needs real-game validation across both external seats.

## Harness version 2 audit (8 October 2026)

Run `Test-CombatDiagnosticHarness.ps1` without a model to check validation and scoring. Its 26 controls pass in Windows PowerShell 5.1 and PowerShell 7, including known-correct responses, malformed responses, truncation, and exact inner-prompt parity with a request rendered by the Java provider.

`CombatDiagnosticGroundTruthTest` independently checks all sixteen simple option outcomes through Forge combat and state-based actions. Together with existing `BasicBlockTests`, 21 engine tests pass. The simple offline calculator deliberately rejects unsupported abilities; it is not a general combat simulator.

Use `-IncludeProtectiveBlocker` or `-SafetyControlOnly` for the additional real-engine safety fixture. At zero life, preserving Platinum Angel prevents losing; blocking the deathtouch attacker kills the Angel and loses. This is a mandatory no-block control, unlike the soft high-life preferences. Its public payload and reference request come from the actual Java provider renderer.

Judgment controls now vary payload order, `-SchemaOptionOrder Same|Reverse`, and `-IdentityFieldOrder DecisionFirst|FingerprintFirst` separately. `-InputFormat Plain` offers a separate text-rendering experiment. These are different prompts, so preserve their configuration when comparing results. The inner structured JSON normalizes optional HTML escaping to match the provider; outer transport escaping is not model-visible.

Outcome diagnostics support `-Instructions Legacy|Clear` (Clear default), per-field scores and constant-answer baselines. `-PredictionSource VerifiedFixture` accepts only the curated fixture set and supplies verified immediate consequences as a separate choice experiment. Supplied facts are never counted as model prediction accuracy. Neither predicted nor supplied diagnostic outcomes are injected into live Forge decisions.

Every model run retains exact public requests and final answers in `requests.jsonl`, including finish reason, usage, latency and error. Reasoning text is not retained. `grade-summary.json` separates hard checks from strategic preferences; do not use an aggregate score as evidence of stronger play. Existing evaluation outputs remain intact.

## Live blocker scope after the audit

External blocker selection now bypasses defenders for whom Forge reports `cantLose()` or `cantLoseForZeroOrLessLife()`. Their existing native declaration is retained without a provider call or an external-decision event. The same protection check participates in revalidation, so protection appearing during a request rejects the external selection through the existing fallback path. Diagnostic preparation remains available to study these positions offline.

This conservative scope boundary does not prove native play optimal or simulate removal of loss-prevention effects. It prevents external overrides in an ability class the current selector has not demonstrated it can handle. It applies to blockers only and uses engine rules rather than card-name matching.

## Native combat-danger comparison

After identity and live legality validation, external blocker selection compares the chosen declaration against the original native declaration using `ComputerUtilCombat.lifeInSeriousDanger` on separate combat objects. If the external declaration predicts a loss and the native declaration does not, it retains the native mapping and records `UNSAFE_COMBAT_CHOICE` / `COMBAT_SAFETY_REJECTED`. This is an estimated safety veto, not an exact outcome certificate. Both-dangerous declarations remain selectable; a merely costly or strategically questionable choice is not rejected on this basis. No scores or recommendations are added to the model prompt.

## Repeatability controls

Test-CombatBlockerRepeatability.ps1 accepts a JSON case manifest. Each case supplies Name, Grade, TracePath, zero-based Index and ExpectedOptionId. TracePath is a JSONL file with a Request object, as retained by the diagnostic tools. Optional AlternateDecisionId tests one specified second identity; otherwise fresh UUIDs are generated. Use -DryRun to inspect cases without calls.

For each position, the tool interleaves repeats of the fixed request with identity-only variants. It preserves the inner prompt exactly except for the decisionId token and matching schema enum, verifies the fingerprint, hashes full requests and inner prompts, and strictly validates responses. A recorded historical choice is a reference comparison, not necessarily a correct strategic answer. Raw final answers and public requests are retained; reasoning text is not.

The October 8 test made 30 valid local-model calls: 24 on four saved engine-verified positions and six on a reconstructed historical Aurochs blocker request with its two original IDs. Each tested identity produced the same choice across three repeats. Different IDs did not change choices in these cases. The model still failed all six protective-blocker choices and matched the soft first-strike high-life preference in none of six calls. This does not establish universal determinism, prove the cause of historical game variation, or prove high-life material preservation optimal. The real-game requests were reconstructed from public audits using the Java-rendered template; they were not original wire captures.
