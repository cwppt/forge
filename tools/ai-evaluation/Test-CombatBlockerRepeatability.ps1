param([Parameter(Mandatory=$true)][string]$CaseManifest,[ValidateRange(2,10)][int]$Repeats=3,[string]$Endpoint='http://localhost:11434/v1/chat/completions',[switch]$DryRun)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'CombatDiagnosticSupport.ps1')
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$cases=@(Get-Content $CaseManifest -Raw|ConvertFrom-Json)
if(-not $cases.Count){throw 'No cases supplied'}
$run=Join-Path $repo ('ai-eval/stage12/blocker-repeatability-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8))
$prepared=@(foreach($case in $cases){
 $traces=@(Get-Content $case.TracePath|ForEach-Object {$_|ConvertFrom-Json})
 if($case.Index -lt 0 -or $case.Index -ge $traces.Count){throw 'Trace index out of range'}
 $body=$traces[$case.Index].Request
 $payload=$body.messages[1].content|ConvertFrom-Json
 if($payload.decisionType -ne 'COMBAT_BLOCKERS' -or $body.response_format.type -ne 'json_schema'){throw 'Require captured structured blocker request'}
 if($case.ExpectedOptionId -cnotin $payload.options.optionId){throw 'Expected choice absent from supplied options'}
 [pscustomobject]@{Case=$case;Body=$body;Payload=$payload;RawRequestUtf8Base64=$traces[$case.Index].RequestUtf8Base64}
})
if($DryRun){$prepared|ForEach-Object {[pscustomobject]@{Case=$_.Case.Name;DecisionId=$_.Payload.decisionId;Model=$_.Body.model;Calls=2*$Repeats}};exit}
New-Item -ItemType Directory $run|Out-Null
[ordered]@{Repeats=$Repeats;Endpoint=$Endpoint;Cases=$cases;Protocol='Interleaved fixed request and fresh decision ID; inner content preserved byte-for-byte except decision ID. No new state, option order, fingerprint, prompt or sampling settings.'}|ConvertTo-Json -Depth 20|Set-Content (Join-Path $run 'run.json')
function Digest([string]$value){$h=[Security.Cryptography.SHA256]::Create();try{([BitConverter]::ToString($h.ComputeHash([Text.Encoding]::UTF8.GetBytes($value)))).Replace('-','')}finally{$h.Dispose()}}
$results=@()
for($repeat=1;$repeat -le $Repeats;$repeat++){
 foreach($item in $prepared){
  foreach($variant in @('Fixed','FreshIdentity')){
   $body=$item.Body|ConvertTo-Json -Depth 45 -Compress|ConvertFrom-Json
   $original=$body.messages[1].content
   if($variant -eq 'FreshIdentity'){
    $old='"decisionId":"'+$item.Payload.decisionId+'"'
    $id=if($item.Case.AlternateDecisionId){[string]$item.Case.AlternateDecisionId}else{[Guid]::NewGuid().ToString()}
    if($item.Case.AlternateDecisionId){$variant='AlternateIdentity'}
    $replacement='"decisionId":"'+$id+'"'
    if(([regex]::Matches($original,[regex]::Escape($old))).Count -ne 1){throw 'Require exactly one inner decision identity'}
    $body.messages[1].content=$original.Replace($old,$replacement)
    $body.response_format.json_schema.schema.properties.decisionId.enum=@($id)
    if($body.messages[1].content.Replace($replacement,$old) -cne $original){throw 'Unexpected inner prompt mutation'}
   }
   $payload=$body.messages[1].content|ConvertFrom-Json
   if($payload.stateFingerprint -cne $item.Payload.stateFingerprint){throw 'Fingerprint changed'}
   $wire=if($variant -eq 'Fixed'){$item.RawRequestUtf8Base64}else{$null}
   $errorText=$null;$choice=$null;$finish=$null
   try{
    $reply=Invoke-CombatDiagnosticRequest $body $Endpoint 60 (Join-Path $run 'requests.jsonl') $wire
    $finish=$reply.choices[0].finish_reason
    if($finish -eq 'length'){throw 'Truncated response'}
    $answer=$reply.choices[0].message.content|ConvertFrom-Json
    Assert-CombatDiagnosticDecision $answer $payload
    $choice=$answer.optionId
   }catch{$errorText=$_.Exception.Message}
   $results += [pscustomobject][ordered]@{Case=$item.Case.Name;Grade=$item.Case.Grade;Variant=$variant;Repeat=$repeat;DecisionId=$payload.decisionId;ExactCapturedWire=[bool]$wire;RequestHash=(Digest $(if($wire){[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($wire))}else{$body|ConvertTo-Json -Depth 45 -Compress}));InnerHash=(Digest $body.messages[1].content);OptionId=$choice;ExpectedOptionId=$item.Case.ExpectedOptionId;MatchesExpectation=($choice -ceq $item.Case.ExpectedOptionId);FinishReason=$finish;Error=$errorText}
   $results|ConvertTo-Json -Depth 10|Set-Content (Join-Path $run 'results.json')
   Write-Output "$($item.Case.Name) $variant $repeat : $choice error=$errorText"
  }
 }
}
$summary=@($results|Group-Object Case,Variant|ForEach-Object {
 $valid=@($_.Group|Where-Object {-not $_.Error})
 [ordered]@{Case=$_.Group[0].Case;Grade=$_.Group[0].Grade;Variant=$_.Group[0].Variant;Calls=$_.Count;Valid=$valid.Count;Errors=$_.Count-$valid.Count;DistinctChoices=@($valid.OptionId|Select-Object -Unique).Count;DistinctRequestHashes=@($_.Group.RequestHash|Select-Object -Unique).Count;Matches=@($valid|Where-Object MatchesExpectation).Count;Choices=@($valid|Group-Object OptionId|Select-Object Name,Count)}
})
$summary|ConvertTo-Json -Depth 10|Set-Content (Join-Path $run 'summary.json')
Write-Output "Output directory: $run"



