"""Bounded matched-seed development benchmark; no replacements or publication."""
import argparse
from concurrent.futures import ThreadPoolExecutor, wait, FIRST_COMPLETED
import json
from pathlib import Path
import shutil
import sys
import threading
import time
import traceback

import CommanderBatch as api
import ParallelCommanderPool as pool

STORE = api.ROOT / 'work/commander-advisory-replays'
SELECTION = [('R2', 2502), ('R3', 2870), ('R3-38L', 3102), ('R2', 2558), ('R3', 2809)]
CELLS = [('five-300-advisory-budget', 5, 300)]


def prepare():
    source = api.STORE / 'seed2500'
    original = api.read(source / 'request.json')
    # Only simulation inputs are reused; experiment source/result files stay frozen.
    for asset in original['assets']:
        if Path(asset['path']).suffix != '.py' and api.sha(asset['path']) != asset['sha256']:
            raise RuntimeError('Frozen simulation input changed: ' + asset['path'])
    if api.inventory(source / 'preferences-snapshot') != original['preferenceHashes']:
        raise RuntimeError('Frozen preferences changed')
    processes = api.process_snapshot()
    api.assert_batch_stopped(source, processes)
    identifier = time.strftime('%Y%m%d-%H%M%S', time.gmtime())
    batch = STORE / identifier
    STORE.mkdir(parents=True, exist_ok=True)
    with api.WorkerLock():
        batch.mkdir()
        shutil.copytree(source / 'decks', batch / 'decks')
        shutil.copytree(source / 'preferences-snapshot', batch / 'preferences-snapshot')
        originals = []
        source_hashes = {}
        for name, seed in SELECTION:
            rows = api.cohort.load_jsonl(source / name / 'attempts.jsonl')
            item = next(a for a in rows if a['runSeed'] == seed)
            if item['valid']: raise RuntimeError('Replay selection must contain failed attempts')
            originals.append(dict(variant=name, seed=seed, originalStatus=item['simulationStatus']))
        for name in ['request.json', 'status.json', 'manifest.json', 'summary.json', 'payload-hashes.json', 'publication.json']:
            path = source / name
            if path.exists(): source_hashes[str(path)] = api.sha(path)
        for name, _, _ in api.VARIANTS:
            for filename in ['attempts.jsonl', 'valid-games.jsonl', 'failed-games.jsonl', 'cohort-summary.json']:
                path = source / name / filename
                source_hashes[str(path)] = api.sha(path)
        request = dict(sourceBatch=str(source), sourceRequestSha256=api.sha(source / 'request.json'),
            selection=originals, cells=CELLS, java=original['java'], jar=original['jar'],
            jarSha256=api.sha(original['jar']), heapGiB=original['heapGiB'],
            activeProcessorCount=original['activeProcessorCount'],
            javaOptions=['-Dforge.ai.evalDiagnostics=true','-Dforge.ai.evalBudgetMs=500','-Dforge.ai.evalWorkLimit=2000'],
            preferenceHashes=api.inventory(batch / 'preferences-snapshot'), deckHashes=api.inventory(batch / 'decks'),
            sourceHashes=source_hashes,
            codeHashes={str(api.HERE/name):api.sha(api.HERE/name) for name in
                ['ReplayFailures.py','CommanderBatch.py','ParallelCommanderPool.py','Run-ValidCommanderCohort.py','Validate-CommanderTelemetry.py']},
            rationale='Same five failed seeds; explicit experimental advisory prediction budget of500ms/2000 checkpoints. Fixed seats/JAR/decks/preferences, 300second game limit, five workers. Conservative heuristic fallbacks may change AI strength; no pooling or publication.')
        api.write(batch / 'request.json', request)
        api.write(batch / 'status.json', dict(state='PREPARED', batch=str(batch), totalGames=len(SELECTION)*len(CELLS)))
    print(json.dumps(dict(batch=str(batch))))


def verify(batch, request):
    if api.sha(request['jar']) != request['jarSha256']: raise RuntimeError('JAR changed')
    for path, expected in request['codeHashes'].items():
        if api.sha(path) != expected: raise RuntimeError('Benchmark source changed: '+path)
    if api.inventory(batch/'decks') != request['deckHashes']: raise RuntimeError('Deck snapshot changed')
    if api.inventory(batch/'preferences-snapshot') != request['preferenceHashes']: raise RuntimeError('Preference snapshot changed')
    for path, expected in request['sourceHashes'].items():
        if api.sha(path) != expected: raise RuntimeError('Original batch record changed: '+path)


def run(batch):
    request=api.read(batch/'request.json')
    results=[]; active=pool.ActiveGames(); stop=threading.Event()
    started=time.monotonic()
    def status(state, **extra):
        api.write(batch/'status.json',dict(state=state,utc=api.utc(),workerPid=api.os.getpid(),
            elapsedSeconds=round(time.monotonic()-started,1),completedGames=len(results),
            totalGames=len(request['selection'])*len(request['cells']),activeGames=active.snapshot(),**extra))
    with api.WorkerLock():
        if (batch/'results.jsonl').exists(): raise RuntimeError('Benchmark already attempted; never overwrite or resume implicitly')
        verify(batch,request)
        try:
            for label, concurrency, timeout in request['cells']:
                verify(batch,request)
                cell=batch/label;cell.mkdir(); jobs=[]
                for ordinal,selected in enumerate(request['selection']):
                    variant=selected['variant'];seed=selected['seed'];output=cell/variant/f'seed-{seed}'
                    output.mkdir(parents=True)
                    index=next(i for i,v in enumerate(api.VARIANTS) if v[0]==variant)
                    job=dict(variant=variant,variantIndex=index,filename=api.VARIANTS[index][1],runSeed=seed,
                        attemptIndex=ordinal,rawDirectory=str(output),reservedUtc=api.utc())
                    api.write(output/'attempt.json',job);jobs.append(job)
                cell_start=time.monotonic(); futures={}; pending=iter(jobs)
                with ThreadPoolExecutor(max_workers=concurrency) as executor:
                    exhausted=False
                    while futures or not exhausted:
                        while len(futures)<concurrency and not exhausted:
                            try:job=next(pending)
                            except StopIteration:exhausted=True;break
                            active.update(job)
                            game_request=dict(request,timeoutSeconds=timeout)
                            futures[executor.submit(pool.execute,job,batch,game_request,active,stop,api)]=job
                        status('RUNNING',cell=label,concurrency=concurrency,timeoutSeconds=timeout)
                        if not futures:break
                        done,_=wait(futures,timeout=1,return_when=FIRST_COMPLETED)
                        for future in done:
                            job=futures.pop(future);item=future.result();active.remove(job)
                            exit_info=api.read(Path(job['rawDirectory'])/'exit.json') if (Path(job['rawDirectory'])/'exit.json').exists() else {}
                            row=dict(cell=label,concurrency=concurrency,timeoutSeconds=timeout,variant=job['variant'],seed=job['runSeed'],
                                valid=item['valid'],status=item['simulationStatus'],wallSeconds=exit_info.get('wallSeconds'),
                                telemetryErrors=item['telemetryErrors'],rawDirectory=job['rawDirectory'])
                            results.append(row)
                            with (batch/'results.jsonl').open('a',encoding='utf-8') as stream:stream.write(json.dumps(row)+'\n')
                selected=[r for r in results if r['cell']==label];wall=time.monotonic()-cell_start
                api.write(cell/'summary.json',dict(cell=label,attempted=len(selected),valid=sum(r['valid'] for r in selected),
                    wallSeconds=wall,validGamesPerHour=sum(r['valid'] for r in selected)/wall*3600))
            verify(batch,request)
            summaries=[api.read(batch/label/'summary.json') for label,_,_ in request['cells']]
            api.write(batch/'summary.json',dict(cells=summaries,sourcePreserved=True,results=results,
                caution='Small deliberately failure-enriched sample; estimates do not predict overall cohort success rates. Replayed seeds may vary with scheduling.'))
            status('COMPLETE')
        except BaseException as error:
            stop.set();status('FAILED',error=repr(error));raise
        finally:stop.set()


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('mode',choices=['prepare','run']);parser.add_argument('--batch')
    args=parser.parse_args()
    if args.mode=='prepare':prepare()
    else:
        batch=Path(args.batch).resolve()
        if batch.parent!=STORE.resolve():raise RuntimeError('Benchmark path outside development store')
        with (batch/'worker.log').open('a',encoding='utf-8',buffering=1) as log:
            sys.stdout=sys.stderr=log
            try:run(batch)
            except BaseException:traceback.print_exc();sys.exit(1)
