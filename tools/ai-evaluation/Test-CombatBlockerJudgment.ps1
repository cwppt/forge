param(
    [string]$Model = 'qwen3:8b',
    [string]$Endpoint = 'http://localhost:11434/v1/chat/completions',
    [ValidateSet('None','Thinking')][string]$Mode = 'None',
    [ValidateSet('JSON_SCHEMA','JSON_OBJECT')][string]$ResponseFormat = 'JSON_SCHEMA',
    [ValidateSet('Structured','Plain')][string]$InputFormat = 'Structured',
    [ValidateSet('Same','Reverse')][string]$SchemaOptionOrder = 'Same',
    [ValidateSet('DecisionFirst','FingerprintFirst')][string]$IdentityFieldOrder = 'DecisionFirst',
    [switch]$IncludeProtectiveBlocker,
    [switch]$SafetyControlOnly,
    [ValidateRange(1,120)][int]$TimeoutSeconds = 60,
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'CombatDiagnosticSupport.ps1')
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run = Join-Path $repo ('ai-eval/stage12/blocker-judgment-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $Mode + '-' + $ResponseFormat + '-' + $InputFormat + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8))
$source = Get-Content (Join-Path $repo 'forge-ai/src/main/java/forge/ai/decision/external/OpenAiCompatibleDecisionProvider.java') -Raw
$section = [regex]::Match($source, 'private static final String BLOCK_SYSTEM_PROMPT = ([\s\S]*?);\r?\n').Groups[1].Value
$prompt = (([regex]::Matches($section, '"([^"\r\n]*)"') | ForEach-Object { $_.Groups[1].Value }) -join '')
if (-not $prompt) { throw 'Cannot read blocker prompt' }
function Permanent([string]$Id,[string]$Name,[int]$Power,[int]$Toughness,[string[]]$Keywords,[bool]$Attacker) {
 return [ordered]@{permanentId=$Id;card=[ordered]@{name=$Name;manaCost='{4}';type='Creature';oracleText=($Keywords -join ', ');tapped=$Attacker;controller=$(if ($Attacker) {'Attacker'} else {'Defender'});power=$Power;toughness=$Toughness;markedDamage=0;counters=@()};summoningSick=$false;canAttack=$false;keywords=@($Keywords)}
}
$families = @(
 @{Name='first-strike';Power=7;Toughness=7;Keywords=@('first strike');BlockPower=2;BlockToughness=2;LowLife=7;Preference='NO_BLOCK'},
 @{Name='deathtouch';Power=1;Toughness=1;Keywords=@('deathtouch');BlockPower=6;BlockToughness=6;LowLife=1;Preference='NO_BLOCK'},
 @{Name='trample';Power=6;Toughness=6;Keywords=@('trample');BlockPower=4;BlockToughness=4;LowLife=3;Preference='NO_BLOCK'},
 @{Name='profitable-block';Power=2;Toughness=2;Keywords=@();BlockPower=4;BlockToughness=4;LowLife=2;Preference='BLOCK'}
)
$fixtures = @()
foreach ($family in $families) {
 foreach ($life in @(20,$family.LowLife)) {
  $case = "$($family.Name)-life$life"
  $attacker = Permanent 'PERM_A' 'Attacking creature' $family.Power $family.Toughness $family.Keywords $true
  $blocker = Permanent 'PERM_B' 'Defending creature' $family.BlockPower $family.BlockToughness @() $false
  $state = [ordered]@{turn=8;phase='COMBAT_DECLARE_BLOCKERS';defendingPlayerId='PLAYER_0';players=@(
   [ordered]@{playerId='PLAYER_0';name='Defender';life=$life;self=$true;mana=[ordered]@{white=0;blue=0;black=0;red=0;green=0;colorless=0;total=0};battlefield=@($blocker)},
   [ordered]@{playerId='PLAYER_1';name='Attacker';life=20;self=$false;mana=$null;battlefield=@($attacker)});attackers=@([ordered]@{permanent=$attacker;targetId='PLAYER_0'});blockers=@([ordered]@{permanent=$blocker;canBlock=$true})}
  $noBlock = [ordered]@{optionId='NO_BLOCK';assignments=@();blockerCount=0;blockedAttackerCount=0}
  $block = [ordered]@{optionId='OPT_8F3AC501BD920A73C42F1006';assignments=@([ordered]@{blockerId='PERM_B';attackerId='PERM_A'});blockerCount=1;blockedAttackerCount=1}
  $hash = [Security.Cryptography.SHA256]::Create()
  try { $fingerprint = ([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes(($state | ConvertTo-Json -Depth 20 -Compress))))).Replace('-','') } finally { $hash.Dispose() }
  foreach ($order in @('NoBlockFirst','BlockFirst')) {
   $options = if ($order -eq 'NoBlockFirst') { @($noBlock,$block) } else { @($block,$noBlock) }
   $payload = [ordered]@{decisionId="probe-$case";decisionType='COMBAT_BLOCKERS';stateFingerprint=$fingerprint;state=$state;options=$options}
   $expected = if ($life -eq $family.LowLife -or $family.Preference -eq 'BLOCK') { $block.optionId } else { 'NO_BLOCK' }
   $fixtures += [pscustomobject]@{Case=$case;Order=$order;GradeType=$(if ($life -eq $family.LowLife) {'Immediate lethal defense'} elseif ($family.Preference -eq 'BLOCK') {'Kill attacker without losing blocker'} else {'Material preservation preference; not a forced strategic optimum'});ExpectedOptionId=$expected;Payload=$payload}
  }
 }
}
if ($IncludeProtectiveBlocker -or $SafetyControlOnly) {
 if ($SafetyControlOnly) {$fixtures=@()}
 $control=Get-Content (Join-Path $PSScriptRoot 'fixtures/protective-blocker.json') -Raw | ConvertFrom-Json
 foreach ($order in @('NoBlockFirst','BlockFirst')) {
  $payload=$control.Payload | ConvertTo-Json -Depth 30 | ConvertFrom-Json
  $none=@($payload.options | Where-Object { $_.assignments.Count -eq 0 }); $block=@($payload.options | Where-Object { $_.assignments.Count -gt 0 })
  $payload.options=if ($order -eq 'NoBlockFirst') {@($none+$block)} else {@($block+$none)}
  $fixtures+=[pscustomobject]@{Case=$control.Case;Order=$order;GradeType='Immediate survival: preserve protective blocker';ExpectedOptionId=$control.ExpectedOptionId;Payload=$payload;ExpectedOutcomes=$control.ExpectedOutcomes}
 }
}
if ($DryRun) { $fixtures | ConvertTo-Json -Depth 25; exit }
New-Item -ItemType Directory -Path $run | Out-Null
[ordered]@{HarnessVersion=2;Model=$Model;Endpoint=$Endpoint;Mode=$Mode;InputFormat=$InputFormat;SchemaOptionOrder=$SchemaOptionOrder;IdentityFieldOrder=$IdentityFieldOrder;IncludeProtectiveBlocker=[bool]($IncludeProtectiveBlocker -or $SafetyControlOnly);SafetyControlOnly=[bool]$SafetyControlOnly;ResponseFormat=$ResponseFormat;TimeoutSeconds=$TimeoutSeconds;MaxTokens=$(if ($Mode -eq 'Thinking') {2048} else {512});SystemPrompt=$prompt;Note='Synthetic public-only counterfactual positions, no other permanents or abilities. Expected strategic preferences are separate from forced lethal-defense checks. Schema enum order can be reversed independently of payload option order.'} | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $run 'run.json') -Encoding UTF8
$fixtures | ConvertTo-Json -Depth 25 | Set-Content (Join-Path $run 'fixtures.json') -Encoding UTF8
$results = @(); $failures = 0
foreach ($fixture in $fixtures) {
 $payload = $fixture.Payload
 $optionEnum=@($payload.options.optionId)
 if ($SchemaOptionOrder -eq 'Reverse') {[array]::Reverse($optionEnum)}
 $schema = [ordered]@{type='object';additionalProperties=$false;properties=[ordered]@{decisionId=[ordered]@{type='string';enum=@($payload.decisionId)};stateFingerprint=[ordered]@{type='string';enum=@($payload.stateFingerprint)};optionId=[ordered]@{type='string';enum=$optionEnum}};required=@('decisionId','stateFingerprint','optionId')}
 if ($IdentityFieldOrder -eq 'FingerprintFirst') {$schema.properties=[ordered]@{stateFingerprint=[ordered]@{type='string';enum=@($payload.stateFingerprint)};decisionId=[ordered]@{type='string';enum=@($payload.decisionId)};optionId=[ordered]@{type='string';enum=$optionEnum}}}
 $body = @{model=$Model;stream=$false;temperature=0;max_tokens=$(if ($Mode -eq 'Thinking') {2048} else {512});reasoning_effort=$(if ($Mode -eq 'Thinking') {'low'} else {'none'});messages=@(@{role='system';content=$prompt},@{role='user';content=(Get-CombatDiagnosticUserText $payload $InputFormat)});response_format=@{type='json_schema';json_schema=@{name='forge_ai_decision';strict=$true;schema=$schema}}}
 if ($ResponseFormat -eq 'JSON_OBJECT') {
  $body.response_format = @{type='json_object'}
 }
 $watch = [Diagnostics.Stopwatch]::StartNew()
 $selected = $null; $errorName = $null; $finishReason = $null; $reasoningChars = 0; $completionTokens = $null
 try {
  $response = Invoke-CombatDiagnosticRequest $body $Endpoint $TimeoutSeconds (Join-Path $run 'requests.jsonl')
  $finishReason = $response.choices[0].finish_reason
  $reasoningChars = ([string]$response.choices[0].message.reasoning).Length + ([string]$response.choices[0].message.reasoning_content).Length
  $completionTokens = $response.usage.completion_tokens
  if ($finishReason -eq 'length') {throw 'Response truncated'}
  $selected = $response.choices[0].message.content | ConvertFrom-Json
  Assert-CombatDiagnosticDecision $selected $payload
 } catch { $errorName = $_.Exception.GetType().Name + ': ' + $_.Exception.Message; $failures++; $selected=$null }
 $watch.Stop()
 $results += [pscustomobject]@{Case=$fixture.Case;Order=$fixture.Order;GradeType=$fixture.GradeType;Expected=$fixture.ExpectedOptionId;Selected=$(if ($selected) {$selected.optionId} else {$null});MatchesExpectation=($null -ne $selected -and $selected.optionId -eq $fixture.ExpectedOptionId);LatencyMs=$watch.ElapsedMilliseconds;Error=$errorName;FinishReason=$finishReason;ReasoningChars=$reasoningChars;CompletionTokens=$completionTokens}
 $results | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $run 'results.json') -Encoding UTF8
 Write-Output "$Mode $($fixture.Case) $($fixture.Order): $($results[-1].Selected) matches=$($results[-1].MatchesExpectation) latency=$($watch.ElapsedMilliseconds) error=$errorName"
 if ($failures -ge 3) { Write-Output 'Stopped after three response failures'; break }
}
$gradeGroups=@($results | Group-Object GradeType | ForEach-Object {[pscustomobject]@{GradeType=$_.Name;Cases=$_.Count;Matches=@($_.Group | Where-Object MatchesExpectation).Count;ResponseFailures=@($_.Group | Where-Object Error).Count}})
$gradeGroups | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $run 'grade-summary.json') -Encoding UTF8
Write-Output "Output directory: $run"

