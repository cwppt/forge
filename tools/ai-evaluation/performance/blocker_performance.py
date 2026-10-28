"""One Phase-3 optimisation, shared diagnostic baseline, frozen correctness dependency."""
from pathlib import Path
import importlib.util,json,os,re,shutil,subprocess,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE))
import validate as core
R=core.R;SOURCE=core.SOURCE;H=core.H;EX=core.EX;e=core.e
BASE=EX/'correctness-busy-board-20261027/validation-06/correctness.jar'
EXPECTED='0fb7ec0758c186ee8d1e85efc6e1fe0a7754f50ff0e5a8433366f484651fc274'
NG=Path('C:/Users/Chris/.m2/repository/org/testng/testng/7.10.2/testng-7.10.2.jar');JC=Path('C:/Users/Chris/.m2/repository/com/beust/jcommander/1.82/jcommander-1.82.jar')
FLAGS=core.FLAGS+['-Dforge.cost.liveSourceFilter=false','-Dforge.combat.blockCostFastPath=false']
METRICS_FLAG='forge.blocker.metrics';CALL_FIELD='requirementCandidates';SKIP_FIELD='avoidedTaxQueries'
RUN='20261028-native-blocker-performance-v3';BRANCH='codex/commander-blocker-performance-v3';RUNNER='blocker_run.py'
TITLE='Native Commander blocker requirement enumeration v3'
DESCRIPTION='Hoist blocking-cost presence into one invocation-local proof in repeated blocker requirement scans'
CHANGE='The original ordered blocker requirement search is unchanged. One complete static-source scan per mustBlockAnAttacker invocation proves whether blocking-cost abilities are absent. Only when none exists can repeated null cost lookups be omitted. Any potential source, including inactive or dynamic taxes, retains all original evaluations and ordering. No result survives the invocation; no legal option, combat prediction, budget or AI heuristic is removed. The shared comparison JAR defaults off; baseline uses forge.blocker.hoistCostPresence=false, candidate true. Earlier cost-source and block-tax fast paths are disabled in both.'
HISTORY='Saved JFR stacks repeatedly traverse AiBlockController.getPossibleBlockers -> CombatUtil.canBlock -> mustBlockAnAttacker -> getBlockCost, including requirement-free attackers. This is a blocker-enumeration complexity change, not another cost-adjustment implementation. A source-control check exposed additional virtual-budget checkpoints in the frozen AiBlockController; that class is deliberately retained byte-for-byte rather than recompiled from incomplete committed source. Exact prediction/copy counts remain unavailable; legal-query and repeated tax-traversal counts are measured at the preserved controller boundary. Acceptance predeclared: >=15% pooled median reduction on both primary blocker workloads, all three JVM-block median ratios <1 and exploratory interval upper <1, primary p95 and allocations no worse than +5%, no focused blocker median regression >5%. A 20% gain is desirable, not assumed. Only after correctness and this performance gate may three matched native smoke seeds run. One candidate only; preserve failure rather than try alternatives.'
ORIGINAL_CLASSES=['forge.game.combat.CombatUtil','forge.game.GameAction','forge.game.trigger.TriggerHandler','forge.game.zone.MagicStack','forge.ai.decision.CommanderRoleTelemetry']
EXTRA_SOURCES=[('forge-ai' if '.ai.' in x else 'forge-game')+'/src/main/java/'+x.replace('.','/')+'.java' for x in ORIGINAL_CLASSES]+['forge-game/src/main/java/forge/game/combat/BlockerSearchMetrics.java','forge-game/src/main/java/forge/game/CombatParityTrace.java']
REGRESSION='forge.ai.BlockingCostHoistTest,forge.ai.CombatParityTraceTest,forge.ai.BlockerTest,forge.ai.ThroneFixtures,forge.view.SimulationGameResultsJsonlTest,forge.view.SimulationTurnMetricsTest'
CASES='early,large-four-player,dragon-tokens,treasure-heavy,lathliss-ganax,lathliss-throne,tempest-valkas,large-combat,wipe-recovery,late-100'
RULES='rules-first-strike,rules-double-strike,rules-deathtouch-trample,rules-menace,rules-lure,rules-restrictions'
def flags(mode):return FLAGS+['-Dforge.blocker.hoistCostPresence='+str(mode=='optimized').lower()]
def java(args,cwd=SOURCE,env=None):return subprocess.run(list(map(str,args)),cwd=cwd,env=env,capture_output=True,text=True,encoding='utf-8',timeout=120)
def bytecode(cp,klass):
    p=java([e.JAVA.with_name('javap.exe'),'-c','-p','-classpath',cp,klass]);assert p.returncode==0,p.stderr
    return re.sub(r'#\d+','#',p.stdout)
def prepare():
    assert core.sha(BASE)==EXPECTED;R.mkdir(exist_ok=False);core.admission(R,H)
    controls=R/'control-source';controls.mkdir();classes=R/'control-classes';classes.mkdir();sources=[]
    for rel in EXTRA_SOURCES[:len(ORIGINAL_CLASSES)]:
        p=java(['git','show','418dac868f3f48349ff0b5d35e9197c65008d6e6:'+rel]);assert p.returncode==0
        f=controls/Path(rel).name;f.write_text(p.stdout,encoding='utf-8');sources.append(f)
    p=java([e.JAVA.with_name('javac.exe'),'--release','17','-cp',BASE,'-d',classes,*sources]);(R/'control-compile.log').write_text(p.stdout+p.stderr);assert p.returncode==0
    parity={}
    for f in classes.rglob('*.class'):
        klass=f.relative_to(classes).with_suffix('').as_posix().replace('/','.');parity[klass]=bytecode(BASE,klass)==bytecode(classes,klass)
    core.write(R/'control-parity.json',parity);assert all(parity.values()),'Required baseline source differs; stop'
    out=R/'engine-classes';out.mkdir();p=java([e.JAVA.with_name('javac.exe'),'--release','17','-cp',BASE,'-d',out,*[SOURCE/f for f in EXTRA_SOURCES]]);(R/'engine-compile.log').write_text(p.stdout+p.stderr);assert p.returncode==0,p.stderr
    entries={f.relative_to(out).as_posix():f.read_bytes() for f in out.rglob('*.class')}
    with zipfile.ZipFile(BASE) as old,zipfile.ZipFile(R/'baseline.jar','w',zipfile.ZIP_DEFLATED) as jar:
        for info in old.infolist():jar.writestr(info,entries.get(info.filename,old.read(info)))
        for n,b in entries.items():
            if n not in old.namelist():jar.writestr(n,b)
    shutil.copy2(R/'baseline.jar',R/'optimized.jar')
    with zipfile.ZipFile(BASE) as x,zipfile.ZipFile(R/'baseline.jar') as y:assert all(x.read(n)==y.read(n) for n in x.namelist() if n not in entries)
    tests=R/'test-classes';tests.mkdir();src=SOURCE/'forge-gui-desktop/src/test/java'
    files=['forge/ai/'+name+'.java' for name in ['AITest','BusyBoardBenchmark','BlockingCostHoistTest','CombatParityTraceTest','BlockerTest','ThroneFixtures']]+['forge/view/'+name+'.java' for name in ['SimulationWorkerProbe','SimulationGameResultsJsonlTest','SimulationTurnMetricsTest']]
    cp=';'.join(map(str,[R/'baseline.jar',NG,H/'forge-gui-desktop/target/test-classes']))
    p=java([e.JAVA.with_name('javac.exe'),'--release','17','-cp',cp,'-d',tests,*[src/f for f in files],SOURCE/EXTRA_SOURCES[-2],SOURCE/EXTRA_SOURCES[-1]]);(R/'test-compile.log').write_text(p.stdout+p.stderr);assert p.returncode==0,p.stderr
    core.write(R/'manifest.json',dict(functionalBaselineSHA256=EXPECTED,baselineSHA256=core.sha(R/'baseline.jar'),treatmentSHA256=core.sha(R/'optimized.jar'),sameJarDifferentStartupFlag=True,changedEntries=list(entries),allOtherEntriesIdentical=True,controlParity=parity,baselineFlags=flags('baseline'),candidateFlags=flags('optimized')))
def testng(label,mode,properties,classes,limit=1200,jfr=False):
    d=R/label;d.mkdir();cwd,env,home,temp=core.runtime(d)
    jar=BASE if mode=='frozen' else R/('optimized.jar' if mode=='optimized' else 'baseline.jar')
    props=list(properties)
    if '-Dforge.bench.verify=true' in props:
        trace=d/'traces';trace.mkdir();props+=['-Dforge.bench.traceDir='+str(trace)]
        if not any(x.startswith('-Dforge.bench.cases=') for x in props):props+=['-Dforge.bench.cases='+CASES+','+RULES]
    cp=';'.join(map(str,[R/'test-classes',jar,NG,JC]));cmd=[e.JAVA,*flags(mode),*e.opts(home,temp),*props]
    if jfr:cmd+=[f'-XX:StartFlightRecording=filename={d/"profile.jfr"},settings=profile,maxsize=64m,dumponexit=true']
    cmd+=['-cp',cp,'org.testng.TestNG','-testclass',classes,'-d',d/'results'];core.launch(d,cmd,cwd,env,limit);return d
def rows(d):return [json.loads(l[11:]) for l in (d/'execution.log').read_text(encoding='utf-8-sig').splitlines() if l.startswith('BUSY_BENCH ')]
def equal(left,right,structured=True):
    a=rows(left);b=rows(right);assert len(a)==len(b)>0
    for x,y in zip(a,b):
        for key in ['case','path','rep','legal','assignments','state','afterDamage','result']:assert x.get(key)==y.get(key),f'Rules/AI difference {x["case"]}/{x["path"]}/{key}'
    if structured:
        from structured_parity import compare
        for f in (left/'traces').glob('*.jsonl'):
            other=right/'traces'/f.name;compare([json.loads(l) for l in f.read_text().splitlines()],[json.loads(l) for l in other.read_text().splitlines()])
def initial():
    props=['-Dfixture.deck='+str(EX/'lathliss-r-throne-20261026/decks/Lathliss_R_Rebuild_V2_Throne.dck')]
    for mode in ['baseline','optimized']:testng('initial-regressions-'+mode,mode,props+['-Dforge.parity.testdir='+str(R/('initial-regressions-'+mode))],REGRESSION,600)
    props=['-Dforge.bench.verify=true','-Dforge.bench.cases=large-four-player','-Dforge.bench.paths=block','-Dforge.bench.repeats=1']
    a=testng('initial-frozen','frozen',props,'forge.ai.BusyBoardBenchmark');b=testng('initial-parity-baseline','baseline',props,'forge.ai.BusyBoardBenchmark');d=testng('initial-parity-optimized','optimized',props,'forge.ai.BusyBoardBenchmark')
    equal(a,b,False);equal(b,d);core.write(R/'initial-gate.json',dict(passed=True,functionalFrozenParity=True,structuredParity=True))
if __name__=='__main__':
    with (H/'work/commander-batches/worker.lock').open('r+b') as lock:
        import msvcrt
        lock.seek(0);msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1)
        if '--prepare' in sys.argv:prepare()
        elif '--initial' in sys.argv:initial()
