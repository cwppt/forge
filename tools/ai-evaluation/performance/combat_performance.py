"""Bounded native combat v1. Prepare interactively; run extended gates detached."""
from pathlib import Path
import datetime,difflib,hashlib,json,os,re,shutil,subprocess,sys,traceback,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE))
import validate as core
R=core.R;SOURCE=core.SOURCE;H=core.H;EX=core.EX;e=core.e
BASE=EX/'correctness-busy-board-20261027/validation-06/correctness.jar'
EXPECTED='0fb7ec0758c186ee8d1e85efc6e1fe0a7754f50ff0e5a8433366f484651fc274'
NG=Path('C:/Users/Chris/.m2/repository/org/testng/testng/7.10.2/testng-7.10.2.jar')
JC=Path('C:/Users/Chris/.m2/repository/com/beust/jcommander/1.82/jcommander-1.82.jar')
FLAGS=core.FLAGS+['-Dforge.combat.blockCostFastPath=true']
write=core.write;sha=core.sha;status=core.status
def java(command,cwd,env):
    return subprocess.run(list(map(str,command)),cwd=cwd,env=env,capture_output=True,text=True,timeout=120)
def bytecode(cp,klass='forge.game.card.CardState'):
    p=subprocess.run([str(e.JAVA.with_name('javap.exe')),'-c','-p','-classpath',str(cp),klass],capture_output=True,text=True,check=True)
    return re.sub(r'#\d+','#',p.stdout).splitlines()
def prepare():
    assert sha(BASE)==EXPECTED,'Validated baseline changed'
    shutil.copy2(BASE,R/'baseline.jar');status('building-and-checking-parity')
    setup=R/'setup';setup.mkdir();cwd,env,home,temp=core.runtime(setup)
    original=R/'original-classes';original.mkdir()
    control=SOURCE.parent/'forge-commander-correctness-20261027/forge-game/src/main/java/forge/game/card/CardState.java'
    p=java([e.JAVA.with_name('javac.exe'),'--release','17','-cp',BASE,'-d',original,control,SOURCE.parent/'forge-commander-correctness-20261027/forge-game/src/main/java/forge/game/combat/CombatUtil.java'],cwd,env)
    (R/'control-compile.log').write_text(p.stdout+p.stderr);assert p.returncode==0,'Control compilation failed'
    expected=bytecode(BASE);actual=bytecode(original)
    diff=list(difflib.unified_diff(expected,actual,fromfile='frozen-engine',tofile='validated-source'))
    (R/'control-parity.diff').write_text('\n'.join(diff))
    assert not diff,'Required local source dependency differs from the frozen engine; inspect parity evidence'
    assert bytecode(BASE,'forge.game.combat.CombatUtil')==bytecode(original,'forge.game.combat.CombatUtil'),'CombatUtil source differs from frozen engine'
    classes=R/'engine-classes';classes.mkdir();src=SOURCE/'forge-game/src/main/java/forge/game/card'
    p=java([e.JAVA.with_name('javac.exe'),'--release','17','-cp',BASE,'-d',classes,src/'CardState.java',src/'BlockCostMetrics.java',SOURCE/'forge-game/src/main/java/forge/game/combat/CombatUtil.java'],cwd,env)
    (R/'treatment-compile.log').write_text(p.stdout+p.stderr);assert p.returncode==0,'Treatment compilation failed'
    entries={p.relative_to(classes).as_posix():p.read_bytes() for p in classes.rglob('*.class')}
    with zipfile.ZipFile(BASE) as z,zipfile.ZipFile(R/'optimized.jar','w',zipfile.ZIP_DEFLATED) as out:
        for info in z.infolist():out.writestr(info,entries.get(info.filename,z.read(info)))
        for name,data in entries.items():
            if name not in z.namelist():out.writestr(name,data)
    with zipfile.ZipFile(BASE) as a,zipfile.ZipFile(R/'optimized.jar') as b:
        assert all(a.read(n)==b.read(n) for n in a.namelist() if n not in entries)
        # The generated record must be instruction-equivalent too; retain its original bytes.
        nested='forge/game/card/CardState$LandTraitChanges.class'
        if nested in entries:
            def nestedcode(cp):return re.sub(r'#\d+','#',subprocess.check_output([str(e.JAVA.with_name('javap.exe')),'-c','-p','-classpath',str(cp),'forge.game.card.CardState$LandTraitChanges'],text=True))
            assert nestedcode(BASE)==nestedcode(classes),'Unexpected nested-class change'
    tests=R/'test-classes';tests.mkdir()
    source=SOURCE/'forge-gui-desktop/src/test/java';cp=';'.join(map(str,[R/'optimized.jar',NG,H/'forge-gui-desktop/target/test-classes']))
    files=['forge/ai/AITest.java','forge/ai/BusyBoardBenchmark.java','forge/ai/EmptyTriggerRegressionTest.java','forge/ai/BlockCostSourceRegressionTest.java','forge/ai/BlockerTest.java','forge/ai/ThroneFixtures.java','forge/view/SimulationWorkerProbe.java','forge/view/SimulationGameResultsJsonlTest.java','forge/view/SimulationTurnMetricsTest.java']
    p=java([e.JAVA.with_name('javac.exe'),'--release','17','-cp',cp,'-d',tests,*[source/f for f in files],src/'BlockCostMetrics.java'],cwd,env)
    (R/'test-compile.log').write_text(p.stdout+p.stderr);assert p.returncode==0,'Tests failed to compile'
    write(R/'manifest.json',dict(baselineSHA256=EXPECTED,treatmentSHA256=sha(R/'optimized.jar'),sourceBaseline='418dac868f3f48349ff0b5d35e9197c65008d6e6',
        changedEntries=list(entries),sourceParityPassed=True,allOtherJarEntriesIdentical=True,flags=FLAGS,
        sourceHashes={str(p.relative_to(SOURCE)):sha(p) for p in [src/'CardState.java',src/'BlockCostMetrics.java',SOURCE/'forge-game/src/main/java/forge/game/combat/CombatUtil.java',source/'forge/ai/BusyBoardBenchmark.java',source/'forge/ai/EmptyTriggerRegressionTest.java']}))
    status('prepared',baselineSHA256=EXPECTED,treatmentSHA256=sha(R/'optimized.jar'))
def testng(label,mode,properties,classes,limit=600,jfr=False):
    d=R/label;d.mkdir();cwd,env,home,temp=core.runtime(d)
    jar=R/('baseline.jar' if mode=='baseline' else 'optimized.jar')
    flags=FLAGS.copy()
    if mode=='off':flags=[x for x in flags if not x.startswith('-Dforge.combat.blockCostFastPath=')]+['-Dforge.combat.blockCostFastPath=false']
    cp=';'.join(map(str,[R/'test-classes',jar,NG,JC]))
    cmd=[e.JAVA,*flags,*e.opts(home,temp),*properties]
    if jfr:cmd += [f'-XX:StartFlightRecording=filename={d / "profile.jfr"},settings=profile,maxsize=64m,dumponexit=true']
    cmd+=['-cp',cp,'org.testng.TestNG','-testclass',classes,'-d',d/'results']
    core.launch(d,cmd,cwd,env,limit)
    return d
def rows(d):
    return [json.loads(l[11:]) for l in (d/'execution.log').read_text(encoding='utf-8-sig').splitlines() if l.startswith('BUSY_BENCH ')]
def equal(a,b):
    left=rows(a);right=rows(b);assert len(left)==len(right)>0
    for x,y in zip(left,right):
        for key in ['case','path','rep','legal','assignments','state','afterDamage','result']:
            assert x.get(key)==y.get(key),f'Native rules/AI mismatch: {x["case"]}/{x["path"]}/{key}'
def initial():
    status('initial-correctness')
    regression='forge.ai.BlockCostSourceRegressionTest,forge.ai.EmptyTriggerRegressionTest,forge.ai.BlockerTest,forge.ai.ThroneFixtures,forge.view.SimulationGameResultsJsonlTest,forge.view.SimulationTurnMetricsTest'
    props=['-Dfixture.deck='+str(EX/'lathliss-r-throne-20261026/decks/Lathliss_R_Rebuild_V2_Throne.dck')]
    for mode in ['baseline','optimized']:
        existing=R/('initial-regressions-'+mode)/'execution.json'
        if existing.exists():assert json.loads(existing.read_text())['exitCode']==0
        else:testng('initial-regressions-'+mode,mode,props,regression)
    props=['-Dforge.bench.cases=large-four-player','-Dforge.bench.paths=block','-Dforge.bench.repeats=1','-Dforge.bench.verify=true']
    iteration=len(list(R.glob('initial-parity-baseline*')))+1
    a=testng('initial-parity-baseline-'+str(iteration),'baseline',props,'forge.ai.BusyBoardBenchmark')
    b=testng('initial-parity-optimized-'+str(iteration),'optimized',props,'forge.ai.BusyBoardBenchmark');equal(a,b)
    write(R/'initial-gate.json',dict(passed=True,legalPairsAssignmentsDamageAndStateEqual=True))
    status('initial-gate-passed')
def main():
    import msvcrt
    with (H/'work/commander-batches/worker.lock').open('r+b') as lock:
        lock.seek(0);msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1);core.admission(R,H)
        mode=sys.argv[2]
        if mode=='prepare':prepare()
        elif mode=='initial':initial()
        else:raise ValueError('Unknown stage')
if __name__=='__main__':
    try:main()
    except BaseException:
        write(R/('failure-'+sys.argv[2]+'.json'),dict(error=traceback.format_exc()));status('failed',stageArgument=sys.argv[2]);raise
