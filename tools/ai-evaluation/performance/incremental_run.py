"""Versioned baseline, one candidate, measured history, and bounded conditional continuation."""
from pathlib import Path
import collections,copy,csv,importlib.util,json,shutil,statistics,sys,time,traceback
sys.dont_write_bytecode=True
import incremental_performance as api
sys.modules['combat_performance']=api
import combat_run as host
from structured_parity import compare
R=api.R;S=api.SOURCE;c=api.core;e=api.e
old_comparison=host.comparison;old_checkpoint=host.checkpoint;old_integrity=host.python_regressions;old_verify=host.verify

def verify():
    q=old_verify();assert c.sha(api.AGENT)==q['searchAgentSHA256'];assert c.sha(api.REFERENCE)==api.REFERENCE_SHA
    assert c.sha(api.EX/'blocker-performance-v3-20261028/validation-02/checkpoint.json')==q['historicalPhase3CheckpointSHA256']
    assert c.sha(api.EX/'combat-search-v4-20261028/validation-03/checkpoint.json')==q['phase4ProfileCheckpointSHA256'];return q
host.verify=verify

def comparison():
    all_entries=host.DATA['benchmarks'];host.DATA['benchmarks']={k:v for k,v in all_entries.items() if v['mode']!='functional'}
    try:old_comparison()
    finally:host.DATA['benchmarks']=all_entries
    out=host.DATA.get('comparison',{});primary=[out.get(k) for k in ['large-four-player/block','dragon-tokens/block']]
    def credible(v):return v is not None and v['medianChangePercent']<=-2 and len(v['jvmRoundMedianRatios'])==3 and max(v['jvmRoundMedianRatios'])<1 and v['exploratoryJvmBlockRatio95'][1]<1
    regressions=all(v['medianChangePercent']<=5 and v['optimized']['wallMs']['p95']<=1.05*v['baseline']['wallMs']['p95'] and v['optimized']['allocationBytes']['median']<=1.05*v['baseline']['allocationBytes']['median'] and v['optimized']['cpuMs']['median']<=1.05*v['baseline']['cpuMs']['median'] for v in out.values())
    passed=all(credible(v) for v in primary) and regressions
    host.DATA['performanceImprovementDemonstrated']=passed
    host.DATA['promotionPerformanceGate']=passed and all(v['medianChangePercent']<=-15 for v in primary)
    host.DATA['acceptanceCriteria']='Experimental: >=2% primary medians, all three JVM blocks improve, exploratory upper ratio <1; no >5% measured median/p95/CPU/allocation regressions. Promotion consideration additionally requires >=15% on both primaries and full native correctness. Thresholds frozen before repeated measurement.'
    old=read(api.EX/'blocker-performance-v3-20261028/validation-02/checkpoint.json')
    history=dict(schemaVersion=1,functionalOriginalSHA256=api.EXPECTED,permanentObservationBaselineSHA256=api.REFERENCE_SHA,
        previousExperimentalCandidate=dict(runId=old['runId'],adopted=False,gatePassed=False,results={k:v['medianChangePercent'] for k,v in old['comparison'].items() if k.endswith('/block')},comparisonStatus='Historical context only; not combined or used as a concurrent incremental denominator'),
        currentCandidate=dict(source=read(R/'run-request.json')['sourceHead'],engineSHA256=c.sha(R/'optimized.jar'),previousFlagsDisabled=True,correctness=host.DATA['regressions'],synthetic={}),
        cumulativeRule='Use directly measured end-to-end candidate / functional-original ratios from this run. Never add percentages across historical experiments. Whole-game reference includes identical structured telemetry in both arms.')
    for case,v in out.items():
        functional=[r['wallMs'] for name,entry in all_entries.items() if name.startswith('timing-') and entry['mode']=='functional' for r in entry['rows'] if r['case']+'/'+r['path']==case]
        history['currentCandidate']['synthetic'][case]=dict(originalFunctionalMedianMs=statistics.median(functional) if functional else None,
            observationBaselineMedianMs=v['baseline']['wallMs']['median'],candidateMedianMs=v['optimized']['wallMs']['median'],
            incrementalImprovementPercent=-v['medianChangePercent'],cumulativeAgainstFunctionalOriginalPercent=100*(1-v['optimized']['wallMs']['median']/statistics.median(functional)) if functional else None,
            completeThreeJvmBlocks=len(v['jvmRoundMedianRatios'])==3)
    for mode,key in [('baseline','baselineCohort'),('optimized','candidateCohort')]:
        if key in host.DATA:history['currentCandidate'][key]=host.DATA[key]
    if 'baselineCohort' in host.DATA and 'candidateCohort' in host.DATA:
        a=host.DATA['baselineCohort']['validGamesPerHourElapsed'];b=host.DATA['candidateCohort']['validGamesPerHourElapsed'];history['currentCandidate']['measuredWholeGameThroughputChangePercent']=100*(b/a-1) if a else None
    host.DATA['performanceHistory']=history
    host.DATA['conclusion']=('Experimental synthetic gate passed; 15% promotion-performance gate '+('passed' if host.DATA['promotionPerformanceGate'] else 'not met')+'. '+('Matched native parity passed. Retain opt-in experimentally for review; no automatic promotion or second candidate.' if host.DATA.get('boundedNativeBehaviourParity') else 'Matched candidate cohort remains gated/pending; no behaviour-preservation or whole-game speedup claim yet.')) if passed else 'Experimental performance gate not met. Preserve candidate/evidence, leave disabled, skip candidate games, and stop refining this change without new evidence.'

def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def telemetry(d):return [json.loads(l) for l in (d/'combat.jsonl').read_text(encoding='utf-8-sig').splitlines()]
def cohort(mode):
    key='baselineCohort' if mode=='baseline' else 'candidateCohort';started=time.monotonic();attempted=0;validated=0;valid=[]
    q=read(api.EX/'lathliss-r-throne-20261026/request.json');decks=R/'decks'
    if not decks.exists():shutil.copytree(api.EX/'lathliss-r-throne-20261026/decks',decks)
    assert q['deckHashes']==verify()['deckHashes']
    for name,sha in q['deckHashes'].items():assert c.sha(decks/name)==sha
    spec=importlib.util.spec_from_file_location('classifier',S/'tools/ai-evaluation/parallel-v4/Run-ValidCommanderCohort.py');classifier=importlib.util.module_from_spec(spec);spec.loader.exec_module(classifier)
    try:
        for seed in api.SMOKE_SEEDS:
            verify();host.admission(R,api.H);d=R/f'permanent-{mode}-{seed}';d.mkdir();attempted+=1;cwd,env,home,temp=c.runtime(d);prefix=d/f'commander-seed{seed}'
            jar=R/('baseline.jar' if mode=='baseline' else 'optimized.jar')
            cmd=[e.JAVA,*api.flags(mode),*e.opts(home,temp),'-Dforge.simulation.profile=true','-Dforge.parity.trace='+str(d/'combat.jsonl'),'-jar',jar,'sim','-d','Lathliss_R_Rebuild_V2_Throne.dck',*q['pod'],'-D',decks,'-n','1','-s',str(seed),'-f','Commander','-c','900','-q','--capture-simulation-failures','--external-ai-mulligan-disabled']
            for opt,suffix in [('metrics-csv','metrics.csv'),('decision-audit-jsonl','decisions.jsonl'),('game-results-jsonl','games.jsonl'),('game-log-jsonl','gamelog.jsonl'),('commander-role-jsonl','roles.jsonl')]:cmd+=['--'+opt,str(prefix)+'.'+suffix]
            host.ACTIVE.update(stage=d.name,mode=mode)
            try:c.launch(d,cmd,cwd,env,990)
            finally:host.ACTIVE.clear()
            shutil.copy2(d/'execution.log',Path(str(prefix)+'.log'));classified=classifier.classify_batch(d,seed,1,False,0,True);c.write(d/'classification.json',classified);assert len(classified)==1 and classified[0]['valid']
            v=d/'independent-validator';v.mkdir();c.launch(v,[Path(sys.executable).with_name('python.exe'),'-B',S/'tools/ai-evaluation/parallel-v4/Validate-CommanderTelemetry.py',d,'--games-per-seed','1','--expected-seeds','1','--require-clean-runtime','--report',d/'validation.json'],S,env)
            with Path(str(prefix)+'.metrics.csv').open(encoding='utf-8-sig',newline='') as f:assert not any(row.get('providerAttempted')=='true' for row in csv.DictReader(f))
            game=read(Path(str(prefix)+'.games.jsonl'));assert game['valid'] and game['simulationStatus']=='VALID';validated+=1
            trace=telemetry(d);assert not any('unexpandedType' in json.dumps(x) for x in trace)
            evaluation=[]
            for line in (d/'execution.log').read_text(encoding='utf-8-sig').splitlines():
                if line.startswith('AI_EVAL_DECISION '):evaluation.append(json.loads(line[len('AI_EVAL_DECISION '):]))
            turns=[json.loads(l) for l in Path(str(prefix)+'.games.jsonl.profile.jsonl').read_text(encoding='utf-8-sig').splitlines() if json.loads(l).get('type')=='TURN']
            row=dict(seed=seed,mode=mode,winner=game['winner'],draw=game['draw'],turns=game['finalTurnCount'],durationMs=game['durationMs'],workerWallSeconds=read(d/'execution.json')['wallSeconds'],valid=True,retries=0,
                gcMsReportedTurns=sum(x['gcMs'] for x in turns),peakReportedHeapBytes=max((x['heapUsed'] for x in turns),default=None),aiEvaluationScopes=len(evaluation),
                aiFallbackCalls=sum(x.get('fallbackCalls',0) for x in evaluation) if evaluation else None,diagnosticFields=sorted({k for x in evaluation for k in x}),
                resultSHA256=c.sha(Path(str(prefix)+'.games.jsonl')),traceSHA256=c.sha(d/'combat.jsonl'),commandSHA256=c.sha(d/'command.json'),logBytes=(d/'execution.log').stat().st_size)
            if mode=='optimized':
                base=R/f'permanent-baseline-{seed}';prior=read(base/'validation-passed.json');assert c.sha(base/f'commander-seed{seed}.games.jsonl')==prior['resultSHA256'] and c.sha(base/'combat.jsonl')==prior['traceSHA256']
                original=read(base/f'commander-seed{seed}.games.jsonl');assert all(game[k]==original[k] for k in ['winner','draw','finalTurnCount','simulationStatus'])
                try:parity=compare(telemetry(base),trace)
                except AssertionError:
                    c.write(R/f'structured-difference-{seed}.json',dict(baseline=telemetry(base),candidate=trace));host.DATA['differences'].append(dict(seed=seed,kind='structured-rules-sensitive'));raise
                host.DATA['regressions'].setdefault('structuredNative',[]).append(dict(seed=seed,**parity))
            c.write(d/'validation-passed.json',row);valid.append(row);host.DATA.setdefault('smoke' if mode=='optimized' else 'baselineGames',[]).append(row);c.write(R/'checkpoint.json',host.DATA)
    finally:
        elapsed=time.monotonic()-started;host.DATA[key]=dict(engineSHA256=c.sha(R/('baseline.jar' if mode=='baseline' else 'optimized.jar')),flags=api.flags(mode),seeds=api.SMOKE_SEEDS,attempted=attempted,validGames=validated,elapsedWallSeconds=elapsed,
            validGamesPerHourElapsed=3600*validated/elapsed,timeoutOrFailureAttempts=attempted-validated,analysisOrParityGateFailures=validated-len(valid),retryCount=0,complete=len(valid)==len(api.SMOKE_SEEDS),
            wholeGameDurationMs=[x['durationMs'] for x in valid],medianDurationMs=statistics.median([x['durationMs'] for x in valid]) if valid else None,
            throughputScope='Sequential cohort elapsed includes JVM startup, admission, isolated runtime creation and independent validation. Publication occurs outside this interval. Three games provide descriptive throughput, not robust statistical proof.')

def checkpoint(stage):
    if stage=='correctness-gates-passed':
        original=host.testng('functional-full-parity','functional',['-Dforge.bench.verify=true','-Dforge.bench.repeats=1']);api.equal(original,R/'full-parity-baseline',False)
        host.DATA['regressions']['functionalOriginal64Fixtures']=dict(passed=True,workloads=len(api.rows(original)))
        old_checkpoint('building-permanent-game-baseline');cohort('baseline');c.write(R/'permanent-baseline.json',dict(schemaVersion=1,functionalOriginalSHA256=api.EXPECTED,observationReferenceSHA256=api.REFERENCE_SHA,flags=api.flags('baseline'),deckHashes=verify()['deckHashes'],seeds=api.SMOKE_SEEDS,cohort=host.DATA['baselineCohort'],games=host.DATA['baselineGames']));comparison();old_checkpoint('permanent-baseline-completed')
    if stage=='profiling-completed':
        profiles={}
        for name in ['allocation-profile-off','allocation-profile-optimized']:
            records=[json.loads(l) for l in (R/name/'search.jsonl').read_text(encoding='utf-8-sig').splitlines()];assert records[-1]['type']=='AGENT_END' and not records[-1]['errors'];windows=[x for x in records if x['type']=='WINDOW' and x['rep']>=0];profiles[name]=windows
        left=profiles['allocation-profile-off'];right=profiles['allocation-profile-optimized'];assert len(left)==len(right)>0
        summaries=[]
        for a,b in zip(left,right):
            assert (a['case'],a['rep'])==(b['case'],b['rep'])
            for method,stats in a['methods'].items():
                if 'ComputerUtilCombat' in method or 'getCardsIn' in method:assert stats['calls']==b['methods'][method]['calls'],'Native prediction/traversal count changed'
            summaries.append(dict(case=a['case'],rep=a['rep'],predictionMethodEntries=sum(v['calls'] for k,v in a['methods'].items() if 'ComputerUtilCombat' in k),boardAccessEntries=sum(v['calls'] for k,v in a['methods'].items() if 'getCardsIn' in k),countsMatch=True))
        host.DATA['regressions']['predictionAndTraversalCountParity']=summaries
        host.DATA['metricsLimitations']='Named prediction/traversal method-entry counts include overloads. No equivalent-state caching or count claim. Agent/JFR appear only in separate diagnostics; primary timings and cohort throughput have no agent. Current-thread synthetic allocations exclude other threads.'
    return old_checkpoint(stage)

def integrity():
    old_integrity();d=R/'structured-and-programme-tests';d.mkdir();c.launch(d,[Path(sys.executable).with_name('python.exe'),'-B','-m','unittest','test_structured_parity','test_incremental_runner'],api.HERE,host.ENV,120);host.DATA['regressions']['comparatorAndProgrammeGates']=dict(passed=True)
def smoke():
    assert host.DATA['performanceImprovementDemonstrated'];cohort('optimized');host.DATA['boundedNativeBehaviourParity']=True
host.comparison=comparison;host.checkpoint=checkpoint;host.python_regressions=integrity;host.smoke=smoke
if __name__=='__main__':
    try:
        if '--freeze' in sys.argv:
            host.freeze()
        else:host.run()
    except BaseException:c.write(R/'bootstrap-or-run-error.json',dict(error=traceback.format_exc(),utc=host.now()));raise
