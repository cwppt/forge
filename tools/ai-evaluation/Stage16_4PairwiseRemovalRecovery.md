# Stage 16.4: narrow Dead Ringers recovery

Dead Ringers can be supplied as an additional prepared MAIN_PHASE_ACTION option when its exact verified script is present and Forge prepares a useful matching-color pair. Existing Forge fallback and all other decision surfaces retain their previous behavior.

## Eligibility and execution

- External main-phase selection must be enabled with an installed provider.
- Two-player game, deciding AI's MAIN1 or MAIN2, AI priority, empty stack.
- At least one native prepared action and a spare slot within the existing action bound are required. Native options are prepared first and retain their order and IDs; recovered actions are appended.
- Only the exact Dead Ringers Destroy script and fixed 4B mana cost are registered. No subability, trigger, replacement ability, alternative/optional costs, X, splicing or modified script is admitted.
- Both prepared targets must be distinct, current, visible opposing battlefield creatures, legally targetable and exactly equal in current color set. Colorless and multicolor matching pairs are covered by preparation/resolution probes.
- Forge chooses both targets through the existing copied-game preparation and live mapping pipeline. No new pair search, target combinations or strategic heuristic is added.
- Dead Ringers' equal-color condition is a resolution condition, not a target-legality restriction. Mismatched pairs remain legal under ordinary engine targeting but are excluded from this recovery path.
- The model-visible target summaries include current colors. Existing SHA-256 state fingerprinting therefore detects color changes even when both targets change to another matching color set.
- Frozen request decision ID, fingerprint and supplied action ID are validated before live projection/revalidation. The pair guard is checked again before execution, alongside existing source, target, restrictions and payment checks.
- Invalid responses use the native prepared preference; stale requests return to existing native evaluation. Recovered actions never become the native fallback.

## Configuration, audit and limits

No new configuration, provider transport, decision surface or metrics were introduced. Existing external main-phase settings, option IDs, audit events and telemetry apply. Existing defaults remain disabled. COMBAT_ACTION and STACK_RESPONSE do not admit Dead Ringers through this registry entry. The original removed-from-AI card warning/filter remains intact for native behavior.

Recovery intentionally skips cases without a native fallback, with a full native option bound, with unsafe or mismatched prepared targets, or where the existing Forge preparation does not find a useful pair. It does not claim broad pairwise-removal coverage or measured strategic improvement. No real-game evaluation was run for this change.

## Validation

On 2026-10-12, 374 focused tests passed with zero failures, errors or skips (20 test classes). This includes 11 Dead Ringers preparation probes and 12 recovery tests, plus the existing 351 external-AI regressions. Covered recovery behavior includes MAIN1/MAIN2 selection, real payment and resolution, preserved target identity, disabled behavior, mismatching colors, exact-script rejection, wrong request identity, post-request target/color/source/priority changes, lost mana, required native fallback and candidate limits.

`git diff --check` passed. Newly added files were separately checked for trailing whitespace and terminal blank lines. No tracked ai-eval outputs or card scripts changed.

Repeat the focused recovery tests from the repository root:

```powershell
mvn -B -pl forge-gui-desktop -am test '-Dtest=DeadRingersPreparationProbeTest,PairwiseRemovalRecoveryTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DfailIfNoTests=false'
```

Local Aider with Ollama Qwen 14B drafted the implementation excerpts and tests. Codex reviewed the changes, corrected mechanical API/syntax and fixture mistakes, restored a removed test, and ran validation. A temporary loopback relay enforced `think:false` for the local coding worker; Forge's established provider transport was not changed.
