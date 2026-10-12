param([Parameter(Mandatory=$true)][string]$Manifest,[Parameter(Mandatory=$true)][string]$Output,[int]$ExpectedPairs=8,[ValidateSet('LegacyV1','RaceAwareV2')][string]$ExpectedMaterialPolicy='LegacyV1')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'CombatDiagnosticSupport.ps1')
$runs=@(Get-Content $Manifest -Raw|ConvertFrom-Json)
$runs=@($runs|Where-Object {$_.Arm -in @('Control','Material')})
if(-not $runs.Count){throw 'No policy comparison runs'}
function Digest([byte[]]$bytes){$h=[Security.Cryptography.SHA256]::Create();try{([BitConverter]::ToString($h.ComputeHash($bytes))).Replace('-','')}finally{$h.Dispose()}}
function AuditComparable($v){
 if($null -eq $v){return $null}
 if($v -is [array]){$items=[System.Collections.Generic.List[object]]::new();foreach($i in $v){$items.Add((AuditComparable $i))};return ,($items.ToArray())}
 if($v -is [pscustomobject]){$o=[ordered]@{};foreach($p in $v.PSObject.Properties){if($null -ne $p.Value){$o[$p.Name]=AuditComparable $p.Value}};return $o}
 return $v
}
$games=@();$decisions=@();$issueCount=0;$protocol=$null
foreach($run in $runs){
 $config=Get-Content (Join-Path $run.Directory 'run.json') -Raw|ConvertFrom-Json
 if(-not $config.SeededCombatIds -or -not $config.CaptureCombatRequests -or $config.ExperimentalMaterialGuard -ne ($run.Arm -eq 'Material')){throw 'Mismatched evaluation protocol'}
 if($run.Arm -eq 'Control' -and $config.MaterialGuardPolicy -and $config.MaterialGuardPolicy -ne 'Off'){throw 'Unexpected control material policy'}
 if($run.Arm -eq 'Material'){$policy=if($config.MaterialGuardPolicy){$config.MaterialGuardPolicy}else{'LegacyV1'};if($policy -ne $ExpectedMaterialPolicy){throw 'Unexpected material policy'}}
 $settings=[ordered]@{}
 foreach($name in @('Model','Endpoint','TimeoutSeconds','GameTimeoutSeconds','AttackerMaxOptions','BlockerMaxOptions','DamageOrderMaxOptions','AiProfiles','SeededBlockerIds')){$settings[$name]=$config.$name}
 $signature=ConvertTo-CombatDiagnosticJson $settings
 if($null -eq $protocol){$protocol=$signature}elseif($protocol -cne $signature){throw 'Inference or option settings differ across arms'}
 $summary=Get-Content $run.Summary -Raw|ConvertFrom-Json
 $issueCount += @($summary.AuditIssues).Count + @($summary.PublicSnapshotIssues).Count + @($summary.MissingGames).Count
 if($summary.CompletedGames -ne $summary.ExpectedGames){throw 'Incomplete run'}
 foreach($g in $summary.Games){
  $seat=[int]$g.Mode.Substring(4)
  $score=if($g.Draw){0.5}elseif($g.WinnerSeat -eq $seat){1}else{0}
  $games += [pscustomobject]@{Arm=$run.Arm;Key=$g.Game;Score=$score;Winner=$g.Winner;Calls=$g.Calls;Fallbacks=$g.Fallbacks;Directory=$run.Directory}
  $prefix=Join-Path $run.Directory $g.Game
  $audit=@(Get-Content "$prefix.jsonl"|ForEach-Object {$_|ConvertFrom-Json}|Where-Object decisionType -ne COMBAT_SEQUENCE)
  $frames=@(Get-Content "$prefix.requests.jsonl"|ForEach-Object {$_|ConvertFrom-Json})
  if($frames.Count -ne $audit.Count -or $audit.Count -ne $g.Calls){throw 'Capture/audit count mismatch'}
  foreach($row in $audit){
   $matches=@($frames|Where-Object {($_.Request.messages[1].content|ConvertFrom-Json).decisionId -ceq $row.telemetry.decisionId})
   if($matches.Count -ne 1){throw 'Nonunique captured identity within game'}
   $frame=$matches[0];$bytes=[Convert]::FromBase64String($frame.RequestUtf8Base64)
   if((Digest $bytes) -cne $frame.RequestSha256){throw 'Capture hash mismatch'}
   $raw=[Text.UTF8Encoding]::new($false,$true).GetString($bytes)
   if((ConvertTo-CombatDiagnosticJson ($raw|ConvertFrom-Json)) -cne (ConvertTo-CombatDiagnosticJson $frame.Request)){throw 'Captured bytes differ from parsed request'}
   $payload=$frame.Request.messages[1].content|ConvertFrom-Json
   $requiresNoThinking=([uri]$config.Endpoint).Port -eq 11434 -and $config.Model -match '^qwen3(?::|$)'
   if(($requiresNoThinking -and $frame.Request.reasoning_effort -ne 'none') -or $frame.Request.model -cne $config.Model){throw 'Unexpected inference settings in captured request'}
   if($row.telemetry.selectedOptionId -cnotin @($payload.options.optionId)){throw 'Applied option absent from captured request'}
   if($row.telemetry.fallback -and $row.telemetry.selectedOptionId -cne $row.telemetry.heuristicOptionId){throw 'Fallback failed to preserve heuristic'}
   if($row.telemetry.responseAccepted -and ($row.telemetry.returnedOptionId -cne $row.telemetry.selectedOptionId -or $row.telemetry.liveRevalidationResult -ne 'PASSED')){throw 'Accepted response/applied choice mismatch'}
   $answer=[pscustomobject]@{decisionId=$row.telemetry.returnedDecisionId;stateFingerprint=$row.telemetry.returnedFingerprint;optionId=$row.telemetry.returnedOptionId}
   Assert-CombatDiagnosticDecision $answer $payload
   $payload.PSObject.Properties.Remove('decisionType')
   $auditView=$row.modelVisibleDecision
   if($row.decisionType -eq 'COMBAT_DAMAGE_ORDER'){
    # Stage 11 deliberately aliases recipient IDs and omits derived option summaries on the wire.
    # Reconstruct that documented projection from the audit; never alter captured request bytes.
    $auditView=$row.modelVisibleDecision|ConvertTo-Json -Depth 100|ConvertFrom-Json
    $refs=@{};$refs[$auditView.state.source.recipientId]='S';$auditView.state.source.recipientId='S'
    $index=1
    foreach($recipient in $auditView.state.recipients){$alias="C$index";$index++;$refs[$recipient.recipientId]=$alias;$recipient.recipientId=$alias}
    $auditView.options=@(foreach($option in $auditView.options){
     $order=@(foreach($id in $option.orderedRecipientIds){if(-not $refs.ContainsKey($id)){throw 'Unknown audited recipient'};$refs[$id]})
     [pscustomobject][ordered]@{optionId=$option.optionId;order=$order}
    })
   }
   $wirePublic=ConvertTo-CombatDiagnosticJson (AuditComparable $payload)
   $auditPublic=ConvertTo-CombatDiagnosticJson (AuditComparable $auditView)
   if($wirePublic -cne $auditPublic){
    $wirePublic|Set-Content work/seeded-policy-mismatch-wire.json
    $auditPublic|Set-Content work/seeded-policy-mismatch-audit.json
    throw "Public request/audit mismatch: $($g.Game) $($row.telemetry.decisionId)"
   }
   $decisions += [pscustomobject]@{Arm=$run.Arm;Game=$g.Game;CaptureIndex=[array]::IndexOf($frames,$frame);Surface=$row.decisionType;Id=$row.telemetry.decisionId;Fingerprint=$row.telemetry.stateFingerprint;Hash=$frame.RequestSha256;Returned=$row.telemetry.returnedOptionId;Applied=$row.telemetry.selectedOptionId;Fallback=$row.telemetry.fallback;Reason=$row.telemetry.failureReason;Turn=$row.telemetry.turnNumber}
  }
 }
}
if($issueCount){throw 'Audit issues prohibit outcome interpretation'}
if(@($games|Group-Object Arm,Key|Where-Object Count -gt 1).Count){throw 'Duplicate game within comparison arm'}
$pairs=@(foreach($g in $games|Where-Object Arm -eq Control){
 $new=@($games|Where-Object {$_.Arm -eq 'Material' -and $_.Key -ceq $g.Key})
 if($new.Count -ne 1){throw 'Missing paired game'}
 [pscustomobject]@{Game=$g.Key;ControlScore=$g.Score;MaterialScore=$new[0].Score;Delta=$new[0].Score-$g.Score;ControlWinner=$g.Winner;MaterialWinner=$new[0].Winner}
})
if($pairs.Count -ne $ExpectedPairs -or $games.Count -ne 2*$ExpectedPairs){throw 'Unexpected paired game count'}
$shared=@(foreach($d in $decisions|Where-Object Arm -eq Control){
 $new=@($decisions|Where-Object {$_.Arm -eq 'Material' -and $_.Game -ceq $d.Game -and $_.Id -ceq $d.Id})
 if($new.Count -gt 1){throw 'Duplicate matched identity'}
 if($new.Count -eq 1){
  if($d.Fingerprint -cne $new[0].Fingerprint -or $d.Surface -cne $new[0].Surface){throw 'Identity collision'}
  [pscustomobject]@{Game=$d.Game;Surface=$d.Surface;Turn=$d.Turn;Id=$d.Id;SameBytes=($d.Hash -ceq $new[0].Hash);SameReturnedChoice=($d.Returned -ceq $new[0].Returned);SameAppliedChoice=($d.Applied -ceq $new[0].Applied)}
 }
})
$arms=@(foreach($arm in @('Control','Material')){
 $gs=@($games|Where-Object Arm -eq $arm);$ds=@($decisions|Where-Object Arm -eq $arm)
 [pscustomobject]@{Arm=$arm;Games=$gs.Count;Wins=@($gs|Where-Object Score -eq 1).Count;Calls=$ds.Count;MaterialVetoes=@($ds|Where-Object Reason -eq MATERIAL_COMBAT_CHOICE).Count;OtherFallbacks=@($ds|Where-Object {$_.Fallback -and $_.Reason -ne 'MATERIAL_COMBAT_CHOICE'}).Count;Surfaces=@($ds|Group-Object Surface|ForEach-Object {[pscustomobject]@{Surface=$_.Name;Calls=$_.Count}})}
})
$divergences=@(foreach($pair in $pairs){
 $old=@($decisions|Where-Object {$_.Arm -eq 'Control' -and $_.Game -ceq $pair.Game}|Sort-Object CaptureIndex)
 $new=@($decisions|Where-Object {$_.Arm -eq 'Material' -and $_.Game -ceq $pair.Game}|Sort-Object CaptureIndex)
 $first=$null;$prefix=0
 for($i=0;$i -lt [Math]::Min($old.Count,$new.Count);$i++){
  if($old[$i].Hash -cne $new[$i].Hash -or $old[$i].Returned -cne $new[$i].Returned -or $old[$i].Applied -cne $new[$i].Applied){
   $first=[pscustomobject]@{Turn=$new[$i].Turn;Surface=$new[$i].Surface;SameBytes=($old[$i].Hash -ceq $new[$i].Hash);SameReturnedChoice=($old[$i].Returned -ceq $new[$i].Returned);ControlApplied=$old[$i].Applied;MaterialApplied=$new[$i].Applied;MaterialReason=$new[$i].Reason};break
  };$prefix++
 }
 [pscustomobject]@{Game=$pair.Game;IdenticalDecisionPrefix=$prefix;ControlCalls=$old.Count;MaterialCalls=$new.Count;FirstDifference=$first}
})
$result=[ordered]@{MaterialPolicy=$ExpectedMaterialPolicy;Games=$games.Count;AuditIssues=$issueCount;VerifiedCapturedRequests=$decisions.Count;Arms=$arms;Better=@($pairs|Where-Object Delta -gt 0).Count;Worse=@($pairs|Where-Object Delta -lt 0).Count;Same=@($pairs|Where-Object Delta -eq 0).Count;SharedRequests=$shared.Count;SharedRequestsDifferentBytes=@($shared|Where-Object {-not $_.SameBytes}).Count;IdenticalRequestsDifferentReturnedChoice=@($shared|Where-Object {$_.SameBytes -and -not $_.SameReturnedChoice}).Count;Comparisons=$pairs;Shared=$shared;FirstDivergences=$divergences;Rejections=@($decisions|Where-Object Fallback);Runs=$runs;Limit='Paired diagnostic; refer to the predeclared cohort for seed selection. Repeatable IDs do not guarantee model determinism or establish general strength.'}
$result|ConvertTo-Json -Depth 20|Set-Content $Output
[pscustomobject]$result|Select-Object Games,AuditIssues,VerifiedCapturedRequests,Better,Worse,Same,SharedRequests,SharedRequestsDifferentBytes,IdenticalRequestsDifferentReturnedChoice




