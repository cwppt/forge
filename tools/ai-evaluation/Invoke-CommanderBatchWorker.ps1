param([Parameter(Mandatory=$true)][string]$Batch, [string]$PythonExe='C:\Python312\python.exe', [ValidateSet('run','publish')][string]$Mode='run')
$ErrorActionPreference = 'Stop'
try {
    & $PythonExe -u (Join-Path $PSScriptRoot 'CommanderBatch.py') $Mode --batch $Batch >> (Join-Path $Batch 'worker.log') 2>&1
    exit $LASTEXITCODE
} catch {
    $_ | Out-String | Add-Content -LiteralPath (Join-Path $Batch 'bootstrap-error.log')
    exit 1
}
