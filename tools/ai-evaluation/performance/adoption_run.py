"""Final finite four-arm adoption experiment. All four sources present; no RNG-policy change."""
from pathlib import Path
import collections,itertools,json,os,shutil,statistics,sys,threading,time,traceback,types,zipfile
sys.dont_write_bytecode=True
import realgame_run as n
from adoption_build import FILES
from adoption_process import launch_with_counters
from adoption_results import synthetic,whole,ARMS
from realgame_analysis import analyse_game,records
from structured_parity import compare
c=n.c;a=n.api;h=n.h;e=n.e;R=n.R;S=n.S
REF=a.REFERENCE;REF_SHA=a.REFERENCE_SHA;V7=a.EX/'rules-performance-v7-20261028/validation-01';V7B=a.EX/'rng-isolation-v7b-20261028/validation-01'
RUN='20261028-native-performance-adoption-v7c';BRANCH='codex/commander-performance-adoption-v7c'
FEATURES=['forge.combat.blockCostFastPath','forge.cost.liveSourceFilter','forge.blocker.hoistCostPresence','forge.blocker.lazyRequirementSnapshots']
BASE=[x for x in a.flags('baseline') if not any(x.startswith('-D'+f+'=') for f in FEATURES)]
PLAN=[dict(seed=573400+i,deck=n.VARIANTS[i]) for i in range(3)]
CLASSES=a.REGRESSION+',forge.ai.CardPropertyRulesTest,forge.ai.CostSourceRegressionTest,forge.ai.BlockCostSourceRegressionTest,forge.ai.CombinedFeatureTest,forge.ai.SpeculativeRngTest'
LIMITS=dict(wholeGameTargetPercent=10,syntheticBlocks=4,syntheticWarmups=1,syntheticRepetitions=3,matchedSeeds=3,maximumMatchedSeeds=9,
    extension='Not enabled: production RNG ownership/isolation remains incomplete. Exactly three diagnostic seeds per arm if gates pass.',
    smallGains='Report directly measured smaller gains; never reject solely for missing historical or 10% targets.',
    adoption='No B/C/D adoption while RNG safety/trajectory comparability unresolved; A is the existing validated fallback. No automatic default change.',
    historicalBlockerPrimaryTargetPercent=15)
D=dict(schemaVersion=1,runId=RUN,state='prepared',arms={x:dict(classification='BLOCKED',reason='Validation pending') for x in ARMS},games=[],benchmarks={},parity={},regressions={},failedAttempts=[],
    rngSafety='Shared speculative/gameplay RNG coupling remains. No production isolation flag: stable seed ownership, direct references and all worker boundaries not proven safe.',
    comparisonsDiagnosticOnly=True,noProductionPromotion=True,limits=LIMITS)
ACTIVE={};STOP=threading.Event()
def read(p):return n.read(p)
def write(p,v):return c.write(p,v)
def head():return n.head()
def flags(arm):return BASE+['-D'+f+'='+str(arm in ['B','D']).lower() for f in FEATURES]+(['-XX:-DontCompileHugeMethods'] if arm in ['C','D'] else [])
def jar(arm):return R/'combined.jar' if arm in ['B','D','O'] else REF
CURRENT='A';a.flags=lambda mode:flags(CURRENT)

def verify():
    q=read(R/'run-request.json');assert q['sourceHead']==head() and q['limits']==LIMITS
    assert c.sha(REF)==REF_SHA and c.sha(R/'combined.jar')==q['combinedSHA256']
    for key,root in [('sourceHashes',S),('compiledHashes',R),('helperHashes',a.H),('historicalHashes',a.EX)]:
        for p,digest in q[key].items():assert c.sha(root/p)==digest,'Frozen input changed: '+p
    for name,digest in q['deckHashes'].items():assert c.sha(V7/'decks'/name)==digest
    assert c.sha(R/'initial-gate.json')==q['initialGateSHA256'] and read(R/'initial-gate.json')['passed']
    assert q['armFlags']=={x:flags(x) for x in ARMS};return q
h.verify=verify

def testng(label,arm,props=(),classes=CLASSES,limit=1200):
    global CURRENT
    CURRENT=arm;d=R/label;d.mkdir();h.admission(R,a.H);cwd,env,home,temp=c.runtime(d)
    options=list(props)
    if '-Dforge.bench.verify=true' in options:
        trace=d/'traces';trace.mkdir();options+=['-Dforge.bench.traceDir='+str(trace)]
        if not any(x.startswith('-Dforge.bench.cases=') for x in options):options+=['-Dforge.bench.cases='+a.CASES+','+a.RULES]
    cp=';'.join(map(str,[R/'test-classes',jar(arm),a.NG,a.JC]))
    cmd=[e.JAVA,*flags(arm),*e.opts(home,temp),'-Dforge.parity.testdir='+str(d),'-Dfixture.deck='+str(V7/'decks/Lathliss_R_Rebuild_V2_Throne.dck'),
        '-Dadoption.engine='+str(jar(arm)),'-Dadoption.expectedSourceFeatures='+str(arm in ['B','D']).lower(),'-Dadoption.expectedHugeCompile='+str(arm in ['C','D']).lower(),*options,'-cp',cp,'org.testng.TestNG','-testclass',classes,'-d',d/'results']
    ACTIVE.update(stage=label,arm=arm)
    try:c.launch(d,cmd,cwd,env,limit)
    finally:ACTIVE.clear()
    return d

def prepare_tests():
    assert not (R/'test-classes').exists();shutil.copytree(V7B/'test-classes',R/'test-classes')
    shadows=[]
    with zipfile.ZipFile(R/'combined.jar') as combined:
        for file in (R/'test-classes').rglob('*.class'):
            relative=file.relative_to(R/'test-classes').as_posix()
            if relative in combined.namelist():
                target=R/'removed-shadow-classes'/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(file,target)
                assert file.resolve().is_relative_to((R/'test-classes').resolve());shadows.append(dict(path=relative,sha256=c.sha(file)));file.unlink()
    write(R/'removed-shadow-class-provenance.json',shadows)
    src=S/'forge-gui-desktop/src/test/java/forge/ai'
    files=[src/name for name in ['CostSourceRegressionTest.java','BlockCostSourceRegressionTest.java','CombinedFeatureTest.java']]
    p=a.java([e.JAVA.with_name('javac.exe'),'--release','17','-cp',';'.join(map(str,[R/'test-classes',R/'combined.jar',a.NG,a.JC])),'-d',R/'test-classes',*files]);(R/'test-compile.log').write_text(p.stdout+p.stderr);assert p.returncode==0,p.stderr
    print('Combined tests compiled; engine-shadow classes removed from isolated test classpath')

def initial():
    results={}
    diagnostic=['-Dforge.combat.blockCostMetrics=true','-Dforge.cost.sourceMetrics=true','-Dforge.blocker.metrics=true','-Dforge.blocker.snapshotMetrics=true']
    for arm in ['A','B','C','D','O']:
        d=testng('initial-'+arm,arm,diagnostic);counts=h.xmlcounts(d);assert counts['failed']==counts['skipped']==0 and counts['passed']>=54,counts
        proof=records(d/'execution.log','ADOPTION_FEATURE_PROOF ');assert len(proof)==1
        if arm in ['B','D']:assert proof[0]['allFourExecuted']
        results[arm]=dict(tests=counts,effectiveFeaturesAndOrigins=proof[0])
    assert len({x['tests']['passed'] for x in results.values()})==1
    write(R/'initial-gate.json',dict(passed=True,arms=results,allFourCombined=True,rngIsolationImplemented=False))
    print('All four arms plus disabled-source control passed focused gates')

def history():
    roots=['commander-combat-performance-v1-20261027/candidate-02','commander-cost-performance-v2-20261027/validation-03','combat-parity-v2-20261028/validation-03','blocker-performance-v3-20261028/validation-02','combat-search-v4-20261028/validation-03','incremental-performance-v5-01-20261028/validation-02','realgame-performance-v6-20261028/validation-01','rules-performance-v7-20261028/validation-01','rules-parity-v7a-20261028/validation-01','rng-isolation-v7b-20261028/validation-01']
    out={};frozen={}
    for root in roots:
        path=a.EX/root/'checkpoint.json';data=read(path)
        out[root]=dict(state=data.get('state'),conclusion=data.get('conclusion',data.get('outcome',data.get('classification'))),checkpointSHA256=c.sha(path))
        frozen[root+'/checkpoint.json']=c.sha(path)
        report=a.EX/root/'public/REPORT.md'
        if report.exists():
            text=report.read_text(encoding='utf-8-sig');out[root]['reportSHA256']=c.sha(report);out[root]['reportCharactersReviewed']=len(text);frozen[root+'/public/REPORT.md']=c.sha(report)
    return out,frozen

def freeze():
    assert not (R/'run-request.json').exists() and read(R/'initial-gate.json')['passed']
    files=h.command(['git','-C',S,'ls-files']).stdout.splitlines();review,hashes=history();write(R/'historical-review.json',review)
    old=read(V7/'run-request.json')
    write(R/'run-request.json',dict(runId=RUN,sourceHead=head(),sourceBranch=BRANCH,controlSHA256=REF_SHA,functionalSHA256=a.EXPECTED,combinedSHA256=c.sha(R/'combined.jar'),limits=LIMITS,
        armFlags={x:flags(x) for x in ARMS},armEngineSHA256={x:c.sha(jar(x)) for x in ARMS},pod=old['pod'],deckHashes=old['deckHashes'],plan=PLAN,
        sourceHashes={p:c.sha(S/p) for p in files if p.startswith(('tools/ai-evaluation/performance/','tools/ai-evaluation/parallel-v4/')) or p in FILES or p.endswith(('CombinedFeatureTest.java','CostSourceRegressionTest.java','BlockCostSourceRegressionTest.java'))},
        compiledHashes={p.relative_to(R).as_posix():c.sha(p) for p in (R/'test-classes').rglob('*.class')},helperHashes=old['helperHashes'],historicalHashes=hashes,initialGateSHA256=c.sha(R/'initial-gate.json'),
        build=read(R/'build-provenance.json'),composition=read(R/'composition.json'),storageRecovery=read(R/'storage-recovery.json'),maximumGameWorkers=1,heapGiB=3,processors=2,freeSpaceReserveGiB=3,
        rngIsolation=False,rngFinalAssessment=read(R/'rng-final-assessment.json'),diagnosticOnly=True,normalProductionConfigurationChanged=False))
    verify();print('Four-arm comparison, effective flags, provenance and diagnostic-only limits frozen')

def aggregate():
    D['synthetic']=synthetic(D['benchmarks']);D['wholeGame']=whole(D['games'],D['parity'])
    D['performanceHistory']=dict(schemaVersion=1,historicalReview=read(R/'historical-review.json'),currentSynthetic=D['synthetic'],currentWholeGame=D['wholeGame'],
        cumulativeRule='Direct B/A, C/A, D/A, D/C and D/B measurements only. No percentages added from earlier experiments.',rngIsolation=False,adoption=D['arms'])

def report():
    arms=D['wholeGame']['arms'];rows=[]
    for label,field in [('Valid games/hour (active)','validGamesPerHourActive'),('Active elapsed seconds','activeElapsedSeconds'),('CPU seconds (engine + validator)','cpuSeconds'),('GC milliseconds','gcMs'),('Peak reported heap bytes','peakReportedHeapBytes'),('Peak reported process working set','peakReportedProcessWorkingSetBytes'),('AI fallback scopes','aiFallbackScopes'),('Invalid/timeouts','invalidOrTimeouts')]:rows.append('|'+label+'|'+'|'.join(str(arms[x].get(field)) for x in ARMS)+'|')
    for label,key,stat in [('Game duration median ms','gameDurationMs','median'),('Game duration p95 ms','gameDurationMs','p95')]:rows.append('|'+label+'|'+'|'.join(str(arms[x][key].get(stat)) for x in ARMS)+'|')
    return '# Phase 7C — Final combined performance/adoption experiment\n\n'+f"State **{D['state']}**, supervisor PID {os.getpid()}, updated {n.now()}.\n\n"+ \
        '**All four source candidates coexist.** A/C use the exact observation reference; B/D use the seven-entry verified overlay. A=control, B=all source features, C=JVM only, D=all source features plus JVM. Smaller historical gains are not a reason to omit any feature. Abandoned EmptyTrigger candidate is excluded as requested.\n\n'+ \
        '## Runtime and actual implementation provenance\n\n```json\n'+json.dumps(dict(build=read(R/'build-provenance.json'),focusedGates=read(R/'initial-gate.json')),indent=2)+'\n```\n\n'+ \
        'The original source control compiled instruction-equivalently before overlay. Every unaffected method is checked. All other JAR entries, including 363 critical AI/RNG/timeout/observer classes, retain original bytes. Test-class shadows are removed from the isolated test classpath; effective feature fields, class origins, JVM option and actual fast-path counter changes are verified. No incomplete historical engine tree is rebuilt.\n\n'+ \
        '## Final RNG-safety assessment\n\n'+D['rngSafety']+' The existing private-stream API restores synchronous/nested scopes but not child threads or retained Random references. Game-seed/decision identity and every worker boundary remain unproven. No isolation flag or validation policy is introduced. The frozen engine, heuristics, 500 ms / 2,000-work budgets and all safeguards remain unchanged apart from the explicit historical source candidates. These measurements are diagnostic, not adoption evidence for a new configuration.\n\n'+ \
        '## Direct measurements\n\n|Metric|A Control|B Source|C JVM|D Everything|\n|---|---|---|---|---|\n'+'\n'.join(rows)+'\n\n'+ \
        'Small sample medians/p95 are descriptive. CPU includes child engine and validator processes where obtainable; classifier/coordinator CPU excluded. Heap is turn-boundary telemetry. Process working-set peak is kernel-reported when available; query timing limitations remain in local counters. Active time includes admission/startup/game/independent validation/analysis. Publication is reported separately, and its time is not silently counted as simulation.\n\n```json\n'+json.dumps(D['wholeGame'],indent=2)+'\n```\n\n'+ \
        '## Synthetic and direct cumulative effects\n\n```json\n'+json.dumps(D['synthetic'],indent=2)+'\n```\n\n'+ \
        'Four rotated JVM blocks use the same fixtures, one warmup and three measurements. Primary timings have optional counters/profilers disabled while existing game diagnostics remain enabled. Current-thread synthetic CPU/allocation exclude other threads. All requested direct comparisons are measured, not added historical percentages. Changed game trajectories confound a pure speed interpretation; see the strict pairwise results.\n\n'+ \
        '## Strict gameplay parity, validation and classifications\n\n```json\n'+json.dumps(dict(regressions=D['regressions'],pairwiseParity=D['parity'],classifications=D['arms'],failedAttempts=D['failedAttempts']),indent=2)+'\n```\n\n'+ \
        'The comparator is unchanged. A divergence is retained and reported; rules fixtures and independent validation are not waived. The cohort is three diagnostic seeds across the frozen variants; extension to nine is disabled because RNG safety is incomplete. Failed attempts stop the run and are not replaced. No engineering result implies deck strength. The 10% whole-game target remains a clear performance target; smaller gains are reported. The historical blocker-specific 15% target is not redefined.\n\n'+ \
        '## Recommendation\n\n'+D.get('recommendation','Pending safety/regression and bounded diagnostic measurements. No default changes.')+'\n\n'+ \
        ('Stopped reason:\n```\n'+D['failure']+'\n```\n\n' if D.get('failure') else '')+ \
        'No merge, engine promotion, new optimisation phase or production cohort is automatic. Next priority is Lathliss, Rivaz and Gruul deck testing on the best validated configuration. [Manifest](manifest.json) · [Measurements](measurements.json) · [Performance history](performance-history.json) · [Source](https://github.com/cwppt/forge/tree/'+BRANCH+'). Source '+read(R/'run-request.json')['sourceHead']+'. Raw evidence `'+str(R)+'`.\n'

n.DATA=D;n.RUN=RUN;n.DEST='forge-performance/reports/'+RUN;n.URL='https://github.com/cwppt/MTG-deck-lists/tree/main/'+n.DEST;n.aggregate=aggregate;n.report=report;n.ACTIVE=ACTIVE
publish=n.publish

def integrity(arm):
    d=R/('integrity-'+arm);d.mkdir();cwd,env,home,temp=c.runtime(d)
    cp=';'.join(map(str,[R/'test-classes',jar(arm),a.NG,a.JC]));config=dict(java=str(e.JAVA),opts=[*flags(arm),*e.opts(home,temp)],classpath=cp,cwd=str(cwd),env=env,output=str(R/('worker-probes-'+arm)))
    write(d/'worker-config.json',config);env.update(FORGE_EXPERIMENTS=str(a.EX),FORGE_WORKER_TEST_CONFIG=str(d/'worker-config.json'))
    c.launch(d,[Path(sys.executable).with_name('python.exe'),'-B','-m','unittest','test_integrity'],a.HERE,env,600);D['regressions']['failurePaths-'+arm]=dict(passed=True,methods=7)

def game(spec,arm):
    verify();folder=R/arm;folder.mkdir(exist_ok=True)
    existing=next((x for x in D['games'] if x['arm']==arm and x['seed']==spec['seed'] and x.get('valid')),None)
    if existing:
        d=R/existing['directory'];proof=n.check_proof(d);assert proof['engineSHA256']==c.sha(jar(arm));return d
    if not (folder/'engine.jar').exists():shutil.copy2(jar(arm),folder/'engine.jar')
    if not (folder/'decks').exists():shutil.copytree(V7/'decks',folder/'decks')
    assert c.sha(folder/'engine.jar')==c.sha(jar(arm))
    for name,digest in verify()['deckHashes'].items():assert c.sha(folder/'decks'/name)==digest
    previous_api=n.api;previous_launch=c.launch;n.api=types.SimpleNamespace(**vars(a));n.api.REFERENCE_SHA=c.sha(jar(arm));n.R=folder;n.FLAGS=flags(arm);n.verify=verify
    c.launch=lambda d,cmd,cwd,env,limit=330:launch_with_counters(previous_launch,d,cmd,cwd,env,limit)
    started=time.monotonic()
    try:
        row=n.play(spec);d=folder/row['directory'];analysis_start=time.monotonic();row['analysis']=analyse_game(d,spec['seed']);row['measurementElapsedSeconds']+=time.monotonic()-analysis_start
        engine=read(d/'process-counters.json');validator=read(d/'independent-validator/process-counters.json')
        row.update(arm=arm,directory=d.relative_to(R).as_posix(),diagnosticOnly=True,cpuSeconds=engine['cpuSeconds']+validator['cpuSeconds'] if engine['cpuSeconds'] is not None and validator['cpuSeconds'] is not None else None,processPeakBytes=engine['peakWorkingSetBytes'])
        assert row['engineSHA256']==c.sha(jar(arm));D['games'].append(row);write(d/'four-arm-analysis.json',row);return d
    except BaseException:
        d=folder/('game-'+str(spec['seed']));valid=(d/'validation-passed.json').exists();error=dict(**spec,arm=arm,directory=str(d),independentlyValidated=valid,error=traceback.format_exc());D['failedAttempts'].append(error)
        if valid:
            row=n.check_proof(d);row.update(arm=arm,directory=d.relative_to(R).as_posix(),analysis=analyse_game(d,spec['seed']));D['games'].append(row)
        else:D['games'].append(dict(**spec,arm=arm,valid=False,measurementElapsedSeconds=time.monotonic()-started))
        raise
    finally:n.R=R;n.api=previous_api;c.launch=previous_launch

def execute():
    for arm in ARMS:D['arms'][arm]=dict(classification='EXPERIMENTAL',reason='Focused correctness passed; finite validation and diagnostic comparison pending',engineSHA256=c.sha(jar(arm)),flags=flags(arm))
    publish('running-final-correctness-gates')
    unit=R/'offline-tests';unit.mkdir();c.launch(unit,[Path(sys.executable).with_name('python.exe'),'-B','-m','unittest','test_adoption','test_structured_parity'],a.HERE,h.ENV,120);D['regressions']['offline']=True
    for arm in ARMS:integrity(arm);publish('integrity-'+arm+'-passed')
    d=R/'independent-validator-tests';d.mkdir();c.launch(d,[Path(sys.executable).with_name('python.exe'),'-B','-m','unittest','test_valid_commander_cohort'],S/'tools/ai-evaluation/parallel-v4',h.ENV,120);D['regressions']['resultValidator']=dict(passed=True,methods=18)
    traces={}
    for arm in ['A','B','C','D','O']:
        traces[arm]=testng('full-parity-'+arm,arm,['-Dforge.bench.verify=true','-Dforge.bench.repeats=1'],'forge.ai.BusyBoardBenchmark')
        if arm!='A':a.equal(traces['A'],traces[arm])
        D['regressions']['structured64-'+arm]=dict(passed=True,workloads=len(a.rows(traces[arm])));publish('structured-'+arm+'-passed')
    focus='large-four-player,dragon-tokens,large-combat,late-100'
    for block in range(4):
        for arm in ARMS[block:]+ARMS[:block]:
            d=testng(f'timing-{block}-{arm}',arm,['-Dforge.bench.cases='+focus,'-Dforge.bench.paths=block,attack','-Dforge.bench.warmup=1','-Dforge.bench.repeats=3'],'forge.ai.BusyBoardBenchmark')
            D['benchmarks'][d.name]=dict(arm=arm,block=block,rows=a.rows(d));publish(d.name+'-completed')
    publish('synthetic-four-arm-completed');cohort()

def cohort():
    D.setdefault('cohortStartUnix',time.time())
    for index,spec in enumerate(PLAN):
        paths={}
        for arm in ARMS[index:]+ARMS[:index]:
            paths[arm]=game(spec,arm);started=time.monotonic();publish('game-'+str(spec['seed'])+'-'+arm+'-validated')
            row=next(x for x in D['games'] if x['arm']==arm and x['seed']==spec['seed']);row['publicationSeconds']=row.get('publicationSeconds',0)+time.monotonic()-started
            write(paths[arm]/'four-arm-analysis.json',row);write(R/'checkpoint.json',D)
        for left,right in itertools.combinations(ARMS,2):
            try:
                result=compare(records(paths[left]/'combat.jsonl'),records(paths[right]/'combat.jsonl'))
                ga=records(paths[left]/f"commander-seed{spec['seed']}.games.jsonl")[0];gb=records(paths[right]/f"commander-seed{spec['seed']}.games.jsonl")[0]
                assert all(ga[k]==gb[k] for k in ['winner','draw','finalTurnCount','simulationStatus']),'Final outcome mismatch'
                assert records(paths[left]/f"commander-seed{spec['seed']}.roles.jsonl")==records(paths[right]/f"commander-seed{spec['seed']}.roles.jsonl"),'Native role/decision trajectory mismatch'
            except AssertionError as ex:result=dict(passed=False,firstDifference=str(ex),classification='Unresolved gameplay/RNG/budget-sensitive difference; not waived or attributed solely to JVM speed',evidence=[str(paths[left]),str(paths[right])])
            key=left+':'+right;D['parity'][key]=[x for x in D['parity'].get(key,[]) if x['seed']!=spec['seed']]+[dict(seed=spec['seed'],**result)]
        publish('matched-seed-'+str(spec['seed'])+'-completed')
    D['cohortElapsedIncludingPublicationSeconds']=time.time()-D['cohortStartUnix']
    D['arms']['A'].update(classification='ADOPTABLE',reason='Existing validated fallback only; no new speed claim. Known shared-RNG reproducibility limitation remains.')
    aggregate()
    for arm in ['B','C','D']:
        result=D['wholeGame']['directComparisons'][arm+' vs A']
        D['arms'][arm].update(classification='EXPERIMENTAL',reason='All requested implementations measured, but production RNG ownership/comparability remains incomplete; diagnostic performance is not adoption evidence.',
            observedActiveReductionPercent=result.get('activeElapsedReductionPercent'),numeric10PercentTargetMet=result.get('activeElapsedReductionPercent',-999)>=10,observedTrajectoryParity=result.get('sampledGameplayParity'))
    D['recommendation']='Retain A: observation engine '+REF_SHA+', all four historical source flags false, normal JVM, original native budgets and safeguards. Report all measured gains including below 10%; no new configuration adopted. Source and JVM candidates remain experimental. End performance engineering and prioritise Lathliss, Rivaz and Gruul deck testing with the validated fallback; no production cohort launched here.'
    publish('completed');write(R/'completion.json',dict(passed=True,pid=os.getpid(),utc=n.now(),allFeaturesCombined=True,newConfigurationAdopted=False,fullGameDiagnostics=len(D['games']),reportUrl=n.URL))

def run():
    import msvcrt
    with (R/'supervisor.lock').open('a+b') as own:
        own.write(b'0');own.flush();own.seek(0);msvcrt.locking(own.fileno(),msvcrt.LK_NBLCK,1)
        with (a.H/'work/commander-batches/worker.lock').open('r+b') as worker:
            worker.seek(0);msvcrt.locking(worker.fileno(),msvcrt.LK_NBLCK,1);verify();h.admission(R,a.H)
            resume=False
            if (R/'started.json').exists():
                assert '--resume' in sys.argv,'Existing supervisor marker; explicit checked recovery required'
                previous=read(R/'checkpoint.json');assert previous['state']!='completed' and len(previous['benchmarks'])==16 and previous['regressions'].get('structured64-O',{}).get('passed'),'Recovery is limited to the completed-synthetic/cohort boundary'
                assert not previous.get('failedAttempts'),'Failed native attempts require review; never replay them'
                for arm in ARMS:
                    folder=R/arm
                    if folder.exists():
                        for d in folder.glob('game-*'):
                            assert (d/'validation-passed.json').exists(),'Incomplete native child/attempt; preserve and review before resuming: '+str(d)
                            n.check_proof(d)
                saved=R/('resume-evidence-'+str(os.getpid()));saved.mkdir();shutil.copy2(R/'checkpoint.json',saved/'checkpoint.json')
                D.clear();D.update(previous);D.pop('failure',None);resume=True
                write(saved/'resume.json',dict(pid=os.getpid(),utc=n.now(),validatedGamesReplayed=False))
            else:
                with (R/'started.json').open('x') as f:json.dump(dict(pid=os.getpid(),utc=n.now()),f)
            n.STOP=STOP;threading.Thread(target=n.heartbeat,daemon=True).start()
            try:
                if resume:publish('resuming-validated-cohort-checkpoint');cohort()
                else:execute()
            except BaseException:
                D['failure']=traceback.format_exc();D['recommendation']='Stopped at a required gate; no candidate adoption or invented results. Keep the unchanged validated reference.'
                for arm in ARMS:
                    if arm!='A':D['arms'][arm].update(classification='BLOCKED',reason='Required verification incomplete or failed; inspect preserved evidence')
                write(R/'failure-run.json',dict(error=D['failure'],utc=n.now()))
                try:publish('failed-preserved')
                except Exception:write(R/'publication-failure.json',dict(error=traceback.format_exc()))
                raise
            finally:STOP.set();write(R/'heartbeat.json',dict(state='finished',pid=os.getpid(),utc=n.now()))

if __name__=='__main__':
    if '--prepare-tests' in sys.argv or '--initial' in sys.argv:
        import msvcrt
        with (a.H/'work/commander-batches/worker.lock').open('r+b') as lock:
            lock.seek(0);msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1)
            prepare_tests() if '--prepare-tests' in sys.argv else initial()
    elif '--freeze' in sys.argv:freeze()
    elif '--verify-only' in sys.argv:verify();print('Four-arm frozen inputs verified')
    else:run()
