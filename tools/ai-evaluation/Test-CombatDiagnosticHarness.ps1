# Fast offline controls: no Ollama requests. Exercise serialization, grading and rejection paths.
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'CombatDiagnosticSupport.ps1')
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$fixtures=Get-Content (Join-Path $PSScriptRoot 'fixtures/blocker-judgment.json') -Raw | ConvertFrom-Json
$passed=0
function MustReject([scriptblock]$Action) {
    $rejected=$false
    try { & $Action } catch {$rejected=$true}
    if (-not $rejected) {throw 'Expected malformed response to be rejected'}
    $script:passed++
}
foreach ($fixture in $fixtures) {
    $payload=$fixture.Payload
    $truth=@(Get-SimpleCombatDiagnosticTruth $payload)
    $prediction=[pscustomobject]@{decisionId=$payload.decisionId;stateFingerprint=$payload.stateFingerprint;outcomes=$truth}
    Assert-CombatDiagnosticOutcomes $prediction $payload
    $script:passed++
}
$payload=$fixtures[0].Payload
$decision=[pscustomobject]@{decisionId=$payload.decisionId;stateFingerprint=$payload.stateFingerprint;optionId='NO_BLOCK'}
Assert-CombatDiagnosticDecision $decision $payload; $passed++
$bad=$decision | ConvertTo-Json | ConvertFrom-Json; $bad.optionId='no_block'
MustReject {Assert-CombatDiagnosticDecision $bad $payload}
$bad=$decision | ConvertTo-Json | ConvertFrom-Json; $bad.stateFingerprint='wrong'
MustReject {Assert-CombatDiagnosticDecision $bad $payload}
$bad=[pscustomobject]@{decisionId=$payload.decisionId;stateFingerprint=$payload.stateFingerprint;outcomes=@(Get-SimpleCombatDiagnosticTruth $payload)}
$bad.outcomes[0].blockerDies='false'
MustReject {Assert-CombatDiagnosticOutcomes $bad $payload}
$bad=[pscustomobject]@{decisionId=$payload.decisionId;stateFingerprint=$payload.stateFingerprint;outcomes=@(Get-SimpleCombatDiagnosticTruth $payload)}
$bad.outcomes[1].optionId=$bad.outcomes[0].optionId
MustReject {Assert-CombatDiagnosticOutcomes $bad $payload}
$bad=[pscustomobject]@{decisionId=$payload.decisionId;stateFingerprint=$payload.stateFingerprint;outcomes=@(Get-SimpleCombatDiagnosticTruth $payload)}
$bad.outcomes[0].defenderDamage='7'
MustReject {Assert-CombatDiagnosticOutcomes $bad $payload}

# Intercept the HTTP boundary; return known answers without relying on the local model.
$global:combatHarnessMockFinish='stop'
function Invoke-RestMethod($Uri,$Method,$ContentType,$Body,$TimeoutSec) {
    $request=[Text.Encoding]::UTF8.GetString($Body) | ConvertFrom-Json
    $snapshot=$request.messages[1].content | ConvertFrom-Json
    $truth=@(Get-SimpleCombatDiagnosticTruth $snapshot)
    $properties=$request.response_format.json_schema.schema.properties
    if ($properties.noBlockOutcome) {
        $noBlock=$truth | Where-Object optionId -eq NO_BLOCK | Select-Object defenderDamage,attackerDies,blockerDies,defenderLoses
        $block=$truth | Where-Object optionId -ne NO_BLOCK | Select-Object defenderDamage,attackerDies,blockerDies,defenderLoses
        $content=[ordered]@{decisionId=$snapshot.decisionId;stateFingerprint=$snapshot.stateFingerprint;noBlockOutcome=$noBlock;blockOutcome=$block}
    } else {
        $fixture=$fixtures | Where-Object { $_.Payload.decisionId -ceq $snapshot.decisionId } | Select-Object -First 1
        if (-not $fixture) {throw 'Unknown fixture in offline control'}
        $content=[ordered]@{decisionId=$snapshot.decisionId;stateFingerprint=$snapshot.stateFingerprint;optionId=$fixture.ExpectedOptionId}
    }
    return [pscustomobject]@{choices=@([pscustomobject]@{message=[pscustomobject]@{content=($content|ConvertTo-Json -Depth 12 -Compress)};finish_reason=$global:combatHarnessMockFinish});usage=@{completion_tokens=100}}
}
function RunAndFind([scriptblock]$Action) {
    $before=@(Get-ChildItem (Join-Path $repo 'ai-eval/stage12') -Directory | ForEach-Object FullName)
    & $Action | Out-Null
    $after=@(Get-ChildItem (Join-Path $repo 'ai-eval/stage12') -Directory | Where-Object {$_.FullName -notin $before})
    if ($after.Count -ne 1) {throw 'Cannot identify offline control run'}
    return $after[0].FullName
}
$judgment=RunAndFind {& (Join-Path $PSScriptRoot 'Test-CombatBlockerJudgment.ps1') -Model 'offline-control' -Endpoint 'http://offline.invalid' }
$rows=Get-Content (Join-Path $judgment 'results.json') -Raw|ConvertFrom-Json
if ($rows.Count -ne 16 -or @($rows|Where-Object MatchesExpectation).Count -ne 16) {throw 'Known-correct choices were not all accepted'}
$passed++
$outcomeRun=RunAndFind {& (Join-Path $PSScriptRoot 'Test-CombatBlockerOutcomes.ps1') -FixtureDirectory $judgment -Model 'offline-control' -Endpoint 'http://offline.invalid'}
$rows=Get-Content (Join-Path $outcomeRun 'results.json') -Raw|ConvertFrom-Json
if ($rows.Count -ne 8 -or @($rows|Where-Object OutcomesCorrect).Count -ne 8 -or @($rows|Where-Object ChoiceMatchesExpectation).Count -ne 8) {throw 'Known-correct outcomes or choices were not all accepted'}
$passed++
$global:combatHarnessMockFinish='length'
$truncated=RunAndFind {& (Join-Path $PSScriptRoot 'Test-CombatBlockerJudgment.ps1') -Model 'offline-control' -Endpoint 'http://offline.invalid'}
$rows=Get-Content (Join-Path $truncated 'results.json') -Raw|ConvertFrom-Json
if ($rows.Count -ne 3 -or @($rows|Where-Object Error).Count -ne 3) {throw 'Truncated responses were not rejected'}
$passed++
$protective=Get-Content (Join-Path $PSScriptRoot 'fixtures/protective-blocker.json') -Raw|ConvertFrom-Json
if ((Get-CombatDiagnosticUserText $protective.Payload Structured) -cne $protective.ProviderRequest.messages[1].content) {throw 'Inner prompt differs from actual Java provider rendering'}
$passed++
Write-Output "Passed $passed offline controls. Known-correct choices 16/16; outcomes 8/8; three truncated replies rejected. No model calls."

