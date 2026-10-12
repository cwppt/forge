param(
    [ValidateSet('Gatecrasher', 'Aurochs', 'All')][string]$Matchup = 'All',
    [ValidateSet('Both', 'Forge', 'Seat0', 'Seat1')][string]$ExternalMode = 'Both',
    [int[]]$Seeds,
    [string]$Model = 'qwen3:8b',
    [string]$Endpoint = 'http://localhost:11434/v1/chat/completions',
    [ValidateRange(1, 3600)][int]$TimeoutSeconds = 60,
    [ValidateRange(1, 86400)][int]$GameTimeoutSeconds = 1800,
    [ValidateRange(1, 8)][int]$AttackerMaxOptions = 4,
    [ValidateRange(1, 8)][int]$BlockerMaxOptions = 4,
    [ValidateRange(1, 12)][int]$DamageOrderMaxOptions = 6,
    [switch]$ExperimentalMaterialGuard,
    [switch]$ExperimentalRaceAwareMaterialGuard,
    [switch]$CaptureCombatRequests,
    [switch]$SeededBlockerIds,
    [switch]$SeededCombatIds,
    [switch]$PassThru,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$materialPolicy = if ($ExperimentalRaceAwareMaterialGuard) { 'RaceAwareV2' } elseif ($ExperimentalMaterialGuard) { 'LegacyV1' } else { 'Off' }
$materialEnabled = [bool]($ExperimentalMaterialGuard -or $ExperimentalRaceAwareMaterialGuard)
$repoDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$desktopDirectory = Join-Path $repoDirectory 'forge-gui-desktop'
$cohorts = @(
    @{ Name = 'Gatecrasher'; Decks = @('Gatecrasher', 'Air Forces'); Seeds = @(1000..1009) },
    @{ Name = 'Aurochs'; Decks = @('Aurochs Stampede', 'Selesnya Surge'); Seeds = @(101..120) }
)
if ($Matchup -ne 'All') { $cohorts = @($cohorts | Where-Object { $_.Name -eq $Matchup }) }
if ($Seeds -and ($Seeds | Select-Object -Unique).Count -ne $Seeds.Count) { throw 'Seeds must be unique.' }
$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$outputPath = Join-Path $repoDirectory "ai-eval\stage12\$runId"
if (-not $DryRun) {
    if (-not (Test-Path -LiteralPath (Join-Path $desktopDirectory 'target\classes\forge\view\Main.class'))) {
        throw 'Compile the desktop classes before running this cohort.'
    }
    $javaExecutable = (Get-Command java.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    New-Item -ItemType Directory -Path $outputPath | Out-Null
    [ordered]@{ RunId = $runId; Model = $Model; Endpoint = $Endpoint; Matchup = $Matchup; ExternalMode = $ExternalMode;
        MaterialGuardPolicy = $materialPolicy; SeededCombatIds = [bool]$SeededCombatIds; SeededBlockerIds = [bool]$SeededBlockerIds; CaptureCombatRequests = [bool]$CaptureCombatRequests; ExperimentalMaterialGuard = $materialEnabled; Seeds = $Seeds; TimeoutSeconds = $TimeoutSeconds; GameTimeoutSeconds = $GameTimeoutSeconds;
        AttackerMaxOptions = $AttackerMaxOptions; BlockerMaxOptions = $BlockerMaxOptions;
        DamageOrderMaxOptions = $DamageOrderMaxOptions; AiProfiles = @('Default', 'Default'); Cohorts = $cohorts } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $outputPath 'run.json') -Encoding UTF8
}
$classpath = 'target/classes;../forge-ai/target/classes;../forge-gui/target/classes;../forge-game/target/classes;../forge-core/target/classes;target/*'
foreach ($cohort in $cohorts) {
    $selectedSeeds = if ($Seeds) { $Seeds } else { $cohort.Seeds }
    foreach ($seed in $selectedSeeds) {
        $name = "$($cohort.Name)-$seed"
        if ($ExternalMode -ne 'Both') { $name += "-$ExternalMode" }
        $prefix = Join-Path $outputPath $name
        $arguments = @('-cp', $classpath, 'forge.view.Main', 'sim', '-D',
            (Join-Path $repoDirectory 'forge-gui\res\quest\precons'), '-d')
        if ($SeededCombatIds) { $arguments = @("-Dforge.ai.external.combat.seededIds=true") + $arguments }
        if ($SeededBlockerIds) { $arguments = @("-Dforge.ai.external.combat.seededBlockerIds=true") + $arguments }
        if ($CaptureCombatRequests) { $arguments = @("-Dforge.ai.combat.requestCapture=$prefix.requests.jsonl") + $arguments }
        if ($materialEnabled) { $arguments = @('-Dforge.ai.external.combat.materialGuard=true') + $arguments }
        if ($ExperimentalRaceAwareMaterialGuard) { $arguments = @('-Dforge.ai.external.combat.materialGuardRaceAware=true') + $arguments }
        foreach ($deck in $cohort.Decks) {
            $deckPath = Join-Path $repoDirectory "forge-gui\res\quest\precons\$deck.dck"
            if (-not (Test-Path -LiteralPath $deckPath)) { throw "Missing deck: $deckPath" }
            $arguments += "$deck.dck"
        }
        $arguments += @('-n', '1', '-s', "$seed", '-c', "$GameTimeoutSeconds", '-a', 'Default', 'Default',
            '--legacy-order-combatants', '--external-ai-combat-integrated-enabled',
            '--external-ai-mulligan-disabled', '--external-ai-main-phase-disabled',
            '--external-ai-stack-response-disabled',
            '--external-ai-combat-attackers-max-options', "$AttackerMaxOptions",
            '--external-ai-combat-blockers-max-options', "$BlockerMaxOptions",
            '--external-ai-combat-damage-order-max-options', "$DamageOrderMaxOptions",
            '--external-ai-model', $Model, '--external-ai-endpoint', $Endpoint,
            '--external-ai-timeout', "$TimeoutSeconds", '--external-ai-response-format', 'JSON_SCHEMA',
            '--metrics-csv', "$prefix.csv", '--decision-audit-jsonl', "$prefix.jsonl")
        if ($ExternalMode -ne 'Both') {
            $arguments += '--external-ai-seats'
            if ($ExternalMode -eq 'Seat0') { $arguments += '0' }
            if ($ExternalMode -eq 'Seat1') { $arguments += '1' }
        }
        # Windows PowerShell 5.1 joins ArgumentList; preserve spaces, quotes and trailing backslashes.
        $quotedArguments = $arguments | ForEach-Object {
            '"' + (($_ -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
        }
        if ($DryRun) { Write-Output ($quotedArguments -join ' '); continue }
        Write-Host "Stage 12 $($cohort.Name) seed $seed; outputs: $prefix"
        $process = Start-Process -FilePath $javaExecutable -ArgumentList ($quotedArguments -join ' ') `
            -WorkingDirectory $desktopDirectory -WindowStyle Hidden -Wait -PassThru `
            -RedirectStandardOutput "$prefix.stdout.log" -RedirectStandardError "$prefix.stderr.log"
        if ($process.ExitCode -ne 0) { throw "Seed $seed exited with code $($process.ExitCode); inspect $prefix.stderr.log" }
        # Forge can catch a simulation exception and still exit zero. Require its result and exports.
        if (-not (Select-String -LiteralPath "$prefix.stdout.log" -Pattern 'Game Result:' -Quiet)) {
            throw "Seed $seed did not report a game result; inspect its logs."
        }
        if (-not (Test-Path -LiteralPath "$prefix.csv") -or -not (Test-Path -LiteralPath "$prefix.jsonl")) {
            throw "Seed $seed did not export both evaluation files."
        }
        if (Select-String -LiteralPath "$prefix.stderr.log" -Pattern '^\s*(?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*(?:Exception|Error)(?::|$)' -Quiet) {
            throw "Seed $seed reported an engine exception despite a zero process exit code; inspect $prefix.stderr.log."
        }
        if (Select-String -LiteralPath "$prefix.stdout.log" -Pattern 'Stopping slow match as draw' -Quiet) {
            throw "Seed $seed reached the game time limit; partial evidence is preserved at $prefix."
        }
    }
}
if (-not $DryRun) {
    Write-Host "Stage 12 outputs saved to $outputPath"
    if ($PassThru) { Write-Output $outputPath }
}




