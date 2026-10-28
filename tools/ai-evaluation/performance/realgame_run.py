"""Finite Phase 6 observation-only cohort. No candidate, budget change or promotion."""
from pathlib import Path
import base64,csv,datetime,hashlib,importlib.util,json,os,shutil,subprocess,sys,threading,time,traceback,zipfile
sys.dont_write_bytecode=True
import incremental_performance as api
sys.modules['combat_performance']=api
import combat_run as h
from realgame_analysis import analyse_game,records,scope_records,scopes_summary,dist,jfr_summary
from structured_parity import compare
c=api.core;e=api.e;R=api.R;S=api.SOURCE
RUN='20261028-native-realgame-performance-v6';BRANCH='codex/commander-realgame-performance-v6'
DEST='forge-performance/reports/'+RUN;REPO='cwppt/MTG-deck-lists';URL='https://github.com/'+REPO+'/tree/main/'+DEST
OLD=api.EX/'incremental-performance-v5-01-20261028/validation-02'
FLAGS=api.flags('baseline')
VARIANTS=['Lathliss_R_Control.dck','Lathliss_R_Rebuild_B_Door.dck','Lathliss_R_Rebuild_V2_Throne.dck']
PLAN=[dict(seed=573200+i,deck=VARIANTS[i%3]) for i in range(9)]
DATA=dict(schemaVersion=1,runId=RUN,state='pending',games=[],diagnostics=[],candidate=None,readyForLargerCohorts=False)
ACTIVE={};STOP=threading.Event()
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def write(p,value):return c.write(p,value)
def sha(p):return c.sha(p)
def head():return h.command(['git','-C',S,'rev-parse','HEAD']).stdout.strip()

def verify():
    q=read(R/'run-request.json')
    assert sha(R/'engine.jar')==api.REFERENCE_SHA and sha(api.BASE)==api.EXPECTED
    assert q['flags']==FLAGS and q['plan']==PLAN and q['sourceHead']==head()
    for key,root in [('sourceHashes',S),('helperHashes',api.H),('historicalHashes',OLD)]:
        for path,digest in q[key].items():assert sha(root/path)==digest,'Frozen input changed: '+path
    for path,digest in q['deckHashes'].items():assert sha(R/'decks'/path)==digest
    assert sha(R/'validation-gate.json')==q['validationGateSHA256'] and read(R/'validation-gate.json')['passed']
    return q

def freeze():
    assert not (R/'run-request.json').exists(),'Already frozen'
    assert read(OLD/'completion.json')['passed'] and read(OLD/'checkpoint.json')['state']=='completed'
    assert not read(OLD/'checkpoint.json')['performanceImprovementDemonstrated']
    assert sha(api.REFERENCE)==api.REFERENCE_SHA and sha(api.BASE)==api.EXPECTED
    prior=read(OLD/'run-request.json');prior_state=read(OLD/'checkpoint.json')
    assert prior_state['baselineCohort']['complete'] and prior_state['baselineCohort']['validGames']==3
    assert prior_state['baselineCohort']['flags']==FLAGS
    R.mkdir(parents=True,exist_ok=True);c.admission(R,api.H)
    shutil.copy2(api.REFERENCE,R/'engine.jar');shutil.copytree(OLD/'decks',R/'decks')
    tests=R/'analysis-tests';tests.mkdir()
    c.launch(tests,[Path(sys.executable).with_name('python.exe'),'-B','-m','unittest','test_realgame_analysis','test_structured_parity'],api.HERE,h.ENV,120)
    write(R/'validation-gate.json',dict(passed=True,methods=15,evidence='analysis-tests',engineChanged=False,
        inheritedCorrectnessEvidence='Phase 5 completed identical observation-reference engine; no Java engine tests or synthetic fixtures replayed'))
    paths=h.command(['git','-C',S,'ls-files']).stdout.splitlines()
    source={p:sha(S/p) for p in paths if p.startswith(('tools/ai-evaluation/performance/','tools/ai-evaluation/parallel-v4/'))}
    historical=['run-request.json','completion.json','checkpoint.json','permanent-baseline.json']
    for seed in [573100,573101,573102]:
        historical += [f'permanent-baseline-{seed}/'+name for name in ['execution.log','validation-passed.json','validation.json','command.json',f'commander-seed{seed}.games.jsonl',f'commander-seed{seed}.games.jsonl.profile.jsonl']]
    write(R/'run-request.json',dict(schemaVersion=1,runId=RUN,sourceBranch=BRANCH,sourceHead=head(),engineSHA256=api.REFERENCE_SHA,functionalOriginalSHA256=api.EXPECTED,
        flags=FLAGS,plan=PLAN,pod=read(api.EX/'lathliss-r-throne-20261026/request.json')['pod'],deckHashes=prior['deckHashes'],
        sourceHashes=source,helperHashes=prior['helperHashes'],historicalHashes={p:sha(OLD/p) for p in historical},validationGateSHA256=sha(R/'validation-gate.json'),
        maximumGameWorkers=1,heapGiB=3,processors=2,freeSpaceReserveGiB=3,gameTimeoutSeconds=900,watchdogSeconds=990,
        profiling='At most one separate replay of the slowest valid primary game with bounded 64 MiB JFR. Never pooled into primary timing. No search agent.',
        conditionalOptimisation='None pre-authorised by these observations. Stop after diagnostic evidence; any future candidate needs concrete proof, frozen acceptance, deterministic/structured parity and matched end-to-end comparison.',
        futurePromotionGate='At least 15% on both primary synthetic workloads plus full correctness and defensible whole-game benefit; never add historical percentages.',
        recovery='Exclusive existing worker lock. Resume only validated game proofs with all hashes intact. Never automatically rerun ambiguous or failed attempts. No replacement seeds.'))
    # Preserve actual loaded budget implementation, not merely a potentially stale source file.
    javap=Path(e.JAVA).with_name('javap.exe')
    out=h.command([javap,'-c','-p','-classpath',R/'engine.jar','forge.ai.AiEvaluationDiagnostics'])
    assert out.returncode==0; (R/'frozen-diagnostics-bytecode.txt').write_text(out.stdout,encoding='utf-8')
    with zipfile.ZipFile(R/'engine.jar') as jar:
        loaded=jar.read('forge/ai/AiEvaluationDiagnostics.class')
    write(R/'diagnostics-provenance.json',dict(classSHA256=hashlib.sha256(loaded).hexdigest(),bytecodeSHA256=sha(R/'frozen-diagnostics-bytecode.txt'),
        interpretation='prediction increments work then records fallback when work > workLimit OR elapsed >= budgetMs. boundedPrediction catches only the private budget exception. No per-call reason emitted; fallback counts are not decision errors.'))
    write(R/'workspace-preservation.json',dict(originalWorkspace=str(api.H),originalStatus=h.command(['git','-C',api.H,'status','--short']).stdout,isolatedCheckout=str(S),sourceBranch=BRANCH))
    verify();print('Frozen observation-only Phase 6 plan: nine games, at most one separate diagnostic replay')

def aggregate():
    games=DATA['games'];valid=[g for g in games if g.get('valid')]
    active=sum(g.get('measurementElapsedSeconds',0) for g in games)
    elapsed=DATA.get('primaryEndUnix',time.time())-DATA.get('startedUnix',time.time())
    DATA['cohort']=dict(attempted=len(games),validGames=len(valid),invalidOrFailedAttempts=sum(not g.get('valid') for g in games),
        timeoutAttempts=sum(g.get('exitCode')==124 or g.get('timedOut',False) for g in games),retries=0,
        activeElapsedSeconds=active,elapsedIncludingPublicationSeconds=elapsed,
        validGamesPerHourActiveIncludingStartupValidation=3600*len(valid)/active if active else None,
        validGamesPerHourIncludingPublication=3600*len(valid)/elapsed if elapsed>0 else None,
        gameDurationMs=dist([g['durationMs'] for g in valid]),turnCounts=dist([g['turns'] for g in valid]),
        scope='Primary games only. Active elapsed includes admission, JVM startup, game, independent validation and analysis; publication excluded. Second denominator includes publication and any recovery downtime. Diagnostic replay excluded from both.')
    scopes=[];candidate=[]
    for g in valid:
        a,b=scope_records(R/g['directory'],g['seed']);scopes+=a;candidate+=b
    DATA['fallbackAnalysis']=scopes_summary(scopes,candidate)
    DATA['performanceHistory']=dict(schemaVersion=2,phase5=DATA.get('phase5Historical',{}),
        originalValidatedEngineSHA256=api.EXPECTED,observationReferenceSHA256=api.REFERENCE_SHA,currentBaseline=DATA['cohort'],
        currentCandidate=None,incrementalImprovementPercent=None,cumulativeImprovementPercent=None,
        rule='No current candidate; no measured gain. Historical failed experiments are not combined. Phase 5 games remain separate, not pooled.')

def report():
    return '# Phase 6 — Native Commander performance and AI coverage\n\n'+f"State: **{DATA['state']}**. Updated {now()}. Supervisor PID: {os.getpid()}.\n\n"+ \
        'Observation-only engine; all previous experiments disabled. Nine predeclared games across three frozen variants; no failed-game replacements. No deck win-rate inference.\n\n'+ \
        '## Throughput and real-game baseline\n\n```json\n'+json.dumps(DATA.get('cohort',{}),indent=2)+'\n```\n\n'+ \
        'Primary timings use the frozen structured observation reference with normal 500 ms / 2,000 work budgets. Native diagnostics and structured observer overhead are included. No high-overhead profiler or agent is present in primary games. Small-sample p95 estimates are descriptive.\n\n'+ \
        '## Fallback behaviour\n\n```json\n'+json.dumps(DATA.get('fallbackAnalysis',{}),indent=2)+'\n```\n\n'+ \
        'Fallback calls count budget-checkpoint requests, not misplays or distinct decisions. Missing context/reason fields remain unknown. Scope-end work/time limits are observations, not per-call reason attribution. Candidate events in affected scopes are correlations. Budget adequacy and decision-quality loss require counterfactual completed evaluations; this run does not change policy or budgets to manufacture that comparison.\n\n'+ \
        '## Historical baseline and provenance\n\n```json\n'+json.dumps(DATA.get('phase5Historical',{}),indent=2)+'\n```\n\n'+ \
        'The three Phase 5 games are immutable and analysed separately. Deck mix, cohort size, timing accounting and machine conditions differ, so results are not pooled or presented as speedup. Engine '+api.REFERENCE_SHA+'; functional baseline '+api.EXPECTED+'. Source ['+BRANCH+'](https://github.com/cwppt/forge/tree/'+BRANCH+').\n\n'+ \
        '## Attributable bottlenecks and diagnostics\n\n```json\n'+json.dumps(dict(diagnostics=DATA['diagnostics'],bottleneck=DATA.get('bottleneck','Pending representative real-game diagnostic')),indent=2)+'\n```\n\n'+ \
        'Phase 4 method-entry counts established activity, not elapsed-time attribution. Native evaluation scopes may overlap and do not time all combat search. One separate bounded JFR replay estimates CPU/allocation attribution and records observer/diagnostic frames. Its timing is excluded from throughput; a single profiled/unprofiled pair cannot isolate instrumentation overhead. Allocation weights are sampled, heap/permanent telemetry is at turn boundaries, and exact allocation/copy counts remain unavailable.\n\n'+ \
        '## Correctness and engineering decision\n\n'+DATA.get('recommendation','Pending baseline/diagnostic evidence; no speculative optimisation permitted.')+'\n\n'+ \
        'Every scored game requires native failure classification, independent telemetry validation, frozen deck hashes and no external provider attempts. Diagnostic replay parity is checked separately; any difference is preserved and reported. Existing timeout/result regressions and Phase 5 structured tests are inherited only for the identical engine, not claimed as rerun. No engine changes, merge or promotion. Gains are null until a future matched experiment establishes them; no historical percentages are added.\n\n'+ \
        ('Stopped reason: '+DATA['failure']+'\n\n' if 'failure' in DATA else '')+ \
        '[Measurements](measurements.json) · [Frozen manifest](manifest.json) · [Performance history](performance-history.json). Raw commands, logs, validation proofs, hashes, checkpoints and bounded JFR remain local at `'+str(R)+'`.\n'

def publish(stage):
    DATA['state']=stage;aggregate();write(R/'checkpoint.json',DATA)
    write(R/'status.json',dict(stage=stage,pid=os.getpid(),utc=now(),reportUrl=URL))
    public=R/'public';public.mkdir(exist_ok=True)
    write(public/'measurements.json',DATA);write(public/'manifest.json',read(R/'run-request.json'));write(public/'performance-history.json',DATA['performanceHistory'])
    (public/'REPORT.md').write_text(report(),encoding='utf-8')
    owned=read(R/'published-blobs.json') if (R/'published-blobs.json').exists() else {}
    for name in ['REPORT.md','measurements.json','manifest.json','performance-history.json']:
        content=(public/name).read_bytes();assert len(content)<950000
        endpoint='repos/'+REPO+'/contents/'+DEST+'/'+name
        remote=h.gh(endpoint);payload=dict(message='Commander Phase 6 checkpoint: '+stage,branch='main',content=base64.b64encode(content).decode())
        if remote.returncode==0:
            prior=json.loads(remote.stdout);assert name in owned and prior['sha']==owned[name]['sha'],'Unowned or externally changed report'
            payload['sha']=prior['sha']
        else:assert '404' in remote.stderr or 'Not Found' in remote.stderr,remote.stderr[-500:]
        result=h.gh(endpoint,'PUT',payload);assert result.returncode==0,result.stderr[-700:]
        info=json.loads(result.stdout);owned[name]=dict(sha=info['content']['sha'],commit=info['commit']['sha']);write(R/'published-blobs.json',owned)
    write(R/'publication.json',dict(url=URL,utc=now(),state=stage,pid=os.getpid(),files=owned))

def heartbeat():
    while not STOP.wait(5):write(R/'heartbeat.json',dict(utc=now(),pid=os.getpid(),state=DATA['state'],active=ACTIVE,maximumGameWorkers=1))

def proof_files(d):
    return {p.name:sha(p) for p in d.iterdir() if p.is_file() and p.name not in ['validation-passed.json','analysis.json'] and p.suffix not in ['.jfr']}

def check_proof(d):
    row=read(d/'validation-passed.json')
    assert row['valid']
    for p,digest in row['evidenceHashes'].items():assert sha(d/p)==digest,'Validated evidence changed: '+p
    return row

def play(spec,diagnostic=False):
    verify();name=('diagnostic-' if diagnostic else 'game-')+str(spec['seed']);d=R/name
    if d.exists():
        # A completed proof may be reused; an ambiguous child is never replayed.
        assert (d/'validation-passed.json').exists(),'Existing incomplete attempt requires review: '+name
        row=check_proof(d);assert row['deck']==spec['deck'] and row['diagnostic']==diagnostic
        return row
    started=time.monotonic();h.admission(R,api.H);d.mkdir();cwd,env,home,temp=c.runtime(d);prefix=d/f"commander-seed{spec['seed']}"
    profile=['-XX:StartFlightRecording=filename='+str(d/'profile.jfr')+',settings=profile,dumponexit=true,maxsize=64m'] if diagnostic else []
    cmd=[e.JAVA,*FLAGS,*e.opts(home,temp),*profile,'-Dforge.simulation.profile=true','-Dforge.parity.trace='+str(d/'combat.jsonl'),'-jar',R/'engine.jar','sim','-d',spec['deck'],*verify()['pod'],'-D',R/'decks','-n','1','-s',str(spec['seed']),'-f','Commander','-c','900','-q','--capture-simulation-failures','--external-ai-mulligan-disabled']
    for opt,suffix in [('metrics-csv','metrics.csv'),('decision-audit-jsonl','decisions.jsonl'),('game-results-jsonl','games.jsonl'),('game-log-jsonl','gamelog.jsonl'),('commander-role-jsonl','roles.jsonl')]:cmd+=['--'+opt,str(prefix)+'.'+suffix]
    ACTIVE.update(stage=name,seed=spec['seed'])
    try:
        c.launch(d,cmd,cwd,env,990)
        shutil.copy2(d/'execution.log',Path(str(prefix)+'.log'))
        module=importlib.util.spec_from_file_location('v6_classifier',S/'tools/ai-evaluation/parallel-v4/Run-ValidCommanderCohort.py');classifier=importlib.util.module_from_spec(module);module.loader.exec_module(classifier)
        classification=classifier.classify_batch(d,spec['seed'],1,False,0,True);write(d/'classification.json',classification)
        assert len(classification)==1 and classification[0]['valid'],'Native failure classification rejected game'
        v=d/'independent-validator';v.mkdir()
        c.launch(v,[Path(sys.executable).with_name('python.exe'),'-B',S/'tools/ai-evaluation/parallel-v4/Validate-CommanderTelemetry.py',d,'--games-per-seed','1','--expected-seeds','1','--require-clean-runtime','--report',d/'validation.json'],S,env)
        with Path(str(prefix)+'.metrics.csv').open(encoding='utf-8-sig',newline='') as f:assert not any(x.get('providerAttempted')=='true' for x in csv.DictReader(f))
        games=records(Path(str(prefix)+'.games.jsonl'));assert len(games)==1
        game=games[0];assert game['valid'] and game['simulationStatus']=='VALID' and not game['timedOut'] and not game['simulationError'] and not game['engineFailures']
        assert game['runSeed']==spec['seed']
        start=next(x for x in records(Path(str(prefix)+'.roles.jsonl')) if x['type']=='START')
        assert len(start['players'])==4,'Expected a native four-player Commander pod'
        row=dict(**spec,directory=name,diagnostic=diagnostic,valid=True,timedOut=False,durationMs=game['durationMs'],turns=game['finalTurnCount'],
            deckSHA256=verify()['deckHashes'][spec['deck']],engineSHA256=api.REFERENCE_SHA,
            workerWallSeconds=read(d/'execution.json')['wallSeconds'],measurementElapsedSeconds=time.monotonic()-started,evidenceHashes=proof_files(d))
        write(d/'validation-passed.json',row)
        return row
    finally:ACTIVE.clear()

def historical():
    prior=read(OLD/'checkpoint.json');rows=[];candidates=[]
    for seed in [573100,573101,573102]:
        d=OLD/f'permanent-baseline-{seed}'
        a,b=scope_records(d,seed);rows+=a;candidates+=b
    DATA['phase5Historical']=dict(cohort=prior['baselineCohort'],fallback=scopes_summary(rows,candidates),pooled=False,
        phase5Decision=prior['conclusion'],primaryMedianChangePercent={k:v['medianChangePercent'] for k,v in prior['comparison'].items() if k in ['large-four-player/block','dragon-tokens/block']})

def diagnostic(spec):
    row=play(spec,True);d=R/row['directory'];original=R/('game-'+str(spec['seed']))
    result=dict(seed=spec['seed'],deck=spec['deck'],valid=True,excludedFromPrimary=True,gameDurationMs=row['durationMs'])
    try:result['structuredParity']=compare(records(original/'combat.jsonl'),records(d/'combat.jsonl'))
    except AssertionError as ex:
        result['structuredParity']=dict(passed=False,error=str(ex),limitation='Identical engine with instrumentation and wall-clock budgets may differ. Exact source traces retained; no sorting or suppression.')
    jfr=Path(e.JAVA).with_name('jfr.exe');out=d/'jfr-samples.json'
    if not out.exists():
        with out.open('w',encoding='utf-8') as f:subprocess.run([str(jfr),'print','--json','--events','jdk.ExecutionSample,jdk.ObjectAllocationSample',str(d/'profile.jfr')],stdout=f,check=True,timeout=120,creationflags=subprocess.CREATE_NO_WINDOW)
    result['jfr']=jfr_summary(read(out));write(d/'jfr-summary.json',result);DATA['diagnostics']=[result]
    categories=result['jfr']['exclusiveCpuSampleCategories'];dominant=max(categories,key=categories.get) if categories else None
    DATA['bottleneck']=dict(dominantSampleCategory=dominant,samples=categories,interpretation='Sampled real-game CPU attribution only; inspect leaf/inclusive frames before selecting any computation to change. No equivalent-state proof or safe isolated improvement demonstrated.')

def run():
    import msvcrt
    with (R/'supervisor.lock').open('a+b') as own:
        own.write(b'0');own.flush();own.seek(0);msvcrt.locking(own.fileno(),msvcrt.LK_NBLCK,1)
        with (api.H/'work/commander-batches/worker.lock').open('r+b') as worker:
            worker.seek(0);msvcrt.locking(worker.fileno(),msvcrt.LK_NBLCK,1);verify();h.admission(R,api.H)
            if (R/'started.json').exists():
                assert '--resume' in sys.argv,'Existing run; explicit recovery required'
                previous=read(R/'checkpoint.json');assert previous['state']!='completed'
                DATA.update(previous)
            else:write(R/'started.json',dict(pid=os.getpid(),utc=now(),startedUnix=time.time()))
            DATA.setdefault('startedUnix',read(R/'started.json')['startedUnix'])
            DATA.pop('failure',None);threading.Thread(target=heartbeat,daemon=True).start()
            try:
                historical();publish('running-primary-baseline')
                for spec in PLAN:
                    attempt_start=time.monotonic()
                    try:
                        row=play(spec)
                        d=R/row['directory'];analysis_start=time.monotonic();analysis=analyse_game(d,spec['seed']);write(d/'analysis.json',analysis)
                        row['measurementElapsedSeconds']+=time.monotonic()-analysis_start
                        row['analysis']=analysis
                        DATA['games']=[x for x in DATA['games'] if x['seed']!=row['seed']]+[row]
                    except BaseException:
                        d=R/('game-'+str(spec['seed']))
                        if not any(x['seed']==spec['seed'] for x in DATA['games']):
                            if (d/'validation-passed.json').exists():DATA['games'].append(check_proof(d))
                            else:DATA['games'].append(dict(**spec,directory=d.name,valid=False,exitCode=read(d/'execution.json')['exitCode'] if (d/'execution.json').exists() else None,measurementElapsedSeconds=time.monotonic()-attempt_start,error=traceback.format_exc()))
                        raise
                    publish('primary-'+str(len(DATA['games']))+'-of-9-validated')
                DATA.setdefault('primaryEndUnix',time.time());publish('primary-baseline-completed')
                slow=max(DATA['games'],key=lambda x:x['durationMs']);publish('running-separate-jfr-diagnostic')
                diagnostic(dict(seed=slow['seed'],deck=slow['deck']))
                DATA['recommendation']='Retain the validated observation reference. No optimisation implemented: real-game attribution and fallback correlations do not establish a provably reusable subcalculation or decision-quality counterfactual. Review the dominant JFR frames for a narrowly justified next experiment. Keep budgets/policy unchanged; promotion would still need >=15% both synthetic primaries, full parity and matched whole-game benefit.'
                publish('completed');write(R/'completion.json',dict(passed=True,pid=os.getpid(),utc=now(),candidateImplemented=False,readyForLargerCohorts=False,reportUrl=URL))
            except BaseException:
                DATA['failure']=traceback.format_exc();write(R/'failure-'+str(os.getpid())+'.json',dict(error=DATA['failure'],utc=now()))
                try:publish('failed-preserved')
                except Exception:write(R/'publication-failure-'+str(os.getpid())+'.json',dict(error=traceback.format_exc()))
                raise
            finally:STOP.set();write(R/'heartbeat.json',dict(state='finished',pid=os.getpid(),utc=now(),active={}))

if __name__=='__main__':
    if '--freeze' in sys.argv:freeze()
    elif '--verify-only' in sys.argv:verify();print('Frozen Phase 6 inputs verified')
    else:run()
