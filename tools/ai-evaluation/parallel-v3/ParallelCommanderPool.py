"""Only the coordinator writes ledgers; game workers own isolated attempt files."""
from concurrent.futures import ThreadPoolExecutor, wait, FIRST_COMPLETED
from pathlib import Path
import os
import shutil
import subprocess
import threading
import time


class ActiveGames:
    def __init__(self):
        self.lock = threading.Lock()
        self.games = {}

    def update(self, job, **fields):
        with self.lock:
            current = self.games.setdefault(job['runSeed'], dict(variant=job['variant'], seed=job['runSeed'],
                attemptIndex=job['attemptIndex'], pid=None, phase='RESERVED', started=time.monotonic()))
            current.update(fields)

    def remove(self, job):
        with self.lock: self.games.pop(job['runSeed'], None)

    def snapshot(self):
        with self.lock:
            return [dict(variant=g['variant'], seed=g['seed'], attemptIndex=g['attemptIndex'],
                         pid=g['pid'], phase=g['phase'], elapsedSeconds=round(time.monotonic()-g['started'],1))
                    for _,g in sorted(self.games.items())]


def allocated_seed(request, variant_index, ordinal):
    if not 0 <= ordinal < request['maxAttempts']: raise RuntimeError('Attempt ordinal outside budget')
    return request['seed'] + variant_index * request['maxAttempts'] + ordinal


def reserve(batch, request, variant_index, ordinal, api):
    name, filename, _ = api.VARIANTS[variant_index]
    seed = allocated_seed(request, variant_index, ordinal)
    directory = batch / name / f'seed-{seed}'
    directory.mkdir()  # Reservation is exclusive and persisted before submission.
    job = dict(variant=name, variantIndex=variant_index, filename=filename, runSeed=seed,
               attemptIndex=ordinal, rawDirectory=str(directory), reservedUtc=api.utc())
    api.write(directory / 'attempt.json', job)
    api.append_event(batch / 'lifecycle.jsonl', 'ATTEMPT_RESERVED', **job)
    return job


def isolated_runtime(directory, batch, api):
    runtime = directory / 'runtime'
    runtime.mkdir()
    home, user, cache, temp = [runtime / name for name in ('home','user','cache','temp')]
    for path in (home,user,cache,temp): path.mkdir()
    prefs = batch / 'preferences-snapshot'
    if prefs.exists(): shutil.copytree(prefs, user / 'preferences')
    # GuiDesktop uses either cwd or ../forge-gui depending on build version.
    # Both views have private profiles; only their resource tree is shared.
    for name in ('run','forge-gui'):
        view = runtime / name; view.mkdir()
        link = view / 'res'; target = api.ROOT / 'forge-gui/res'
        if os.name == 'nt':
            command = ['cmd.exe','/d','/c','mklink','/J',str(link),str(target)]
            result = subprocess.run(command, capture_output=True, text=True, timeout=30,
                                    creationflags=subprocess.CREATE_NO_WINDOW)
            if result.returncode: raise RuntimeError('Could not create private resource view: '+result.stderr+result.stdout)
        else: link.symlink_to(target, target_is_directory=True)
        (view / 'forge.profile.properties').write_text(
            f'userDir={user.as_posix()}\ncacheDir={cache.as_posix()}\n'
            f'decksDir={(user/"decks").as_posix()}\ncardPicsDir={(cache/"pics/cards").as_posix()}\n', encoding='utf-8')
    environment = os.environ.copy()
    environment.update(APPDATA=str(runtime/'appdata'), LOCALAPPDATA=str(runtime/'localappdata'),
                       USERPROFILE=str(home), TEMP=str(temp), TMP=str(temp))
    # Never inherit Java option injection that could defeat standard AI or limits.
    for key in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'): environment.pop(key,None)
    return runtime / 'run', environment, home, temp


def failure(job, code, status, message):
    return dict(runSeed=job['runSeed'], gameIndex=0, gameId=None, valid=False, simulationStatus=status,
        winner=None, draw=None, finalTurnCount=None, recordedOutcome=None, failures=[],
        telemetryErrors=[message], processExitCode=code, candidateExposure=[],
        attemptIndex=job['attemptIndex'], rawDirectory=job['rawDirectory'])


def execute(job, batch, request, active, stop, api):
    directory = Path(job['rawDirectory'])
    prefix = directory / f'commander-seed{job["runSeed"]}'
    process = None; code = None; started = time.monotonic(); cancelled = False
    try:
        cwd, environment, home, temp = isolated_runtime(directory, batch, api)
        command = [request['java'], f'-Xmx{request["heapGiB"]}g',
            f'-XX:ActiveProcessorCount={request["activeProcessorCount"]}', '-Djava.awt.headless=true',
            f'-Duser.home={home}', f'-Djava.io.tmpdir={temp}', '-jar', request['jar'],
            'sim','-d',job['filename'],*api.OPPONENTS,'-n','1','-s',str(job['runSeed']),
            '-f','Commander','-c',str(request['timeoutSeconds']),'-q','-D',str(batch/'decks'),
            '--capture-simulation-failures']
        options=api.AI_PROFILES.get(request.get('aiProfile','standard'))
        if options is None or request.get('javaOptions',[])!=options: raise RuntimeError('Unsupported AI profile options')
        command[1:1]=options
        for option,suffix in [('metrics-csv','metrics.csv'),('decision-audit-jsonl','decisions.jsonl'),
                ('game-results-jsonl','games.jsonl'),('game-log-jsonl','gamelog.jsonl'),('commander-role-jsonl','roles.jsonl')]:
            command += ['--'+option,str(prefix)+'.'+suffix]
        api.write(directory/'command.json',command)
        api.write(directory/'isolation.json',dict(cwd=str(cwd),home=str(home),temp=str(temp),
            user=str(directory/'runtime/user'),cache=str(directory/'runtime/cache'),
            preferenceHashes=request.get('preferenceHashes',{})))
        api.append_event(batch/'lifecycle.jsonl','ENGINE_LAUNCH',variant=job['variant'],runSeed=job['runSeed'],command=command)
        with Path(str(prefix)+'.log').open('w',encoding='utf-8') as log:
            if stop.is_set(): return failure(job,None,'INTERRUPTED','Coordinator stopped before engine launch')
            process = subprocess.Popen(command,cwd=cwd,env=environment,stdout=log,stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
            api.write(directory/'process.json',dict(pid=process.pid,startedUtc=api.utc(),variant=job['variant'],
                      runSeed=job['runSeed'],attemptIndex=job['attemptIndex'],workerPid=os.getpid(),
                      batch=str(batch),rawDirectory=str(directory),processIdentity=api.capture_process_identity(process.pid,command)))
            active.update(job,pid=process.pid,phase='RUNNING',started=time.monotonic())
            api.append_event(batch/'lifecycle.jsonl','ENGINE_STARTED',variant=job['variant'],runSeed=job['runSeed'],childPid=process.pid)
            while process.poll() is None:
                if stop.is_set() or time.monotonic()-started > request['timeoutSeconds']+120:
                    cancelled = stop.is_set()
                    api.append_event(batch/'lifecycle.jsonl','ENGINE_CANCEL' if cancelled else 'ENGINE_OUTER_TIMEOUT',
                                     variant=job['variant'],runSeed=job['runSeed'],childPid=process.pid)
                    api.kill_tree(process)
                    break
                stop.wait(0.5)
            code = process.returncode
        api.write(directory/'exit.json',dict(code=code,wallSeconds=time.monotonic()-started,endedUtc=api.utc()))
        api.append_event(batch/'lifecycle.jsonl','ENGINE_EXIT',variant=job['variant'],runSeed=job['runSeed'],
                         childPid=process.pid,exitCode=code)
        active.update(job,phase='VALIDATING')
        if cancelled: return failure(job,code,'INTERRUPTED','Coordinator requested cancellation')
        rows = api.classify(directory,job['runSeed'],code)
        if len(rows)!=1: return failure(job,code,'TELEMETRY_FAILED','Expected exactly one game per isolated process')
        item = rows[0]
        if item['valid']:
            try:
                checked, validation = api.completed_attempt(directory,job['runSeed'],code)
                api.write(directory/'validation.json',validation)
                item = checked
            except (RuntimeError,ValueError,OSError,KeyError) as error:
                item.update(valid=False,winner=None,draw=None,simulationStatus='TELEMETRY_FAILED')
                item['telemetryErrors'].append('Independent validation: '+str(error))
        item.update(attemptIndex=job['attemptIndex'],rawDirectory=job['rawDirectory'])
        return item
    except Exception as error:
        if process is not None and process.poll() is None:
            api.kill_tree(process); code=process.returncode
        api.write(directory/'launcher-error.json',dict(error=repr(error),observedProcessExitCode=code,utc=api.utc()))
        api.append_event(batch/'lifecycle.jsonl','ATTEMPT_WORKER_ERROR',variant=job['variant'],runSeed=job['runSeed'],error=repr(error))
        return failure(job,code,'LAUNCH_FAILED' if process is None else 'TELEMETRY_FAILED',repr(error))


def accept(state, job, item, request, api):
    if item['runSeed']!=job['runSeed'] or item['gameIndex']!=0 or item['attemptIndex']!=job['attemptIndex']:
        raise RuntimeError('Worker result does not match its reserved attempt')
    if any(a['runSeed']==item['runSeed'] or a['attemptIndex']==item['attemptIndex'] for a in state['attempts']):
        raise RuntimeError('Duplicate completion; refusing to count twice')
    state['attempts'].append(item)
    state['attempts'].sort(key=lambda a:a['attemptIndex'])
    state['batches'].append(dict(runSeed=item['runSeed'],requestedGames=1,attemptedGames=1,
                                processExitCode=item['processExitCode'],rawDirectory=item['rawDirectory']))
    state['batches'].sort(key=lambda b:b['runSeed'])
    api.save_state(state['output'],state['attempts'],request,state['batches'])


def pool_progress(batch, request, states, active, api):
    games = active.snapshot()
    variants = []
    for name,_,_ in api.VARIANTS:
        state=states[name]
        valid=sum(a['valid'] for a in state['attempts'])
        running=sum(g['variant']==name and g['phase']=='RUNNING' for g in games)
        in_flight=sum(g['variant']==name for g in games)
        variants.append(dict(variant=name,valid=valid,attempted=len(state['attempts'])+in_flight,
            completedAttempts=len(state['attempts']),failed=sum(not a['valid'] and a['simulationStatus']!='INTERRUPTED' for a in state['attempts']),
            interrupted=sum(a['simulationStatus']=='INTERRUPTED' for a in state['attempts']),
            running=running,inFlight=in_flight,remaining=request['target']-valid))
    valid=sum(v['valid'] for v in variants)
    api.write(batch/'status.json',dict(state='RUNNING',heartbeatUtc=api.utc(),workerPid=os.getpid(),
        seed=request['seed'],results=str(batch),concurrency=request['parallelGames'],
        activeGames=[g for g in games if g['phase']=='RUNNING'],inFlightGames=games,
        activeGameCount=sum(g['phase']=='RUNNING' for g in games),variants=variants,
        overall=dict(valid=valid,target=request['target']*len(api.VARIANTS),remaining=request['target']*len(api.VARIANTS)-valid,
                     attempted=sum(v['attempted'] for v in variants),failed=sum(v['failed'] for v in variants),interrupted=sum(v['interrupted'] for v in variants),
                     running=sum(v['running'] for v in variants))))


def preflight(batch, request, api, processes=None):
    empty_reservations=[]
    if processes is None: processes=api.process_snapshot()
    api.assert_batch_stopped(batch,processes)
    # No ledger writes until every recorded process has been checked stopped.
    for name,_,_ in api.VARIANTS:
        for directory in (batch/name).glob('seed-*'): api.assert_child_stopped(directory,processes)
    # Reject misattributed reservations and ledger seeds before recovery writes.
    for index,(name,_,_) in enumerate(api.VARIANTS):
        output=batch/name
        base=request['seed']+index*request['maxAttempts']
        for directory in output.glob('seed-*'):
            seed=int(directory.name[5:])
            if not base<=seed<base+request['maxAttempts']: raise RuntimeError('Recovered seed is outside its variant range')
            if not (directory/'attempt.json').exists():
                if any(directory.iterdir()): raise RuntimeError('Missing reservation identity for nonempty attempt')
                reservation=dict(variant=name,variantIndex=index,filename=api.VARIANTS[index][1],runSeed=seed,
                    attemptIndex=seed-base,rawDirectory=str(directory),reservedUtc=None,recoveredEmptyReservation=True)
                empty_reservations.append((directory,reservation))
            else: reservation=api.read(directory/'attempt.json')
            if (reservation['variant'],reservation['variantIndex'],reservation['filename'],reservation['runSeed'],reservation['attemptIndex'])!=(name,index,api.VARIANTS[index][1],seed,seed-base):
                raise RuntimeError('Reservation identity mismatch')
            if Path(reservation['rawDirectory']).resolve()!=directory.resolve(): raise RuntimeError('Reservation raw path mismatch')
            if (directory/'command.json').exists():
                command=api.read(directory/'command.json')
                if command[command.index('-s')+1]!=str(seed) or command[command.index('-d')+1]!=api.VARIANTS[index][1]:
                    raise RuntimeError('Engine command identity mismatch')
        ledger=api.cohort.load_jsonl(output/'attempts.jsonl') if (output/'attempts.jsonl').exists() else []
        for item in ledger:
            if not base<=item['runSeed']<base+request['maxAttempts'] or item['gameIndex']!=0:
                raise RuntimeError('Invalid ledger identity')
            if Path(item['rawDirectory']).resolve()!= (output/f"seed-{item['runSeed']}").resolve():
                raise RuntimeError('Ledger raw path mismatch')
    return empty_reservations


def recover(batch,request,api):
    processes=api.process_snapshot()
    empty_reservations=preflight(batch,request,api,processes)
    states={}; global_seeds=set()
    for directory,reservation in empty_reservations: api.write(directory/'attempt.json',reservation)
    for index,(name,_,_) in enumerate(api.VARIANTS):
        attempts,batches=api.reconcile_variant(batch,request,name,processes)
        for item in attempts:
            ordinal=item['runSeed']-(request['seed']+index*request['maxAttempts'])
            if not 0<=ordinal<request['maxAttempts']: raise RuntimeError('Recovered seed is outside its variant range')
            if item['runSeed'] in global_seeds: raise RuntimeError('Duplicate seed across variants')
            global_seeds.add(item['runSeed'])
            item['attemptIndex']=ordinal
        attempts.sort(key=lambda a:a['attemptIndex'])
        if sum(a['valid'] for a in attempts)>request['target']: raise RuntimeError('Recovered valid count exceeds target')
        api.save_state(batch/name,attempts,request,batches)
        states[name]=dict(attempts=attempts,batches=batches,output=batch/name,
                         nextOrdinal=max([-1]+[a['attemptIndex'] for a in attempts])+1)
    return states


def run_batch(batch, request, api, execute_attempt=execute):
    if not 1<=request['parallelGames']<=16: raise RuntimeError('Invalid concurrency')
    states=recover(batch,request,api)
    active=ActiveGames(); stop=threading.Event(); futures={}; turn=0
    pool=ThreadPoolExecutor(max_workers=request['parallelGames'],thread_name_prefix='ForgeGame')
    try:
        while True:
            # Round-robin variants. Reserve each slot against its remaining valid
            # target, including running and validating jobs, so no overshoot.
            misses=0
            while len(futures)<request['parallelGames'] and misses<len(api.VARIANTS):
                index=turn%len(api.VARIANTS); turn+=1
                name=api.VARIANTS[index][0]; state=states[name]
                in_flight=sum(job['variant']==name for job in futures.values())
                valid=sum(a['valid'] for a in state['attempts'])
                if valid+in_flight>=request['target'] or state['nextOrdinal']>=request['maxAttempts']:
                    misses+=1; continue
                api.verify(request)
                job=reserve(batch,request,index,state['nextOrdinal'],api)
                state['nextOrdinal']+=1
                active.update(job)
                futures[pool.submit(execute_attempt,job,batch,request,active,stop,api)]=job
                misses=0
            pool_progress(batch,request,states,active,api)
            if not futures: break
            done,_=wait(futures,timeout=1,return_when=FIRST_COMPLETED)
            for future in sorted(done,key=lambda f:futures[f]['runSeed']):
                job=futures.pop(future)
                item=future.result()
                accept(states[job['variant']],job,item,request,api)
                active.remove(job)
                api.append_event(batch/'lifecycle.jsonl','RESULT_ACCEPTED',variant=job['variant'],
                    runSeed=job['runSeed'],attemptIndex=job['attemptIndex'],valid=item['valid'])
        incomplete=[name for name,state in states.items() if sum(a['valid'] for a in state['attempts'])!=request['target']]
        if incomplete: raise RuntimeError('Attempt limit exhausted: '+', '.join(incomplete))
    finally:
        stop.set()
        pool.shutdown(wait=True,cancel_futures=True)
