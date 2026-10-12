param([Parameter(Mandatory=$true)][string]$RunDirectory, [Parameter(Mandatory=$true)][string]$OutputPath)
$ErrorActionPreference = 'Stop'
$issues = New-Object 'System.Collections.Generic.List[string]'
$snapshotIssues = New-Object 'System.Collections.Generic.List[string]'
$checkedBlockSnapshots = 0; $checkedDamageSnapshots = 0; $ambiguousDamageSnapshots = 0
$games = @(); $allDecisions = @(); $allSequences = @(); $examples = @()
$settingsPath = Join-Path $RunDirectory 'run.json'
$settings = if (Test-Path -LiteralPath $settingsPath) { Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json } else { $null }
$mode = if ($settings.ExternalMode) { $settings.ExternalMode } else { 'Both' }
$expected = @(@(1000..1009) | ForEach-Object { "Gatecrasher-$_" }) + @(@(101..120) | ForEach-Object { "Aurochs-$_" })
if ($settings -and $settings.Cohorts) {
    $expected = @(foreach ($cohort in $settings.Cohorts) {
        $seeds = if ($settings.Seeds) { $settings.Seeds } else { $cohort.Seeds }
        foreach ($seed in $seeds) { "$($cohort.Name)-$seed" }
    })
}
if ($mode -ne 'Both') { $expected = @($expected | ForEach-Object { "$_-$mode" }) }
foreach ($stem in $expected) {
    $prefix = Join-Path $RunDirectory $stem
    if (-not (Test-Path -LiteralPath "$prefix.csv")) { continue }
    $rows = @(Get-Content -LiteralPath "$prefix.jsonl" | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json })
    $seqs = @($rows | Where-Object { $_.decisionType -eq 'COMBAT_SEQUENCE' })
    $decs = @($rows | Where-Object { $_.decisionType -ne 'COMBAT_SEQUENCE' })
    $byId = @{}; $seenRefs = @{}; $seenSequences = @{}
    foreach ($row in $decs) {
        $event = $row.telemetry
        if ($mode -eq 'Forge') { $issues.Add("${stem}: provider decision in Forge-only arm") }
        if (($mode -eq 'Seat0' -and $event.playerSeat -ne 0) -or ($mode -eq 'Seat1' -and $event.playerSeat -ne 1)) { $issues.Add("${stem}: provider decision on excluded seat") }
        if ($null -eq $event -or $event.decisionSurface -notin @('COMBAT_ATTACKERS','COMBAT_BLOCKERS','COMBAT_DAMAGE_ORDER')) { $issues.Add("${stem}: unexpected decision surface"); continue }
        if ($byId.ContainsKey($event.decisionId)) { $issues.Add("${stem}: duplicate decision identity") }
        $byId[$event.decisionId] = $row
        $request = $row.modelVisibleDecision
        if ($request.decisionId -ne $event.decisionId -or $request.stateFingerprint -ne $event.stateFingerprint -or $request.state.turn -ne $event.turnNumber) { $issues.Add("${stem}: request identity mismatch") }
        if ($event.selectedOptionId -notin @($request.options.optionId)) { $issues.Add("${stem}: selected option absent from immutable request") }
        if ($event.fallback -and $event.selectedOptionId -ne $event.heuristicOptionId) { $issues.Add("${stem}: fallback did not preserve heuristic option") }
        if ($event.responseAccepted -and ($event.returnedDecisionId -ne $event.decisionId -or $event.returnedFingerprint -ne $event.stateFingerprint -or $event.returnedOptionId -ne $event.selectedOptionId -or $event.liveRevalidationResult -ne 'PASSED')) { $issues.Add("${stem}: accepted response identity/revalidation mismatch") }
        foreach ($option in $request.options) {
            if ($event.decisionSurface -eq 'COMBAT_BLOCKERS') {
                foreach ($assignment in $option.assignments) {
                    if ($assignment.attackerId -notin @($request.state.attackers | ForEach-Object { $_.permanent.permanentId }) -or $assignment.blockerId -notin @($request.state.blockers | ForEach-Object { $_.permanent.permanentId })) { $issues.Add("${stem}: block option references absent combatants") }
                }
            } elseif ($event.decisionSurface -eq 'COMBAT_DAMAGE_ORDER') {
                if (@($option.orderedRecipientIds).Count -ne @($request.state.recipients).Count -or @($option.orderedRecipientIds | Select-Object -Unique).Count -ne @($option.orderedRecipientIds).Count -or @(Compare-Object @($option.orderedRecipientIds) @($request.state.recipients.recipientId)).Count -ne 0) { $issues.Add("${stem}: damage option not a recipient permutation") }
            }
        }
        if ($row.modelVisibleDecision.PSObject.Properties.Name -contains 'combatSequenceId') { $issues.Add("${stem}: sequence metadata in provider payload") }
    }
    foreach ($seq in $seqs) {
        if ($seenSequences.ContainsKey($seq.combatSequenceId)) { $issues.Add("${stem}: duplicate sequence identity") }
        $seenSequences[$seq.combatSequenceId] = $true
        foreach ($issue in $seq.coherenceIssues) { $issues.Add("${stem}: $issue") }
        if ($seq.providerCallCount -ne @($seq.decisions | Where-Object providerAttempted).Count) { $issues.Add("${stem}: provider count mismatch") }
        if ($seq.fallbackCount -ne @($seq.decisions | Where-Object fallback).Count) { $issues.Add("${stem}: fallback count mismatch") }
        if ($seq.totalProviderLatencyMs -ne [long](($seq.decisions | Measure-Object providerLatencyMs -Sum).Sum)) { $issues.Add("${stem}: latency sum mismatch") }
        if ($seq.declaredAttackerCount -ne @($seq.declaredAttackers).Count -or $seq.declaredBlockerCount -ne @($seq.declaredBlockers).Count -or $seq.attackersBlocked + $seq.unblockedAttackers -ne $seq.declaredAttackerCount) { $issues.Add("${stem}: declaration totals mismatch") }
        foreach ($reference in $seq.decisions) {
            $row = $byId[$reference.decisionId]; $event = $row.telemetry
            if ($seenRefs.ContainsKey($reference.decisionId)) { $issues.Add("${stem}: duplicated decision reference") }
            $seenRefs[$reference.decisionId] = $true
            if ($null -eq $event -or $event.combatSequenceId -ne $seq.combatSequenceId -or $event.turnNumber -ne $seq.turnNumber -or $event.activePlayerSeat -ne $seq.activePlayerSeat -or $event.gameId -ne $seq.gameId -or $event.runSeed -ne $seq.runSeed -or $event.decisionSurface -ne $reference.surface -or $event.playerSeat -ne $reference.decidingPlayerSeat -or $event.defendingPlayerSeat -ne $reference.defendingPlayerSeat -or $event.responseAccepted -ne $reference.accepted -or $event.fallback -ne $reference.fallback -or $event.failureReason -ne $reference.failureReason -or $event.providerAttempted -ne $reference.providerAttempted -or $event.providerLatencyMs -ne $reference.providerLatencyMs -or $event.heuristicOptionId -ne $reference.heuristicOptionId -or $event.selectedOptionId -ne $reference.selectedOptionId) { $issues.Add("${stem}: inconsistent audit reference $($reference.decisionId)") }
            if ($reference.surface -eq 'COMBAT_BLOCKERS') {
                $actualNames = @($row.modelVisibleDecision.state.attackers | ForEach-Object { $_.permanent.card.name } | Sort-Object)
                $declaredNames = @($seq.declaredAttackers.name | Sort-Object)
                $checkedBlockSnapshots++
                if (($actualNames -join '|') -ne ($declaredNames -join '|')) { $snapshotIssues.Add("${stem}: blocker request/declared attacker names differ in $($seq.combatSequenceId)") }
            } elseif ($reference.surface -eq 'COMBAT_DAMAGE_ORDER') {
                $state = $row.modelVisibleDecision.state
                $sources = if ($state.sourceAttacking) { @($seq.declaredAttackers | Where-Object { $_.name -eq $state.source.name }) } else { @($seq.declaredBlockers | Where-Object { $_.name -eq $state.source.name }) }
                if ($sources.Count -ne 1) { $ambiguousDamageSnapshots++ } else {
                    $expectedNames = if ($state.sourceAttacking) {
                        @($seq.declaredBlockAssignments | Where-Object { $_.attackerRef -eq $sources[0].ref } | ForEach-Object { $assignment = $_; $seq.declaredBlockers | Where-Object { $_.ref -eq $assignment.blockerRef } | ForEach-Object { $_.name } } | Sort-Object)
                    } else {
                        @($seq.declaredBlockAssignments | Where-Object { $_.blockerRef -eq $sources[0].ref } | ForEach-Object { $assignment = $_; $seq.declaredAttackers | Where-Object { $_.ref -eq $assignment.attackerRef } | ForEach-Object { $_.name } } | Sort-Object)
                    }
                    $checkedDamageSnapshots++
                    if (($expectedNames -join '|') -ne (@($state.recipients.name | Sort-Object) -join '|')) { $snapshotIssues.Add("${stem}: damage request/declared block recipient names differ in $($seq.combatSequenceId)") }
                }
            }
        }
        if (@($seq.decisions.surface | Select-Object -Unique).Count -eq 3) { $examples += [ordered]@{ Game = $stem; Sequence = $seq } }
    }
    foreach ($id in $byId.Keys) { if (-not $seenRefs.ContainsKey($id)) { $issues.Add("${stem}: unreferenced decision $id") } }
    $stdout = Get-Content -LiteralPath "$prefix.stdout.log" -Raw
    if ($stdout -notmatch 'Game Result:' -or $stdout -match 'Stopping slow match as draw') { $issues.Add("${stem}: missing or truncated game result") }
    if (Select-String -LiteralPath "$prefix.stderr.log" -Pattern '^\s*(?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*(?:Exception|Error)(?::|$)' -Quiet) { $issues.Add("${stem}: engine exception") }
    $duration = if ($stdout -match 'gameResults games=1 draws=\d+ avgGameLengthMs=([\d.]+)') { [double]$Matches[1] } else { 0 }
    $consoleCounts = [ordered]@{ integratedCombatSequences = $seqs.Count;
        totalCombatProviderCalls = @($decs | Where-Object { $_.telemetry.providerAttempted }).Count;
        combatSequencesWithFallback = @($seqs | Where-Object { $_.fallbackCount -gt 0 }).Count;
        combatSequenceFallbackCount = @($decs | Where-Object { $_.telemetry.fallback }).Count;
        combatSequencesEndingLethal = @($seqs | Where-Object { $_.afterState.lethal }).Count }
    foreach ($metric in $consoleCounts.Keys) {
        if ($stdout -notmatch ($metric + '=(\d+)') -or [long]$Matches[1] -ne $consoleCounts[$metric]) { $issues.Add("${stem}: console/audit metric mismatch for $metric") }
    }
    $winner = if ($decs.Count) { $decs[0].finalWinner } else { $null }
    if ($stdout -match 'Game Result: Game \d+ ended in \d+ ms\. (.+) has won!') { $winner = $Matches[1] }
    $winnerSeat = if ($winner -match '^Ai\((\d+)\)') { [int]$Matches[1] - 1 } else { $null }
    $draw = $stdout -match 'gameResults games=1 draws=1'
    foreach ($metric in @('providerCalls','mainPhaseProviderCalls','stackResponseProviderCalls')) {
        if ($stdout -notmatch ('(?<!\w)' + $metric + '=(\d+)') -or [long]$Matches[1] -ne 0) { $issues.Add("${stem}: unexpected noncombat provider calls") }
    }
    $games += [ordered]@{ Game = $stem; Matchup = $stem.Split('-')[0]; Mode = $mode; Seed = [int]$stem.Split('-')[1]; DurationMs = $duration; Winner = $winner; WinnerSeat = $winnerSeat; Draw = $draw;
        Sequences = $seqs.Count; Calls = $decs.Count; Fallbacks = @($decs | Where-Object { $_.telemetry.fallback }).Count;
        AllThree = @($seqs | Where-Object { @($_.decisions.surface | Select-Object -Unique).Count -eq 3 }).Count;
        IncompleteSequences = @($seqs | Where-Object { -not $_.damageCompleted }).Count }
    $allDecisions += $decs; $allSequences += $seqs
}
function Percentile($values, [double]$fraction) {
    $sorted = @($values | Sort-Object)
    if ($sorted.Count -eq 0) { return 0 }
    return $sorted[[int][Math]::Ceiling($sorted.Count * $fraction) - 1]
}
$surfaceStats = @()
foreach ($surface in @('COMBAT_ATTACKERS','COMBAT_BLOCKERS','COMBAT_DAMAGE_ORDER')) {
    $events = @($allDecisions | Where-Object { $_.decisionType -eq $surface } | ForEach-Object { $_.telemetry })
    $surfaceStats += [ordered]@{ Surface = $surface; Calls = @($events | Where-Object providerAttempted).Count;
        Accepted = @($events | Where-Object responseAccepted).Count; Fallbacks = @($events | Where-Object fallback).Count;
        StaleOrRevalidationFailures = @($events | Where-Object staleOrRevalidationFailure).Count;
        Disagreements = @($events | Where-Object { $_.responseAccepted -and $_.selectedOptionId -ne $_.heuristicOptionId }).Count;
        P50LatencyMs = (Percentile $events.providerLatencyMs 0.5); P95LatencyMs = (Percentile $events.providerLatencyMs 0.95);
        MaxLatencyMs = (($events | Measure-Object providerLatencyMs -Maximum).Maximum) }
}
$result = [ordered]@{ RunDirectory = [IO.Path]::GetFullPath($RunDirectory); Mode = $mode; CompletedGames = $games.Count; ExpectedGames = $expected.Count;
    MissingGames = @($expected | Where-Object { $_ -notin $games.Game }); AuditIssues = @($issues.ToArray());
    PublicSnapshotIssues = @($snapshotIssues.ToArray()); CheckedBlockSnapshots = $checkedBlockSnapshots;
    CheckedDamageSnapshots = $checkedDamageSnapshots; AmbiguousDamageSnapshots = $ambiguousDamageSnapshots;
    Sequences = $allSequences.Count; ProviderCalls = @($allDecisions | Where-Object { $_.telemetry.providerAttempted }).Count;
    Fallbacks = @($allDecisions | Where-Object { $_.telemetry.fallback }).Count;
    SequencesWithFallback = @($allSequences | Where-Object { $_.fallbackCount -gt 0 }).Count;
    AllThreeSequences = $examples.Count; LethalSequences = @($allSequences | Where-Object { $_.afterState.lethal }).Count;
    IncompleteSequences = @($allSequences | Where-Object { -not $_.damageCompleted }).Count;
    P50SequenceProviderLatencyMs = (Percentile $allSequences.totalProviderLatencyMs 0.5);
    P95SequenceProviderLatencyMs = (Percentile $allSequences.totalProviderLatencyMs 0.95);
    AvgProviderCallsPerCombat = $(if ($allSequences.Count) { @($allDecisions | Where-Object { $_.telemetry.providerAttempted }).Count / [double]$allSequences.Count } else { 0 });
    AvgProviderLatencyPerCombatMs = $(if ($allSequences.Count) { [double](($allSequences | Measure-Object totalProviderLatencyMs -Sum).Sum) / $allSequences.Count } else { 0 });
    SurfaceStats = $surfaceStats; Games = $games; AllThreeExamples = $examples }
$result | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
[pscustomobject]@{ CompletedGames = $games.Count; Sequences = $allSequences.Count; Calls = $result.ProviderCalls;
    Fallbacks = $result.Fallbacks; AllThreeSequences = $examples.Count; AuditIssues = $issues.Count; PublicSnapshotIssues = $snapshotIssues.Count }

