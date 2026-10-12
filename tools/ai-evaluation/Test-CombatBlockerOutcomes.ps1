param([Parameter(Mandatory=$true)][string]$FixtureDirectory,
 [string]$Model='qwen3:8b', [string]$Endpoint='http://localhost:11434/v1/chat/completions',
 [ValidateRange(1,120)][int]$TimeoutSeconds=60,
 [ValidateSet('Array','Named')][string]$OutcomeLayout='Named',
 [ValidateSet('Structured','Plain')][string]$InputFormat='Structured',
 [ValidateSet('Legacy','Clear')][string]$Instructions='Clear',
 [ValidateSet('Model','VerifiedFixture')][string]$PredictionSource='Model')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'CombatDiagnosticSupport.ps1')
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run=Join-Path $repo ('ai-eval/stage12/blocker-outcomes-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+$InputFormat+'-'+$Instructions+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path $run | Out-Null
$savedFixtures=Get-Content (Join-Path $FixtureDirectory 'fixtures.json') -Raw | ConvertFrom-Json
$fixtures=@($savedFixtures | Where-Object Order -eq NoBlockFirst)
if ($fixtures.Count -ne 8) { throw 'Expected the eight paired synthetic positions' }
if ($PredictionSource -eq 'VerifiedFixture') {
 $checkedHash=(Get-FileHash (Join-Path $PSScriptRoot 'fixtures/blocker-judgment.json')).Hash
 if ((Get-FileHash (Join-Path $FixtureDirectory 'fixtures.json')).Hash -ne $checkedHash) {throw 'VerifiedFixture requires the exact fixture file checked by CombatDiagnosticGroundTruthTest'}
}
$choicePrompt=(Get-Content (Join-Path $FixtureDirectory 'run.json') -Raw | ConvertFrom-Json).SystemPrompt
$prompt='Calculate immediate Magic combat outcomes for each supplied option. Only the creatures and abilities shown exist; assume no spells or activated abilities. First strike occurs before ordinary damage; deathtouch makes any positive creature damage lethal; trample assigns lethal damage to blockers before excess player damage. Both sides deal ordinary damage simultaneously. Return only a JSON object with decisionId, stateFingerprint, and outcomes. For each supplied option include optionId, defenderDamage (integer), attackerDies (boolean), blockerDies (boolean), and defenderLoses (boolean). NO_BLOCK deals attacker power to the defender and neither creature dies. Do not choose an option or evaluate future turns.'
if ($OutcomeLayout -eq 'Named') { $prompt=$prompt.Replace('and outcomes. For each supplied option include optionId,', 'noBlockOutcome and blockOutcome. For each of these two named objects include') }
if ($Instructions -eq 'Clear') { $prompt+=' Field definitions: defenderDamage is damage dealt to the defending PLAYER ONLY, excluding all damage to creatures. defenderLoses is true exactly when player life minus defenderDamage is zero or less. attackerDies and blockerDies refer to the attacking and available blocking CREATURES respectively. An unassigned blocker does not take combat damage. A blocked attacker without trample deals zero damage to the player even if it kills the blocker. First-strike damage and state-based creature deaths happen before ordinary damage, so a blocker killed in first strike does not hit back.' }
[ordered]@{HarnessVersion=2;Model=$Model;Endpoint=$Endpoint;FixtureDirectory=$FixtureDirectory;PredictionSource=$PredictionSource;InputFormat=$InputFormat;Instructions=$Instructions;OutcomeLayout=$OutcomeLayout;SystemPrompt=$prompt;ChoicePrompt=$choicePrompt;MaxTokens=1024;ReasoningEffort='none';Note='Diagnostic outcome task and two-stage choice only; neither is a Forge decision type or live behavior.'} | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $run 'run.json') -Encoding UTF8
$results=@()
function Query($Payload,[string]$SystemPrompt,$Schema,[bool]$OutcomeRequest=$false) {
 $userText=if ($OutcomeRequest) {Get-CombatDiagnosticUserText $Payload $InputFormat} else {ConvertTo-CombatDiagnosticJson $Payload}
 $body=@{model=$Model;temperature=0;stream=$false;reasoning_effort='none';max_tokens=1024;messages=@(@{role='system';content=$SystemPrompt},@{role='user';content=$userText});response_format=@{type='json_schema';json_schema=@{name='combat_diagnostic';strict=$true;schema=$Schema}}}
 $response=Invoke-CombatDiagnosticRequest $body $Endpoint $TimeoutSeconds (Join-Path $run 'requests.jsonl')
 if ($response.choices[0].finish_reason -eq 'length') { throw 'Response truncated' }
 return ($response.choices[0].message.content|ConvertFrom-Json)
}
foreach ($fixture in $fixtures) {
 $payload=$fixture.Payload
 $errors=@();$predicted=$null;$selected=$null;$correct=$false;$responseError=$null;$outcomeValid=$false;$choiceValid=$false
 # Validate fixture assumptions and compute truth before making any model request.
 $expected=@(Get-SimpleCombatDiagnosticTruth $payload)
 $watch=[Diagnostics.Stopwatch]::StartNew()
 try {
  $outcomeItem=@{type='object';additionalProperties=$false;properties=@{optionId=@{type='string';enum=@($payload.options.optionId)};defenderDamage=@{type='integer';minimum=0};attackerDies=@{type='boolean'};blockerDies=@{type='boolean'};defenderLoses=@{type='boolean'}};required=@('optionId','defenderDamage','attackerDies','blockerDies','defenderLoses')}
  $schema=@{type='object';additionalProperties=$false;properties=@{decisionId=@{type='string';enum=@($payload.decisionId)};stateFingerprint=@{type='string';enum=@($payload.stateFingerprint)};outcomes=@{type='array';items=$outcomeItem;minItems=2;maxItems=2}};required=@('decisionId','stateFingerprint','outcomes')}
  if ($OutcomeLayout -eq 'Named') {
   $namedItem=@{type='object';additionalProperties=$false;properties=[ordered]@{defenderDamage=@{type='integer';minimum=0};attackerDies=@{type='boolean'};blockerDies=@{type='boolean'};defenderLoses=@{type='boolean'}};required=@('defenderDamage','attackerDies','blockerDies','defenderLoses')}
   $schema=@{type='object';additionalProperties=$false;properties=[ordered]@{decisionId=@{type='string';enum=@($payload.decisionId)};stateFingerprint=@{type='string';enum=@($payload.stateFingerprint)};noBlockOutcome=$namedItem;blockOutcome=$namedItem};required=@('decisionId','stateFingerprint','noBlockOutcome','blockOutcome')}
  }
  if ($PredictionSource -eq 'VerifiedFixture') {
   $predicted=[pscustomobject]@{decisionId=$payload.decisionId;stateFingerprint=$payload.stateFingerprint;outcomes=$fixture.ExpectedOutcomes}
  } else { $predicted=Query $payload $prompt $schema $true }
  if ($OutcomeLayout -eq 'Named' -and $PredictionSource -eq 'Model') {
   $predicted.noBlockOutcome | Add-Member -NotePropertyName optionId -NotePropertyValue 'NO_BLOCK'
   $predicted.blockOutcome | Add-Member -NotePropertyName optionId -NotePropertyValue ($payload.options | Where-Object optionId -ne NO_BLOCK).optionId
   $predicted | Add-Member -NotePropertyName outcomes -NotePropertyValue @($predicted.noBlockOutcome,$predicted.blockOutcome)
  }
  Assert-CombatDiagnosticOutcomes $predicted $payload
  $outcomeValid=$true
  foreach ($truth in $expected) {
   $prediction=$predicted.outcomes|Where-Object optionId -eq $truth.optionId
   foreach ($field in @('defenderDamage','attackerDies','blockerDies','defenderLoses')) {if ($prediction.$field -ne $truth.$field) {$errors+="$($truth.optionId) $field expected=$($truth.$field) predicted=$($prediction.$field)"}}
  }
  $correct=$errors.Count -eq 0
  if ($PredictionSource -eq 'VerifiedFixture' -and -not $correct) {throw 'Verified fixture goldens disagree with the supported truth calculator'}
  # Feed only model-produced public outcome predictions, never the grade or expected answer.
  $augmented=[ordered]@{decisionId=$payload.decisionId;decisionType=$payload.decisionType;stateFingerprint=$payload.stateFingerprint;state=$payload.state;options=$payload.options;estimatedImmediateOutcomes=$predicted.outcomes}
  $choiceSchema=@{type='object';additionalProperties=$false;properties=@{decisionId=@{type='string';enum=@($payload.decisionId)};stateFingerprint=@{type='string';enum=@($payload.stateFingerprint)};optionId=@{type='string';enum=@($payload.options.optionId)}};required=@('decisionId','stateFingerprint','optionId')}
  $outcomeGuidance=if ($PredictionSource -eq 'VerifiedFixture') {' The included immediate outcomes were verified against Forge for these synthetic fixtures. They are public combat consequences, not strategic rankings. Use them when comparing options. Life is a resource; consider both material preservation and lethal defense.'} else {' The included immediate-outcome estimates were generated by you; verify them against the public state before choosing. Life is a resource; consider both material preservation and lethal defense.'}
  $selected=Query $augmented ($choicePrompt+$outcomeGuidance) $choiceSchema
  Assert-CombatDiagnosticDecision $selected $payload
  $choiceValid=$true
 } catch {$responseError=$_.Exception.GetType().Name+': '+$_.Exception.Message;$errors+=$responseError;$selected=$null}
 $watch.Stop()
 $selectedTruth=$expected|Where-Object optionId -ceq $selected.optionId
 $results+=[pscustomobject]@{Case=$fixture.Case;OutcomesCorrect=$correct;OutcomeResponseValid=$outcomeValid;ChoiceResponseValid=$choiceValid;ResponseError=$responseError;ImmediateLossSelected=($choiceValid -and $selectedTruth.defenderLoses);Predictions=$predicted.outcomes;ExpectedOutcomes=$expected;Errors=$errors;ExpectedChoice=$fixture.ExpectedOptionId;SelectedChoice=$selected.optionId;ChoiceMatchesExpectation=($null -ne $selected -and $selected.optionId -eq $fixture.ExpectedOptionId);GradeType=$fixture.GradeType;TotalLatencyMs=$watch.ElapsedMilliseconds}
 $results|ConvertTo-Json -Depth 12|Set-Content (Join-Path $run 'results.json') -Encoding UTF8
 Write-Output "$($fixture.Case): outcomesCorrect=$correct choice=$($selected.optionId) matches=$($results[-1].ChoiceMatchesExpectation) errors=$($errors -join '; ')"
}
$fieldSummary=@()
foreach ($field in @('defenderDamage','attackerDies','blockerDies','defenderLoses')) {
 $total=0;$matches=0;$constantBaseline=0
 foreach ($result in $results) {
  foreach ($truth in $result.ExpectedOutcomes) {
   $total++
   $prediction=$result.Predictions | Where-Object optionId -ceq $truth.optionId
   if ($result.OutcomeResponseValid -and $prediction.$field -eq $truth.$field) {$matches++}
   $constant=if ($field -eq 'defenderDamage') {0} else {$false}
   if ($truth.$field -eq $constant) {$constantBaseline++}
  }
 }
 $fieldSummary+=[pscustomobject]@{Field=$field;Comparisons=$total;Matches=$matches;ConstantZeroOrFalseMatches=$constantBaseline}
}
[ordered]@{PredictionSource=$PredictionSource;Cases=$results.Count;FullyCorrectOutcomes=@($results|Where-Object OutcomesCorrect).Count;
 ResponseFailures=@($results|Where-Object ResponseError).Count;ImmediateLossChoices=@($results|Where-Object ImmediateLossSelected).Count;
 PreferenceGroups=@($results | Group-Object GradeType | ForEach-Object {[pscustomobject]@{GradeType=$_.Name;Cases=$_.Count;Matches=@($_.Group|Where-Object ChoiceMatchesExpectation).Count}});
 FieldSummary=$fieldSummary} | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $run 'grade-summary.json') -Encoding UTF8
Write-Output "Output directory: $run"
