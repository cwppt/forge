# Windows PowerShell 5.1 launcher. Task Scheduler owns the worker lifetime.
[CmdletBinding()]
param(
    [ValidateRange(1,1000)][int]$GamesPerVariant = 25,
    [ValidateRange(1,2147480000)][int]$Seed = 2400,
    [ValidateRange(0,30000)][int]$MaxAttemptsPerVariant = 0,
    [ValidateRange(30,3600)][int]$TimeoutSeconds = 180,
    [ValidateRange(1,16)][int]$MaxParallelGames = 1,
    [ValidateSet('Standard','BoundedAdvisory')][string]$AiProfile = 'Standard',
    [string]$ExperimentSpec,
    [ValidateSet('Legacy','R3-R4-A')][string]$CandidateSet = 'Legacy',
    [switch]$PreviewOnly,
    [switch]$PublishOnCompletion,
    [switch]$Resume,
    [string]$PythonExe = 'C:\Python312\python.exe',
    [switch]$PublicationOnly
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$store = Join-Path $root 'work\commander-batches'
$requestPath = Join-Path $store "seed$Seed\request.json"
$runnerVersion = 'parallel-v4'
if (Test-Path -LiteralPath $requestPath) {
    $savedRequest = Get-Content -LiteralPath $requestPath -Raw | ConvertFrom-Json
    $runnerVersion = 'serial'
    if ($savedRequest.PSObject.Properties.Name -contains 'runnerVersion') { $runnerVersion = $savedRequest.runnerVersion }
}
if ($runnerVersion -notin @('parallel-v3','parallel-v4')) {
    if ($AiProfile -ne 'Standard') { throw 'Existing batches must keep their saved AI profile.' }
    $PSBoundParameters.Remove('AiProfile') | Out-Null
}
if ($runnerVersion -ne 'parallel-v4') {
    foreach ($key in @('ExperimentSpec','CandidateSet','PreviewOnly')) {
        if ($PSBoundParameters.ContainsKey($key)) { throw 'Explicit experiment configuration options require a new v4 batch; existing batches retain their frozen configuration.' }
    }
}
if ($runnerVersion -in @('parallel-v2','parallel-v3','parallel-v4')) {
    & (Join-Path $PSScriptRoot "$runnerVersion\Start-CommanderBatch.ps1") @PSBoundParameters
    return
}
if ($runnerVersion -ne 'serial') { throw "Unknown saved runner version: $runnerVersion" }
if ($MaxParallelGames -ne 1) { throw 'Existing serial batches must retain concurrency 1.' }

if ($MaxAttemptsPerVariant -eq 0) { $MaxAttemptsPerVariant = $GamesPerVariant * 3 }
if ($MaxAttemptsPerVariant -lt $GamesPerVariant) { throw 'Attempt limit must be at least the valid-game target.' }
if (-not (Test-Path -LiteralPath $PythonExe -PathType Leaf)) { throw "Python not found: $PythonExe" }
$sha = [Security.Cryptography.SHA256]::Create()
$suffix = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($root)))).Replace('-','').Substring(0,12)
$taskName = "ForgeCommander-$suffix"
New-Item -ItemType Directory -Path $store -Force | Out-Null
# Prevent simultaneous launchers from registering/replacing the same task.
try { $launchLock = [IO.File]::Open((Join-Path $store 'launch.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
catch { throw 'Another launcher is active. Check status before retrying.' }
try {
    $service = Get-Service Schedule -ErrorAction Stop
    if ($service.Status -ne 'Running') { throw 'Task Scheduler is not running.' }
    $existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($existing) {
        $workerScript = Join-Path $PSScriptRoot 'Invoke-CommanderBatchWorker.ps1'
        $directWorker = Join-Path $PSScriptRoot 'CommanderBatch.py'
        $v2Worker = Join-Path $PSScriptRoot 'parallel-v2\CommanderBatch.py'
        $v3Worker = Join-Path $PSScriptRoot 'parallel-v3\CommanderBatch.py'
        $v4Worker = Join-Path $PSScriptRoot 'parallel-v4\CommanderBatch.py'
        if (-not (($existing.Actions.Arguments -like "*$workerScript*") -or ($existing.Actions.Arguments -like "*$directWorker*") -or ($existing.Actions.Arguments -like "*$v2Worker*") -or ($existing.Actions.Arguments -like "*$v3Worker*") -or ($existing.Actions.Arguments -like "*$v4Worker*"))) { throw 'Task name is occupied by an unrelated action.' }
        if ($existing.State -eq 'Running' -or $existing.State -eq 'Queued') { throw 'A Commander batch is already active. Use the status command.' }
    }
    # A worker started outside Task Scheduler must also prevent a second launch.
    $probe = & $PythonExe -c "import sys; sys.path.insert(0, sys.argv[1]); import CommanderBatch as c; lock=c.WorkerLock(); lock.__enter__(); lock.__exit__()" $PSScriptRoot 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Worker lock unavailable: $probe" }
    $batch = Join-Path $store "seed$Seed"
    $mode = 'run'
    if ($PublicationOnly) {
        if (-not (Test-Path -LiteralPath (Join-Path $batch 'payload-hashes.json'))) { throw 'No validated publication snapshot for this seed.' }
        $mode = 'publish'
    } else {
        $arguments = @((Join-Path $PSScriptRoot 'CommanderBatch.py'), 'prepare', '--games', "$GamesPerVariant", '--seed', "$Seed", '--max-attempts', "$MaxAttemptsPerVariant", '--timeout', "$TimeoutSeconds")
        if ($PublishOnCompletion) { $arguments += '--publish' }
        if ($Resume) { $arguments += '--resume' }
        $prepared = & $PythonExe @arguments 2>&1
        if ($LASTEXITCODE -ne 0) { throw "Batch preparation failed: $prepared" }
        $batch = ($prepared | ConvertFrom-Json).batch
    }
    $pythonWindowless = Join-Path (Split-Path -Parent $PythonExe) 'pythonw.exe'
    if (-not (Test-Path -LiteralPath $pythonWindowless)) { throw 'The windowless Python interpreter pythonw.exe is required for scheduled execution.' }
    $worker = Join-Path $PSScriptRoot 'CommanderBatch.py'
    foreach ($value in @($worker,$batch,$pythonWindowless)) { if ($value.Contains('"')) { throw 'Quotation marks are not supported in paths.' } }
    $action = New-ScheduledTaskAction -Execute $pythonWindowless -Argument ('-u "{0}" {1} --batch "{2}"' -f $worker,$mode,$batch) -WorkingDirectory $root
    $principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
    $hours = [Math]::Ceiling((3 * $MaxAttemptsPerVariant * ($TimeoutSeconds + 125) + 1800) / 3600)
    $settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours $hours) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    $task = New-ScheduledTask -Action $action -Principal $principal -Settings $settings -Description 'Forge standard-AI Commander runner; explicit on-demand execution; no periodic trigger.'
    Register-ScheduledTask -TaskName $taskName -InputObject $task -Force | Out-Null
    $diagnostics = Join-Path $batch 'diagnostics'
    New-Item -ItemType Directory -Path $diagnostics -Force | Out-Null
    $launchId = [Guid]::NewGuid().ToString('N')
    Export-ScheduledTask -TaskName $taskName | Set-Content -LiteralPath (Join-Path $diagnostics "task-launch-$launchId.xml") -Encoding UTF8
    [IO.File]::WriteAllText((Join-Path $diagnostics "task-launch-$launchId.json"), (@{ utc=[DateTime]::UtcNow.ToString('o'); task=$taskName; action=$pythonWindowless; mode=$mode; launcherPid=$PID; user=$principal.UserId } | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText((Join-Path $store 'scheduler.json'), (@{ taskName=$taskName; batch=$batch; mode=$mode } | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
    Start-ScheduledTask -TaskName $taskName
    [pscustomobject]@{ Task=$taskName; Seed=$Seed; Mode=$mode; Results=$batch; State='Submitted to Task Scheduler' }
} finally { $launchLock.Dispose() }
