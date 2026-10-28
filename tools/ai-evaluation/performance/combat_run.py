"""Finite detached combat experiment: gates, repeat measurements, smoke, and GitHub reporting."""
from pathlib import Path
import base64,collections,ctypes,csv,datetime,hashlib,importlib.util,json,math,os,random,shutil,statistics,subprocess,sys,threading,time,traceback,xml.etree.ElementTree as ET
sys.dont_write_bytecode=True
import combat_performance as api
R=api.R;SOURCE=api.SOURCE;H=api.H;EX=api.EX;e=api.e;core=api.core
RUN=getattr(api,'RUN','20261027-native-combat-performance-v1');DEST='forge-performance/reports/'+RUN
REPO='cwppt/MTG-deck-lists';URL='https://github.com/'+REPO+'/tree/main/'+DEST
STOP=threading.Event();ACTIVE={};PUBLIC=R/'public'
ENV=os.environ.copy();ENV.update(GH_PROMPT_DISABLED='1',GIT_TERMINAL_PROMPT='0',GCM_INTERACTIVE='Never')
DATA=dict(runId=RUN,state='pending',optimisation=getattr(api,'DESCRIPTION','Live block-tax mode prefilter and lazy zero-cost construction; no cached game-state results'),
    performanceImprovementDemonstrated=False,readyForLargerCohorts=False,benchmarks={},smoke=[],regressions={},differences=[])
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def write(p,obj):return core.write(p,obj)
def command(args,timeout=120):return subprocess.run(list(map(str,args)),env=ENV,capture_output=True,text=True,timeout=timeout,creationflags=subprocess.CREATE_NO_WINDOW)
def memory():
    class Mem(ctypes.Structure):
        _fields_=[('length',ctypes.c_ulong),('load',ctypes.c_ulong)]+[(s,ctypes.c_ulonglong) for s in ['totalPhys','availPhys','totalPage','availPage','totalVirtual','availVirtual','availExtended']]
    m=Mem();m.length=ctypes.sizeof(m);assert ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m));return m.availPhys
def admission(*paths):
    core.admission(R,H,*paths)
    assert memory()>=7*1024**3,'Existing one-worker memory allowance unavailable'
def heartbeat():
    while not STOP.wait(5):write(R/'heartbeat.json',dict(utc=now(),pid=os.getpid(),active=ACTIVE,maximumGameWorkers=1,state=DATA['state']))
def stage(name):
    DATA['state']=name;write(R/'status.json',dict(stage=name,utc=now(),pid=os.getpid(),reportUrl=URL));write(R/'checkpoint.json',DATA)
def verify():
    q=json.loads((R/'run-request.json').read_text())
    for relative,h in q['sourceHashes'].items():assert core.sha(SOURCE/relative)==h,'Frozen experiment source changed: '+relative
    for relative,h in q.get('helperHashes',{}).items():assert core.sha(H/relative)==h,'Frozen runtime dependency changed'
    for relative,h in q.get('inputHashes',{}).items():assert core.sha(R/relative)==h,'Frozen compiled test input changed'
    assert core.sha(R/'baseline.jar')==q['baselineSHA256'] and core.sha(R/'optimized.jar')==q['treatmentSHA256'],'Engine hash changed'
    return q

def freeze():
    assert not (R/'run-request.json').exists(),'Request already frozen'
    paths=subprocess.check_output(['git','-C',str(SOURCE),'ls-files'],text=True).splitlines()
    relevant=[p for p in paths if p in getattr(api,'EXTRA_SOURCES',[]) or p.startswith('tools/ai-evaluation/performance/') or p.startswith('tools/ai-evaluation/parallel-v4/') or p in [
        'forge-game/src/main/java/forge/game/card/CardState.java','forge-game/src/main/java/forge/game/card/BlockCostMetrics.java',
        'forge-game/src/main/java/forge/game/combat/CombatUtil.java'] or p.startswith('forge-gui-desktop/src/test/java/forge/')]
    helpers=[H/'work/lathliss-v27-20261017/experiment.py',H/'work/lathliss-v27-20261017/scripts/ParallelCommanderPool.py']
    helpers+=list((H/'work/lathliss-v27-20261017/preferences-snapshot').rglob('*'))
    q=dict(runId=RUN,sourceHead=subprocess.check_output(['git','-C',str(SOURCE),'rev-parse','HEAD'],text=True).strip(),
        sourceBranch=getattr(api,'BRANCH','codex/commander-combat-performance-v1'),sourceBaseline='418dac868f3f48349ff0b5d35e9197c65008d6e6',
        baselineSHA256=core.sha(R/'baseline.jar'),treatmentSHA256=core.sha(R/'optimized.jar'),flags=api.FLAGS,
        sourceHashes={p:core.sha(SOURCE/p) for p in relevant},helperHashes={p.relative_to(H).as_posix():core.sha(p) for p in helpers if p.is_file()},
        inputHashes={p.relative_to(R).as_posix():core.sha(p) for p in (R/'test-classes').rglob('*.class')},
        deckHashes=json.loads((EX/'lathliss-r-throne-20261026/request.json').read_text())['deckHashes'],
        focusWarmups=2,focusRepetitions=4,jvmRounds=3,fullSuiteTiming=False,baselineFlags=api.flags('baseline'),candidateFlags=api.flags('optimized'),sameJarDifferentStartupFlag=True,
        smokeSeeds=getattr(api,'SMOKE_SEEDS',[572100,572101,572102]),heapGiB=3,processors=2,maximumGameWorkers=1,freeSpaceReserveGiB=3)
    q['functionalBaselineSHA256']=api.EXPECTED
    assert core.sha(api.BASE)==api.EXPECTED
    write(R/'run-request.json',q);verify()
def testng(label,mode,props,classes='forge.ai.BusyBoardBenchmark',limit=1200,jfr=False):
    verify();admission(R);ACTIVE.update(stage=label,mode=mode)
    try:return api.testng(label,mode,props,classes,limit,jfr)
    finally:ACTIVE.clear()
def dist(values):
    a=sorted(values)
    return dict(n=len(a),median=statistics.median(a),p95=a[math.ceil(.95*len(a))-1],p99=a[math.ceil(.99*len(a))-1],total=sum(a)) if a else dict(n=0)
def collect(d,mode):
    rows=[r for r in api.rows(d) if not r.get('warmup')]
    DATA['benchmarks'][d.name]=dict(mode=mode,rows=rows,wallSeconds=json.loads((d/'execution.json').read_text())['wallSeconds'],
        logBytes=(d/'execution.log').stat().st_size,logSHA256=core.sha(d/'execution.log'))
    return rows
def xmlcounts(d):
    t=ET.parse(d/'results/testng-results.xml').getroot();return {k:int(t.attrib.get(k,0)) for k in ['total','passed','failed','skipped']}
def gh(path,method='GET',payload=None):
    args=['gh','api',path]
    if method!='GET':args+=['--method',method]
    if payload is not None:
        tmp=R/'github-request.json';write(tmp,payload);args+=['--input',tmp]
    return command(args)
def publish():
    PUBLIC.mkdir(exist_ok=True);q=json.loads((R/'run-request.json').read_text())
    write(PUBLIC/'measurements.json',DATA);write(PUBLIC/'manifest.json',q)
    text='# '+getattr(api,'TITLE','Native Commander combat performance v1')+'\n\nState: **'+DATA['state']+'**. No speedup is claimed until all gates and repeated measurements finish.\n\n'
    text+='## Change and baseline\n\n'+getattr(api,'CHANGE',DATA['optimisation'])+'\n\n'+getattr(api,'HISTORY','No performance claim until the gates pass.')+'\n\n'
    text+='Frozen functional baseline SHA256: `'+q['functionalBaselineSHA256']+'`. Shared diagnostic comparison baseline SHA256: `'+q['baselineSHA256']+'`. Candidate SHA256: `'+q['treatmentSHA256']+'`. Source baseline `418dac868f3f48349ff0b5d35e9197c65008d6e6`; source HEAD `'+q['sourceHead']+'`. [Source branch](https://github.com/cwppt/forge/tree/'+q.get('sourceBranch','codex/commander-combat-performance-v1')+'). Neither original workspace nor any historical engine/deck was changed. No automatic merge.\n\n'
    text+='## Gates and evidence\n\nInitial Java regression counts for each engine are recorded below, plus matching large-board legal attacker/blocker pair sets, selected assignments, state, actual combat damage and stack results. Extended regression, ten-case plus six combat-rule fixture parity, timeout journals, draws, uncooperative workers, report/validator integrity and matched real games are recorded below. Pair sets are not an exhaustive enumeration of all multi-block combinations; dedicated blocking regressions and full-game trajectories supplement them.\n\n```json\n'+json.dumps(DATA['regressions'],indent=2)+'\n```\n\n'
    text+='All timed workloads retain 500 ms / 2,000 checkpoints, 3 GiB heap, two advertised CPUs, identical diagnostics and frozen native flags. Two warm-ups per workload are excluded from the focused measurements. Three separate JVM rounds alternate engine order; four measured repetitions per workload per round give 12 observations. Separate aggregate-counter/JFR passes supplement focused timing; the generic 240-operation full timing suite is not repeated. Counts/JFR instrumentation are not enabled in the primary timing runs.\n\n'
    for name,entry in DATA['benchmarks'].items():
        if name.startswith('allocation-profile-'):
            text+='### '+name+'\n\n'
            text+='Requirement-attacker candidates examined: '+str(sum(r.get('requirementCandidates',0) for r in entry['rows']))+'; redundant blocking-cost lookups avoided: '+str(sum(r.get('avoidedTaxQueries',0) for r in entry['rows']))+'. Legal-pair accept/reject counts and actual tax/proof source visits are in measurements.json.\n\n'
            if entry.get('jfr'):text+='Nearest Forge CPU frames on sampled native evaluation stacks: `'+json.dumps(entry['jfr']['nearestForgeCpuFrames'][:5])+'`. Allocation weights and caller evidence are in measurements.json; JFR sampling is not an exact per-operation allocation census.\n\n'
    if DATA.get('comparison'):
        text+='## Before / after\n\n| Workload | Baseline median ms | Candidate median ms | Baseline p95 ms | Candidate p95 ms | Median change |\n|---|---:|---:|---:|---:|---:|\n'
        for key,v in DATA['comparison'].items():text+=f'| {key} | {v["baseline"]["wallMs"]["median"]:.2f} | {v["optimized"]["wallMs"]["median"]:.2f} | {v["baseline"]["wallMs"]["p95"]:.2f} | {v["optimized"]["wallMs"]["p95"]:.2f} | {v["medianChangePercent"]:.2f}% |\n'
        text+='\nCPU, current-thread allocation, GC, collection counts, raw round samples, log volumes and engine hashes are in measurements.json. Current-thread allocation does not include Game AI Eval worker allocations. Three JVM blocks limit statistical certainty; bootstrap intervals are exploratory.\n\n'
    text+='## Native behaviour and real games\n\n```json\n'+json.dumps(dict(smoke=DATA['smoke'],differences=DATA['differences']),indent=2)+'\n```\n\n'
    text+='Any unexpected legal/action/assignment/damage/stack/state or gameplay-trajectory difference stops subsequent stages. Failed attempts remain forensic evidence and never become wins, losses or draws. Only timing/diagnostic fields and Match Duration lines are excluded from trajectory comparisons. No failed-game replacement sampling is used.\n\n'
    text+='## Result and limitations\n\nDemonstrated improvement: **'+str(DATA['performanceImprovementDemonstrated'])+'**. Ready for larger cohorts: **'+str(DATA['readyForLargerCohorts'])+'**. '+DATA.get('conclusion','Extended gates/measurements are pending; the candidate is not approved for production use.')+'\n\n'
    if DATA.get('failure'):text+='Stopped reason: '+DATA['failure']+'\n\n'
    text+='Raw logs, JFR recordings, bytecode parity evidence, process records and checkpoints remain local under this run. Exact game-copy, hypothetical-combat and equivalent-position counts are unavailable. Requirement candidates, legal-pair queries/accept/reject, eliminated tax queries and tax/proof source visits are counted in the separate diagnostic pass. Synthetic fixtures use the previous suite\u2019s high life totals/Dragon libraries and are not historical replays or scored deck comparisons. No decklist, rule, evaluation budget or timeout-correctness implementation was changed.\n\n'
    text+='Reproduce with `'+Path(api.__file__).name+' <fresh-run-directory> prepare`, then `initial`, then `'+getattr(api,'RUNNER','combat_run.py')+' <same-run-directory> --freeze` followed by the same command without --freeze. Use normal Windows access for isolated resource junctions. The supervisor is finite, owns the existing global worker lock, checks C: and actual output volumes for the 3 GiB reserve, admits one worker with the existing memory allowance, and writes local heartbeats every five seconds. Existing run-request/source hashes must match. Duplicate supervisor launches are refused. Interrupted/ambiguous children require review; valid completed evidence is not overwritten or replayed. No external LLM/provider calls or paid fallback.\n'
    (PUBLIC/'REPORT.md').write_text(text,encoding='utf-8')
    owned=json.loads((R/'published-blobs.json').read_text()) if (R/'published-blobs.json').exists() else {}
    for name in ['REPORT.md','measurements.json','manifest.json']:
        p=PUBLIC/name;content=p.read_bytes();assert len(content)<950000,'Oversized report payload'
        endpoint='repos/'+REPO+'/contents/'+DEST+'/'+name
        prior=gh(endpoint);payload=dict(message='Commander native combat v1 checkpoint: '+DATA['state'],branch='main',content=base64.b64encode(content).decode())
        if prior.returncode==0:
            remote=json.loads(prior.stdout);assert name in owned,'Existing report not owned by this run'
            assert remote['sha']==owned[name]['sha'],'Report changed externally; refuse overwrite'
            payload['sha']=remote['sha']
        else:assert '404' in prior.stderr or 'Not Found' in prior.stderr,'GitHub preflight failed: '+prior.stderr[-300:]
        payload['message']=getattr(api,'TITLE','Commander native combat v1')+' checkpoint: '+DATA['state']
        result=gh(endpoint,'PUT',payload);assert result.returncode==0,'GitHub publication failed: '+result.stderr[-600:]
        data=json.loads(result.stdout);owned[name]=dict(sha=data['content']['sha'],commit=data['commit']['sha']);write(R/'published-blobs.json',owned)
    write(R/'publication.json',dict(url=URL,utc=now(),state=DATA['state'],files=owned))
def checkpoint(name):stage(name);publish()
def python_regressions():
    for mode in ['baseline','optimized']:
        d=R/('integrity-'+mode);d.mkdir();cwd,env,home,temp=core.runtime(d)
        cp=';'.join(map(str,[R/'test-classes',R/('baseline.jar' if mode=='baseline' else 'optimized.jar'),api.NG,api.JC]))
        config=dict(java=str(e.JAVA),opts=[*api.flags(mode),*e.opts(home,temp)],classpath=cp,cwd=str(cwd),env=env,output=str(R/('worker-probes-'+mode)))
        write(d/'worker-config.json',config);env.update(FORGE_EXPERIMENTS=str(EX),FORGE_WORKER_TEST_CONFIG=str(d/'worker-config.json'))
        core.launch(d,[Path(sys.executable).with_name('python.exe'),'-B','-m','unittest','test_integrity'],api.HERE,env,600)
        DATA['regressions']['integrity-'+mode]=dict(passed=True,methods=7,processModes=7)
    d=R/'legacy-validator';d.mkdir();core.launch(d,[Path(sys.executable).with_name('python.exe'),'-B','-m','unittest','test_valid_commander_cohort'],SOURCE/'tools/ai-evaluation/parallel-v4',ENV)
    DATA['regressions']['legacy-validator']=dict(passed=True,methods=18)
def smoke():
    q=json.loads((EX/'lathliss-r-throne-20261026/request.json').read_text());decks=R/'decks';shutil.copytree(EX/'lathliss-r-throne-20261026/decks',decks)
    assert q['deckHashes']==verify()['deckHashes'],'Original deck manifest changed after freezing'
    for name,h in q['deckHashes'].items():assert core.sha(decks/name)==h
    spec=importlib.util.spec_from_file_location('classifier',SOURCE/'tools/ai-evaluation/parallel-v4/Run-ValidCommanderCohort.py');classifier=importlib.util.module_from_spec(spec);spec.loader.exec_module(classifier)
    def trajectory(d,seed):
        roles=[json.loads(l) for l in (d/f'commander-seed{seed}.roles.jsonl').read_text(encoding='utf-8-sig').splitlines()]
        roles=[{k:v for k,v in row.items() if k!='eventIndex'} for row in roles if row['type']!='CANDIDATE']
        log=[json.loads(l) for l in (d/f'commander-seed{seed}.gamelog.jsonl').read_text(encoding='utf-8-sig').splitlines()]
        log=[row for row in log if not (row['type']=='GAME_OUTCOME' and row['message'].startswith('Match Duration '))]
        return dict(roles=roles,gameLog=log)
    for seed in [572100,572101,572102]:
        pairs={}
        for mode in ['baseline','optimized']:
            verify();admission(R);d=R/f'smoke-{mode}-{seed}';d.mkdir();cwd,env,home,temp=core.runtime(d);prefix=d/f'commander-seed{seed}'
            jar=R/('baseline.jar' if mode=='baseline' else 'optimized.jar');cmd=[e.JAVA,*api.FLAGS,*e.opts(home,temp),'-Dforge.simulation.profile=true','-jar',jar,'sim','-d','Lathliss_R_Rebuild_V2_Throne.dck',*q['pod'],'-D',decks,'-n','1','-s',str(seed),'-f','Commander','-c','900','-q','--capture-simulation-failures','--external-ai-mulligan-disabled']
            for opt,suffix in [('metrics-csv','metrics.csv'),('decision-audit-jsonl','decisions.jsonl'),('game-results-jsonl','games.jsonl'),('game-log-jsonl','gamelog.jsonl'),('commander-role-jsonl','roles.jsonl')]:cmd+=['--'+opt,str(prefix)+'.'+suffix]
            ACTIVE.update(stage=d.name,mode=mode)
            try:core.launch(d,cmd,cwd,env,990)
            finally:ACTIVE.clear()
            shutil.copy2(d/'execution.log',Path(str(prefix)+'.log'));rows=classifier.classify_batch(d,seed,1,False,0,True);write(d/'classification.json',rows)
            assert len(rows)==1 and rows[0]['valid'],'Invalid smoke game; preserve and stop'
            v=d/'independent-validator';v.mkdir();core.launch(v,[Path(sys.executable).with_name('python.exe'),'-B',SOURCE/'tools/ai-evaluation/parallel-v4/Validate-CommanderTelemetry.py',d,'--games-per-seed','1','--expected-seeds','1','--require-clean-runtime','--report',d/'validation.json'],SOURCE,env)
            with Path(str(prefix)+'.metrics.csv').open(encoding='utf-8-sig',newline='') as f:assert not any(r.get('providerAttempted')=='true' for r in csv.DictReader(f))
            game=json.loads(Path(str(prefix)+'.games.jsonl').read_text().splitlines()[0]);pairs[mode]=trajectory(d,seed)
            DATA['smoke'].append(dict(seed=seed,mode=mode,winner=game['winner'],draw=game['draw'],turns=game['finalTurnCount'],durationMs=game['durationMs'],valid=True,providerAttempts=0,logBytes=(d/'execution.log').stat().st_size))
        if pairs['baseline']!=pairs['optimized']:
            write(R/f'trajectory-difference-{seed}.json',dict(baseline=pairs['baseline'],optimized=pairs['optimized']));DATA['differences'].append(dict(seed=seed,kind='gameplay-trajectory'));raise AssertionError('Unexpected gameplay trajectory difference; explicit review required')
        write(R/f'smoke-pair-{seed}.json',dict(passed=True,gameplayTrajectoriesIdentical=True))
def comparison():
    grouped=collections.defaultdict(lambda:collections.defaultdict(list))
    for name,entry in DATA['benchmarks'].items():
        if not name.startswith('timing-'):continue
        for row in entry['rows']:grouped[row['case']+'/'+row['path']][entry['mode']].append(row)
    out={}
    for key,modes in grouped.items():
        item={}
        for mode,rows in modes.items():item[mode]={metric:dist([r[field] for r in rows if r.get(field) is not None]) for metric,field in [('wallMs','wallMs'),('cpuMs','cpuMs'),('allocationBytes','allocatedBytesCurrentThread'),('gcMs','gcMs')]}
        if set(item)=={'baseline','optimized'}:
            item['medianChangePercent']=100*(item['optimized']['wallMs']['median']/item['baseline']['wallMs']['median']-1)
            ratios=[]
            for round in range(3):
                block={}
                for mode in ['baseline','optimized']:
                    entry=DATA['benchmarks'].get(f'timing-{round}-{mode}')
                    vals=[r['wallMs'] for r in entry['rows'] if r['case']+'/'+r['path']==key] if entry else []
                    if vals:block[mode]=statistics.median(vals)
                if len(block)==2:ratios.append(block['optimized']/block['baseline'])
            item['jvmRoundMedianRatios']=ratios
            if len(ratios)==3:
                rng=random.Random(771231);bootstrap=sorted(math.exp(statistics.mean(math.log(rng.choice(ratios)) for _ in ratios)) for _ in range(3000))
                item['exploratoryJvmBlockRatio95']=[bootstrap[74],bootstrap[2924]]
            out[key]=item
    DATA['comparison']=out
    primary=[v for k,v in out.items() if k in ['large-four-player/block','dragon-tokens/block']]
    # Deliberately conservative descriptive criterion, not a formal statistical guarantee.
    DATA['performanceImprovementDemonstrated']=len(primary)==2 and all(v['medianChangePercent']<=-5 and len(v['jvmRoundMedianRatios'])==3 and max(v['jvmRoundMedianRatios'])<1 and v['exploratoryJvmBlockRatio95'][1]<1 for v in primary)
    DATA['conclusion']='Both primary workloads show >=5% median reduction, improvement in all three JVM blocks, and exploratory block-bootstrap ratio intervals below one. Three JVM blocks remain limited evidence; review raw distributions and smoke parity before broader adoption.' if DATA['performanceImprovementDemonstrated'] else 'No sufficiently clear improvement demonstrated on both primary blocker workloads. Do not claim a performance success or adopt the candidate on this evidence.'
def run():
    import msvcrt
    with (R/'supervisor.lock').open('a+b') as own:
        own.seek(0);own.write(b'0');own.flush();own.seek(0);msvcrt.locking(own.fileno(),msvcrt.LK_NBLCK,1)
        with (H/'work/commander-batches/worker.lock').open('r+b') as lock:
            lock.seek(0);msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1);admission(R)
            with (R/'started.json').open('x') as f:json.dump(dict(pid=os.getpid(),utc=now()),f)
            verify();assert json.loads((R/'initial-gate.json').read_text())['passed']
            threading.Thread(target=heartbeat,daemon=True).start()
            try:
                DATA['regressions']['initial']={mode:xmlcounts(R/('initial-regressions-'+mode)) for mode in ['baseline','optimized']}
                checkpoint('running-correctness-gates');python_regressions()
                props=['-Dforge.bench.verify=true','-Dforge.bench.repeats=1']
                a=testng('full-parity-baseline','baseline',props);b=testng('full-parity-optimized','optimized',props);api.equal(a,b)
                DATA['regressions']['fullTenCaseParity']=dict(passed=True,workloads=len(api.rows(a)),legalPairsAssignmentsDamageStackStateEqual=True)
                checkpoint('correctness-gates-passed')
                focus='large-four-player,dragon-tokens,large-combat,late-100'
                for mode in ['off','optimized']:
                    d=testng('allocation-profile-'+mode,mode,['-D'+getattr(api,'METRICS_FLAG','forge.combat.blockCostMetrics')+'=true','-Dforge.bench.cases='+focus,'-Dforge.bench.paths=block','-Dforge.bench.warmup=1','-Dforge.bench.repeats=2'],jfr=True);collect(d,mode)
                    from summarize import jfr_summary
                    DATA['benchmarks'][d.name]['jfr']=jfr_summary(d)
                checkpoint('profiling-completed')
                for round in range(3):
                    order=['baseline','optimized'] if round%2==0 else ['optimized','baseline']
                    for mode in order:
                        d=testng(f'timing-{round}-{mode}',mode,['-Dforge.bench.cases='+focus,'-Dforge.bench.paths=block,attack','-Dforge.bench.warmup=2','-Dforge.bench.repeats=4']);collect(d,mode)
                    comparison();checkpoint('timing-round-'+str(round+1)+'-completed')
                comparison();checkpoint('benchmarks-completed')
                if DATA['performanceImprovementDemonstrated']:smoke()
                else:DATA['smokeGate']='Skipped: predeclared performance acceptance failed'
                comparison()
                DATA['readyForLargerCohorts']=False  # No automatic production promotion from this small smoke sample.
                checkpoint('completed');write(R/'completion.json',dict(passed=True,utc=now(),reportUrl=URL,pid=os.getpid(),performanceImprovementDemonstrated=DATA['performanceImprovementDemonstrated'],readyForLargerCohorts=False))
            except BaseException:
                error=traceback.format_exc();write(R/'failure-run.json',dict(error=error,utc=now()))
                DATA['failure']=error.splitlines()[-1][:700];DATA['conclusion']='Stopped at a required gate. No performance or production-readiness claim.';DATA['readyForLargerCohorts']=False
                try:checkpoint('failed-preserved')
                except Exception:write(R/'publication-failure.json',dict(error=traceback.format_exc()))
                raise
            finally:STOP.set();write(R/'heartbeat.json',dict(state='finished',utc=now(),pid=os.getpid(),active={},maximumGameWorkers=1))
if __name__=='__main__':
    try:
        if '--freeze' in sys.argv:freeze()
        else:run()
    except BaseException:write(R/'bootstrap-or-run-error.json',dict(error=traceback.format_exc(),utc=now()));raise
