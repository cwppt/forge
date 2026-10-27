param(
    [Parameter(Mandatory = $true)] [string] $ForgeExe,
    [Parameter(Mandatory = $true)] [string] $Deck1,
    [Parameter(Mandatory = $true)] [string] $Deck2,
    [Parameter(Mandatory = $true)] [string] $Deck3,
    [Parameter(Mandatory = $true)] [string] $Deck4,
    [string] $DeckDirectory = "",
    [int] $GamesPerSeed = 25,
    [long] $SeedStart = 2000,
    [int] $SeedCount = 4,
    [long[]] $ExcludedSeeds = @(),
    [int] $TimeoutSeconds = 180,
    [string] $OutputDirectory = ".\commander-evaluation",
    [switch] $EnableExternalAi,
    [string] $Model = "",
    [string] $Endpoint = "http://localhost:11434/v1/chat/completions",
    [int] $ExternalAiTimeoutSeconds = 20,
    [int] $MainPhaseMaxActions = 3,
    [int] $StackResponseMaxActions = 3,
    [int] $CombatAttackersMaxOptions = 4,
    [switch] $CaptureFailures,
    [switch] $CaptureRoleTelemetry,
    [ValidateRange(0, 10000)] [int] $ValidGames = 0,
    [ValidateRange(0, 30000)] [int] $MaxAttempts = 0,
    [ValidateRange(1, 25)] [int] $ReplacementBatchSize = 5,
    [string] $PythonExe = 'python'
)

$ErrorActionPreference = "Stop"

$forgeExePath = (Resolve-Path -LiteralPath $ForgeExe).Path
$forgeWorkingDirectory = Split-Path -Parent $forgeExePath
$outputDirectoryPath = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $outputDirectoryPath | Out-Null

if ($ValidGames -gt 0) {
    $configPath = Join-Path $outputDirectoryPath 'cohort-config.json'
    if (Test-Path -LiteralPath $configPath) { throw "Use a new output directory: $configPath exists." }
    $config = @{}
    foreach ($key in @('ForgeExe','Deck1','Deck2','Deck3','Deck4','DeckDirectory',
        'TimeoutSeconds','EnableExternalAi','Model','Endpoint','ExternalAiTimeoutSeconds',
        'MainPhaseMaxActions','StackResponseMaxActions','CombatAttackersMaxOptions','CaptureRoleTelemetry')) {
        $value = Get-Variable -Name $key -ValueOnly
        $config[$key] = if ($value -is [System.Management.Automation.SwitchParameter]) { [bool]$value } else { $value }
    }
    $config.ForgeExe = $forgeExePath
    if ($DeckDirectory) { $config.DeckDirectory = (Resolve-Path -LiteralPath $DeckDirectory).Path }
    $config.CaptureFailures = $true
    $config.ExcludedSeeds = @($ExcludedSeeds)
    $config | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding utf8
    if ($MaxAttempts -eq 0) { $MaxAttempts = $ValidGames * 3 }
    & $PythonExe (Join-Path $PSScriptRoot 'Run-ValidCommanderCohort.py') --config $configPath `
        --output $outputDirectoryPath --target $ValidGames --max-attempts $MaxAttempts `
        --batch-size $ReplacementBatchSize --seed-start $SeedStart
    if ($LASTEXITCODE -ne 0) { throw 'Valid-game cohort stopped; inspect cohort-summary.json and runner logs.' }
    return
}

function Invoke-CommanderCohort {
    param(
        [long] $RunSeed
    )

    $prefix = Join-Path $outputDirectoryPath "commander-seed$RunSeed"
    $arguments = @(
        "sim",
        "-d", $Deck1, $Deck2, $Deck3, $Deck4,
        "-n", "$GamesPerSeed",
        "-s", "$RunSeed",
        "-f", "Commander",
        "-c", "$TimeoutSeconds",
        "-q",
        "--metrics-csv", "$prefix.metrics.csv",
        "--decision-audit-jsonl", "$prefix.decisions.jsonl",
        "--game-results-jsonl", "$prefix.games.jsonl",
        "--game-log-jsonl", "$prefix.gamelog.jsonl"
    )
    if ($CaptureFailures) { $arguments += '--capture-simulation-failures' }
    if ($CaptureRoleTelemetry) { $arguments += @('--commander-role-jsonl', "$prefix.roles.jsonl") }
    foreach ($suffix in @('log','games.jsonl','gamelog.jsonl','metrics.csv','decisions.jsonl','roles.jsonl')) {
        if (Test-Path -LiteralPath "$prefix.$suffix") { throw "Refusing to overwrite $prefix.$suffix" }
    }

    if (-not [string]::IsNullOrWhiteSpace($DeckDirectory)) {
        $arguments += @("-D", $DeckDirectory)
    }

    if ($EnableExternalAi) {
        if ([string]::IsNullOrWhiteSpace($Model)) {
            throw "Model is required when -EnableExternalAi is set."
        }

        $arguments += @(
            "--external-ai-mulligan-enabled",
            "--external-ai-main-phase-enabled",
            "--external-ai-main-phase-max-actions", "$MainPhaseMaxActions",
            "--external-ai-stack-response-enabled",
            "--external-ai-stack-response-max-actions", "$StackResponseMaxActions",
            "--external-ai-combat-attackers-enabled",
            "--external-ai-combat-attackers-max-options", "$CombatAttackersMaxOptions",
            "--external-ai-endpoint", $Endpoint,
            "--external-ai-model", $Model,
            "--external-ai-timeout", "$ExternalAiTimeoutSeconds"
        )
    }

    Write-Host "Running Commander cohort seed $RunSeed..."
    Push-Location -LiteralPath $forgeWorkingDirectory
    try {
        $LASTEXITCODE = $null
        & $forgeExePath @arguments 2>&1 | Tee-Object -FilePath "$prefix.log"
        $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { $LASTEXITCODE }
    } finally {
        Pop-Location
    }

    if ($exitCode -ne 0) {
        throw "Commander cohort seed $RunSeed exited with code $exitCode"
    }
}

for ($offset = 0; $offset -lt $SeedCount; $offset++) {
    if (($SeedStart + $offset) -in $ExcludedSeeds) {
        Write-Host "Skipping explicitly excluded seed $($SeedStart + $offset)."
        continue
    }
    Invoke-CommanderCohort ($SeedStart + $offset)
}

Write-Host "Commander cohorts written to $outputDirectoryPath"
