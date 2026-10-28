"""Reuse finite supervisor, with Phase-3 performance and structured-smoke gates."""
import copy,csv,importlib.util,json,shutil,sys,traceback
from pathlib import Path
sys.dont_write_bytecode=True
import blocker_performance as api
sys.modules['combat_performance']=api
import combat_run as host
from structured_parity import compare
original_comparison=host.comparison;original_checkpoint=host.checkpoint;original_integrity=host.python_regressions
api.SMOKE_SEEDS=[572200,572201,572202]
R=api.R;S=api.SOURCE;c=api.core;e=api.e

def comparison():
    original_comparison();items=host.DATA.get('comparison',{})
    primary=[items.get(k) for k in ['large-four-player/block','dragon-tokens/block']]
    def pass_primary(v):
        return v is not None and v['medianChangePercent']<=-15 and len(v['jvmRoundMedianRatios'])==3 and max(v['jvmRoundMedianRatios'])<1 and v['exploratoryJvmBlockRatio95'][1]<1 and v['optimized']['wallMs']['p95']<=1.05*v['baseline']['wallMs']['p95'] and v['optimized']['allocationBytes']['median']<=1.05*v['baseline']['allocationBytes']['median']
    passed=all(pass_primary(v) for v in primary) and all(v['medianChangePercent']<=5 for k,v in items.items() if k.endswith('/block'))
    host.DATA['performanceImprovementDemonstrated']=passed
    host.DATA['acceptanceCriteria']='>=15% median reduction on both primary blocker workloads; all three JVM blocks improve and exploratory interval upper <1; primary p95/allocation <=+5%; no focused blocker median >+5%; rules parity and matched smoke are separate gates'
    host.DATA['conclusion']='Predeclared synthetic performance gate passed. Adoption still requires deterministic and structured smoke parity; no automatic promotion.' if passed else 'Predeclared 15% primary-workload performance gate not met. Preserve measurements; no smoke cohort or production adoption. This is the single permitted candidate for this pass.'

def checkpoint(stage):
    if stage=='profiling-completed':
        a=host.DATA['benchmarks']['allocation-profile-off']['rows'];b=host.DATA['benchmarks']['allocation-profile-optimized']['rows'];assert len(a)==len(b)>0
        for x,y in zip(a,b):
            for field in ['case','path','rep','legalPairQueries','legalPairAccepted','legalPairRejected']:
                assert x[field]==y[field],f'Native search query/decision count changed: {field}'
            assert x['legalPairQueries']==x['legalPairAccepted']+x['legalPairRejected']>0
        host.DATA['regressions']['enumerationCountParity']=dict(passed=True,workloads=len(a),meaning='Legal-pair queries and accept/reject counts match. Counts are not exhaustive multi-block assignment enumeration.')
        host.DATA['metricsLimitations']='Exact hypothetical combat evaluation, game-copy and equivalent-position counts are unavailable: frozen AiBlockController/ComputerUtilCombat classes are preserved, including local virtual-budget/cache dependencies. Avoided tax queries and board-source visits are counted directly. Legal-query elapsed time includes nested work; JFR CPU/allocation samples are estimates. Counts/JFR run separately from primary timing.'
    return original_checkpoint(stage)

def integrity():
    original_integrity();d=R/'structured-comparator-tests';d.mkdir()
    c.launch(d,[Path(sys.executable).with_name('python.exe'),'-B','-m','unittest','test_structured_parity','test_blocker_runner'],api.HERE,host.ENV,120)
    host.DATA['regressions']['structuredComparatorAndPerformanceGate']=dict(passed=True)

def smoke():
    assert host.DATA['performanceImprovementDemonstrated'],'Performance gate must pass before smoke'
    q=json.loads((api.EX/'lathliss-r-throne-20261026/request.json').read_text());decks=R/'decks';shutil.copytree(api.EX/'lathliss-r-throne-20261026/decks',decks)
    assert q['deckHashes']==host.verify()['deckHashes']
    for name,h in q['deckHashes'].items():assert c.sha(decks/name)==h
    spec=importlib.util.spec_from_file_location('classifier',S/'tools/ai-evaluation/parallel-v4/Run-ValidCommanderCohort.py');classifier=importlib.util.module_from_spec(spec);spec.loader.exec_module(classifier)
    for seed in api.SMOKE_SEEDS:
        traces=[];outcomes=[]
        for mode in ['baseline','optimized']:
            host.verify();host.admission(R,api.H);d=R/f'smoke-{mode}-{seed}';d.mkdir();cwd,env,home,temp=c.runtime(d);prefix=d/f'commander-seed{seed}';trace=d/'combat.jsonl'
            jar=R/('baseline.jar' if mode=='baseline' else 'optimized.jar')
            cmd=[e.JAVA,*api.flags(mode),*e.opts(home,temp),'-Dforge.simulation.profile=true','-Dforge.parity.trace='+str(trace),'-jar',jar,'sim','-d','Lathliss_R_Rebuild_V2_Throne.dck',*q['pod'],'-D',decks,'-n','1','-s',str(seed),'-f','Commander','-c','900','-q','--capture-simulation-failures','--external-ai-mulligan-disabled']
            for opt,suffix in [('metrics-csv','metrics.csv'),('decision-audit-jsonl','decisions.jsonl'),('game-results-jsonl','games.jsonl'),('game-log-jsonl','gamelog.jsonl'),('commander-role-jsonl','roles.jsonl')]:cmd+=['--'+opt,str(prefix)+'.'+suffix]
            host.ACTIVE.update(stage=d.name,mode=mode)
            try:c.launch(d,cmd,cwd,env,990)
            finally:host.ACTIVE.clear()
            shutil.copy2(d/'execution.log',Path(str(prefix)+'.log'));records=classifier.classify_batch(d,seed,1,False,0,True);c.write(d/'classification.json',records);assert len(records)==1 and records[0]['valid']
            v=d/'independent-validator';v.mkdir();c.launch(v,[Path(sys.executable).with_name('python.exe'),'-B',S/'tools/ai-evaluation/parallel-v4/Validate-CommanderTelemetry.py',d,'--games-per-seed','1','--expected-seeds','1','--require-clean-runtime','--report',d/'validation.json'],S,env)
            with Path(str(prefix)+'.metrics.csv').open(encoding='utf-8-sig',newline='') as f:assert not any(row.get('providerAttempted')=='true' for row in csv.DictReader(f))
            game=json.loads(Path(str(prefix)+'.games.jsonl').read_text());assert game['valid'] and game['simulationStatus']=='VALID'
            t=[json.loads(l) for l in trace.read_text().splitlines()];assert not any('unexpandedType' in json.dumps(x) for x in t),'Incomplete trigger context'
            assert {'ATTACK_ASSIGNMENTS','BLOCK_ASSIGNMENTS','BATCH_BEGIN','BATCH_END','TRIGGER_READY','RESOLVE_START','RESOLVED'} <= {x['type'] for x in t}
            traces.append(t);outcomes.append({k:game[k] for k in ['winner','draw','finalTurnCount','simulationStatus']})
            host.DATA['smoke'].append(dict(seed=seed,mode=mode,winner=game['winner'],draw=game['draw'],turns=game['finalTurnCount'],durationMs=game['durationMs'],valid=True,traceSHA256=c.sha(trace),logBytes=(d/'execution.log').stat().st_size,providerAttempts=0))
        assert outcomes[0]==outcomes[1],'Matched outcome changed'
        try:parity=compare(traces[0],traces[1])
        except AssertionError:
            c.write(R/f'structured-difference-{seed}.json',dict(baseline=traces[0],candidate=traces[1]));host.DATA['differences'].append(dict(seed=seed,kind='structured-rules-sensitive'));raise
        c.write(R/f'smoke-pair-{seed}.json',parity);host.DATA['regressions'].setdefault('structuredSmoke',[]).append(dict(seed=seed,**parity))
    host.DATA['boundedNativeBehaviourParity']=True

host.comparison=comparison;host.checkpoint=checkpoint;host.python_regressions=integrity;host.smoke=smoke
if __name__=='__main__':
    try:
        if '--freeze' in sys.argv:host.freeze()
        else:host.run()
    except BaseException:c.write(R/'bootstrap-or-run-error.json',dict(error=traceback.format_exc(),utc=host.now()));raise
