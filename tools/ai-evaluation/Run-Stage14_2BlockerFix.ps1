[CmdletBinding()]
param(
    [string]$Model = 'qwen3:8b',
    [string]$Endpoint = 'http://localhost:11434/v1/chat/completions',
    [string]$Python = 'C:\Python312\python.exe',
    [switch]$FrozenOnly
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$arguments = @((Join-Path $PSScriptRoot 'evaluate_stage14_2.py'), '--model', $Model, '--endpoint', $Endpoint)
if ($FrozenOnly) { $arguments += '--frozen-only' }
$logRoot = Join-Path $repo 'work/stage14-2'
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$log = Join-Path $logRoot ('runner-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$quoted = $arguments | ForEach-Object { '"' + (($_ -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"' }
$process = Start-Process -FilePath $Python -ArgumentList ($quoted -join ' ') -WorkingDirectory $repo `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput "$log.stdout.log" -RedirectStandardError "$log.stderr.log"
$process.WaitForExit()
Get-Content -LiteralPath "$log.stdout.log"
if ($process.ExitCode -ne 0) { throw "Stage 14.2 evaluation failed; evidence retained. See $log.stderr.log" }
