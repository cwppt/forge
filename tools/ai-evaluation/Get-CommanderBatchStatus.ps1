[CmdletBinding()]
param([int]$Seed=0, [switch]$Detailed)
$ErrorActionPreference = 'Stop'
function Convert-HeartbeatUtc($value) {
    if ($value -is [DateTime]) { return $value.ToUniversalTime() }
    return [DateTimeOffset]::Parse([string]$value, [Globalization.CultureInfo]::InvariantCulture).UtcDateTime
}
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$store = Join-Path $root 'work\commander-batches'
if ($Seed -gt 0) { $batch = Join-Path $store "seed$Seed" }
else {
    if (-not (Test-Path -LiteralPath (Join-Path $store 'latest.json'))) { Write-Output 'No unattended batch has been created.'; return }
    $batch = (Get-Content -LiteralPath (Join-Path $store 'latest.json') -Raw | ConvertFrom-Json).batch
}
$status = Get-Content -LiteralPath (Join-Path $batch 'status.json') -Raw | ConvertFrom-Json
if (Test-Path -LiteralPath (Join-Path $batch 'heartbeat.json')) {
    $heartbeat = Get-Content -LiteralPath (Join-Path $batch 'heartbeat.json') -Raw | ConvertFrom-Json
    if ((Convert-HeartbeatUtc $heartbeat.heartbeatUtc) -gt (Convert-HeartbeatUtc $status.heartbeatUtc)) { $status.heartbeatUtc = $heartbeat.heartbeatUtc }
}
$status | Add-Member -NotePropertyName HeartbeatAgeSeconds -NotePropertyValue ([Math]::Round(([DateTime]::UtcNow - (Convert-HeartbeatUtc $status.heartbeatUtc)).TotalSeconds))
if (Test-Path -LiteralPath (Join-Path $store 'scheduler.json')) {
    $scheduler = Get-Content -LiteralPath (Join-Path $store 'scheduler.json') -Raw | ConvertFrom-Json
    if ($scheduler.batch -eq $batch) {
        try {
            $task = Get-ScheduledTask -TaskName $scheduler.taskName -ErrorAction Stop
            $info = Get-ScheduledTaskInfo -TaskName $scheduler.taskName -ErrorAction Stop
            $workerExit = $null
            if ($task.State -ne 'Running' -and $task.State -ne 'Queued') { $workerExit = $info.LastTaskResult }
            $status | Add-Member -NotePropertyName Scheduler -NotePropertyValue ([pscustomobject]@{ Task=$scheduler.taskName; State=[string]$task.State; WorkerExitCode=$workerExit; LastTaskResultCode=$info.LastTaskResult })
            if ($task.State -notin @('Running','Queued') -and $status.state -in @('RUNNING','VALIDATING','PUBLISHING')) {
                $status | Add-Member -NotePropertyName LastRecordedState -NotePropertyValue $status.state
                $status.state = 'WORKER_STOPPED'
                if ($workerExit -eq 3221225786) { Write-Warning 'Worker exited with 0xC000013A (console-control termination). This is separate from a Forge game failure.' }
            }
        } catch { $status | Add-Member -NotePropertyName Scheduler -NotePropertyValue 'Unavailable in this session; disk status remains readable.' }
    }
}
if ($status.state -in @('RUNNING','VALIDATING','PUBLISHING') -and $status.PSObject.Properties.Name -contains 'workerPid') {
    $worker = Get-Process -Id $status.workerPid -ErrorAction SilentlyContinue
    if (-not $worker) {
        $status | Add-Member -NotePropertyName LastRecordedState -NotePropertyValue $status.state -Force
        $status.state = 'WORKER_STOPPED'
    }
}
$request = Get-Content -LiteralPath (Join-Path $batch 'request.json') -Raw | ConvertFrom-Json
$concurrency = 1
if ($request.PSObject.Properties.Name -contains 'parallelGames') { $concurrency = $request.parallelGames }
$status | Add-Member -NotePropertyName ConfiguredConcurrency -NotePropertyValue $concurrency
$aiProfile = 'standard'
if ($request.PSObject.Properties.Name -contains 'aiProfile') { $aiProfile = $request.aiProfile }
$status | Add-Member -NotePropertyName ConfiguredAiProfile -NotePropertyValue $aiProfile
if (-not ($status.PSObject.Properties.Name -contains 'activeGames')) {
    $games = @()
    if ($status.state -eq 'RUNNING' -and $status.PSObject.Properties.Name -contains 'childPid') {
        if (Get-Process -Id $status.childPid -ErrorAction SilentlyContinue) {
            $games = @([pscustomobject]@{ variant=$status.activeVariant; seed=$status.activeSeed; pid=$status.childPid; elapsedSeconds=$status.currentAttemptSeconds; phase='RUNNING' })
        }
    }
    $status | Add-Member -NotePropertyName activeGames -NotePropertyValue $games
    $status | Add-Member -NotePropertyName activeGameCount -NotePropertyValue $games.Count
}
if (-not ($status.PSObject.Properties.Name -contains 'overall')) {
    $variantCount = 3
    if ($request.PSObject.Properties.Name -contains 'candidateSpecs') { $variantCount = $request.candidateSpecs.Count }
    $valid = ($status.variants | Measure-Object valid -Sum).Sum
    $status | Add-Member -NotePropertyName overall -NotePropertyValue ([pscustomobject]@{ valid=$valid; target=$variantCount*$request.target; remaining=$variantCount*$request.target-$valid; attempted=($status.variants | Measure-Object attempted -Sum).Sum; failed=($status.variants | Measure-Object failed -Sum).Sum })
}

if ($status.state -eq 'WORKER_STOPPED' -and $status.PSObject.Properties.Name -contains 'activeGames') {
    $status | Add-Member -NotePropertyName LastRecordedActiveGames -NotePropertyValue $status.activeGames
    $status.activeGames = @()
    $status.activeGameCount = 0
    if ($status.overall.PSObject.Properties.Name -contains 'running') { $status.overall.running = 0 }
    if ($status.PSObject.Properties.Name -contains 'inFlightGames') {
        $status | Add-Member -NotePropertyName LastRecordedInFlightGames -NotePropertyValue $status.inFlightGames
        $status.inFlightGames = @()
    }
    foreach ($variant in $status.variants) {
        if ($variant.PSObject.Properties.Name -contains 'running') { $variant.running = 0 }
        if ($variant.PSObject.Properties.Name -contains 'inFlight') { $variant.inFlight = 0 }
    }
}
$status | Format-List
if ($status.variants) { $status.variants | Format-Table -AutoSize }
if ($status.state -eq 'RUNNING' -and $status.childPid) { Write-Output 'Counts above describe completed attempts; the displayed active game is still in progress.' }
if ($status.state -in @('RUNNING','VALIDATING','PUBLISHING') -and $status.HeartbeatAgeSeconds -gt 60) {
    Write-Warning 'Heartbeat is stale. The worker may be interrupted; inspect scheduler state and logs before resuming.'
}
if ($status.PSObject.Properties.Name -contains 'activeGames' -and $status.activeGames) { $status.activeGames | Format-Table variant,seed,pid,elapsedSeconds,phase -AutoSize }
if ($Detailed) {
    foreach ($file in @('summary.json','publication.json','bootstrap-error.log')) {
        $path = Join-Path $batch $file
        if (Test-Path -LiteralPath $path) { Write-Output "${file}:"; Get-Content -LiteralPath $path }
    }
    $log = Join-Path $batch 'worker.log'
    if (Test-Path -LiteralPath $log) { Write-Output 'Recent worker log:'; Get-Content -LiteralPath $log -Tail 20 }
    $sessionLog = Get-ChildItem -LiteralPath $batch -Filter 'worker-session-*.log' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($sessionLog) { Write-Output 'Latest worker session:'; Get-Content -LiteralPath $sessionLog.FullName -Tail 20 }
    $lifecycle = Join-Path $batch 'lifecycle.jsonl'
    if (Test-Path -LiteralPath $lifecycle) { Write-Output 'Recent lifecycle events:'; Get-Content -LiteralPath $lifecycle -Tail 8 }
    Write-Output "Manifest: $(Join-Path $batch 'manifest.json')"
}
