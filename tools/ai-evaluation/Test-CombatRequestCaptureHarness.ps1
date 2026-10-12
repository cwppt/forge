$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'CombatDiagnosticSupport.ps1')
$global:combatCaptureObserved=$null
function Invoke-RestMethod {param($Uri,$Method,$ContentType,$Body,$TimeoutSec)
 $global:combatCaptureObserved=[Convert]::ToBase64String($Body)
 $request=[Text.Encoding]::UTF8.GetString($Body)|ConvertFrom-Json
 $payload=$request.messages[1].content|ConvertFrom-Json
 $answer=[ordered]@{decisionId=$payload.decisionId;stateFingerprint=$payload.stateFingerprint;optionId=$payload.options[0].optionId}|ConvertTo-Json -Compress
 return [pscustomobject]@{choices=@([pscustomobject]@{finish_reason='stop';message=[pscustomobject]@{content=$answer}})}
}
$fixture=Get-Content (Join-Path $PSScriptRoot 'fixtures/protective-blocker.json') -Raw|ConvertFrom-Json
$raw=$fixture.ProviderRequest|ConvertTo-Json -Depth 45
$wire=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($raw))
$trace=Join-Path ([IO.Path]::GetTempPath()) ('combat-wire-control-'+[Guid]::NewGuid().ToString('N')+'.jsonl')
$reply=Invoke-CombatDiagnosticRequest $fixture.ProviderRequest 'http://offline.invalid' 1 $trace $wire
if($global:combatCaptureObserved -cne $wire){throw 'Raw request bytes changed'}
Assert-CombatDiagnosticDecision ($reply.choices[0].message.content|ConvertFrom-Json) $fixture.Payload
$observed=$global:combatCaptureObserved
$rejected=$false
try{Invoke-CombatDiagnosticRequest $fixture.ProviderRequest 'http://offline.invalid' 1 $trace '/w=='|Out-Null}catch{$rejected=$true}
if(-not $rejected -or $global:combatCaptureObserved -cne $observed){throw 'Invalid UTF-8 was sent'}
Remove-Variable combatCaptureObserved -Scope Global
Write-Output 'Passed exact-byte transport, strict final response and invalid UTF-8 controls; no network calls.'
