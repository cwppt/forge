"""Checked Phase 7C recovery: optional benchmark counters only; immutable parent evidence."""
from pathlib import Path
import copy,hashlib,json,os,shutil,subprocess,sys,traceback,zipfile
sys.dont_write_bytecode=True
import adoption_run as p
n=p.n;c=p.c;a=p.a;h=p.h;e=p.e;R=p.R;S=p.S
PARENT=a.EX/'performance-adoption-v7c-20261028/validation-01'
ORIGINAL_HEAD='e85ec717206c51182b5cac6393407be2aece14b8'
COMBINED_SHA='9107411b42f66408f0e9ffae47fd095210095bf4484d88a34b6fbd2a30193dd4'
BENCH_SOURCE='forge-gui-desktop/src/test/java/forge/ai/'
REPAIR_SOURCES=[BENCH_SOURCE+x for x in ['BusyBoardBenchmark.java','BenchmarkOptionalMetrics.java','BenchmarkMetricsCompatibilityTest.java']]
ID='20261028-native-performance-adoption-v7c-recovery-01'
D=p.D

def read(path):return p.read(path)
def write(path,value):return c.write(path,value)
def parent_manifest():
    q=read(PARENT/'run-request.json');assert q['sourceHead']==ORIGINAL_HEAD
    assert c.sha(PARENT/'combined.jar')==COMBINED_SHA and c.sha(p.REF)==p.REF_SHA
    state=read(PARENT/'checkpoint.json');assert state['state']=='failed-preserved' and not state['benchmarks'] and not state['games']
    assert state['regressions']['resultValidator']['passed']
    for arm in p.ARMS:assert state['regressions']['failurePaths-'+arm]['passed']
    for relative,digest in q['compiledHashes'].items():assert c.sha(PARENT/relative)==digest,'Parent compiled evidence changed: '+relative
    for path,digest in q['sourceHashes'].items():
        if path=='tools/ai-evaluation/performance/adoption_run.py':
            old=subprocess.check_output(['git','-C',str(S),'show',ORIGINAL_HEAD+':'+path],text=True,encoding='utf-8-sig')
            expected=old.replace("['CostSourceRegressionTest.java','BlockCostSourceRegressionTest.java','CombinedFeatureTest.java']","['CostSourceRegressionTest.java','BlockCostSourceRegressionTest.java','CombinedFeatureTest.java','BusyBoardBenchmark.java','BenchmarkOptionalMetrics.java','BenchmarkMetricsCompatibilityTest.java']")
            assert (S/path).read_text(encoding='utf-8-sig')==expected,'Unexpected change to original supervisor'
        else:assert c.sha(S/path)==digest,'Unrelated parent source changed: '+path
    for relative,digest in q['helperHashes'].items():assert c.sha(a.H/relative)==digest
    return q,state

def verify():
    q=read(R/'run-request.json');assert q['sourceHead']==p.head() and q['limits']==p.LIMITS
    assert c.sha(p.REF)==p.REF_SHA and c.sha(R/'combined.jar')==COMBINED_SHA
    for key,root in [('sourceHashes',S),('compiledHashes',R),('parentEvidence',PARENT),('helperHashes',a.H),('historicalHashes',a.EX)]:
        for name,digest in q[key].items():assert c.sha(root/name)==digest,'Recovery input changed: '+name
    for name,digest in q['deckHashes'].items():assert c.sha(p.V7/'decks'/name)==digest,'Frozen deck changed: '+name
    assert q['armFlags']=={arm:p.flags(arm) for arm in p.ARMS}
    assert q['plan']==p.PLAN and c.sha(R/'initial-gate.json')==q['initialGateSHA256']
    for path,digest in q['recoveryGates'].items():assert c.sha(R/path)==digest,'Recovery gate evidence changed: '+path
    for path,digest in q['verificationEvidence'].items():assert c.sha(R/path)==digest,'Corrected verification evidence changed: '+path
    assert read(R/'corrected-control.json')['passed'] and read(R/'adapter-tests.json')['passed']
    return q
p.verify=verify;h.verify=verify


def prepare():
    q,state=parent_manifest();assert not (R/'combined.jar').exists();R.mkdir(parents=True,exist_ok=True);h.admission(R,a.H)
    for name in ['combined.jar','composition.json','build-provenance.json','initial-gate.json','historical-review.json','rng-final-assessment.json','storage-recovery.json']:
        shutil.copy2(PARENT/name,R/name)
    shutil.copytree(PARENT/'test-classes',R/'test-classes')
    compile_sources=[S/name for name in REPAIR_SOURCES]
    result=a.java([e.JAVA.with_name('javac.exe'),'--release','17','-cp',';'.join(map(str,[R/'test-classes',R/'combined.jar',a.NG,a.JC])),'-d',R/'test-classes',*compile_sources])
    (R/'benchmark-repair-compile.log').write_text(result.stdout+result.stderr);assert result.returncode==0,result.stderr
    delta={}
    for path in (R/'test-classes').rglob('*.class'):
        relative=path.relative_to(R/'test-classes').as_posix();old=q['compiledHashes'].get('test-classes/'+relative)
        if old!=c.sha(path):
            assert relative=='forge/ai/BusyBoardBenchmark.class' or relative.startswith(('forge/ai/BenchmarkOptionalMetrics','forge/ai/BenchmarkMetricsCompatibilityTest')),'Unexpected class rebuilt: '+relative
            delta[relative]=dict(parentSHA256=old,recoverySHA256=c.sha(path))
    with zipfile.ZipFile(R/'combined.jar') as jar:
        assert not any(f.relative_to(R/'test-classes').as_posix() in jar.namelist() for f in (R/'test-classes').rglob('*.class')),'Engine class shadow introduced'
    write(R/'test-class-delta.json',delta)
    old_command=read(PARENT/'full-parity-A/command.json');prior=a.EX/'rng-isolation-v7b-20261028/validation-01/unchanged-full-parity'
    write(R/'recovery-cause.json',dict(category='A: test-runner/benchmark classpath linkage defect',exception='NoClassDefFoundError: forge/game/combat/RequirementSnapshotMetrics',
        location='BusyBoardBenchmark.deterministicWorkloads line 119 before the first measured native action',originalChildExit=read(PARENT/'full-parity-A/execution.json'),
        originalCommand=old_command,priorSuccessfulCommand=read(prior/'command.json'),priorSuccessfulExecution=read(prior/'execution.json'),
        previousMetricsClassPresent=(prior.parent/'test-classes/forge/game/combat/RequirementSnapshotMetrics.class').exists(),parentMetricsClassPresent=False,
        repair='Benchmark-only optional metrics adapter: disabled/absent counters return unavailable null; present malformed/failing providers still fail. Workloads/assertions/comparator unchanged. No engine rebuild or shadow classes.',
        originalEnvironment='Full child environment was not persisted. The complete command and isolated runtime directories are preserved; the frozen runtime-helper hashes were checked. Explicit -cp excludes reliance on environment CLASSPATH.',
        parentReport='https://github.com/cwppt/MTG-deck-lists/tree/main/forge-performance/reports/20261028-native-performance-adoption-v7c',
        unchangedEngines=dict(control=p.REF_SHA,combined=COMBINED_SHA)))
    print('Compiled only the benchmark adapter/tests; both engines and original evidence unchanged')


def adapter_tests():
    rows={}
    for arm in p.ARMS:
        d=p.testng('adapter-'+arm,arm,classes='forge.ai.BenchmarkMetricsCompatibilityTest',limit=180)
        counts=h.xmlcounts(d);assert counts==dict(total=6,passed=6,failed=0,skipped=0),counts;rows[arm]=counts
    write(R/'adapter-tests.json',dict(passed=True,arms=rows,assertionsUnchanged=True,engineClassesShadowed=False))


def control():
    d=p.testng('corrected-full-parity-A','A',['-Dforge.bench.verify=true','-Dforge.bench.repeats=1'],'forge.ai.BusyBoardBenchmark')
    assert h.xmlcounts(d)==dict(total=1,passed=1,failed=0,skipped=0)
    prior=a.EX/'rng-isolation-v7b-20261028/validation-01/unchanged-full-parity'
    a.equal(prior,d)
    assert len(a.rows(d))==64
    write(R/'corrected-control.json',dict(passed=True,workloads=64,strictStructuredParityWithPriorSuccessfulControl=True,referenceSHA256=p.REF_SHA,
        commandSHA256=c.sha(d/'command.json'),logSHA256=c.sha(d/'execution.log'),priorCommandSHA256=c.sha(prior/'command.json'),evidence=d.name))
    print('Corrected unchanged control passed all 64 workloads and strict parity against prior successful control')


def freeze():
    q,state=parent_manifest();assert read(R/'corrected-control.json')['passed'] and read(R/'adapter-tests.json')['passed'] and not (R/'run-request.json').exists()
    files=h.command(['git','-C',S,'ls-files']).stdout.splitlines()
    parent_files=['run-request.json','checkpoint.json','failure-run.json','status.json','heartbeat.json','publication.json','published-blobs.json','initial-gate.json','combined.jar','build-provenance.json','composition.json']
    for name in ['command.json','execution.log','execution.json','process.json','results/testng-results.xml']:parent_files.append('full-parity-A/'+name)
    parent_files += [f.relative_to(PARENT).as_posix() for f in (PARENT/'full-parity-A/traces').glob('*.jsonl')]
    for arm in p.ARMS:
        for name in ['command.json','execution.log','execution.json']:parent_files.append('integrity-'+arm+'/'+name)
    for name in ['command.json','execution.log','execution.json']:parent_files.append('independent-validator-tests/'+name)
    write(R/'run-request.json',dict(runId=ID,sourceHead=p.head(),sourceBranch=p.BRANCH,parentSourceHead=ORIGINAL_HEAD,controlSHA256=p.REF_SHA,combinedSHA256=COMBINED_SHA,limits=p.LIMITS,
        armFlags=q['armFlags'],armEngineSHA256=q['armEngineSHA256'],pod=q['pod'],deckHashes=q['deckHashes'],plan=q['plan'],
        sourceHashes={name:c.sha(S/name) for name in files if name.startswith(('tools/ai-evaluation/performance/','tools/ai-evaluation/parallel-v4/')) or name in REPAIR_SOURCES or name in q['sourceHashes']},
        compiledHashes={f.relative_to(R).as_posix():c.sha(f) for f in (R/'test-classes').rglob('*.class')},parentEvidence={name:c.sha(PARENT/name) for name in parent_files},
        helperHashes=q['helperHashes'],historicalHashes=q['historicalHashes'],initialGateSHA256=c.sha(R/'initial-gate.json'),
        recoveryGates={name:c.sha(R/name) for name in ['corrected-control.json','adapter-tests.json','test-class-delta.json','recovery-cause.json']},
        verificationEvidence={f.relative_to(R).as_posix():c.sha(f) for directory in ['corrected-full-parity-A','adapter-A','adapter-B','adapter-C','adapter-D'] for f in [R/directory/'command.json',R/directory/'execution.log',R/directory/'execution.json',R/directory/'results/testng-results.xml',*list((R/directory/'traces').glob('*.jsonl'))]},
        rngIsolation=False,diagnosticOnly=True,maximumGameWorkers=1,heapGiB=3,processors=2,freeSpaceReserveGiB=3,reusedPassingRegressions=state['regressions']))
    verify();print('Recovery request frozen; no engine rebuild and no completed measurement replay')


def report():
    intro='# Phase 7C recovery — optional benchmark counter linkage\n\n'+ \
        'The original [failed experiment](https://github.com/cwppt/MTG-deck-lists/tree/main/forge-performance/reports/20261028-native-performance-adoption-v7c) and its raw evidence remain unchanged. This is a separately published recovery, not a replacement or new optimisation candidate.\n\n'+ \
        'Cause: BusyBoardBenchmark directly linked the optional RequirementSnapshotMetrics class, absent from the validated control JAR. Earlier passing runs had a test-classpath copy. Phase 7C removed that shadow correctly, exposing a benchmark dependency error before its first measured native action. The counter adapter now reports disabled/absent metrics as unavailable; broken present providers still fail. Engine JARs, benchmark cases, assertions, comparator, flags, budgets and seeds are unchanged.\n\n```json\n'+ \
        json.dumps(dict(cause=read(R/'recovery-cause.json'),adapterTests=read(R/'adapter-tests.json'),correctedControl=read(R/'corrected-control.json'),reusedPassingGates=D['regressions'].get('reusedParent')),indent=2)+'\n```\n\n'
    return intro+base_report().replace('# Phase 7C — Final combined performance/adoption experiment','## Original four-arm protocol and recovery progress')


base_report=p.report
base_aggregate=p.aggregate
def aggregate():
    if D['arms']['A'].get('classification')=='ADOPTABLE':
        D['arms']['A']['classification']='VALIDATED'
        D['arms']['A']['reason']='Existing validated reference remains the default, with known RNG reproducibility limits; no replacement promoted.'
    base_aggregate()
p.aggregate=aggregate;n.aggregate=aggregate
n.report=report
n.RUN=ID;n.DEST='forge-performance/reports/20261028-native-performance-adoption-v7c/recovery-01';n.URL='https://github.com/cwppt/MTG-deck-lists/tree/main/'+n.DEST
D=p.D;D['runId']=ID


def execute():
    verify();q=read(R/'run-request.json');D['regressions']['reusedParent']=q['reusedPassingRegressions'];D['regressions'].update(q['reusedPassingRegressions'])
    D['regressions']['adapterCompatibility']=read(R/'adapter-tests.json');D['regressions']['correctedControl']=read(R/'corrected-control.json')
    for arm in p.ARMS:D['arms'][arm]=dict(classification='EXPERIMENTAL',reason='Previously passed focused and failure-path gates reused; corrected benchmark verification pending',engineSHA256=c.sha(p.jar(arm)),flags=p.flags(arm))
    p.publish('recovery-control-passed-validating-remaining-arms')
    d=R/'recovery-offline-tests';d.mkdir();c.launch(d,[Path(sys.executable).with_name('python.exe'),'-B','-m','unittest','test_structured_parity','test_adoption_recovery'],a.HERE,h.ENV,120);D['regressions']['recoveryOffline']=True
    reference=R/'corrected-full-parity-A';D['regressions']['structured64-A']=dict(passed=True,workloads=64,evidence='corrected-full-parity-A')
    for arm in ['B','C','D','O']:
        d=p.testng('corrected-full-parity-'+arm,arm,['-Dforge.bench.verify=true','-Dforge.bench.repeats=1'],'forge.ai.BusyBoardBenchmark')
        a.equal(reference,d);D['regressions']['structured64-'+arm]=dict(passed=True,workloads=len(a.rows(d)));p.publish('recovery-structured-'+arm+'-passed')
    focus='large-four-player,dragon-tokens,large-combat,late-100'
    for block in range(4):
        for arm in p.ARMS[block:]+p.ARMS[:block]:
            d=p.testng(f'timing-{block}-{arm}',arm,['-Dforge.bench.cases='+focus,'-Dforge.bench.paths=block,attack','-Dforge.bench.warmup=1','-Dforge.bench.repeats=3'],'forge.ai.BusyBoardBenchmark')
            D['benchmarks'][d.name]=dict(arm=arm,block=block,rows=a.rows(d));p.publish('recovery-'+d.name+'-completed')
    p.publish('synthetic-four-arm-completed');p.cohort()

p.execute=execute
if __name__=='__main__':
    if '--prepare' in sys.argv or '--adapter-tests' in sys.argv or '--control' in sys.argv:
        import msvcrt
        with (a.H/'work/commander-batches/worker.lock').open('r+b') as lock:
            lock.seek(0);msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1)
            if '--prepare' in sys.argv:prepare()
            elif '--adapter-tests' in sys.argv:adapter_tests()
            else:control()
    elif '--freeze' in sys.argv:freeze()
    elif '--verify-only' in sys.argv:verify();print('Recovery inputs and original evidence verified')
    else:p.run()
