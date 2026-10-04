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
    [int] $TimeoutSeconds = 180,
    [string] $OutputDirectory = ".\commander-evaluation",
    [switch] $EnableExternalAi,
    [string] $Model = "",
    [string] $Endpoint = "http://localhost:11434/v1/chat/completions",
    [int] $ExternalAiTimeoutSeconds = 20,
    [int] $MainPhaseMaxActions = 3,
    [int] $StackResponseMaxActions = 3,
    [int] $CombatAttackersMaxOptions = 4
)

$ErrorActionPreference = "Stop"

$forgeExePath = (Resolve-Path -LiteralPath $ForgeExe).Path
$forgeWorkingDirectory = Split-Path -Parent $forgeExePath
$outputDirectoryPath = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $outputDirectoryPath | Out-Null

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
    Invoke-CommanderCohort ($SeedStart + $offset)
}

Write-Host "Commander cohorts written to $outputDirectoryPath"
