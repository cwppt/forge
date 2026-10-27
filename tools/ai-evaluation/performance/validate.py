"""Finite detached native validation. Uses the existing isolation helper and global worker lock."""
from pathlib import Path
import ctypes, datetime, hashlib, importlib.util, json, os, shutil, subprocess, sys, time, traceback, zipfile
sys.dont_write_bytecode=True
SOURCE=Path(__file__).resolve().parents[3]
R=Path(sys.argv[1]).resolve()
H=Path('C:/Users/Chris/Workspace/forge-commander-harness')
EX=H.parent/'forge-experiments'
sys.path.insert(0,str(H/'work/lathliss-v27-20261017'))
import experiment as e
sys.path.insert(0,str(Path(__file__).resolve().parent))
from report_integrity import correct
from recovery import plan
FLAGS=['-Dforge.ai.manaFeasibilityBudgetFix=false','-Dforge.ai.virtualBlockerBudgetFix=true','-Dforge.ai.reunionReanimate=true','-Dforge.ai.feldonGraveyardFix=true','-Dforge.ai.rivazTutor1=true','-Dforge.ai.rivazFixes=true','-Dforge.ai.fourCardSupport=true','-Dforge.ai.swashBalanced=true','-Dforge.ai.swashTreasurePolicy=false','-Dforge.ai.evalBudgetMs=500','-Dforge.ai.evalWorkLimit=2000','-Dforge.ai.evalDiagnostics=true','-Dforge.telemetry.resources=true']
def write(p,obj):
    tmp=p.with_suffix(p.suffix+'.tmp');tmp.write_text(json.dumps(obj,indent=2)+'\n',encoding='utf-8');tmp.replace(p)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def status(stage,**extra):write(R/'status.json',dict(stage=stage,pid=os.getpid(),utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),**extra))
def admission(*paths):
    for p in paths:
        # Check both the lexical path and resolved junction target volume.
        for target in {Path(p).absolute(),Path(p).resolve()}:
            assert shutil.disk_usage(target).free>=3*1024**3,'3 GiB disk reserve: '+str(target)
def runtime(d):
    cwd,env,home,temp=e.runtime(d)
    for key in list(env):
        if any(x in key.upper() for x in ['API_KEY','AUTH_TOKEN','ACCESS_TOKEN','OLLAMA','OPENAI','ANTHROPIC','CODEX']):env.pop(key,None)
    return cwd,env,home,temp
def launch(d,command,cwd,env,limit=330):
    admission(R,d);code=e.launch(d,list(map(str,command)),cwd,env,limit=limit)
    if code:raise RuntimeError(f'Child failed ({code}); evidence: {d.name}')
def smoke():
    status('native-full-game-smoke')
    base=R/'baseline.jar';patched=R/'correctness.jar'
    manifest=json.loads((R/'engine-manifest.json').read_text())
    assert sha(base)==manifest['baselineSHA256'] and sha(patched)==manifest['correctnessSHA256'],'Engine changed; refuse recovery'
    decks=R/'decks';frozen=EX/'lathliss-r-throne-20261026/decks'
    if not decks.exists():shutil.copytree(frozen,decks)
    q=json.loads((EX/'lathliss-r-throne-20261026/request.json').read_text())
    for name,h in q['deckHashes'].items():assert sha(decks/name)==h,'Frozen deck changed'
    spec=importlib.util.spec_from_file_location('classifier',SOURCE/'tools/ai-evaluation/parallel-v4/Run-ValidCommanderCohort.py');classifier=importlib.util.module_from_spec(spec);spec.loader.exec_module(classifier)
    for label,jar in [('baseline',base),('correctness',patched),('profile',patched)]:
        for seed in ([571900] if label=='profile' else [571900,571901]):
            group=R/f'smoke-{label}-{seed}';group.mkdir(exist_ok=True)
            attempts=[]
            for prior in sorted(group.glob('attempt-*')):
                terminal=prior/'execution.json'
                if not terminal.exists():raise RuntimeError('Prior worker ownership unresolved; preserve attempt and refuse replay: '+str(prior))
                code=json.loads(terminal.read_text())['exitCode']
                rows=classifier.classify_batch(prior,seed,1,False,code,True)
                assert len(rows)==1,'Ambiguous prior terminal evidence'
                row=rows[0]
                proofPath=prior/'validation-passed.json'
                if row['valid']:
                    assert proofPath.exists(),'Completed game needs independent revalidation; do not replay it'
                    proof=json.loads(proofPath.read_text())
                    assert proof.get('passed') is True and proof['resultSHA256']==sha(prior/f'commander-seed{seed}.games.jsonl') and proof['commandSHA256']==sha(prior/'command.json'),'Completed evidence changed; refuse replay'
                attempts.append(dict(variant=label,runSeed=seed,attemptIndex=int(prior.name.split('-')[1]),valid=row['valid'],
                    independentlyValidated=row['valid'] and (prior/'validation-passed.json').exists(),
                    simulationStatus=row['simulationStatus'],ownedWorkerAbsent=code is not None))
            pending=plan([(label,seed)],attempts)
            if not pending:continue
            d=group/f'attempt-{pending[0]["attemptIndex"]:02d}';d.mkdir();wc,ev,ho,te=runtime(d);prefix=d/f'commander-seed{seed}'
            extra=['-Dforge.simulation.profile=true'] if label=='profile' else []
            cmd=[e.JAVA,*FLAGS,*extra,*e.opts(ho,te),'-jar',jar,'sim','-d','Lathliss_R_Rebuild_V2_Throne.dck',*q['pod'],'-D',decks,'-n','1','-s',str(seed),'-f','Commander','-c','900','-q','--capture-simulation-failures']
            for opt,suffix in [('metrics-csv','metrics.csv'),('decision-audit-jsonl','decisions.jsonl'),('game-results-jsonl','games.jsonl'),('game-log-jsonl','gamelog.jsonl'),('commander-role-jsonl','roles.jsonl')]:cmd+=['--'+opt,str(prefix)+'.'+suffix]
            launch(d,cmd,wc,ev,990);shutil.copy2(d/'execution.log',Path(str(prefix)+'.log'))
            rows=classifier.classify_batch(d,seed,1,False,0,True);write(d/'classification.json',rows)
            assert len(rows)==1 and rows[0]['valid'],f'Independent classifier rejected {d.name}'
            v=d/'independent-validator';v.mkdir()
            launch(v,[Path(sys.executable).with_name('python.exe'),'-B',SOURCE/'tools/ai-evaluation/parallel-v4/Validate-CommanderTelemetry.py',d,'--games-per-seed','1','--expected-seeds','1','--require-clean-runtime','--report',d/'validation.json'],wc,ev)
            write(d/'validation-passed.json',dict(passed=True,resultSHA256=sha(Path(str(prefix)+'.games.jsonl')),commandSHA256=sha(d/'command.json')))

def main():
    import msvcrt
    lock=H/'work/commander-batches/worker.lock'
    assert lock.exists(),'Established global worker lock missing'
    with lock.open('r+b') as stream:
        until=time.monotonic()+6*3600
        while True:
            stream.seek(0)
            try:msvcrt.locking(stream.fileno(),msvcrt.LK_NBLCK,1);break
            except OSError:
                if time.monotonic()>until:raise RuntimeError('Established global worker lock unavailable for six hours')
                status('queued-behind-existing-worker');time.sleep(15)
        admission(R,H)
        # Existing admission policy: one 3 GiB JVM plus 4 GiB free-memory reserve.
        class Mem(ctypes.Structure):
            _fields_=[('length',ctypes.c_ulong),('load',ctypes.c_ulong)]+[(s,ctypes.c_ulonglong) for s in ['totalPhys','availPhys','totalPage','availPage','totalVirtual','availVirtual','availExtended']]
        m=Mem();m.length=ctypes.sizeof(m);assert ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m))
        assert m.availPhys>=7*1024**3,'Insufficient memory for one safely admitted worker'
        if '--resume-smoke' in sys.argv:
            assert json.loads((R/'correctness-gate.json').read_text())['passed'],'Correctness gate required'
            for label in ['baseline','correctness']:
                assert json.loads((R/('busy-'+label)/'execution.json').read_text())['exitCode']==0,'Benchmark gate required'
            smoke();status('validation-completed',correctnessGatePassed=True,fullGameSmokeGames=5,performanceImprovementClaimed=False)
            write(R/'completion.json',dict(passed=True,pid=os.getpid(),correctnessGatePassed=True,fullGameSmokeGames=5,resumed=True));return
        status('building')
        base=R/'baseline.jar';original=EX/'klauth-k2-k4-20261025/telemetry-engine/engine.jar'
        assert sha(original)=='2d82615d9310c0f2fb6f97736881ae2f05f7d958e03536c00a723ae05aed7ac6'
        shutil.copy2(original,base)
        prod=R/'engine-classes';tests=R/'test-classes';prod.mkdir();tests.mkdir()
        setup=R/'setup';setup.mkdir();cwd,env,home,temp=runtime(setup)
        ng=Path('C:/Users/Chris/.m2/repository/org/testng/testng/7.10.2/testng-7.10.2.jar')
        jc=Path('C:/Users/Chris/.m2/repository/com/beust/jcommander/1.82/jcommander-1.82.jar')
        src=SOURCE/'forge-gui-desktop/src'
        c=R/'compile-engine';c.mkdir()
        launch(c,[e.JAVA.with_name('javac.exe'),'--release','17','-cp',base,'-d',prod,
                  *[src/'main/java/forge/view'/name for name in ['TimeLimitedCodeBlock.java','SimulationWorker.java','SimulateMatch.java','SimulationTurnMetrics.java']]],cwd,env)
        patched=R/'correctness.jar';entries={p.relative_to(prod).as_posix():p.read_bytes() for p in prod.rglob('*.class')}
        with zipfile.ZipFile(base) as z,zipfile.ZipFile(patched,'w',zipfile.ZIP_DEFLATED) as w:
            for info in z.infolist():w.writestr(info,entries.get(info.filename,z.read(info)))
            for name,data in entries.items():
                if name not in z.namelist():w.writestr(name,data)
        with zipfile.ZipFile(base) as a,zipfile.ZipFile(patched) as b:
            assert all(a.read(n)==b.read(n) for n in a.namelist() if n not in entries)
        write(R/'engine-manifest.json',dict(baselineSHA256=sha(base),correctnessSHA256=sha(patched),changedEntries=list(entries),allOtherEntriesIdentical=True,nativeAiRulesClassesUnchanged=True))
        c=R/'compile-tests';c.mkdir();cp=';'.join(map(str,[patched,ng,H/'forge-gui-desktop/target/test-classes']))
        launch(c,[e.JAVA.with_name('javac.exe'),'--release','17','-cp',cp,'-d',tests,
            *[src/'test/java'/p for p in ['forge/ai/AITest.java','forge/view/SimulationWorkerProbe.java','forge/view/SimulationGameResultsJsonlTest.java','forge/view/SimulationTurnMetricsTest.java','forge/ai/BusyBoardBenchmark.java','forge/ai/ThroneFixtures.java','forge/ai/BlockerTest.java']]],cwd,env)
        cp=';'.join(map(str,[tests,patched,ng,jc]))
        config=dict(java=str(e.JAVA),opts=e.opts(home,temp),classpath=cp,cwd=str(cwd),env=env,output=str(R/'worker-probes'))
        write(R/'worker-test-config.json',config)
        childenv=env.copy();childenv.update(FORGE_EXPERIMENTS=str(EX),FORGE_WORKER_TEST_CONFIG=str(R/'worker-test-config.json'))
        status('correctness-tests')
        d=R/'python-correctness';d.mkdir()
        launch(d,[sys.executable,'-B','-m','unittest','test_integrity'],Path(__file__).parent,childenv,600)
        d=R/'java-correctness';d.mkdir()
        launch(d,[e.JAVA,*FLAGS,*e.opts(home,temp),'-Dfixture.deck='+str(EX/'lathliss-r-throne-20261026/decks/Lathliss_R_Rebuild_V2_Throne.dck'),'-cp',cp,'org.testng.TestNG','-testclass','forge.view.SimulationGameResultsJsonlTest,forge.view.SimulationTurnMetricsTest,forge.ai.ThroneFixtures,forge.ai.BlockerTest','-d',d/'results'],cwd,env)
        d=R/'legacy-validator-tests';d.mkdir()
        launch(d,[sys.executable,'-B','-m','unittest','test_valid_commander_cohort'],SOURCE/'tools/ai-evaluation/parallel-v4',env)
        for name in ['lathliss-r-rebuild-ab-20261026','lathliss-r-throne-20261026']:
            correct(EX/name,R/(name+'-correction'))
        write(R/'correctness-gate.json',dict(passed=True,workerProcessModes=7,reportGamesIndependentlyChecked=300))
        status('busy-board-baseline')
        for label,jar in [('baseline',base),('correctness',patched)]:
            d=R/('busy-'+label);d.mkdir();wc,ev,ho,te=runtime(d)
            bcp=';'.join(map(str,[tests,jar,ng,jc]));recording=d/'profile.jfr'
            launch(d,[e.JAVA,*FLAGS,*e.opts(ho,te),f'-XX:StartFlightRecording=filename={recording},settings=profile,maxsize=64m,dumponexit=true',
                '-cp',bcp,'org.testng.TestNG','-testclass','forge.ai.BusyBoardBenchmark','-d',d/'results'],wc,ev,900)
        smoke()
        status('validation-completed',correctnessGatePassed=True,fullGameSmokeGames=5,performanceImprovementClaimed=False)
        write(R/'completion.json',dict(passed=True,pid=os.getpid(),correctnessGatePassed=True,performanceOptimisation='UNRESOLVED: no computational changes in this treatment',fullGameSmokeGames=5))

if __name__=='__main__':
    try:main()
    except BaseException:
        write(R/'failure.json',dict(error=traceback.format_exc(),pid=os.getpid()))
        status('failed',evidence='failure.json')
        raise
