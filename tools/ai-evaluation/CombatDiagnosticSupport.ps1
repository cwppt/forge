# Offline diagnostic support only; never called by the live Forge provider.
function ConvertTo-CombatDiagnosticJson($Value) {
    # JsonObject.toString() in the Java provider emits literal HTML characters.
    # Normalize PowerShell 5.1's optional HTML escaping in the inner JSON.
    $json=$Value | ConvertTo-Json -Depth 45 -Compress
    return $json.Replace('\u0027',"'").Replace('\u003c','<').Replace('\u003e','>').Replace('\u0026','&').Replace('\u003d','=')
}
function Invoke-CombatDiagnosticRequest($Body, [string]$Endpoint, [int]$TimeoutSeconds, [string]$TracePath, [string]$RawRequestUtf8Base64 = $null) {
    $requestJson = if ($RawRequestUtf8Base64) {
        $raw=[Convert]::FromBase64String($RawRequestUtf8Base64)
        $decoded=[Text.Encoding]::UTF8.GetString($raw)
        if ([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($decoded)) -cne $RawRequestUtf8Base64) {throw 'Raw request is not losslessly encoded UTF-8'}
        $decoded
    } else {$Body | ConvertTo-Json -Depth 45 -Compress}
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $reply = $null; $failure = $null
    try {
        $reply = Invoke-RestMethod -Uri $Endpoint -Method Post -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($requestJson)) -TimeoutSec $TimeoutSeconds
        return $reply
    } catch { $failure = $_.Exception.Message; throw }
    finally {
        $watch.Stop()
        $answer = if ($reply -and $reply.choices -and $reply.choices.Count) {$reply.choices[0]} else {$null}
        # Keep the actual public request and final answer; retain only reasoning length.
        [ordered]@{Request=($requestJson | ConvertFrom-Json);Content=$answer.message.content;FinishReason=$answer.finish_reason;
            Usage=$reply.usage;ReasoningChars=([string]$answer.message.reasoning).Length+([string]$answer.message.reasoning_content).Length;
            LatencyMs=$watch.ElapsedMilliseconds;Error=$failure} | ConvertTo-Json -Depth 45 -Compress | Add-Content -LiteralPath $TracePath -Encoding UTF8
    }
}
function Assert-CombatDiagnosticDecision($Decision, $Payload) {
    if (-not $Decision -or $Decision.decisionId -cne $Payload.decisionId -or $Decision.stateFingerprint -cne $Payload.stateFingerprint -or
        $Decision.optionId -cnotin $Payload.options.optionId -or @($Decision.PSObject.Properties.Name).Count -ne 3) { throw 'Invalid structured decision or identity' }
}
function Get-CombatDiagnosticUserText($Payload, [string]$InputFormat) {
    if ($InputFormat -eq 'Structured') { return (ConvertTo-CombatDiagnosticJson $Payload) }
    $attacker=$Payload.state.attackers[0].permanent; $blocker=$Payload.state.blockers[0].permanent
    $player=$Payload.state.players | Where-Object playerId -eq $Payload.state.defendingPlayerId
    $keywords=if ($attacker.keywords.Count) {$attacker.keywords -join ', '} else {'none'}
    $blockKeywords=if ($blocker.keywords.Count) {$blocker.keywords -join ', '} else {'none'}
    $options=@($Payload.options | ForEach-Object { if ($_.assignments.Count -eq 0) { "$($_.optionId): declare no blockers" } else { "$($_.optionId): block the attacker with the defending creature" } })
    return "decisionId: $($Payload.decisionId)`nstateFingerprint: $($Payload.stateFingerprint)`nThe defending PLAYER has $($player.life) life. One attacking creature, $($attacker.card.name), is $($attacker.card.power)/$($attacker.card.toughness), with keywords: $keywords. Its public rules text is: $($attacker.card.oracleText). One available blocking creature, $($blocker.card.name), is $($blocker.card.power)/$($blocker.card.toughness), with keywords: $blockKeywords. Its public rules text is: $($blocker.card.oracleText). Both have zero marked damage. This is the declare-blockers step; the attacking creature is already attacking the defending player. Only these creatures and listed abilities exist; assume no spells or activated abilities.`nSupplied options:`n$($options -join "`n")"
}
function Get-SimpleCombatDiagnosticTruth($Payload) {
    if ($Payload.state.attackers.Count -ne 1 -or $Payload.state.blockers.Count -ne 1 -or $Payload.options.Count -ne 2) {throw 'Truth calculator supports only the single-attacker synthetic fixtures'}
    $a=$Payload.state.attackers[0].permanent; $b=$Payload.state.blockers[0].permanent
    if ($a.card.oracleText -cne ($a.keywords -join ', ') -or $b.card.oracleText -ne '' -or
        $Payload.state.players.Count -ne 2 -or @($Payload.state.players | Where-Object { $_.battlefield.Count -ne 1 }).Count) {throw 'Truth calculator excludes extra permanents and unmodeled abilities'}
    if ($b.keywords.Count -ne 0 -or @($a.keywords | Where-Object { $_ -notin @('first strike','deathtouch','trample') }).Count -or
        $a.card.markedDamage -ne 0 -or $b.card.markedDamage -ne 0 -or $a.card.power -lt 0 -or $b.card.power -lt 0 -or $a.card.toughness -le 0 -or $b.card.toughness -le 0) {throw 'Unsupported synthetic combat assumptions'}
    $player=$Payload.state.players | Where-Object playerId -eq $Payload.state.defendingPlayerId
    $block=@($Payload.options | Where-Object { $_.assignments.Count -eq 1 })
    $none=@($Payload.options | Where-Object { $_.assignments.Count -eq 0 })
    if ($block.Count -ne 1 -or $none.Count -ne 1 -or $none[0].optionId -cne 'NO_BLOCK' -or $block[0].assignments[0].blockerId -cne $b.permanentId -or $block[0].assignments[0].attackerId -cne $a.permanentId) {throw 'Unsupported option mapping'}
    $blockerDies=$a.card.power -ge $b.card.toughness -or ('deathtouch' -in $a.keywords -and $a.card.power -gt 0)
    $blockerDealsDamage=-not ('first strike' -in $a.keywords -and $blockerDies)
    $attackerDies=$blockerDealsDamage -and $b.card.power -ge $a.card.toughness
    $lethalToBlocker=if ('deathtouch' -in $a.keywords) {1} else {$b.card.toughness}
    $playerDamage=if ('trample' -in $a.keywords) {[Math]::Max(0,$a.card.power-$lethalToBlocker)} else {0}
    return @([pscustomobject]@{optionId=$none[0].optionId;defenderDamage=[int]$a.card.power;attackerDies=$false;blockerDies=$false;defenderLoses=($a.card.power -ge $player.life)},
        [pscustomobject]@{optionId=$block[0].optionId;defenderDamage=[int]$playerDamage;attackerDies=[bool]$attackerDies;blockerDies=[bool]$blockerDies;defenderLoses=($playerDamage -ge $player.life)})
}
function Assert-CombatDiagnosticOutcomes($Predicted,$Payload) {
    if ($Predicted.decisionId -cne $Payload.decisionId -or $Predicted.stateFingerprint -cne $Payload.stateFingerprint -or
        $Predicted.outcomes.Count -ne 2 -or @($Predicted.outcomes.optionId | Select-Object -Unique).Count -ne 2) {throw 'Invalid outcome identity or duplicate options'}
    foreach ($outcome in $Predicted.outcomes) {
        if ($outcome.optionId -cnotin $Payload.options.optionId -or @($outcome.PSObject.Properties.Name).Count -ne 5) {throw 'Invalid outcome option or properties'}
        foreach ($flag in @('attackerDies','blockerDies','defenderLoses')) {if ($outcome.$flag -isnot [bool]) {throw "Outcome $flag must be a JSON boolean"}}
        if ($outcome.defenderDamage -isnot [ValueType] -or $outcome.defenderDamage -is [bool] -or $outcome.defenderDamage -lt 0 -or
            [Math]::Floor([double]$outcome.defenderDamage) -ne [double]$outcome.defenderDamage) {throw 'Player damage must be a nonnegative integer'}
    }
}


