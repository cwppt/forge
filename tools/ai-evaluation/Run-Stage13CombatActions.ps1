[CmdletBinding()]
param(
    [string]$Model = 'qwen3:8b',
    [string]$Endpoint = 'http://localhost:11434/v1/chat/completions',
    [long[]]$Seeds = @(1301, 1302),
    [ValidateSet('All','Tricks','Removal')][string]$Matchup = 'All',
    [ValidateRange(1,8)][int]$MaxActions = 4,
    [ValidateRange(1,300)][int]$TimeoutSeconds = 60,
    [ValidateRange(1,86400)][int]$GameTimeoutSeconds = 600,
    [switch]$FixtureSmoke,
    [switch]$CombatTrio,
    [switch]$StackResponse,
    [switch]$RequestDiagnostics,
    [switch]$DryRun,
    [switch]$PassThru
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$desktop = Join-Path $repo 'forge-gui-desktop'
$cohorts = @(
    @{ Name='Tricks'; Decks=@('Counter Surge','Bestial Strength') },
    @{ Name='Removal'; Decks=@('Brute Force','Bestial Strength') }
)
if ($Matchup -ne 'All') { $cohorts = @($cohorts | Where-Object { $_.Name -eq $Matchup }) }
if (($Seeds | Select-Object -Unique).Count -ne $Seeds.Count) { throw 'Seeds must be unique.' }
$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
$output = Join-Path $repo "ai-eval\stage13\$runId"
$classPath = 'target/classes;../forge-ai/target/classes;../forge-gui/target/classes;../forge-game/target/classes;../forge-core/target/classes;target/*'
if (-not $DryRun) {
    $java = (Get-Command java.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    if (-not (Test-Path -LiteralPath (Join-Path $desktop 'target\classes\forge\view\Main.class'))) { throw 'Compile desktop classes first.' }
    New-Item -ItemType Directory -Path $output | Out-Null
    [ordered]@{ Model=$Model; Endpoint=$Endpoint; Seeds=$Seeds; Matchup=$Matchup; MaxActions=$MaxActions;
        FixtureSmoke=[bool]$FixtureSmoke; CombatTrio=[bool]$CombatTrio; StackResponse=[bool]$StackResponse; RequestDiagnostics=[bool]$RequestDiagnostics;
        TimeoutSeconds=$TimeoutSeconds; GameTimeoutSeconds=$GameTimeoutSeconds; Cohorts=$cohorts } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'run.json') -Encoding UTF8
}
if ($FixtureSmoke) {
    $prefix = Join-Path $output 'fixture-smoke'
    $arguments = @('-Dforge.ai.combatAction.diagnostics=true','-Dforge.ai.damageOrder.requestDiagnostics=true',
        "-Dforge.ai.combat.requestCapture=$prefix.requests.jsonl","-Dforge.ai.combatAction.eligibilityAudit=$prefix.eligibility.jsonl",'-cp',"target/test-classes;$classPath",
        'forge.ai.CombatActionOllamaSmoke',"$prefix.jsonl",$Model,$Endpoint)
    $quoted = $arguments | ForEach-Object { '"' + (($_ -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"' }
    if ($DryRun) { Write-Output ($quoted -join ' '); return }
    $process = Start-Process -FilePath $java -ArgumentList ($quoted -join ' ') -WorkingDirectory $desktop `
        -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput "$prefix.stdout.log" -RedirectStandardError "$prefix.stderr.log"
    if ($process.ExitCode -ne 0 -or -not (Select-String -LiteralPath "$prefix.stdout.log" -Pattern 'Stage 13 fixture smoke accepted:' -Quiet)) { throw 'Fixture smoke failed; inspect preserved logs.' }
    Write-Host "Stage 13 fixture smoke evidence: $output"
    if ($PassThru) { Write-Output $output }
    return
}
foreach ($cohort in $cohorts) {
    foreach ($seed in $Seeds) {
        $prefix = Join-Path $output "$($cohort.Name)-$seed"
        $arguments = @('-cp',$classPath,'forge.view.Main','sim','-D',(Join-Path $repo 'forge-gui\res\quest\precons'),'-d')
        if ($RequestDiagnostics) {
            $arguments = @('-Dforge.ai.combatAction.diagnostics=true','-Dforge.ai.damageOrder.requestDiagnostics=true',"-Dforge.ai.combat.requestCapture=$prefix.requests.jsonl","-Dforge.ai.combatAction.eligibilityAudit=$prefix.eligibility.jsonl") + $arguments
        }
        foreach ($deck in $cohort.Decks) {
            if (-not (Test-Path -LiteralPath (Join-Path $repo "forge-gui\res\quest\precons\$deck.dck"))) { throw "Missing deck: $deck" }
            $arguments += "$deck.dck"
        }
        $arguments += @('-n','1','-s',"$seed",'-c',"$GameTimeoutSeconds",'-a','Default','Default',
            '--external-ai-combat-action-enabled','--external-ai-combat-action-max-actions',"$MaxActions",
            '--external-ai-mulligan-disabled','--external-ai-main-phase-disabled',
            '--external-ai-combat-integrated-disabled',
            '--external-ai-model',$Model,'--external-ai-endpoint',$Endpoint,'--external-ai-timeout',"$TimeoutSeconds",
            '--external-ai-response-format','JSON_SCHEMA','--metrics-csv',"$prefix.csv",'--decision-audit-jsonl',"$prefix.jsonl")
        if ($CombatTrio) { $arguments += @('--legacy-order-combatants','--external-ai-combat-attackers-enabled','--external-ai-combat-blockers-enabled','--external-ai-combat-damage-order-enabled') }
        else { $arguments += @('--external-ai-combat-attackers-disabled','--external-ai-combat-blockers-disabled','--external-ai-combat-damage-order-disabled') }
        if ($StackResponse) { $arguments += '--external-ai-stack-response-enabled' }
        else { $arguments += '--external-ai-stack-response-disabled' }
        $quoted = $arguments | ForEach-Object { '"' + (($_ -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"' }
        if ($DryRun) { Write-Output ($quoted -join ' '); continue }
        Write-Host "Stage 13 $($cohort.Name), seed $seed; outputs: $prefix"
        $process = Start-Process -FilePath $java -ArgumentList ($quoted -join ' ') -WorkingDirectory $desktop `
            -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput "$prefix.stdout.log" -RedirectStandardError "$prefix.stderr.log"
        if ($process.ExitCode -ne 0) { throw "Simulation exited $($process.ExitCode); inspect $prefix.stderr.log" }
        if (-not (Select-String -LiteralPath "$prefix.stdout.log" -Pattern 'Game Result:' -Quiet)) { throw 'Simulation did not finish; inspect preserved logs.' }
        if (Select-String -LiteralPath "$prefix.stdout.log" -Pattern 'Stopping slow match as draw' -Quiet) { throw 'Simulation timed out; partial evidence preserved.' }
        if (Select-String -LiteralPath "$prefix.stderr.log" -Pattern '^\s*(?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*(?:Exception|Error)(?::|$)' -Quiet) { throw 'Engine exception; inspect preserved logs.' }
        if (-not (Test-Path -LiteralPath "$prefix.jsonl") -or -not (Test-Path -LiteralPath "$prefix.csv")) { throw 'Missing evaluation exports.' }
        $events = @(Get-Content -LiteralPath "$prefix.jsonl" | ForEach-Object { $_ | ConvertFrom-Json } | Where-Object { $_.decisionType -eq 'COMBAT_ACTION' })
        [ordered]@{ Seed=$seed; Matchup=$cohort.Name; Decisions=$events.Count;
            Actions=@($events | Where-Object { -not $_.telemetry.fallback -and -not $_.telemetry.passSelection }).Count;
            Passes=@($events | Where-Object { $_.telemetry.passSelection }).Count;
            Fallbacks=@($events | Where-Object { $_.telemetry.fallback }).Count;
            Stale=@($events | Where-Object { $_.telemetry.staleOrRevalidationFailure }).Count;
            AvgProviderLatencyMs=($events | ForEach-Object { $_.telemetry.providerLatencyMs } | Measure-Object -Average).Average } |
            ConvertTo-Json | Set-Content -LiteralPath "$prefix.summary.json" -Encoding UTF8
    }
}
if (-not $DryRun) { Write-Host "Stage 13 evidence: $output"; if ($PassThru) { Write-Output $output } }
