[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Path)
$ErrorActionPreference = 'Stop'
# Python uses only its standard library; no provider or game process is launched by analysis.
$python = (Get-Command python.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
& $python (Join-Path $PSScriptRoot 'analyze_stage14.py') $Path
if ($LASTEXITCODE -ne 0) { throw 'Stage 14 analysis failed.' }
