[CmdletBinding()]
param(
    [string]$Model = 'qwen3:8b',
    [string]$Endpoint = 'http://localhost:11434/v1/chat/completions',
    [int[]]$Seeds = @(1501,1502,1503,1504,1505),
    [ValidateRange(1,32)][int]$MaxFacts = 12,
    [string]$Python = 'C:\Python312\python.exe'
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$arguments = @((Join-Path $PSScriptRoot 'evaluate_stage15.py'), '--model', $Model, '--endpoint', $Endpoint, '--max-facts', $MaxFacts, '--seeds') + $Seeds
$logs = Join-Path $repo 'work/stage15'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$log = Join-Path $logs ('runner-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$quoted = $arguments | ForEach-Object { '"' + (($_ -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"' }
$process = Start-Process -FilePath $Python -ArgumentList ($quoted -join ' ') -WorkingDirectory $repo `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput "$log.stdout.log" -RedirectStandardError "$log.stderr.log"
$process.WaitForExit()
Get-Content -LiteralPath "$log.stdout.log"
if ($process.ExitCode -ne 0) { throw "Stage 15 evaluation failed; evidence retained. See $log.stderr.log" }
