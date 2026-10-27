import argparse, csv, json, re
from collections import Counter
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('directory', type=Path)
parser.add_argument('--games-per-seed', type=int, default=1)
parser.add_argument('--report', type=Path)
parser.add_argument('--expected-seeds', type=int)
parser.add_argument('--require-audits', action='store_true')
parser.add_argument('--require-clean-runtime', action='store_true')
parser.add_argument('--allow-invalid-outcome-mismatch', action='store_true', help='Preserve outcome mismatches only for results explicitly marked invalid')
parser.add_argument('--require-literal-turn-match', action='store_true')
args = parser.parse_args()

def jsonl(path):
    assert path.is_file(), f'Missing file: {path}'
    return [json.loads(line) for line in path.read_text(encoding='utf-8-sig').splitlines() if line.strip()]

reports = []
for games_path in sorted(args.directory.glob('commander-seed*.games.jsonl')):
    prefix = games_path.name.removesuffix('.games.jsonl')
    seed = int(prefix.removeprefix('commander-seed'))
    games = jsonl(games_path)
    logs = jsonl(args.directory / (prefix + '.gamelog.jsonl'))
    audits = jsonl(args.directory / (prefix + '.decisions.jsonl'))
    if args.require_audits:
        assert audits, f'{prefix}: decision audit is empty'
    with (args.directory / (prefix + '.metrics.csv')).open(encoding='utf-8-sig', newline='') as f:
        raw_rows = list(csv.reader(f))
    columns = raw_rows[0]
    assert len(columns) == len(set(columns)), 'Duplicate CSV columns'
    assert all(len(row) == len(columns) for row in raw_rows[1:]), f'{prefix}: CSV row widths differ: {Counter(map(len, raw_rows))}'
    rows = [dict(zip(columns, row)) for row in raw_rows[1:]]
    assert len(games) == args.games_per_seed
    assert {g['gameIndex'] for g in games} == set(range(args.games_per_seed))
    assert all(g['runSeed'] == seed and g['durationMs'] > 0 and g['finalTurnCount'] > 0 for g in games)
    assert all((g['winner'] is None) == g['draw'] for g in games)
    joins = {(g['gameId'], g['gameIndex']): g for g in games}
    assert len(joins) == len(games), 'Duplicate game identities'
    assert all((e['gameId'], e['gameIndex']) in joins for e in logs), 'Orphan game-log event'
    for game_key, game in joins.items():
        events = [e for e in logs if (e['gameId'], e['gameIndex']) == game_key]
        assert events and events[0]['type'] not in ('MATCH_RESULTS', 'GAME_OUTCOME'), 'Game log starts at outcome'
        assert [e['eventIndex'] for e in events] == list(range(len(events)))
        assert events[-1]['type'] == 'MATCH_RESULTS'
        assert all(e['runSeed'] == seed and isinstance(e['message'], str) for e in events)
        turns = [int(m.group(1)) for e in events if e['type'] == 'TURN' and (m := re.search(r'Turn (\d+)', e['message']))]
        assert turns == sorted(turns), 'Turn entries are reversed'
        assert turns and max(turns) == game['finalTurnCount'], 'Final TURN/result mismatch'
        game_rows = [r for r in rows if (int(r['gameId']), int(r['gameIndex'])) == game_key]
        assert {int(r['playerSeat']) for r in game_rows if r['decisionType'] == 'MULLIGAN_KEEP'} == {0, 1, 2, 3}
    assert len({r['decisionId'] for r in rows}) == len(rows), 'Duplicate decisions'
    metrics = {r['decisionId']: r for r in rows}
    for row in rows:
        assert int(row['runSeed']) == seed and (int(row['gameId']), int(row['gameIndex'])) in joins
        assert 0 <= int(row['playerSeat']) < 4
        assert float(row['latencyMs']) >= 0
    audit_types = Counter()
    audited_ids = []
    for audit in audits:
        event = audit.get('telemetry', audit)
        decision_id = event['decisionId']
        audited_ids.append(decision_id)
        metric = metrics[decision_id]
        game = joins[(event['gameId'], event['gameIndex'])]
        assert event['runSeed'] == seed
        assert event['playerSeat'] == int(metric['playerSeat'])
        assert event.get('stateFingerprint', event.get('fingerprint')) == metric['stateFingerprint']
        assert audit.get('winner', audit.get('finalWinner')) == game['winner'] and audit['draw'] == game['draw']
        assert audit['gameDurationMs'] == game['durationMs']
        assert audit['finalTurnCount'] == game['finalTurnCount']
        if metric['fallback'] == 'true':
            assert event['fallback'] is True
            assert event.get('failureReason', event.get('fallbackReason')) == metric['fallbackReason']
        def check_visible_state(value):
            if isinstance(value, dict):
                if 'self' in value and 'hand' in value and not value['self']:
                    assert value['hand'] == [], 'Opponent private hand exposed'
                for child in value.values(): check_visible_state(child)
            elif isinstance(value, list):
                for child in value: check_visible_state(child)
        check_visible_state(audit.get('state', audit.get('modelVisibleDecision', {})))
        audit_types[audit.get('decisionType', 'UNKNOWN')] += 1
    assert len(audited_ids) == len(set(audited_ids)), 'Duplicate audit decisions'
    expected_audits = {r['decisionId'] for r in rows if r['decisionType'] != 'MULLIGAN_KEEP'}
    if args.require_audits or audits:
        assert set(audited_ids) == expected_audits, 'Audit/metrics decision coverage mismatch'
    log_text = (args.directory / (prefix + '.log')).read_text(encoding='utf-8-sig')
    assert 'unsupported card was requested' not in log_text
    assert 'Unable to write' not in log_text and 'Could not load deck' not in log_text
    assert 'Exception in thread' not in log_text
    console_results = re.findall(r'^Game Result: Game (\d+) ended in (\d+) ms\. (.*?) has won!$', log_text, re.MULTILINE)
    console_draws = re.findall(r'^Game Result: Game (\d+) ended in a Draw! Took (\d+) ms\.$', log_text, re.MULTILINE)
    console = {int(index)-1: (int(duration), winner.rstrip('\r')) for index, duration, winner in console_results}
    console.update({int(index)-1: (int(duration), None) for index, duration in console_draws})
    assert len(console) == len(games), 'Console game results missing'
    turn_comparisons = []
    for game in games:
        assert console[game['gameIndex']] == (game['durationMs'], game['winner']), 'Console/result mismatch'
        outcomes = [e['message'] for e in logs if e['gameId'] == game['gameId'] and e['type'] == 'GAME_OUTCOME']
        logged_winners = [message.split(' has won', 1)[0] for message in outcomes if ' has won' in message]
        outcome_match = logged_winners == ([] if game['draw'] else [game['winner']])
        assert outcome_match or (args.allow_invalid_outcome_mismatch and game.get('valid') is False), 'Game-log winner/result mismatch'
        logged_turns = [int(m.group(1)) for message in outcomes if (m := re.fullmatch(r'Turn (\d+)', message))]
        literal_match = logged_turns == [game['finalTurnCount']]
        legacy_match = logged_turns == [(game['finalTurnCount'] + 1) // 2]
        assert literal_match or legacy_match, 'Unexplained logged/result turn count mismatch'
        turn_comparisons.append(dict(gameIndex=game['gameIndex'], individualTurns=game['finalTurnCount'], loggedOutcomeTurns=logged_turns, literalMatch=literal_match, outcomeMatch=outcome_match))
        if args.require_literal_turn_match:
            assert literal_match, 'Outcome log divides raw turn count by two'
    timeout_indices = []
    engine_error_indices = []
    engine_errors = []
    pending_timeout = False
    pending_engine_error = False
    for line in log_text.splitlines():
        if 'Stopping slow match as draw' in line or 'Simulation timed out;' in line:
            pending_timeout = True
        if re.match(r'^java\.[\w.]+Exception:', line):
            engine_errors.append(line)
            pending_engine_error = True
        match = re.search(r'Game Result: Game (\d+)', line)
        if match:
            if pending_timeout: timeout_indices.append(int(match.group(1)) - 1)
            if pending_engine_error: engine_error_indices.append(int(match.group(1)) - 1)
            pending_timeout = False
            pending_engine_error = False
    for game in games:
        if game.get('engineFailures') and game['gameIndex'] not in engine_error_indices:
            engine_error_indices.append(game['gameIndex'])
        if 'timedOut' in game:
            assert isinstance(game['timedOut'], bool)
            assert game['timedOut'] == (game['gameIndex'] in timeout_indices)
            assert game['simulationError'] is None or isinstance(game['simulationError'], str)
    if args.require_clean_runtime:
        assert not timeout_indices and not engine_error_indices and not engine_errors and all(
            g.get('simulationError') is None and g.get('valid', True) for g in games), f'{prefix}: timeout or engine exception found'
    reports.append(dict(seed=seed, games=games, turnComparisons=turn_comparisons, timedOutGameIndices=timeout_indices, engineErrorGameIndices=engine_error_indices, engineErrors=engine_errors, runtimeStatus='WARNINGS' if timeout_indices or engine_error_indices or engine_errors else 'CLEAN', timeoutFlagExported=all('timedOut' in g for g in games), gameLogEvents=len(logs), metricRows=len(rows), csvColumns=len(columns), auditRecords=len(audits), auditTypes=dict(audit_types), decisionTypes=dict(Counter(r['decisionType'] for r in rows)), providerAttempts=sum(r['providerAttempted']=='true' for r in rows), fallbacks=sum(r['fallback']=='true' for r in rows), logOrder='chronological', validation='PASS'))
assert reports, 'No results found'
if args.expected_seeds is not None:
    assert len(reports) == args.expected_seeds, f'Expected {args.expected_seeds} completed seeds, got {len(reports)}'
report = dict(runs=reports, validation='PASS')
if args.report:
    args.report.write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
print(json.dumps(report, indent=2))
