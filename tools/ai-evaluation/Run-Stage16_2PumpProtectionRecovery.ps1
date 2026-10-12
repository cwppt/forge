[CmdletBinding()]
param(
    [string]$Model = 'qwen3:8b',
    [string]$Endpoint = 'http://127.0.0.1:11434/v1/chat/completions',
    [long[]]$Seeds = @(16201,16202),
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$desktop = Join-Path $repo 'forge-gui-desktop'
$output = Join-Path $repo ('ai-eval\stage16_2\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$arguments = @('-cp','target/test-classes;target/classes;../forge-ai/target/classes;../forge-gui/target/classes;../forge-game/target/classes;../forge-core/target/classes;target/*',
    'forge.ai.Stage16PumpClusterRecoverySmoke',$output,$Model,$Endpoint,($Seeds -join ','))
$quoted = $arguments | ForEach-Object { '"' + (($_ -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"' }
if ($DryRun) { Write-Output ($quoted -join ' '); return }
New-Item -ItemType Directory -Path $output | Out-Null
$java = (Get-Command java.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$process = Start-Process -FilePath $java -ArgumentList ($quoted -join ' ') -WorkingDirectory $desktop -WindowStyle Hidden -Wait -PassThru `
    -RedirectStandardOutput (Join-Path $output 'stdout.log') -RedirectStandardError (Join-Path $output 'stderr.log')
if ($process.ExitCode -ne 0) { throw "Ability recovery smoke failed; inspect $output" }
Write-Output $output
