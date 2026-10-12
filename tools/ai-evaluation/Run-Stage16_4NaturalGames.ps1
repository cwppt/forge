[CmdletBinding()]
param(
    [string]$Model = 'qwen3:8b',
    [string]$Endpoint = 'http://127.0.0.1:11434/v1/chat/completions',
    [long[]]$Seeds = @(16411,16412),
    [ValidateSet('All','Aurochs','Surge')][string]$Matchup = 'All',
    [ValidateSet('Both','Control','External')][string]$Mode = 'Both',
    [ValidateSet(0,1)][int[]]$ExternalSeats = @(0),
    [ValidateRange(1,300)][int]$TimeoutSeconds = 60,
    [ValidateRange(1,86400)][int]$GameTimeoutSeconds = 600,
    [switch]$RequestDiagnostics,
    [switch]$DryRun,
    [switch]$PassThru
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$desktop = Join-Path $repo 'forge-gui-desktop'
$cohorts = @(
    @{Name='Aurochs'; Decks=@('Burial','Aurochs Stampede')},
    @{Name='Surge'; Decks=@('Burial','Selesnya Surge')}
)
if ($Matchup -ne 'All') { $cohorts = @($cohorts | Where-Object { $_.Name -eq $Matchup }) }
if (@($Seeds | Select-Object -Unique).Count -ne $Seeds.Count) { throw 'Seeds must be unique.' }
if (@($ExternalSeats | Select-Object -Unique).Count -ne $ExternalSeats.Count) { throw 'Seats must be unique.' }
$modes = @('Control','External'); if ($Mode -ne 'Both') { $modes = @($Mode) }
$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
$output = Join-Path $repo "ai-eval\stage16_4\natural-$runId"
$classPath = 'target/classes;../forge-ai/target/classes;../forge-gui/target/classes;../forge-game/target/classes;../forge-core/target/classes;target/*'
foreach ($cohort in $cohorts) { foreach ($deck in $cohort.Decks) {
    if (-not (Test-Path -LiteralPath (Join-Path $repo "forge-gui\res\quest\precons\$deck.dck"))) { throw "Missing deck: $deck" }
} }
if (-not $DryRun) {
    $java = (Get-Command java.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    New-Item -ItemType Directory -Path $output | Out-Null
    [ordered]@{RunId=$runId; Model=$Model; Endpoint=$Endpoint; Seeds=$Seeds; Mode=$Mode; ExternalSeats=$ExternalSeats;
        Cohorts=$cohorts; RequestDiagnostics=[bool]$RequestDiagnostics; GameTimeoutSeconds=$GameTimeoutSeconds;
        TimeoutSeconds=$TimeoutSeconds; LegacyCombatOrder=$true; ExperimentalGuards=$false; Kind='TARGETED_NATURAL_PAIRWISE_RECOVERY';
        MainPhaseOnly=$true; ExpectedGameCount=($cohorts.Count * $Seeds.Count * $ExternalSeats.Count * $modes.Count)} |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $output 'run.json') -Encoding UTF8
}
foreach ($cohort in $cohorts) { foreach ($seed in $Seeds) { foreach ($seat in $ExternalSeats) { foreach ($runMode in $modes) {
    $label = "$($cohort.Name)-$seed-seat$seat-$runMode"
    $prefix = Join-Path $output $label
    $external = ''; if ($runMode -eq 'External') { $external = "$seat" }
    $arguments = @('-Dforge.ai.fullGame.audit=true',"-Dforge.ai.fullGame.runId=$label", "-Dforge.ai.fullGame.externalSeats=$external",
        '-Dforge.ai.external.combat.materialGuard=false','-Dforge.ai.external.combat.materialGuardRaceAware=false')
    if ($RequestDiagnostics) {
        $arguments += @("-Dforge.ai.combat.requestCapture=$prefix.requests.jsonl")
    }
    $arguments += @('-cp',$classPath,'forge.view.Main','sim','-D',(Join-Path $repo 'forge-gui\res\quest\precons'),'-d')
    foreach ($deck in $cohort.Decks) { $arguments += "$deck.dck" }
    $arguments += @('-n','1','-s',"$seed",'-c',"$GameTimeoutSeconds",'-a','Default','Default','--legacy-order-combatants',
        '--external-ai-combat-integrated-disabled','--external-ai-model',$Model,'--external-ai-endpoint',$Endpoint,
        '--external-ai-timeout',"$TimeoutSeconds",'--external-ai-response-format','JSON_SCHEMA',
        '--metrics-csv',"$prefix.csv",'--decision-audit-jsonl',"$prefix.jsonl")
    if ($RequestDiagnostics) { $arguments += '--external-ai-request-diagnostics' }
    if ($runMode -eq 'External') {
        $arguments += @('--external-ai-main-phase-enabled','--external-ai-seats',"$seat",
            '--external-ai-main-phase-max-actions',3,
            '--external-ai-full-game-disabled','--external-ai-mulligan-disabled',
            '--external-ai-stack-response-disabled','--external-ai-combat-attackers-disabled',
            '--external-ai-combat-blockers-disabled','--external-ai-combat-damage-order-disabled',
            '--external-ai-combat-action-disabled','--external-ai-memory-disabled')
    } else {
        $arguments += @('--external-ai-main-phase-disabled','--external-ai-seats',
            '--external-ai-main-phase-max-actions',3,
            '--external-ai-full-game-disabled','--external-ai-mulligan-disabled',
            '--external-ai-stack-response-disabled','--external-ai-combat-attackers-disabled',
            '--external-ai-combat-blockers-disabled','--external-ai-combat-damage-order-disabled',
            '--external-ai-combat-action-disabled','--external-ai-memory-disabled')
    }
    $quoted = $arguments | ForEach-Object { '"' + (($_ -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"' }
    if ($DryRun) { Write-Output ($quoted -join ' '); continue }
    Write-Host "Stage 16.4 $label"
    $start = Get-Date
    $process = Start-Process -FilePath $java -ArgumentList ($quoted -join ' ') -WorkingDirectory $desktop `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput "$prefix.stdout.log" -RedirectStandardError "$prefix.stderr.log"
    $watchdog = -not $process.WaitForExit(($GameTimeoutSeconds + 120) * 1000)
    if ($watchdog) { $process.Kill(); $process.WaitForExit() }
    $process.Refresh()
    $timedOut = $watchdog -or [bool](Select-String -LiteralPath "$prefix.stdout.log" -Pattern 'Stopping slow match as draw' -Quiet)
    $crash = $process.ExitCode -ne 0 -or [bool](Select-String -LiteralPath "$prefix.stderr.log" -Pattern '^\s*(?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*(?:Exception|Error)(?::|$)' -Quiet)
    $finished = [bool](Select-String -LiteralPath "$prefix.stdout.log" -Pattern 'Game Result:' -Quiet)
    [ordered]@{Label=$label; Matchup=$cohort.Name; Seed=$seed; ExternalSeat=$seat; Mode=$runMode;
        Decks=$cohort.Decks; ExitCode=$process.ExitCode; TimedOut=$timedOut; Crash=$crash; Completed=$finished -and -not $timedOut -and -not $crash;
        WallTimeMs=((Get-Date)-$start).TotalMilliseconds} | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath "$prefix.run.json" -Encoding UTF8
    if ($timedOut -or $crash -or -not $finished) {
        throw "Stage 16.4 integration failure in $label; evidence preserved in $output. Review before continuing."
    }
    if (-not (Select-String -LiteralPath "$prefix.jsonl" -Pattern 'FULL_GAME_SUMMARY' -Quiet)) { throw "Missing full-game summary: $prefix" }
} } } }
if (-not $DryRun) {
    Write-Host "Stage 16.4 evidence: $output"
    if ($PassThru) { Write-Output $output }
}
