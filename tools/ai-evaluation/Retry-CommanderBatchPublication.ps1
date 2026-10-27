[CmdletBinding()]
param([Parameter(Mandatory=$true)][int]$Seed, [switch]$DryRun, [string]$PythonExe='C:\Python312\python.exe')
$ErrorActionPreference = 'Stop'
if ($DryRun) {
    $root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
    $batch = Join-Path $root "work\commander-batches\seed$Seed"
    $request = Get-Content -LiteralPath (Join-Path $batch 'request.json') -Raw | ConvertFrom-Json
    $worker = Join-Path $PSScriptRoot 'CommanderBatch.py'
    if ($request.PSObject.Properties.Name -contains 'runnerVersion' -and $request.runnerVersion -in @('parallel-v2','parallel-v3','parallel-v4')) { $worker = Join-Path $PSScriptRoot "$($request.runnerVersion)\CommanderBatch.py" }
    & $PythonExe $worker publish --batch $batch --dry-run
    if ($LASTEXITCODE -ne 0) { throw 'Publication dry run failed. No commit or push was performed.' }
} else {
    & (Join-Path $PSScriptRoot 'Start-CommanderBatch.ps1') -Seed $Seed -PublicationOnly -PythonExe $PythonExe
}
