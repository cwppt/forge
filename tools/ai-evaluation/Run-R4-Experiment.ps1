<# Local R4 comparisons using the established isolated parallel pool. No publishing. #>
[CmdletBinding()]
param(
    [ValidateRange(1,1000)][int]$GamesPerVariant = 2,
    [ValidateRange(1,2147480000)][int]$Seed = 461017,
    [ValidateRange(30,3600)][int]$TimeoutSeconds = 180,
    [ValidateRange(1,4)][int]$MaxParallelGames = 2,
    [string]$PythonExe = 'C:\Python312\python.exe',
    [string]$JavaExe = 'C:\Program Files\Java\jdk-25.0.4.1\bin\java.exe',
    [switch]$DryRun,
    [switch]$Publish,
    [switch]$PublishExisting
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($Publish -or $PublishExisting) { throw 'R4 experiments are local-only; publication is disabled.' }
$arguments = @((Join-Path $PSScriptRoot 'R4Experiment.py'), '--games', "$GamesPerVariant", '--seed', "$Seed", '--timeout', "$TimeoutSeconds", '--workers', "$MaxParallelGames", '--java', $JavaExe)
if ($DryRun) { $arguments += '--prepare-only' }
& $PythonExe @arguments
if ($LASTEXITCODE -ne 0) { throw "R4 experiment exited $LASTEXITCODE. Inspect its preserved logs and attempts.json." }
