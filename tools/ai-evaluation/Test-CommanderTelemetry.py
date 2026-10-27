"""Exercise the validator against a real run and deliberately corrupted copies."""
import csv
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

source = Path(sys.argv[1])
validator = Path(__file__).with_name('Validate-CommanderTelemetry.py')
def validate(directory):
    return subprocess.run([sys.executable, str(validator), str(directory),
                           '--games-per-seed', '4', '--require-audits'],
                          capture_output=True, text=True)
assert validate(source).returncode == 0, 'Baseline must pass'
cases = ['csv-width', 'missing-audit', 'duplicate-audit', 'final-turn', 'winner', 'seed', 'orphan-log']
for case in cases:
    with tempfile.TemporaryDirectory(dir=source.parent) as temporary:
        target = Path(temporary)
        for path in source.iterdir():
            if path.is_file(): shutil.copy2(path, target / path.name)
        if case == 'csv-width':
            path = next(target.glob('*.metrics.csv'))
            with path.open(newline='', encoding='utf-8-sig') as stream: rows = list(csv.reader(stream))
            rows[1].pop()
            with path.open('w', newline='', encoding='utf-8') as stream: csv.writer(stream).writerows(rows)
        else:
            suffix = 'decisions' if 'audit' in case else ('gamelog' if case == 'orphan-log' else 'games')
            path = next(target.glob(f'*.{suffix}.jsonl'))
            rows = [json.loads(line) for line in path.read_text(encoding='utf-8-sig').splitlines()]
            if case == 'missing-audit': rows.pop()
            elif case == 'duplicate-audit': rows.append(rows[0])
            elif case == 'final-turn': rows[0]['finalTurnCount'] += 1
            elif case == 'winner': rows[0]['winner'] = 'Incorrect winner'
            elif case == 'seed': rows[0]['runSeed'] += 1
            elif case == 'orphan-log': rows[0]['gameIndex'] = 999
            path.write_text('\n'.join(json.dumps(row) for row in rows) + '\n', encoding='utf-8')
        result = validate(target)
        assert result.returncode != 0, f'Corruption accepted: {case}'
        print(f'PASS: rejected {case}')
print('PASS: baseline and seven corruption checks')

# Failure-aware allowance cannot relax winner checks on valid games.
for valid in (True,False):
    with tempfile.TemporaryDirectory(dir=source.parent) as temporary:
        target=Path(temporary)
        for path in source.iterdir():
            if path.is_file(): shutil.copy2(path,target/path.name)
        path=next(target.glob('*.games.jsonl'))
        games=[json.loads(line) for line in path.read_text(encoding='utf-8-sig').splitlines()]
        games[0]['valid']=valid
        path.write_text(''.join(json.dumps(row)+'\n' for row in games),encoding='utf-8')
        path=next(target.glob('*.gamelog.jsonl'))
        logs=[json.loads(line) for line in path.read_text(encoding='utf-8-sig').splitlines()]
        event=next(e for e in logs if e['gameIndex']==games[0]['gameIndex'] and e['type']=='GAME_OUTCOME' and ' has won' in e['message'])
        event['message']='Incorrect winner has won because all opponents have lost'
        path.write_text(''.join(json.dumps(row)+'\n' for row in logs),encoding='utf-8')
        command=[sys.executable,str(validator),str(target),'--games-per-seed','4','--require-audits','--allow-invalid-outcome-mismatch']
        result=subprocess.run(command,capture_output=True,text=True)
        assert (result.returncode==0)==(not valid),result.stderr
        print('PASS: outcome mismatch allowed only for explicitly invalid game',valid)
