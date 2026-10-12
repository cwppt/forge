"""Read-only, deterministic aggregation of Stage 14 audit exports (standard library only)."""
import collections
import base64
import hashlib
import json
import math
import pathlib
import statistics
import sys
from urllib.parse import urlparse

SURFACES = ('MULLIGAN_KEEP','MAIN_PHASE_ACTION','STACK_RESPONSE','COMBAT_ATTACKERS',
            'COMBAT_BLOCKERS','COMBAT_DAMAGE_ORDER','COMBAT_ACTION')

def verify_requests(path):
    checked = []
    manifest = json.loads((path/'run.json').read_text(encoding='utf-8-sig')) if (path/'run.json').exists() else {}
    ollama_port = urlparse(manifest.get('Endpoint','')).port == 11434
    for file in sorted(path.glob('*.requests.jsonl')):
        audit = file.with_name(file.name.replace('.requests.jsonl','.jsonl'))
        refs = {r['decisionId']:r for r in (json.loads(line) for line in audit.read_text(encoding='utf-8-sig').splitlines())
                if r.get('recordType')=='FULL_GAME_DECISION'} if audit.exists() else {}
        for line in file.read_text(encoding='utf-8-sig').splitlines():
            capture = json.loads(line)
            body = base64.b64decode(capture['RequestUtf8Base64'])
            request = json.loads(body)
            payload = json.loads(request['messages'][1]['content'])
            errors = []
            if hashlib.sha256(body).hexdigest().upper() != capture['RequestSha256']: errors.append('wire hash mismatch')
            if request != capture['Request']: errors.append('wire/parsed request mismatch')
            if any(key in json.dumps(payload) for key in ('externalAiGameId','turnSequenceId')): errors.append('audit correlation leaked')
            if ollama_port and request['model'].lower().split(':')[0] == 'qwen3' and request.get('reasoning_effort') != 'none': errors.append('thinking not disabled')
            state = payload.get('state',{})
            for p in state.get('players',[]):
                if p.get('self') is False and p.get('hand'): errors.append('opponent hand exposed')
            schema = request.get('response_format',{}).get('json_schema',{}).get('schema',{})
            if schema:
                props = schema.get('properties',{})
                for key in ('decisionId','stateFingerprint'):
                    if props.get(key,{}).get('type')!='string': errors.append(f'{key} schema missing')
                    if 'enum' in props.get(key,{}) and props[key]['enum'] != [payload[key]]: errors.append(f'{key} enum mismatch')
                ref = refs.get(payload['decisionId'])
                if ref is None: errors.append('request missing decision audit')
                elif ref.get('stateFingerprint') != payload['stateFingerprint']: errors.append('audit/request fingerprint mismatch')
                elif ref['accepted'] and ref['selectedOption'] not in props.get('optionId',{}).get('enum',[]): errors.append('accepted option outside request enum')
            checked.append(dict(audit=file.name,decisionId=payload['decisionId'],surface=payload['decisionType'],errors=errors))
    result = dict(checked=len(checked),passed=sum(not c['errors'] for c in checked),failures=[c for c in checked if c['errors']],
                  bySurface=dict(collections.Counter(c['surface'] for c in checked)))
    (path/'request-verification.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    return result

def percentile(values, q):
    values = sorted(values)
    return values[max(0, math.ceil(len(values)*q)-1)] if values else 0

def mean(values):
    return statistics.mean(values) if values else 0

def analyze(path):
    path = pathlib.Path(path)
    games = []
    decisions = []
    turns = []
    for file in sorted(path.glob('*.run.json')):
        meta = json.loads(file.read_text(encoding='utf-8-sig'))
        audit = file.with_name(file.name.replace('.run.json', '.jsonl'))
        records = [json.loads(line) for line in audit.read_text(encoding='utf-8-sig').splitlines()] if audit.exists() else []
        summaries = [r for r in records if r.get('recordType') == 'FULL_GAME_SUMMARY']
        meta['summary'] = summaries[0] if summaries else None
        meta['audit'] = audit.name
        games.append(meta)
        for r in records:
            r['audit'] = audit.name
            r['mode'] = meta['Mode']
            if r.get('recordType') == 'FULL_GAME_DECISION': decisions.append(r)
            if r.get('recordType') == 'TURN_SEQUENCE': turns.append(r)
    external = [g for g in games if g['Mode'] == 'External' and g['summary']]
    control = [g for g in games if g['Mode'] == 'Control' and g['summary']]
    calls = [d for d in decisions if d['providerAttempted']]
    def stats(items):
        attempts = [d for d in items if d['providerAttempted']]
        latencies = [d['providerLatencyMs'] for d in attempts]
        reasons = collections.Counter(d['failureReason'] for d in items if d['fallback'])
        return dict(decisions=len(items), providerCalls=len(attempts), accepted=sum(d['accepted'] for d in items),
                    fallbacks=sum(d['fallback'] for d in items), fallbackReasons=dict(reasons),
                    staleOrRevalidationFailures=sum(d['staleOrRevalidationFailure'] for d in items),
                    agreements=sum(d['agreement'] for d in items),
                    disagreements=sum(d['accepted'] and not d['agreement'] for d in items),
                    passSelections=sum(d['passSelection'] for d in items),
                    noAttackSelections=sum(d['noAttackSelection'] for d in items),
                    noBlockSelections=sum(d['noBlockSelection'] for d in items),
                    duplicates=sum(d['duplicateRequest'] for d in items),
                    providerLatencyMs=sum(latencies), avgProviderLatencyMs=mean(latencies),
                    p50ProviderLatencyMs=percentile(latencies,.5),p95ProviderLatencyMs=percentile(latencies,.95),
                    maxProviderLatencyMs=max(latencies,default=0), fallbackRate=sum(reasons.values())/len(attempts) if attempts else 0,
                    providerUsage={key:sum(d.get('usage',{}).get(key,0) for d in attempts)
                                   for key in ('prompt_tokens','completion_tokens','total_tokens')},
                    requestsWithUsage=sum(bool(d.get('usage')) for d in attempts),
                    timeouts=reasons['TIMEOUT'],providerExceptions=reasons['PROVIDER_EXCEPTION'],
                    malformedResponses=reasons['MALFORMED_RESPONSE']+reasons['EMPTY_RESPONSE'],
                    identityMismatches=sum(reasons[r] for r in ('DECISION_ID_MISMATCH','FINGERPRINT_MISMATCH','INVALID_OPTION')))
    def outcome(g):
        s = g['summary']
        if s['draw']: return 'draw'
        # Simulation identities explicitly identify the initial seat, including eliminated players.
        winner = s['winner']
        prefix = f"Ai({g['ExternalSeat']+1})-"
        if not winner.startswith(('Ai(1)-','Ai(2)-')): raise ValueError(f'Unknown simulation winner identity: {winner}')
        return 'win' if winner.startswith(prefix) else 'loss'
    totals = [g['summary']['metrics']['externalFullGameProviderLatencyMs'] for g in external]
    counts = [g['summary']['metrics']['externalFullGameProviderCalls'] for g in external]
    report = dict(games=len(games),completed=sum(g['Completed'] for g in games),crashes=sum(g['Crash'] for g in games),
                  timeouts=sum(g['TimedOut'] for g in games),externalGames=len(external),controlGames=len(control),
                  overall=stats(decisions), bySurface={s:stats([d for d in decisions if d['surface']==s]) for s in SURFACES},
                  externalFullGameProviderCalls=sum(counts), externalFullGameProviderLatencyMs=sum(totals),
                  externalFullGameAvgCallsPerGame=mean(counts),externalFullGameAvgLatencyPerGame=mean(totals),
                  externalFullGameP50LatencyPerGame=percentile(totals,.5),externalFullGameP95LatencyPerGame=percentile(totals,.95),
                  externalFullGameMaxCallLatencyMs=max((d['providerLatencyMs'] for d in calls),default=0),
                  externalAvgGameDurationMs=mean([g['summary']['durationMs'] for g in external]),
                  controlAvgGameDurationMs=mean([g['summary']['durationMs'] for g in control]),
                  externalAverageTurns=mean([g['summary']['turns'] for g in external]),
                  controlAverageTurns=mean([g['summary']['turns'] for g in control]),
                  externalOutcomes=dict(collections.Counter(outcome(g) for g in external)),
                  controlOutcomesForMatchedSeat=dict(collections.Counter(outcome(g) for g in control)),
                  mirroredSeats={str(seat):dict(collections.Counter(outcome(g) for g in external if g['ExternalSeat']==seat)) for seat in (0,1)},
                  combatActionCategories=dict(collections.Counter(d.get('actionCategory','') for d in decisions if d['surface']=='COMBAT_ACTION' and d['accepted'] and not d['passSelection'])),
                  slowestRequests=sorted(calls,key=lambda d:d['providerLatencyMs'],reverse=True)[:5])
    pairs = {(g['Matchup'],g['Seed'],g['ExternalSeat']):g for g in control}
    report['matchedPairs'] = sum((g['Matchup'],g['Seed'],g['ExternalSeat']) in pairs for g in external)
    report['controlProviderCalls'] = sum(d['providerAttempted'] for d in decisions if d['mode']=='Control')
    report['requestVerification'] = verify_requests(path)
    report['classification'] = 'A. mechanically unreliable' if report['crashes'] or report['timeouts'] or report['overall']['staleOrRevalidationFailures'] else 'B. mechanically reliable but strategically inconclusive'
    report['interpretation'] = 'Small matched cohort: descriptive outcomes only; no statistical superiority claim. Review full-game evidence before adding features or tuning strategy.'
    candidates = []
    for t in turns:
        seq = [d for d in decisions if d['externalAiGameId']==t['externalAiGameId'] and d['turn']==t['turn']]
        if not any(d['providerAttempted'] for d in seq): continue
        reasons = []
        if any(d['fallback'] for d in seq): reasons.append('fallback')
        if any(d['passSelection'] for d in seq): reasons.append('PASS')
        if any(d['surface']=='COMBAT_ACTION' and d['accepted'] and not d['passSelection'] for d in seq): reasons.append('combat interaction')
        disagreements = sum(d['accepted'] and not d['agreement'] for d in seq)
        if disagreements > 1: reasons.append('multiple Forge disagreements')
        candidates.append((len(seq)+3*disagreements+5*len(reasons),dict(audit=t['audit'],turnSequenceId=t['turnSequenceId'],turn=t['turn'],reasons=reasons,decisions=seq,beforeMainPhase=t.get('beforeMainPhase'))))
    review = [c[1] for c in sorted(candidates,key=lambda x:(-x[0],x[1]['audit'],x[1]['turn']))[:5]]
    (path/'aggregate.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    (path/'review-index.json').write_text(json.dumps(review,indent=2)+'\n',encoding='utf-8')
    lines = ['# Stage 14 full-game evaluation','',report['classification'],'',report['interpretation'],'',
             f"Games: {report['completed']}/{report['games']} completed; {report['matchedPairs']} matched pairs; crashes {report['crashes']}; timeouts {report['timeouts']}.",
             f"External outcomes: {report['externalOutcomes']}; matched native-seat outcomes: {report['controlOutcomesForMatchedSeat']}; mirrors: {report['mirroredSeats']}.",
             f"External calls/game: {mean(counts):.2f}; provider latency/game: {mean(totals)/1000:.2f}s; p50/p95: {percentile(totals,.5)/1000:.2f}/{percentile(totals,.95)/1000:.2f}s.",
             f"Fallbacks: {report['overall']['fallbacks']}; stale/revalidation: {report['overall']['staleOrRevalidationFailures']}; duplicate requests: {report['overall']['duplicates']}; control calls: {report['controlProviderCalls']}.",'',
             '| Surface | Calls | Accepted | Fallbacks | Agreements / disagreements | PASS | Mean latency ms |','|---|---:|---:|---:|---:|---:|---:|']
    for s,v in report['bySurface'].items(): lines.append(f"| {s} | {v['providerCalls']} | {v['accepted']} | {v['fallbacks']} | {v['agreements']} / {v['disagreements']} | {v['passSelections']} | {v['avgProviderLatencyMs']:.2f} |")
    lines += ['', '## Review index', '']
    for r in review: lines.append(f"- {r['audit']}, {r['turnSequenceId']}: {', '.join(r['reasons']) or 'high decision count'}; {len(r['decisions'])} decisions. See review-index.json and original audit for state/options.")
    (path/'report.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print('\n'.join(lines))
    return report

if __name__ == '__main__': analyze(sys.argv[1])
