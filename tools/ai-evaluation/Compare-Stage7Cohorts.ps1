param([string] $InputDirectory = ".\stage7-evaluation")

Get-ChildItem -LiteralPath $InputDirectory -Filter "*.log" | Sort-Object Name | ForEach-Object {
    $cohort = $_.BaseName
    $log = Get-Content -LiteralPath $_.FullName -Raw
    $games = ([regex]::Matches($log, "Game Result:")).Count
    $draws = ([regex]::Matches($log, "ended in a Draw")).Count
    $durations = [regex]::Matches($log, "(?:ended in |Took )(?<ms>\d+) ms") | ForEach-Object { [long] $_.Groups["ms"].Value }
    $wins = @{}
    [regex]::Matches($log, "ms\. (?<winner>.+?) has won!") | ForEach-Object {
        $name = $_.Groups["winner"].Value.Trim()
        $wins[$name] = 1 + [int] $wins[$name]
    }
    $audits = @()
    $jsonl = Join-Path $InputDirectory "$cohort.jsonl"
    if (Test-Path -LiteralPath $jsonl) {
        $audits = @(Get-Content -LiteralPath $jsonl | Where-Object { $_ } | ConvertFrom-Json)
    }
    $calls = $audits.Count
    $fallbacks = @($audits | Where-Object fallback).Count
    $disagreements = @($audits | Where-Object { -not $_.agreement -and $_.source -eq "EXTERNAL_PROVIDER" }).Count
    $providerLatency = @($audits | ForEach-Object { [double] $_.providerLatencyMs } | Sort-Object)
    $positions = $audits | Group-Object selectedActionId | Sort-Object Name | ForEach-Object {
        "$($_.Name)=$($_.Count)"
    }
    function Get-Percentile([object[]] $Values, [double] $P) {
        if ($Values.Count -eq 0) { return 0 }
        return $Values[[Math]::Max(0, [Math]::Ceiling($P * $Values.Count) - 1)]
    }
    [pscustomobject]@{
        Cohort = $cohort; Games = $games; Draws = $draws
        Wins = ($wins.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ";"
        ProviderCalls = $calls
        FallbackRate = if ($calls) { $fallbacks / $calls } else { 0 }
        DisagreementRate = if ($calls) { $disagreements / $calls } else { 0 }
        ActionPositions = $positions -join ";"
        AvgLatencyMs = if ($calls) { ($providerLatency | Measure-Object -Average).Average } else { 0 }
        P50LatencyMs = Get-Percentile $providerLatency 0.50
        P95LatencyMs = Get-Percentile $providerLatency 0.95
        AvgGameDurationMs = if ($durations.Count) { ($durations | Measure-Object -Average).Average } else { 0 }
    }
} | Format-Table -AutoSize
