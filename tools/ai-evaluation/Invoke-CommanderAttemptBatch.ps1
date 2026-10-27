param([string]$Config, [long]$Seed, [int]$Games, [string]$Directory)
$ErrorActionPreference = 'Stop'
$parameters = Get-Content -LiteralPath $Config -Raw | ConvertFrom-Json -AsHashtable
$parameters.SeedStart = $Seed
$parameters.SeedCount = 1
$parameters.GamesPerSeed = $Games
$parameters.OutputDirectory = $Directory
& (Join-Path $PSScriptRoot 'Run-CommanderCohort.ps1') @parameters
