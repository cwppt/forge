# Disposable status and launcher-guard fixtures; no simulation or publication.
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$store = Join-Path $root 'work\commander-batches'
$seed = 1900000000 + (Get-Random -Minimum 1 -Maximum 1000000)
$batch = Join-Path $store "seed$seed"
New-Item -ItemType Directory -Path $batch | Out-Null
@{target=1;parallelGames=5;runnerVersion='parallel-v3'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $batch 'request.json')
@{state='RUNNING';heartbeatUtc='2026-01-01T00:00:00Z';workerPid=2147483647;seed=$seed;results=$batch;concurrency=5;activeGameCount=1;activeGames=@(@{variant='R2';seed=$seed;pid=2147483646;elapsedSeconds=10;phase='RUNNING'});inFlightGames=@();overall=@{valid=0;target=3;remaining=3;running=1};variants=@(@{variant='R2';valid=0;attempted=1;failed=0;interrupted=0;running=1;inFlight=1;remaining=1})} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $batch 'status.json')
$before = (Get-FileHash -LiteralPath (Join-Path $batch 'status.json')).Hash
$output = & (Join-Path $root 'tools\ai-evaluation\Get-CommanderBatchStatus.ps1') -Seed $seed 3>&1 | Out-String
if ($output -notmatch 'WORKER_STOPPED' -or $output -notmatch 'ConfiguredConcurrency\s+: 5' -or $output -notmatch 'activeGameCount\s+: 0') { throw "Status fixture failed: $output" }
$after = (Get-FileHash -LiteralPath (Join-Path $batch 'status.json')).Hash
if ($before -ne $after) { throw 'Status command mutated its fixture' }
Write-Output 'PASS: stale status, five-worker display, cleared active games, read-only status'
# Mock scheduler reads; the launcher must reject an active worker before preparing.
function Get-Service { [pscustomobject]@{Status='Running'} }
function Get-ScheduledTask { [pscustomobject]@{State='Running';Actions=@([pscustomobject]@{Arguments=(Join-Path $root 'tools\ai-evaluation\CommanderBatch.py')})} }
$blocked=$false
try { & (Join-Path $root 'tools\ai-evaluation\Start-CommanderBatch.ps1') -Seed $seed -GamesPerVariant 1 -MaxParallelGames 16 } catch { if ($_.Exception.Message -match 'already active') { $blocked=$true } else { throw } }
if (-not $blocked) { throw 'Active-worker launch guard failed' }
if (Test-Path -LiteralPath (Join-Path $store 'seed1800000000')) { throw 'Launch guard created an experiment' }
Write-Output 'PASS: public v3 routing, concurrency16 and active-worker launch guard'
Write-Output "Preserved disposable status fixture: $batch"

# A saved v2 batch continues using v2, including its original limit.
@{target=1;parallelGames=5;runnerVersion='parallel-v2'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $batch 'request.json')
function Get-ScheduledTask { [pscustomobject]@{State='Running';Actions=@([pscustomobject]@{Arguments=(Join-Path $root 'tools\ai-evaluation\parallel-v3\CommanderBatch.py')})} }
$blocked=$false
try { & (Join-Path $root 'tools\ai-evaluation\Start-CommanderBatch.ps1') -Seed $seed -GamesPerVariant 1 -MaxParallelGames 5 -Resume } catch { if ($_.Exception.Message -match 'already active') { $blocked=$true } else { throw } }
if (-not $blocked) { throw 'v2 shared-task compatibility failed' }
$bounded=$false
try { & (Join-Path $root 'tools\ai-evaluation\Start-CommanderBatch.ps1') -Seed $seed -GamesPerVariant 1 -MaxParallelGames 8 -Resume } catch { $bounded=$true }
if (-not $bounded) { throw 'v2 concurrency bound was changed' }
Write-Output 'PASS: saved v2 routing, frozen five-game limit and shared-task compatibility'

# New profile is accepted for a new v3 request, and rejected for frozen v2.
$blocked=$false
try { & (Join-Path $root 'tools\ai-evaluation\Start-CommanderBatch.ps1') -Seed 1800000000 -GamesPerVariant 100 -MaxParallelGames 5 -AiProfile BoundedAdvisory -TimeoutSeconds 300 -CandidateSet R3-R4-A -ExperimentSpec (Join-Path $root 'tools\ai-evaluation\experiments\R3-vs-R4-A.json') } catch { if ($_.Exception.Message -match 'already active') { $blocked=$true } else { throw } }
if (-not $blocked) { throw 'Bounded advisory profile routing failed' }
$protected=$false
try { & (Join-Path $root 'tools\ai-evaluation\Start-CommanderBatch.ps1') -Seed $seed -GamesPerVariant 1 -MaxParallelGames 5 -AiProfile BoundedAdvisory -Resume } catch { if ($_.Exception.Message -match 'saved AI profile') { $protected=$true } else { throw } }
if (-not $protected) { throw 'Frozen v2 AI profile was not protected' }
Write-Output 'PASS: opt-in bounded profile dispatch and legacy AI-profile protection'
