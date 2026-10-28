"""Phase 5: one query-local unused snapshot elimination; frozen reference family."""
from pathlib import Path
import json,os,re,shutil,subprocess,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE))
import validate as core
R=core.R;SOURCE=core.SOURCE;H=core.H;EX=core.EX;e=core.e
BASE=EX/'correctness-busy-board-20261027/validation-06/correctness.jar';EXPECTED='0fb7ec0758c186ee8d1e85efc6e1fe0a7754f50ff0e5a8433366f484651fc274'
REFERENCE=EX/'combat-parity-v2-20261028/validation-03/observed-baseline.jar';REFERENCE_SHA='5d5b2603a4796a6d0b5281e18554908fffa9fe987028bf1616944d9112fe0803'
AGENT=EX/'combat-search-v4-20261028/validation-03/search-agent.jar'
NG=Path('C:/Users/Chris/.m2/repository/org/testng/testng/7.10.2/testng-7.10.2.jar');JC=Path('C:/Users/Chris/.m2/repository/com/beust/jcommander/1.82/jcommander-1.82.jar')
FLAGS=core.FLAGS+['-Dforge.cost.liveSourceFilter=false','-Dforge.combat.blockCostFastPath=false','-Dforge.blocker.hoistCostPresence=false']
METRICS_FLAG='forge.blocker.snapshotMetrics';CALL_FIELD='requirementSnapshotQueries';SKIP_FIELD='requirementSnapshotsAvoided'
RUN='20261028-native-incremental-performance-v5-01';BRANCH='codex/commander-incremental-performance-v5';RUNNER='incremental_run.py';SMOKE_SEEDS=[573100,573101,573102]
TITLE='Commander incremental performance programme: v5 candidate 01'
DESCRIPTION='Omit a combat-blocker snapshot only when the unchanged requirement helper cannot read it'
CHANGE='Every requirement query rechecks live keyword prefixes. If no must-be-blocked count/validity keyword exists, the original helper receives an immutable empty view: it cannot inspect that argument in this branch. Otherwise a fresh actual snapshot is supplied. All costs, legal options, prediction calls, budgets and ordering remain unchanged. No result cache or cross-call reuse. Candidate is opt-in via forge.blocker.lazyRequirementSnapshots=true. Earlier experiments are disabled and not combined. Functional baseline is frozen 0fb7ec...; the permanent observation reference adds only the previously validated structured observer.'
HISTORY='Phase 4 measured heavy search-control work (makeGoodBlocks, makeTradeBlocks, makeGangBlocks), 131,514 prediction method entries and 1,858,534 board-access entries across eight operations. Proxy signatures did not prove state equivalence, so no prediction cache is used. This candidate targets unused snapshot construction in their shared legality path. Predeclared experimental gate: >=2% median gain on both primary workloads, all three JVM blocks improve with exploratory upper ratio <1, no >5% median/p95/CPU/allocation regression in measured combat workloads. Promotion consideration still requires >=15% on both primary workloads and full correctness/native validation. Smaller credible gains may remain experimental; negligible benefit ends this candidate without refinement.'
EXTRA_SOURCES=['forge-game/src/main/java/forge/game/combat/CombatUtil.java','forge-game/src/main/java/forge/game/combat/RequirementSnapshotMetrics.java']
REGRESSION='forge.ai.RequirementSnapshotTest,forge.ai.BlockingCostHoistTest,forge.ai.CombatParityTraceTest,forge.ai.BlockerTest,forge.ai.ThroneFixtures,forge.view.SimulationGameResultsJsonlTest,forge.view.SimulationTurnMetricsTest'
CASES='early,large-four-player,dragon-tokens,treasure-heavy,lathliss-ganax,lathliss-throne,tempest-valkas,large-combat,wipe-recovery,late-100';RULES='rules-first-strike,rules-double-strike,rules-deathtouch-trample,rules-menace,rules-lure,rules-restrictions'
def flags(mode):return FLAGS+['-Dforge.blocker.lazyRequirementSnapshots='+str(mode=='optimized').lower()]
def java(cmd,cwd=SOURCE):return subprocess.run(list(map(str,cmd)),cwd=cwd,capture_output=True,text=True,encoding='utf-8',timeout=120)
def bytecode(cp,klass):
    p=java([e.JAVA.with_name('javap.exe'),'-c','-p','-classpath',cp,klass]);assert p.returncode==0,p.stderr;return re.sub(r'#\d+','#',p.stdout)
def prepare():
    assert core.sha(BASE)==EXPECTED and core.sha(REFERENCE)==REFERENCE_SHA
    R.mkdir(exist_ok=False);core.admission(R,H);control=R/'control-source';control.mkdir();out=R/'control-classes';out.mkdir()
    p=java(['git','show','418dac868f3f48349ff0b5d35e9197c65008d6e6:'+EXTRA_SOURCES[0]]);assert p.returncode==0
    f=control/'CombatUtil.java';f.write_text(p.stdout,encoding='utf-8');p=java([e.JAVA.with_name('javac.exe'),'--release','17','-cp',REFERENCE,'-d',out,f]);assert p.returncode==0,p.stderr
    assert bytecode(REFERENCE,'forge.game.combat.CombatUtil')==bytecode(out,'forge.game.combat.CombatUtil'),'Reference dependency source mismatch'
    classes=R/'engine-classes';classes.mkdir();p=java([e.JAVA.with_name('javac.exe'),'--release','17','-cp',REFERENCE,'-d',classes,*[SOURCE/f for f in EXTRA_SOURCES]]);(R/'compile.log').write_text(p.stdout+p.stderr);assert p.returncode==0,p.stderr
    entries={p.relative_to(classes).as_posix():p.read_bytes() for p in classes.rglob('*.class')}
    shutil.copy2(REFERENCE,R/'baseline.jar')
    with zipfile.ZipFile(REFERENCE) as x,zipfile.ZipFile(R/'optimized.jar','w',zipfile.ZIP_DEFLATED) as y:
        for info in x.infolist():y.writestr(info,entries.get(info.filename,x.read(info)))
        for name,data in entries.items():
            if name not in x.namelist():y.writestr(name,data)
    with zipfile.ZipFile(REFERENCE) as x,zipfile.ZipFile(R/'optimized.jar') as y:assert all(x.read(n)==y.read(n) for n in x.namelist() if n not in entries)
    tests=R/'test-classes';shutil.copytree(EX/'blocker-performance-v3-20261028/validation-02/test-classes',tests)
    files=['forge/ai/'+name+'.java' for name in ['AITest','BusyBoardBenchmark','RequirementSnapshotTest','BlockingCostHoistTest','CombatParityTraceTest','BlockerTest','ThroneFixtures']]+['forge/view/'+name+'.java' for name in ['SimulationWorkerProbe','SimulationGameResultsJsonlTest','SimulationTurnMetricsTest']]
    src=SOURCE/'forge-gui-desktop/src/test/java';cp=';'.join(map(str,[R/'optimized.jar',NG,tests]));p=java([e.JAVA.with_name('javac.exe'),'--release','17','-cp',cp,'-d',tests,*[src/f for f in files],SOURCE/EXTRA_SOURCES[1]]);(R/'test-compile.log').write_text(p.stdout+p.stderr);assert p.returncode==0,p.stderr
    core.write(R/'manifest.json',dict(functionalBaselineSHA256=EXPECTED,referenceSHA256=REFERENCE_SHA,baselineSHA256=core.sha(R/'baseline.jar'),candidateSHA256=core.sha(R/'optimized.jar'),sourceControlParity=True,allOtherJarEntriesIdentical=True,changedEntries=list(entries),agentSHA256=core.sha(AGENT)))
def testng(label,mode,properties,classes,limit=1200,jfr=False):
    d=R/label;d.mkdir();cwd,env,home,temp=core.runtime(d);jar=BASE if mode=='functional' else R/('optimized.jar' if mode=='optimized' else 'baseline.jar');props=list(properties)
    if '-Dforge.bench.verify=true' in props:
        if mode!='functional':t=d/'traces';t.mkdir();props+=['-Dforge.bench.traceDir='+str(t)]
        if not any(p.startswith('-Dforge.bench.cases=') for p in props):props+=['-Dforge.bench.cases='+CASES+','+RULES]
    cp=';'.join(map(str,[R/'test-classes',jar,NG,JC]));cmd=[e.JAVA,*flags(mode),*e.opts(home,temp),*props]
    if jfr:cmd += ['-javaagent:'+str(AGENT),'-Dforge.search.output='+str(d/'search.jsonl'),'-Dforge.search.probe=true',f'-XX:StartFlightRecording=filename={d/"profile.jfr"},settings=profile,maxsize=64m,dumponexit=true']
    cmd+=['-cp',cp,'org.testng.TestNG','-testclass',classes,'-d',d/'results'];core.launch(d,cmd,cwd,env,limit);return d
def rows(d):return [json.loads(l[11:]) for l in (d/'execution.log').read_text(encoding='utf-8-sig').splitlines() if l.startswith('BUSY_BENCH ')]
def equal(left,right,structured=True):
    a=rows(left);b=rows(right);assert len(a)==len(b)>0
    for x,y in zip(a,b):
        for key in ['case','path','rep','legal','assignments','state','afterDamage','result']:assert x.get(key)==y.get(key),f'Rules/AI changed: {x["case"]}/{x["path"]}/{key}'
    if structured:
        from structured_parity import compare
        for f in (left/'traces').glob('*.jsonl'):compare([json.loads(l) for l in f.read_text(encoding='utf-8-sig').splitlines()],[json.loads(l) for l in (right/'traces'/f.name).read_text(encoding='utf-8-sig').splitlines()])
def initial():
    for mode in ['baseline','optimized']:
        d=R/('initial-regressions-'+mode);props=['-Dforge.parity.testdir='+str(d),'-Dfixture.deck='+str(EX/'lathliss-r-throne-20261026/decks/Lathliss_R_Rebuild_V2_Throne.dck')];testng(d.name,mode,props,REGRESSION,600)
    props=['-Dforge.bench.verify=true','-Dforge.bench.cases=large-four-player','-Dforge.bench.paths=block','-Dforge.bench.repeats=1']
    a=testng('initial-functional','functional',props,'forge.ai.BusyBoardBenchmark');b=testng('initial-parity-baseline','baseline',props,'forge.ai.BusyBoardBenchmark');d=testng('initial-parity-candidate','optimized',props,'forge.ai.BusyBoardBenchmark');equal(a,b,False);equal(b,d)
    core.write(R/'initial-gate.json',dict(passed=True,functionalBaselineParity=True,structuredParity=True))
if __name__=='__main__':
    import msvcrt
    with (H/'work/commander-batches/worker.lock').open('r+b') as lock:
        lock.seek(0);msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1)
        if '--prepare' in sys.argv:prepare()
        elif '--initial' in sys.argv:initial()
