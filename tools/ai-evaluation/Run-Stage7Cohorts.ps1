param(
    [Parameter(Mandatory = $true)] [string] $ForgeExe,
    [Parameter(Mandatory = $true)] [string] $Deck1,
    [Parameter(Mandatory = $true)] [string] $Deck2,
    [string] $Model = "",
    [string] $Endpoint = "http://localhost:11434/v1/chat/completions",
    [int] $GamesPerSeed = 20,
    [long] $SeedStart = 1000,
    [int] $SeedCount = 1,
    [int] $MaxActions = 3,
    [string] $OutputDirectory = ".\stage7-evaluation"
)

$ErrorActionPreference = "Stop"
$forgeExePath = (Resolve-Path -LiteralPath $ForgeExe).Path
$forgeWorkingDirectory = Split-Path -Parent $forgeExePath
$outputDirectoryPath = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $outputDirectoryPath | Out-Null

function Invoke-Cohort {
    param([string] $Name, [long] $RunSeed, [string[]] $ExternalArguments)
    $prefix = Join-Path $outputDirectoryPath "$Name-seed$RunSeed"
    $arguments = @("sim", "-d", $Deck1, $Deck2, "-n", "$GamesPerSeed", "-s", "$RunSeed", "-q",
        "--metrics-csv", "$prefix.csv") + $ExternalArguments
    if ($ExternalArguments -contains "--external-ai-main-phase-enabled") {
        $arguments += @("--decision-audit-jsonl", "$prefix.jsonl")
    }
    Push-Location -LiteralPath $forgeWorkingDirectory
    try {
        $LASTEXITCODE = $null
        & $forgeExePath @arguments 2>&1 | Tee-Object -FilePath "$prefix.log"
        # The packaged Windows GUI launcher may not populate LASTEXITCODE on success.
        $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { $LASTEXITCODE }
    } finally {
        Pop-Location
    }
    if ($exitCode -ne 0) { throw "$Name exited with code $exitCode" }
}

$transport = @("--external-ai-endpoint", $Endpoint, "--external-ai-model", $Model,
    "--external-ai-timeout", "20")
for ($offset = 0; $offset -lt $SeedCount; $offset++) {
    $runSeed = $SeedStart + $offset
    Invoke-Cohort "A-forge-only" $runSeed @()
    Invoke-Cohort "B-external-mulligan" $runSeed (@("--external-ai-mulligan-enabled") + $transport)
    Invoke-Cohort "C-external-main" $runSeed (@("--external-ai-main-phase-enabled",
        "--external-ai-main-phase-max-actions", "$MaxActions") + $transport)
    Invoke-Cohort "D-external-both" $runSeed (@("--external-ai-mulligan-enabled",
        "--external-ai-main-phase-enabled", "--external-ai-main-phase-max-actions", "$MaxActions") + $transport)
    Invoke-Cohort "E-forced-failure" $runSeed @("--external-ai-main-phase-enabled",
        "--external-ai-main-phase-max-actions", "$MaxActions", "--external-ai-endpoint",
        "http://127.0.0.1:1/v1/chat/completions", "--external-ai-model", $Model,
        "--external-ai-timeout", "1")
}

Write-Host "Cohorts written to $outputDirectoryPath. Repeat with Deck1/Deck2 reversed for mirrored seating."
