"""Bounded replacement sampling. Raw exports stay immutable; attempts.jsonl is authoritative for inclusion."""
import argparse
from collections import Counter, defaultdict
import csv
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys


def load_jsonl(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8-sig').splitlines() if line.strip()]


def signature(failure):
    return failure['category'] + ': ' + (failure.get('message') or '')


def implicated_card(failure):
    """A mapping error names the unmapped object, not the card being evaluated."""
    message = failure.get('message') or ''
    match = re.match(r"Couldn't map (.+)/\d+$", message)
    if match:
        name = re.sub(r'\s*\(\d+\)$', '', match[1])
        return re.split(r' - (?:Creature|Artifact|Enchantment|Planeswalker|Land|Instant|Sorcery)\b', name)[0].strip()
    if failure.get('stage') == 'ability-evaluation': return failure.get('card')
    return None


def root_signature(failure):
    message = failure.get('message') or ''
    if not message:
        return failure['category'] + ': message unavailable (' + failure.get('stage', 'unknown-stage') + ')'
    if message.startswith("Couldn't map "):
        stack = failure.get('stackTrace') or ''
        operation = ('commander-snapshot' if 'copyCommandersToSnapshot' in stack else
                     'stack-host' if '.copyStack(' in stack else 'copied-state')
        return failure['category'] + ': GameCopier unmapped object (' + operation + ')'
    return signature(failure)


def console_failures(text):
    """Read exception blocks, not provider connection warnings or individual stack frames."""
    by_game = defaultdict(list)
    pending_index = 0
    lines = text.splitlines()
    i = 0
    pattern = re.compile(r'^(?:Exception in thread "[^"]+" )?([\w.$]+(?:Exception|Error))(?::\s*(.*))?$')
    while i < len(lines):
        line = lines[i]
        start = re.search(r'Simulation Attempt: gameIndex=(\d+)', line)
        if start: pending_index = int(start[1])
        result = re.search(r'Game Result: Game (\d+)', line)
        if result: pending_index = int(result[1])
        match = pattern.match(line)
        if match:
            block = [line]
            category, message = match.group(1), match.group(2)
            i += 1
            while i < len(lines) and (lines[i].startswith('\tat ') or lines[i].startswith('Caused by:')
                                     or lines[i].startswith('\t...')):
                block.append(lines[i])
                cause = pattern.match(lines[i].removeprefix('Caused by: '))
                if lines[i].startswith('Caused by:') and cause:
                    category, message = cause.group(1), cause.group(2)
                i += 1
            by_game[pending_index].append(dict(category=category, message=message, stage='console',
                card=None, deckIdentifier=None, playerSeat=None, stackTrace='\n'.join(block)))
            continue
        i += 1
    return by_game


def wilson(failed, total):
    if not total: return None
    z = 1.95996398454
    p = failed / total
    center = (p + z*z/(2*total))/(1 + z*z/total)
    half = z*math.sqrt(p*(1-p)/total + z*z/(4*total*total))/(1 + z*z/total)
    return [max(0., center-half), min(1., center+half)]


def fisher_two_sided(a, b, c, d):
    """Descriptive two-sided exact test; no multiple-comparison or causal claim."""
    n = a+b+c+d
    if n == 0: return None
    row, col = a+b, a+c
    def probability(x):
        return math.comb(col, x)*math.comb(n-col, row-x)/math.comb(n, row)
    observed = probability(a)
    return min(1., sum(probability(x) for x in range(max(0, row-(n-col)), min(row, col)+1)
                      if probability(x) <= observed*(1+1e-12)))


def telemetry_errors(game, logs, metrics, audits, require_audits):
    errors = []
    key = (game['gameId'], game['gameIndex'])
    events = [e for e in logs if (e['gameId'], e['gameIndex']) == key]
    rows = [r for r in metrics if (int(r['gameId']), int(r['gameIndex'])) == key]
    decisions = {r['decisionId']: r for r in rows}
    game_audits = [a for a in audits if (a.get('telemetry', a)['gameId'],
                                        a.get('telemetry', a)['gameIndex']) == key]
    if not events or events[-1]['type'] != 'MATCH_RESULTS': errors.append('missing-terminal-log')
    if [e['eventIndex'] for e in events] != list(range(len(events))): errors.append('log-event-order')
    turns = [int(m[1]) for e in events if e['type'] == 'TURN' and
             (m := re.search(r'Turn (\d+)', e['message']))]
    if not turns or max(turns) != game['finalTurnCount'] or turns != sorted(turns): errors.append('final-turn-log')
    if any(e['runSeed'] != game['runSeed'] for e in events): errors.append('log-seed')
    outcomes = [e['message'] for e in events if e['type'] == 'GAME_OUTCOME']
    winners = [message.split(' has won', 1)[0] for message in outcomes if ' has won' in message]
    if winners != ([] if game['draw'] else [game['winner']]): errors.append('logged-winner')
    if (game['winner'] is None) != game['draw']: errors.append('winner-draw')
    if len(decisions) != len(rows): errors.append('duplicate-metrics')
    if any(int(r['runSeed']) != game['runSeed'] for r in rows): errors.append('metrics-seed')
    if require_audits:
        expected = {r['decisionId'] for r in rows if r['decisionType'] != 'MULLIGAN_KEEP'}
        observed = [a.get('telemetry', a)['decisionId'] for a in game_audits]
        if set(observed) != expected or len(observed) != len(set(observed)): errors.append('audit-coverage')
        if {int(r['playerSeat']) for r in rows if r['decisionType']=='MULLIGAN_KEEP'} != {0,1,2,3}:
            errors.append('mulligan-seat-coverage')
    for audit in game_audits:
        event = audit.get('telemetry', audit)
        metric = decisions.get(event['decisionId'])
        if metric is None: errors.append('orphan-audit'); continue
        if event['runSeed'] != game['runSeed'] or event['playerSeat'] != int(metric['playerSeat']):
            errors.append('audit-identity')
        if event.get('stateFingerprint', event.get('fingerprint')) != metric['stateFingerprint']:
            errors.append('audit-fingerprint')
        if audit.get('winner', audit.get('finalWinner')) != game['winner'] or audit['draw'] != game['draw']:
            errors.append('audit-outcome')
        if audit['finalTurnCount'] != game['finalTurnCount'] or audit['gameDurationMs'] != game['durationMs']:
            errors.append('audit-final-metric')
    return sorted(set(errors))


def classify_batch(directory, seed, requested, require_audits, exit_code=0, require_roles=False):
    prefix = directory / f'commander-seed{seed}'
    text = prefix.with_suffix('.log').read_text(encoding='utf-8-sig') if prefix.with_suffix('.log').exists() else ''
    caught = console_failures(text)
    console = {int(m[1])-1: (int(m[2]), m[3].rstrip('\r')) for m in re.finditer(
        r'^Game Result: Game (\d+) ended in (\d+) ms\. (.*?) has won!$', text, re.MULTILINE)}
    console.update({int(m[1])-1: (int(m[2]), None) for m in re.finditer(
        r'^Game Result: Game (\d+) ended in a Draw! Took (\d+) ms\.$', text, re.MULTILINE)})
    starts = {int(m[1]): int(m[2]) for m in re.finditer(
        r'Simulation Attempt: gameIndex=(\d+) gameId=(\d+)', text)}
    results_path = Path(str(prefix)+'.games.jsonl')
    global_errors = []
    try:
        games = load_jsonl(results_path)
        logs = load_jsonl(Path(str(prefix)+'.gamelog.jsonl'))
        audits = load_jsonl(Path(str(prefix)+'.decisions.jsonl'))
        with Path(str(prefix)+'.metrics.csv').open(newline='', encoding='utf-8-sig') as stream:
            raw = list(csv.reader(stream))
        if not raw or len(raw[0]) != len(set(raw[0])) or any(len(row) != len(raw[0]) for row in raw[1:]):
            global_errors.append('csv-width-or-header')
        metrics = [dict(zip(raw[0], row)) for row in raw[1:]] if raw else []
        keys = {(g['gameId'], g['gameIndex']) for g in games}
        if len(keys) != len(games): global_errors.append('duplicate-game-identity')
        if any((e['gameId'],e['gameIndex']) not in keys for e in logs): global_errors.append('orphan-log')
        if any((int(r['gameId']),int(r['gameIndex'])) not in keys for r in metrics): global_errors.append('orphan-metrics')
    except (OSError, ValueError, KeyError, IndexError) as error:
        games, logs, audits, metrics = [], [], [], []
        global_errors.append('missing-or-malformed-telemetry: '+str(error))
    indexed = {g['gameIndex']: g for g in games}
    role_events = defaultdict(list)
    role_load_error = None
    if require_roles:
        try:
            for event in load_jsonl(Path(str(prefix)+'.roles.jsonl')):
                role_events[(event['gameId'],event['gameIndex'])].append(event)
        except (OSError, ValueError, KeyError) as error:
            role_load_error = 'missing-or-malformed-role-telemetry: '+str(error)
    indices = sorted(set(indexed) | set(starts) | set(caught))
    attempts = []
    for index in indices:
        game = indexed.get(index)
        failures = list(game.get('engineFailures', [])) if game else []
        # Structured capture and the console stack describe the same failure; do not count both.
        counts = Counter(signature(f) for f in failures)
        for failure in caught.get(index, []):
            sig = signature(failure)
            if counts[sig]: counts[sig] -= 1
            else: failures.append(failure)
        errors = list(global_errors)
        if game:
            if require_roles:
                events=role_events.get((game['gameId'],game['gameIndex']),[])
                if role_load_error: errors.append(role_load_error)
                elif not events or events[0]['type']!='START' or events[-1]['type']!='END': errors.append('role-terminal-coverage')
                elif ([e['eventIndex'] for e in events]!=list(range(len(events))) or
                      any(e['runSeed']!=seed for e in events)): errors.append('role-identity-or-order')
                elif game['finalTurnCount']>0 and (
                      not any(e['type']=='SNAPSHOT' for e in events) or
                      not any(e['type']=='TURN' for e in events) or
                      max(e.get('rawTurn',0) for e in events)!=game['finalTurnCount']): errors.append('role-observation-coverage')
            if 'engineFailures' not in game or 'candidateExposure' not in game:
                errors.append('failure-capture-schema-missing')
            if console.get(index) != (game['durationMs'],game['winner']): errors.append('console-result')
            if game['runSeed'] != seed or not 0 <= index < requested: errors.append('unexpected-seed-or-index')
            try: errors += telemetry_errors(game, logs, metrics, audits, require_audits)
            except (KeyError, ValueError, TypeError) as error: errors.append('malformed-game-telemetry: '+str(error))
            if game.get('simulationError') and not failures:
                failures.append(dict(category=game['simulationError'], message='simulation-level failure',
                                     card=None, deckIdentifier=None, playerSeat=None, stage='game-execution'))
            if game.get('timedOut') and not any('Timeout' in f['category'] for f in failures):
                failures.append(dict(category='GameTimeout', message='per-game time budget exceeded',
                                     card=None, deckIdentifier=None, playerSeat=None, stage='game-timeout'))
        else:
            errors.append('started-game-missing-result')
        timed_out = bool(game and game.get('timedOut'))
        valid = bool(game and not failures and not errors and game.get('valid', True) and not timed_out)
        status = 'VALID' if valid else ('TIMEOUT' if timed_out else 'ENGINE_FAILED' if failures else 'TELEMETRY_FAILED')
        attempts.append(dict(runSeed=seed, gameIndex=index, gameId=game['gameId'] if game else starts.get(index),
            valid=valid, simulationStatus=status, winner=game['winner'] if valid else None,
            draw=game['draw'] if valid else None, finalTurnCount=game['finalTurnCount'] if game else None,
            recordedOutcome=game, failures=failures, telemetryErrors=sorted(set(errors)), processExitCode=exit_code,
            candidateExposure=game.get('candidateExposure', []) if game else []))
    return attempts


def summarize(attempts, target, max_attempts, batches):
    valid = [a for a in attempts if a['valid']]
    failed = [a for a in attempts if not a['valid']]
    wins = Counter(a['winner'] for a in valid if not a['draw'])
    players = {e.get('playerIdentity') for a in attempts for e in a['candidateExposure'] if e.get('playerIdentity')}
    for player in sorted(players): wins.setdefault(player, 0)
    causes, cards, decks, implicated = {}, {}, {}, {}
    for i, attempt in enumerate(attempts):
        for f in attempt['failures']:
            for mapping, key in ((causes, root_signature(f)), (cards, f.get('card') or 'UNATTRIBUTED'),
                                 (decks, f.get('deckIdentifier') or 'UNATTRIBUTED'),
                                 (implicated, implicated_card(f) or 'UNATTRIBUTED')):
                entry = mapping.setdefault(key, dict(occurrences=0, gameIndices=set(), seeds=set()))
                entry['occurrences'] += 1; entry['gameIndices'].add(i); entry['seeds'].add(attempt['runSeed'])
    def compact(mapping):
        return {key: dict(occurrences=value['occurrences'], failedGames=len(value['gameIndices']),
                          attemptIndices=sorted(value['gameIndices']), seeds=sorted(value['seeds']))
                for key,value in sorted(mapping.items())}
    exposure_groups = defaultdict(set)
    eligible_indices={i for i,a in enumerate(attempts) if a['simulationStatus']!='INTERRUPTED'}
    for i, attempt in enumerate(attempts):
        if i not in eligible_indices: continue
        for exposure in attempt['candidateExposure']:
            exposure_groups[(exposure['playerSeat'], exposure['deckIdentifier'], exposure['card'])].add(i)
        # Copy preparation may fail before the evaluator observation hook is reached.
        for failure in attempt['failures']:
            if failure.get('card') and failure.get('playerSeat') is not None and failure.get('deckIdentifier'):
                exposure_groups[(failure['playerSeat'], failure['deckIdentifier'], failure['card'])].add(i)
    failed_indices = {i for i,a in enumerate(attempts) if not a['valid'] and i in eligible_indices}
    association = []
    for (seat, deck, card), exposed in exposure_groups.items():
        a = len(exposed & failed_indices); b = len(exposed)-a
        c = len(failed_indices-exposed); d = len(eligible_indices)-len(exposed)-c
        evaluated_failures = sum(any(f.get('card')==card and f.get('playerSeat')==seat for f in attempts[i]['failures'])
                           for i in exposed)
        attributable = sum(any(f.get('stage')=='ability-evaluation' and f.get('card')==card
                              and f.get('playerSeat')==seat for f in attempts[i]['failures']) for i in exposed)
        association.append(dict(playerSeat=seat, deckIdentifier=deck, card=card, exposedGames=len(exposed),
            failedExposedGames=a, validExposedGames=b, attributableFailedGames=attributable,
            failedEvaluationsAtCard=evaluated_failures,
            failureRateWhenExposed=a/(a+b), failureRateWhenUnexposed=c/(c+d) if c+d else None,
            exposedFailureRate95CI=wilson(a,a+b), fisherTwoSidedP=fisher_two_sided(a,b,c,d)))
    association.sort(key=lambda x:(-x['attributableFailedGames'], -x['failedExposedGames'],x['card']))
    return dict(targetValidGames=target, validGames=len(valid), attemptedGames=len(attempts),
        failedGames=len(failed), engineFailures=sum(a['simulationStatus']=='ENGINE_FAILED' for a in attempts),
        timeouts=sum(a['simulationStatus']=='TIMEOUT' for a in attempts),
        interruptedGames=sum(a['simulationStatus']=='INTERRUPTED' for a in attempts),
        telemetryFailures=sum(a['simulationStatus']=='TELEMETRY_FAILED' for a in attempts),
        failureRate=len(failed)/len(attempts) if attempts else None,
        engineFailureRateAmongUninterrupted=(sum(a['simulationStatus']=='ENGINE_FAILED' for a in attempts)/sum(a['simulationStatus']!='INTERRUPTED' for a in attempts)) if any(a['simulationStatus']!='INTERRUPTED' for a in attempts) else None,
        failureRate95CI=wilson(len(failed),len(attempts)),
        maxAttempts=max_attempts, status='TARGET_REACHED' if len(valid)==target else 'INCOMPLETE',
        wins=dict(wins), validDraws=sum(a['draw'] for a in valid),
        winRates={name:count/len(valid) for name,count in wins.items()} if valid else {},
        failedRecordedWinners=dict(Counter((a.get('recordedOutcome') or {}).get('winner') or 'NO_RECORDED_WINNER' for a in failed)),
        processLaunchFailures=sum(b['attemptedGames']==0 or b['processExitCode']!=0 for b in batches),
        failuresByRootCause=compact(causes), failuresByCard=compact(cards), failuresByDeck=compact(decks),
        failuresByImplicatedCard=compact(implicated),
        attributionDefinitions=dict(failuresByCard='card whose ability was being evaluated, not proof of causation',
            failuresByDeck='deck of evaluating controller, not necessarily owner of unmapped object',
            failuresByImplicatedCard='exact failing ability for ability-evaluation, or object explicitly named in mapping exception'),
        cardExposureAssociations=association, batches=batches,
        exposureBasis='Unique card/controller evaluation observations plus explicit failed-candidate contexts; no complete drawn-card census.',
        biasCaution='Replacement estimates outcomes conditional on no detected engine failure. Card exposure is evaluated-ability exposure, not every drawn card. Associations are exploratory and affected by game length; no non-bias guarantee.',
        hundredGameCohortStarted=False)


def write_state(output, attempts, target, max_attempts, batches, skipped_seeds=None):
    for name, rows in [('attempts', attempts), ('valid-games', [a for a in attempts if a['valid']]),
                        ('failed-games', [a for a in attempts if not a['valid']])]:
        path = output / (name+'.jsonl')
        temporary = path.with_suffix('.tmp')
        temporary.write_text(''.join(json.dumps(row,ensure_ascii=False)+'\n' for row in rows), encoding='utf-8')
        temporary.replace(path)
    summary = summarize(attempts,target,max_attempts,batches)
    summary['skippedSeeds'] = skipped_seeds or []
    summary['skippedSeedCount'] = len(skipped_seeds or [])
    path = output/'cohort-summary.json'
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(summary, indent=2,ensure_ascii=False)+'\n', encoding='utf-8')
    temporary.replace(path)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resume',action='store_true',help='Continue saved attempts; preserve unfinished batches as failed attempts')
    parser.add_argument('--config',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--target',type=int,required=True)
    parser.add_argument('--max-attempts',type=int,required=True)
    parser.add_argument('--batch-size',type=int,default=5)
    parser.add_argument('--seed-start',type=int,default=2400)
    args=parser.parse_args()
    if args.target<=0 or args.max_attempts<args.target or args.batch_size<=0: parser.error('Invalid limits')
    output=args.output.resolve(); output.mkdir(parents=True,exist_ok=True)
    if (output/'attempts.jsonl').exists() and not args.resume: parser.error('Refusing to overwrite an existing cohort')
    config=json.loads(args.config.read_text(encoding='utf-8-sig'))
    excluded_seeds=set(int(seed) for seed in (config.get('ExcludedSeeds') or []))
    skipped_seeds=[]
    attempts=[]; batches=[]; seed=args.seed_start; empty_runs=0
    if args.resume:
        attempts=load_jsonl(output/'attempts.jsonl')
        previous=json.loads((output/'cohort-summary.json').read_text(encoding='utf-8-sig'))
        if previous['targetValidGames']!=args.target: parser.error('Resume target must match saved cohort')
        batches=previous['batches']
        skipped_seeds=previous.get('skippedSeeds',[])
        known={b['runSeed'] for b in batches}
        for directory in sorted(output.glob('seed-*')):
            interrupted_seed=int(directory.name.split('-')[1])
            if interrupted_seed in known: continue
            recovered=classify_batch(directory,interrupted_seed,args.batch_size,bool(config.get('EnableExternalAi')),-1)
            for item in recovered:
                item['valid']=False; item['winner']=None; item['draw']=None
                item['simulationStatus']='INTERRUPTED'
                item['telemetryErrors'].append('Interrupted batch: exports incomplete; excluded from engine-failure bias analysis')
                item['attemptIndex']=len(attempts); item['rawDirectory']=str(directory); attempts.append(item)
            batches.append(dict(runSeed=interrupted_seed,requestedGames=args.batch_size,attemptedGames=len(recovered),
                                processExitCode=-1,rawDirectory=str(directory),interrupted=True))
        seed=max([args.seed_start-1]+[b['runSeed'] for b in batches]+[s['runSeed'] for s in skipped_seeds])+1
    write_state(output,attempts,args.target,args.max_attempts,batches,skipped_seeds)
    while sum(a['valid'] for a in attempts)<args.target and len(attempts)<args.max_attempts:
        if seed in excluded_seeds:
            skipped_seeds.append(dict(runSeed=seed,status='SKIPPED',reason='Explicit known-blocker seed exclusion',attemptedGames=0))
            write_state(output,attempts,args.target,args.max_attempts,batches,skipped_seeds)
            print(f'Skipping explicitly excluded seed {seed}; no game attempt counted.',flush=True)
            seed+=1
            continue
        count=min(args.batch_size,args.target-sum(a['valid'] for a in attempts),args.max_attempts-len(attempts))
        directory=output/f'seed-{seed}'; directory.mkdir()
        command=['pwsh','-NoProfile','-File',str(Path(__file__).with_name('Invoke-CommanderAttemptBatch.ps1')),
                 '-Config',str(args.config.resolve()),'-Seed',str(seed),'-Games',str(count),'-Directory',str(directory)]
        print(f'Starting seed {seed}: up to {count} attempts; {sum(a["valid"] for a in attempts)}/{args.target} valid.',flush=True)
        with (directory/'launcher.log').open('w',encoding='utf-8') as stream:
            process=subprocess.Popen(command,stdout=stream,stderr=subprocess.STDOUT)
            try: code=process.wait(timeout=count*int(config['TimeoutSeconds'])+180)
            except subprocess.TimeoutExpired:
                if os.name=='nt': subprocess.run(['taskkill','/PID',str(process.pid),'/T','/F'],capture_output=True)
                else: process.kill()
                process.wait(); code=-1
        batch=classify_batch(directory,seed,count,bool(config.get('EnableExternalAi')),code,bool(config.get('CaptureRoleTelemetry')))
        for item in batch:
            item['attemptIndex']=len(attempts); item['rawDirectory']=str(directory)
            attempts.append(item)
        empty_runs=empty_runs+1 if not batch else 0
        batches.append(dict(runSeed=seed,requestedGames=count,attemptedGames=len(batch),processExitCode=code,
                            rawDirectory=str(directory)))
        summary=write_state(output,attempts,args.target,args.max_attempts,batches,skipped_seeds)
        print(f'Completed seed {seed}: valid={summary["validGames"]}, attempted={summary["attemptedGames"]}, failed={summary["failedGames"]}.',flush=True)
        if empty_runs>=3:
            print('Stopped after three launches produced no started-game evidence.',file=sys.stderr); break
        seed+=1
    summary=write_state(output,attempts,args.target,args.max_attempts,batches,skipped_seeds)
    print(json.dumps({k:summary[k] for k in ['status','validGames','attemptedGames','engineFailures','timeouts','failureRate']},indent=2))
    return 0 if summary['status']=='TARGET_REACHED' else 2


if __name__=='__main__': sys.exit(main())
