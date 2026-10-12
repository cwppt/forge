param(
    [int[]]$Seeds = @(102, 103, 111, 114, 118),
    [switch]$RequestDiagnostics,
    [string]$OutputDirectory,
    [string]$Model = 'qwen3:8b',
    [string]$Endpoint = 'http://localhost:11434/v1/chat/completions',
    [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
$repoDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$desktopDirectory = Join-Path $repoDirectory 'forge-gui-desktop'
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $repoDirectory $(if ($RequestDiagnostics) { 'stage11-1-diagnostics' } else { 'stage11-1-validation' })
}
$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $desktopDirectory 'target\classes\forge\view\Main.class'))) {
    throw 'Run the focused Maven build first to compile the desktop classes.'
}
$classpath = 'target/classes;../forge-ai/target/classes;../forge-gui/target/classes;../forge-game/target/classes;../forge-core/target/classes;target/*'
Push-Location -LiteralPath $desktopDirectory
try {
    foreach ($seed in $Seeds) {
        $arguments = @('-cp', $classpath, 'forge.view.Main', 'sim', '-d',
            '../forge-gui/res/quest/precons/Aurochs Stampede.dck',
            '../forge-gui/res/quest/precons/Selesnya Surge.dck', '-n', '1', '-s', "$seed",
            '--legacy-order-combatants', '--external-ai-combat-damage-order-enabled',
            '--external-ai-combat-damage-order-max-options', '6',
            '--external-ai-model', $Model, '--external-ai-endpoint', $Endpoint,
            '--external-ai-timeout', "$TimeoutSeconds", '--external-ai-response-format', 'JSON_SCHEMA',
            '--decision-audit-jsonl', (Join-Path $outputPath "seed-$seed.jsonl"))
        if ($RequestDiagnostics) { $arguments += '--external-ai-request-diagnostics' }
        Write-Host "Stage 11.1 seed $seed (request diagnostics: $RequestDiagnostics)"
        $logPath = Join-Path $outputPath "seed-$seed.log"
        $stdoutPath = Join-Path $outputPath "seed-$seed.stdout.log"
        $stderrPath = Join-Path $outputPath "seed-$seed.stderr.log"
        # Start-Process joins ArgumentList in PowerShell 5.1; quote each argument using Windows escaping.
        $quotedArguments = $arguments | ForEach-Object {
            '"' + (($_ -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
        }
        $javaExecutable = (Get-Command java.exe -CommandType Application -ErrorAction Stop).Source
        $process = Start-Process -FilePath $javaExecutable -ArgumentList ($quotedArguments -join ' ') `
            -WorkingDirectory $desktopDirectory -WindowStyle Hidden -Wait -PassThru `
            -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
        Set-Content -LiteralPath $logPath -Value $null
        Get-Content -LiteralPath $stdoutPath | Tee-Object -FilePath $logPath -Append
        Get-Content -LiteralPath $stderrPath | Tee-Object -FilePath $logPath -Append
        if ($process.ExitCode -ne 0) { throw "Seed $seed failed with exit code $($process.ExitCode)" }
    }
} finally { Pop-Location }
