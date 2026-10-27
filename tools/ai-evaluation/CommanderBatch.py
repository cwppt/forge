"""Unattended, serial standard-AI batches. Scheduled by the PowerShell interfaces."""
import argparse
import csv
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import threading
import tempfile
import signal
import traceback
import ctypes
import uuid
import faulthandler
from datetime import datetime, timezone

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
STORE = ROOT / 'work' / 'commander-batches'
VARIANTS = [('R2', 'Rivaz-R2.dck', 36), ('R3', 'Rivaz-R3.dck', 37),
            ('R3-38L', 'Rivaz-R3-38L.dck', 38)]
OPPONENTS = ['Ganax-Far-Traveler-B2.dck', 'Ganax-Acolyte-G1.dck', 'Lathliss-V2.5.dck']
spec = importlib.util.spec_from_file_location('cohort', HERE / 'Run-ValidCommanderCohort.py')
cohort = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cohort)


def utc():
    return datetime.now(timezone.utc).isoformat()


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def write(path, value):
    path = Path(path)
    temp = path.with_suffix(path.suffix + '.tmp')
    temp.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    for retry in range(30):
        try:
            temp.replace(path)
            break
        except PermissionError:
            if retry == 29: raise
            time.sleep(0.1)


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def git(repo, *args):
    return subprocess.run(['git', '-C', str(repo), *args], check=True, capture_output=True,
                          text=True, timeout=120).stdout.strip()


def land_count(deck):
    # Verify live card types rather than counting a hand-maintained land-name list.
    if not hasattr(land_count, 'cards'):
        cards = {}
        for p in (ROOT / 'forge-gui/res/cardsfolder').rglob('*.txt'):
            name = card_type = None
            for line in p.read_text(encoding='utf-8-sig').splitlines():
                if line.startswith('Name:') and name is None: name = line[5:]
                if line.startswith('Types:') and card_type is None: card_type = line[6:]
            if name: cards[name] = 'Land' in (card_type or '').split()
        land_count.cards = cards
    cards = land_count.cards
    section = None
    count = 0
    for line in deck.read_text(encoding='utf-8-sig').splitlines():
        if line.startswith('['): section = line
        elif section == '[Main]' and line.strip():
            qty, name = line.split(' ', 1)
            name = name.split('|')[0]
            if name not in cards: raise RuntimeError('Unknown card: ' + name)
            if cards[name]: count += int(qty)
    return count


def verify(request):
    for asset in request['assets']:
        expected = asset['sha256']
        # Reviewed infrastructure upgrades are additive provenance, never edits
        # to the frozen experiment request or simulation inputs.
        revisions = STORE / f'seed{request.get("seed", 0)}' / 'runner-revisions.jsonl'
        if Path(asset['path']).resolve() == (HERE / 'CommanderBatch.py').resolve() and revisions.exists():
            for revision in cohort.load_jsonl(revisions):
                if revision['previousSha256'] != expected or sha(revision['previousSource']) != expected:
                    raise RuntimeError('Broken runner revision chain')
                expected = revision['sha256']
        if sha(asset['path']) != expected:
            raise RuntimeError('Input changed: ' + asset['path'])
    if request['ai']['externalAIEnabled'] is not False:
        raise RuntimeError('Only standard Forge AI is permitted')


def prepare(args):
    STORE.mkdir(parents=True, exist_ok=True)
    batch = STORE / f'seed{args.seed}'
    if args.resume:
        request = read(batch / 'request.json')
        if request['target'] != args.games or request['maxAttempts'] != args.max_attempts:
            raise RuntimeError('Resume must use the saved game target and attempt limit')
        if request['publishOnCompletion'] != args.publish:
            raise RuntimeError('Resume must preserve the saved publication option')
        if (batch / 'payload-hashes.json').exists(): raise RuntimeError('Already validated; use publication retry')
        verify(request)
    else:
        if (ROOT / f'work/commander-comparisons/seed{args.seed}').exists():
            raise RuntimeError('Seed already has an older experiment')
        if (ROOT / f'work/decklist-source/rakdos/forge-tests/reports/seed{args.seed}').exists():
            raise RuntimeError('Published seed already exists')
        if batch.exists(): raise RuntimeError('Batch exists; use -Resume with its original settings')
        jar = ROOT / 'forge-gui-desktop/target/forge-gui-desktop-2.0.16-SNAPSHOT-jar-with-dependencies.jar'
        deckdir = ROOT / 'work/commander-smoke-decks'
        lands = {name: land_count(deckdir / filename) for name, filename, _ in VARIANTS}
        for name, _, expected in VARIANTS:
            if lands[name] != expected: raise RuntimeError(f'{name}: expected {expected} lands, got {lands[name]}')
        assets = [dict(path=str(jar), sha256=sha(jar))]
        decks = [dict(file=f, sha256=sha(deckdir / f)) for f in [v[1] for v in VARIANTS] + OPPONENTS]
        batch.mkdir()  # Exclusive creation: never overwrite an experiment.
        (batch / 'decks').mkdir()
        for deck in decks:
            shutil.copy2(deckdir / deck['file'], batch / 'decks' / deck['file'])
            assets.append(dict(path=str(batch / 'decks' / deck['file']), sha256=deck['sha256']))
        for filename in ['CommanderBatch.py', 'Run-ValidCommanderCohort.py', 'Validate-CommanderTelemetry.py']:
            assets.append(dict(path=str(HERE / filename), sha256=sha(HERE / filename)))
        request = dict(schemaVersion=1, seed=args.seed, target=args.games, maxAttempts=args.max_attempts,
            timeoutSeconds=args.timeout, heapGiB=4, activeProcessorCount=2, parallelGames=1, createdUtc=utc(), jar=str(jar),
            java=str(Path(shutil.which('java.exe') or shutil.which('java')).resolve()),
            forgeCommit=git(ROOT, 'rev-parse', 'HEAD'), forgeDirty=bool(git(ROOT, 'status', '--porcelain')),
            deckHashes=decks, landCounts=lands, assets=assets, publishOnCompletion=args.publish,
            ai=dict(externalAIEnabled=False, captureFailures=True, captureRoles=True),
            seatPolicy='fixed', seats=['Rivaz candidate', *OPPONENTS],
            seedPolicy='Same base seed plus attempt offset for each variant; divergent RNG streams are not paired games')
        write(batch / 'request.json', request)
    write(batch / 'status.json', dict(state='QUEUED', heartbeatUtc=utc(), seed=args.seed, results=str(batch)))
    write(STORE / 'latest.json', dict(batch=str(batch)))
    print(json.dumps(dict(batch=str(batch))))


class WorkerLock:
    """Windows file lock is released by the OS even after a crash; never unlink it."""
    def __enter__(self):
        import msvcrt
        STORE.mkdir(parents=True, exist_ok=True)
        self.stream = (STORE / 'worker.lock').open('a+b')
        self.stream.seek(0)
        self.stream.write(b'0'); self.stream.flush(); self.stream.seek(0)
        try: msvcrt.locking(self.stream.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            self.stream.close()
            raise RuntimeError('Another batch worker is running')
        return self

    def __exit__(self, *exc):
        self.stream.close()


def progress(batch, request, state, **extra):
    variants = []
    for name, _, _ in VARIANTS:
        p = batch / name / 'cohort-summary.json'
        s = read(p) if p.exists() else {}
        variants.append(dict(variant=name, valid=s.get('validGames', 0), attempted=s.get('attemptedGames', 0),
                             failed=s.get('failedGames', 0), remaining=request['target'] - s.get('validGames', 0)))
    write(batch / 'status.json', dict(state=state, heartbeatUtc=utc(), workerPid=os.getpid(),
        seed=request['seed'], results=str(batch), variants=variants, **extra))


def kill_tree(process):
    subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], capture_output=True, timeout=30)
    process.wait(timeout=30)


def classify(directory, seed, code):
    attempts = cohort.classify_batch(directory, seed, 1, False, code, True)
    if not attempts:
        attempts = [dict(runSeed=seed, gameIndex=0, gameId=None, valid=False, simulationStatus='LAUNCH_FAILED',
            winner=None, draw=None, finalTurnCount=None, recordedOutcome=None, failures=[],
            telemetryErrors=['No started-game evidence'], processExitCode=code, candidateExposure=[])]
    for a in attempts:
        if code != 0:
            a.update(valid=False, winner=None, draw=None, simulationStatus='PROCESS_FAILED')
            a['telemetryErrors'].append(f'Process exited {code}')
        # A provider attempt must fail inclusion, even if no call succeeded.
        metrics = directory / f'commander-seed{seed}.metrics.csv'
        if metrics.exists():
            with metrics.open(encoding='utf-8-sig', newline='') as stream:
                rows = list(csv.DictReader(stream))
            if any(r.get('providerAttempted') != 'false' for r in rows):
                a.update(valid=False, winner=None, draw=None, simulationStatus='TELEMETRY_FAILED')
                a['telemetryErrors'].append('Provider attempt or missing provider flag')
    return attempts


def save_state(output, attempts, request, batches):
    # A read-only status reader or antivirus may briefly deny Windows replacement.
    for retry in range(30):
        try:
            summary = cohort.write_state(output, attempts, request['target'], request['maxAttempts'], batches)
            summary['unknownProcessExits'] = sum(b['processExitCode'] is None for b in batches)
            summary['workerInterruptions'] = sum(bool(a.get('workerInterrupted')) or a['simulationStatus']=='INTERRUPTED' for a in attempts)
            summary['processLaunchFailures'] = sum(b['attemptedGames']==0 or b['processExitCode'] not in (0, None) for b in batches)
            write(output / 'cohort-summary.json', summary)
            return summary
        except PermissionError:
            if retry == 29: raise
            time.sleep(0.1)


def append_event(path, event, **details):
    with Path(path).open('a', encoding='utf-8') as stream:
        stream.write(json.dumps(dict(utc=utc(), event=event, pid=os.getpid(), **details), ensure_ascii=False)+'\n')
        stream.flush()
        os.fsync(stream.fileno())


def process_context():
    context = dict(pid=os.getpid(), parentPid=os.getppid(), executable=sys.executable)
    if os.name == 'nt':
        kernel = ctypes.windll.kernel32
        kernel.GetConsoleWindow.restype = ctypes.c_void_p
        session = ctypes.c_ulong()
        kernel.ProcessIdToSessionId(os.getpid(), ctypes.byref(session))
        context.update(sessionId=session.value, consoleWindow=kernel.GetConsoleWindow() or 0)
    return context


def independent_validation(directory):
    command = [sys.executable, str(HERE / 'Validate-CommanderTelemetry.py'), str(directory),
               '--games-per-seed', '1', '--expected-seeds', '1', '--require-clean-runtime']
    report = subprocess.run(command, capture_output=True, text=True, timeout=120)
    if report.returncode: raise RuntimeError('Independent validation failed: ' + report.stderr)
    validation = json.loads(report.stdout)
    if any(r['providerAttempts'] != 0 for r in validation['runs']):
        raise RuntimeError('External provider attempt detected')
    return validation


def completion_hashes(directory, seed):
    return {suffix: sha(directory / f'commander-seed{seed}.{suffix}') for suffix in
            ['log', 'games.jsonl', 'gamelog.jsonl', 'metrics.csv', 'decisions.jsonl', 'roles.jsonl']}


def completed_attempt(directory, seed, code):
    if code is None:
        text = (directory / f'commander-seed{seed}.log').read_text(encoding='utf-8-sig')
        if 'gameResults attempted=1 valid=1 failed=0 ' not in text:
            raise RuntimeError('Missing clean terminal engine summary; exit was not observed')
    rows = classify(directory, seed, 0 if code is None else code)
    if len(rows) != 1 or not rows[0]['valid']: raise RuntimeError('Completion is not a clean valid game')
    validation = independent_validation(directory)
    rows[0]['processExitCode'] = code  # Never invent an observed exit code of zero.
    return rows[0], validation


def assert_child_stopped(directory):
    child = read(directory / 'process.json') if (directory / 'process.json').exists() else None
    if child:
        check = subprocess.run(['tasklist', '/FI', f'PID eq {child["pid"]}', '/FO', 'CSV', '/NH'],
                               capture_output=True, text=True, timeout=30)
        if check.returncode: raise RuntimeError('Could not check recorded child PID')
        if f'"{child["pid"]}"' in check.stdout:
            raise RuntimeError(f'Orphan process {child["pid"]} still exists; stop it before recovery')


def reconcile_variant(batch, request, name):
    output = batch / name
    output.mkdir(exist_ok=True)
    attempts = cohort.load_jsonl(output / 'attempts.jsonl') if (output / 'attempts.jsonl').exists() else []
    batches = read(output / 'cohort-summary.json')['batches'] if (output / 'cohort-summary.json').exists() else []
    keys = [(a['runSeed'], a['gameIndex']) for a in attempts]
    if len(keys) != len(set(keys)): raise RuntimeError('Duplicate seed/game identities in ledger; refusing recovery')
    directories = sorted(output.glob('seed-*'))
    # Check all children before changing any ledger, including previously saved seeds.
    for directory in directories: assert_child_stopped(directory)
    known = {b['runSeed'] for b in batches}
    # The ledger is saved before the summary. Recover that write window without
    # classifying a completed ledger entry a second time as an interruption.
    for seed in sorted({a['runSeed'] for a in attempts} - known):
        items = [a for a in attempts if a['runSeed'] == seed]
        batches.append(dict(runSeed=seed, requestedGames=1, attemptedGames=len(items),
            processExitCode=items[0]['processExitCode'], rawDirectory=items[0]['rawDirectory'],
            recoveredSummary=True))
        known.add(seed)
    for directory in directories:
        seed = int(directory.name[5:])
        previous = [a for a in attempts if a['runSeed']==seed]
        if seed in known and not any(a['simulationStatus']=='INTERRUPTED' for a in previous): continue
        code = read(directory / 'exit.json')['code'] if (directory / 'exit.json').exists() else None
        try:
            item, validation = completed_attempt(directory, seed, code)
            item.update(workerInterrupted=True, completionEvidence='independently-validated-terminal-exports')
            proof = dict(runSeed=seed, observedProcessExitCode=code, validation=validation,
                         hashes=completion_hashes(directory, seed))
            if not (directory / 'recovery.json').exists(): write(directory / 'recovery.json', proof)
            elif read(directory / 'recovery.json')['hashes'] != proof['hashes']:
                raise RuntimeError('Recovered exports changed')
            recovered = [item]
        except (RuntimeError, OSError, ValueError, KeyError) as error:
            if previous: continue  # Preserve the original interrupted record unchanged.
            recovered = classify(directory, seed, -1)
            for item in recovered:
                item['simulationStatus'] = 'INTERRUPTED'
                item['workerInterrupted'] = True
                item['telemetryErrors'].append('Recovery validation: '+str(error))
        if previous:
            if len(previous)!=1 or len(recovered)!=1: raise RuntimeError('Ambiguous recovery identity')
            index = attempts.index(previous[0])
            recovered[0].update(attemptIndex=previous[0]['attemptIndex'], rawDirectory=str(directory))
            append_event(batch / 'reconciliation.jsonl', 'PROMOTE_COMPLETED_ATTEMPT', original=previous[0], recovered=recovered[0])
            attempts[index] = recovered[0]
        else:
            for item in recovered:
                item.update(attemptIndex=len(attempts), rawDirectory=str(directory))
                append_event(batch / 'reconciliation.jsonl', 'RECOVER_UNLEDGERED_ATTEMPT', recovered=item)
                attempts.append(item)
        batches = [b for b in batches if b['runSeed'] != seed]
        batches.append(dict(runSeed=seed, requestedGames=1, attemptedGames=len(recovered),
            processExitCode=recovered[0]['processExitCode'], rawDirectory=str(directory),
            interrupted=True, recoveredCompleted=all(a['valid'] for a in recovered)))
    save_state(output, attempts, request, batches)
    return attempts, batches


def run_variant(batch, request, name, filename):
    output = batch / name
    attempts, batches = reconcile_variant(batch, request, name)
    seed = max([request['seed'] - 1] + [b['runSeed'] for b in batches]) + 1
    while sum(a['valid'] for a in attempts) < request['target'] and len(attempts) < request['maxAttempts']:
        verify(request)
        directory = output / f'seed-{seed}'
        directory.mkdir()
        prefix = directory / f'commander-seed{seed}'
        command = [request['java'], '-Xmx4g', '-XX:ActiveProcessorCount=2', '-Djava.awt.headless=true',
            '-jar', request['jar'], 'sim', '-d', filename, *OPPONENTS, '-n', '1', '-s', str(seed),
            '-f', 'Commander', '-c', str(request['timeoutSeconds']), '-q', '-D', str(batch / 'decks'),
            '--capture-simulation-failures']
        for option, suffix in [('metrics-csv', 'metrics.csv'), ('decision-audit-jsonl', 'decisions.jsonl'),
                ('game-results-jsonl', 'games.jsonl'), ('game-log-jsonl', 'gamelog.jsonl'),
                ('commander-role-jsonl', 'roles.jsonl')]:
            command += ['--' + option, str(prefix) + '.' + suffix]
        write(directory / 'command.json', command)
        append_event(batch / 'lifecycle.jsonl', 'ENGINE_LAUNCH', variant=name, runSeed=seed, command=command)
        started = time.monotonic()
        code = -1
        process = None
        with Path(str(prefix) + '.log').open('w', encoding='utf-8') as log:
            try:
                process = subprocess.Popen(command, cwd=ROOT / 'forge-gui', stdout=log, stderr=subprocess.STDOUT,
                    creationflags=subprocess.CREATE_NO_WINDOW)
                write(directory / 'process.json', dict(pid=process.pid, startedUtc=utc()))
                append_event(batch / 'lifecycle.jsonl', 'ENGINE_STARTED', variant=name, runSeed=seed, childPid=process.pid)
                while process.poll() is None:
                    progress(batch, request, 'RUNNING', activeVariant=name, activeSeed=seed,
                             childPid=process.pid, currentAttemptSeconds=round(time.monotonic()-started))
                    if time.monotonic() - started > request['timeoutSeconds'] + 120:
                        append_event(batch / 'lifecycle.jsonl', 'ENGINE_OUTER_TIMEOUT', runSeed=seed, childPid=process.pid)
                        kill_tree(process)
                        break
                    time.sleep(5)
                code = process.returncode
            except Exception as error:
                log.write('\nLauncher error: ' + repr(error) + '\n'); log.flush()
                if process is not None and process.poll() is None: kill_tree(process)
        write(directory / 'exit.json', dict(code=code, wallSeconds=time.monotonic()-started, endedUtc=utc()))
        append_event(batch / 'lifecycle.jsonl', 'ENGINE_EXIT', variant=name, runSeed=seed, childPid=getattr(process,'pid',None), exitCode=code)
        for item in classify(directory, seed, code):
            item.update(attemptIndex=len(attempts), rawDirectory=str(directory))
            attempts.append(item)
        batches.append(dict(runSeed=seed, requestedGames=1, attemptedGames=1, processExitCode=code,
                            rawDirectory=str(directory)))
        summary = save_state(output, attempts, request, batches)
        progress(batch, request, 'RUNNING', activeVariant=name)
        print(f'{name}: {summary["validGames"]}/{request["target"]} valid; {len(attempts)} attempts', flush=True)
        seed += 1
    if sum(a['valid'] for a in attempts) != request['target']:
        raise RuntimeError(f'{name}: maximum attempts exhausted')


def final_gate(batch, request):
    verify(request)
    results = []
    for name, _, _ in VARIANTS:
        output = batch / name
        attempts = cohort.load_jsonl(output / 'attempts.jsonl')
        valid = [a for a in attempts if a['valid']]
        if len(valid) != request['target']: raise RuntimeError('Wrong valid count for ' + name)
        for item in valid:
            directory = Path(item['rawDirectory'])
            if directory.resolve().parent != output.resolve(): raise RuntimeError('Raw path outside batch')
            exit_code = read(directory / 'exit.json')['code'] if (directory / 'exit.json').exists() else None
            if exit_code is None:
                proof = read(directory / 'recovery.json')
                if proof['hashes'] != completion_hashes(directory, item['runSeed']): raise RuntimeError('Recovered exports changed')
            rechecked, validation = completed_attempt(directory, item['runSeed'], exit_code)
            if rechecked['recordedOutcome'] != item['recordedOutcome']:
                raise RuntimeError('Final classification disagrees with inclusion ledger')
            write(directory / 'validation.json', validation)
        wins = sum((a['recordedOutcome']['winner'] or '').startswith('Ai(1)-') for a in valid)
        results.append(dict(variant=name, games=len(valid), attempts=len(attempts), failures=len(attempts)-len(valid),
            wins=wins, winRate=wins/len(valid), averageDurationSeconds=sum(a['recordedOutcome']['durationMs'] for a in valid)/len(valid)/1000,
            seeds=[a['runSeed'] for a in attempts], providerAttempts=0, validation='PASS'))
    return results


def inventory(directory):
    return {str(p.relative_to(directory)).replace('\\', '/'): sha(p)
            for p in sorted(directory.rglob('*')) if p.is_file()}


def complete(batch, request):
    results = final_gate(batch, request)
    write(batch / 'summary.json', dict(validation='PASS', results=results,
        caution='Fixed seats; replacement results are conditional on no detected engine failure. Smoke games are not pooled with previous experiments.'))
    manifest = dict(request=request, completedUtc=utc(), validation='PASS', results=results)
    if (batch / 'runner-revisions.jsonl').exists():
        manifest['runnerRevisions'] = cohort.load_jsonl(batch / 'runner-revisions.jsonl')
    if (batch / 'manifest.json').exists():
        previous = read(batch / 'manifest.json')
        if previous['request'] != request or previous['results'] != results or previous['validation'] != 'PASS':
            raise RuntimeError('Existing manifest disagrees with final validation')
    else:
        write(batch / 'manifest.json', manifest)
    payload = batch / 'publication-payload'
    staging = Path(tempfile.mkdtemp(prefix='payload-preparation-', dir=batch))
    for name, _, _ in VARIANTS: shutil.copytree(batch / name, staging / name)
    shutil.copytree(batch / 'decks', staging / 'decks')
    for filename in ['request.json', 'summary.json', 'manifest.json']: shutil.copy2(batch / filename, staging / filename)
    for filename in ['runner-revisions.jsonl', 'reconciliation.jsonl', 'lifecycle.jsonl']:
        if (batch / filename).exists(): shutil.copy2(batch / filename, staging / filename)
    if payload.exists():
        if inventory(payload) != inventory(staging):
            raise RuntimeError('Existing publication snapshot differs; refusing overwrite')
        # Preserve the extra preparation snapshot for diagnosis after recovery.
    else:
        staging.rename(payload)
    write(batch / 'payload-hashes.json', inventory(payload))


def publish(batch, dry_run=False):
    request = read(batch / 'request.json')
    # Revalidate live exports, then verify the immutable publication snapshot.
    final_gate(batch, request)
    payload = batch / 'publication-payload'
    hashes = read(batch / 'payload-hashes.json')
    if inventory(payload) != hashes: raise RuntimeError('Publication payload changed')
    repo = ROOT / 'work/decklist-source'
    relative = f'rakdos/forge-tests/reports/seed{request["seed"]}'
    destination = repo / relative
    if git(repo, 'branch', '--show-current') != 'main': raise RuntimeError('Publication requires main')
    if git(repo, 'remote', 'get-url', 'origin') != 'https://github.com/cwppt/MTG-deck-lists.git':
        raise RuntimeError('Unexpected publication remote')
    state_path = batch / 'publication.json'
    state = read(state_path) if state_path.exists() else {}
    dirty = git(repo, 'status', '--porcelain', '--untracked-files=all')
    if destination.exists():
        if not state.get('ownedDestination') or inventory(destination) != hashes:
            raise RuntimeError('Existing publication destination; refusing overwrite')
        changed = git(repo, 'diff', '--name-only') + '\n' + git(repo, 'diff', '--cached', '--name-only')
        untracked = git(repo, 'ls-files', '--others', '--exclude-standard')
        if any(p and not p.startswith(relative + '/') for p in (changed + '\n' + untracked).splitlines()):
            raise RuntimeError('Unrelated repository changes; publication deferred')
    elif dirty:
        raise RuntimeError('Deck repository has unrelated pending changes')
    if dry_run:
        print(json.dumps(dict(validation='PASS', publication='DRY_RUN', destination=str(destination), files=len(hashes))))
        return
    progress(batch, request, 'PUBLISHING')
    if not destination.exists():
        # Ownership is recorded before copy; partial copies are never overwritten on retry.
        state.update(ownedDestination=True, startedUtc=utc())
        write(state_path, state)
        shutil.copytree(payload, destination)
    if not state.get('commit'):
        git(repo, 'add', '--', relative)
        paths = git(repo, 'diff', '--cached', '--name-only').splitlines()
        if not paths or any(not p.startswith(relative + '/') for p in paths):
            raise RuntimeError('Staged changes do not match publication destination')
        git(repo, 'commit', '-m', f'Add validated Commander batch seed {request["seed"]}', '--', relative)
        state['commit'] = git(repo, 'rev-parse', 'HEAD')
        write(state_path, state)
    if git(repo, 'status', '--porcelain'): raise RuntimeError('Repository changed before push')
    if git(repo, 'rev-parse', 'HEAD') != state['commit']: raise RuntimeError('HEAD changed; manual reconciliation required')
    git(repo, 'push', 'origin', state['commit'] + ':refs/heads/main')
    state.update(state='PUBLISHED', publishedUtc=utc())
    write(state_path, state)
    progress(batch, request, 'PUBLISHED', publicationCommit=state['commit'])


def work(args):
    batch = Path(args.batch).resolve()
    if batch.parent != STORE.resolve(): raise RuntimeError('Batch must be within commander-batches')
    request = read(batch / 'request.json')
    with WorkerLock():
        stop = threading.Event()
        def heartbeat():
            while not stop.is_set():
                write(batch / 'heartbeat.json', dict(heartbeatUtc=utc(), workerPid=os.getpid()))
                stop.wait(5)
        thread = threading.Thread(target=heartbeat, daemon=True)
        thread.start()
        try:
            if args.mode == 'publish':
                publish(batch, args.dry_run)
                return
            verify(request)
            progress(batch, request, 'RUNNING')
            for name, filename, _ in VARIANTS: run_variant(batch, request, name, filename)
            progress(batch, request, 'VALIDATING')
            complete(batch, request)
            progress(batch, request, 'VALIDATED')
            if request['publishOnCompletion']: publish(batch)
        except Exception as error:
            validated = (batch / 'manifest.json').exists() and (batch / 'payload-hashes.json').exists()
            progress(batch, request, 'PUBLICATION_FAILED' if validated else 'FAILED', error=str(error), exitCode=1)
            raise
        finally:
            stop.set()
            thread.join(timeout=10)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode', choices=['prepare', 'run', 'publish', 'reconcile', 'probe'])
    p.add_argument('--batch')
    p.add_argument('--games', type=int, default=25)
    p.add_argument('--seed', type=int, default=2400)
    p.add_argument('--max-attempts', type=int, default=75)
    p.add_argument('--timeout', type=int, default=180)
    p.add_argument('--resume', action='store_true')
    p.add_argument('--publish', action='store_true')
    p.add_argument('--dry-run', action='store_true')
    p.add_argument('--seconds', type=int, default=30)
    args = p.parse_args()
    if args.mode == 'prepare':
        if not 1 <= args.games <= args.max_attempts <= 30000 or not 30 <= args.timeout <= 3600 or args.seed <= 0:
            p.error('Invalid limits')
        prepare(args)
    elif args.mode == 'probe':
        directory = Path(args.batch)
        directory.mkdir(parents=True, exist_ok=True)
        write(directory / 'probe-start.json', dict(utc=utc(), **process_context()))
        for second in range(args.seconds):
            write(directory / 'probe-heartbeat.json', dict(utc=utc(), second=second))
            time.sleep(1)
        write(directory / 'probe-complete.json', dict(utc=utc(), exitCode=0, **process_context()))
    elif args.mode == 'reconcile':
        batch = Path(args.batch).resolve()
        if batch.parent != STORE.resolve(): raise RuntimeError('Batch must be within commander-batches')
        with WorkerLock():
            request = read(batch / 'request.json')
            verify(request)
            for name, _, _ in VARIANTS: reconcile_variant(batch, request, name)
            progress(batch, request, 'RECONCILED_STOPPED', note='Recovery only; simulations have not resumed')
    else: work(args)


if __name__ == '__main__':
    if len(sys.argv)>1 and sys.argv[1] in ('run','publish','probe','reconcile') and '--batch' in sys.argv and '--dry-run' not in sys.argv:
        logdir = Path(sys.argv[sys.argv.index('--batch')+1])
        logdir.mkdir(parents=True, exist_ok=True)
        # pythonw has no stdout/stderr: open our own UTF-8 log before doing work.
        logfile = logdir / ('worker-session-'+uuid.uuid4().hex+'.log')
        with logfile.open('w', encoding='utf-8', buffering=1) as stream:
            sys.stdout = sys.stderr = stream
            faulthandler.enable(file=stream)
            append_event(logdir / 'lifecycle.jsonl', 'CLI_START', mode=sys.argv[1], log=str(logfile), context=process_context())
            def interrupted(signum, frame):
                append_event(logdir / 'lifecycle.jsonl', 'CONTROL_SIGNAL', signal=signum)
                raise KeyboardInterrupt('Worker received signal '+str(signum))
            for signum in (signal.SIGINT, getattr(signal, 'SIGBREAK', signal.SIGINT)):
                signal.signal(signum, interrupted)
            try:
                main()
                append_event(logdir / 'lifecycle.jsonl', 'CLI_EXIT', mode=sys.argv[1], exitCode=0)
            except BaseException as error:
                traceback.print_exc()
                append_event(logdir / 'lifecycle.jsonl', 'CLI_EXIT', mode=sys.argv[1], exitCode=1, error=repr(error))
                sys.exit(1)
    else:
        main()
