"""Finite Phase 7B native fixture supervisor. No full games, production flag or promotion."""
from pathlib import Path
import collections,hashlib,json,os,shutil,sys,threading,time,traceback
sys.dont_write_bytecode=True
import realgame_run as n
from rng_evidence import read,records,historical,audit_native
c=n.c;a=n.api;h=n.h;e=n.e;R=n.R;S=n.S
P7=a.EX/'rules-performance-v7-20261028/validation-01';P7A=a.EX/'rules-parity-v7a-20261028/validation-01'
RUN='20261028-native-rng-isolation-v7b';BRANCH='codex/commander-rng-isolation-v7b';FLAGS=a.flags('baseline');a.flags=lambda mode:FLAGS
DATA=dict(schemaVersion=1,runId=RUN,state='prepared',classification='C: fixtures pending',regressions={},nativeFixtures={},productionCorrectionImplemented=False,productionIsolationFlag=None,fasterJvmDisabled=True,readyForPromotion=False)
STOP=threading.Event();ACTIVE={}
def write(p,v):return c.write(p,v)
def head():return n.head()
def verify():
    q=read(R/'run-request.json');assert q['sourceHead']==head() and q['flags']==FLAGS
    for key,root in [('sourceHashes',S),('compiledHashes',R),('historicalHashes',P7A),('helperHashes',a.H)]:
        for path,digest in q[key].items():assert c.sha(root/path)==digest,'Frozen input changed: '+path
    assert c.sha(a.REFERENCE)==a.REFERENCE_SHA and c.sha(R/'baseline.jar')==a.REFERENCE_SHA
    assert c.sha(P7/'decks/Lathliss_R_Rebuild_V2_Throne.dck')==q['fixtureDeckSHA256']
    assert c.sha(R/'initial-gate.json')==q['initialGateSHA256'] and read(R/'initial-gate.json')['passed'];return q
h.verify=verify

def prepare():
    assert not (R/'baseline.jar').exists();R.mkdir(parents=True,exist_ok=True);h.admission(R,a.H)
    assert read(P7A/'completion.json')['passed'] and c.sha(a.REFERENCE)==a.REFERENCE_SHA
    shutil.copy2(a.REFERENCE,R/'baseline.jar');shutil.copytree(P7/'test-classes',R/'test-classes')
    src=S/'forge-gui-desktop/src/test/java/forge/ai/SpeculativeRngTest.java'
    p=a.java([e.JAVA.with_name('javac.exe'),'--release','17','-cp',';'.join(map(str,[R/'test-classes',a.REFERENCE,a.NG,a.JC])),'-d',R/'test-classes',src]);(R/'compile.log').write_text(p.stdout+p.stderr);assert p.returncode==0,p.stderr
    write(R/'historical-evidence.json',historical(P7A))
    for cls in ['forge.util.MyRandom','forge.ai.AiEvaluationDiagnostics','forge.ai.ComputerUtilCombat','forge.ai.AiBlockController','forge.ai.ComputerUtil','forge.ai.ComputerUtilCard','forge.ai.ability.CharmAi','forge.ai.ability.TokenAi','forge.ai.ability.DigAi','forge.ai.AiController']:
        out=h.command([e.JAVA.with_name('javap.exe'),'-c','-p','-classpath',a.REFERENCE,cls]);assert out.returncode==0;(R/(cls+'.bytecode.txt')).write_text(out.stdout,encoding='utf-8')
    print('Native actual-draw fixture compiled against frozen reference; historical evidence read only')

def testng(label,classes):
    ACTIVE.update(stage=label)
    props=['-Dforge.parity.testdir='+str(R/label),'-Dfixture.deck='+str(P7/'decks/Lathliss_R_Rebuild_V2_Throne.dck')]
    try:return a.testng(label,'baseline',props,classes,1200)
    finally:ACTIVE.clear()
def initial():
    d=testng('initial-native-rng','forge.ai.SpeculativeRngTest');assert h.xmlcounts(d)==dict(total=10,passed=10,failed=0,skipped=0)
    result=audit_native(d);write(R/'initial-gate.json',dict(passed=True,tests=10,classification=result['classification'],fixture=result))

def freeze():
    assert not (R/'run-request.json').exists() and read(R/'initial-gate.json')['passed']
    files=h.command(['git','-C',S,'ls-files']).stdout.splitlines();old=['checkpoint.json','completion.json','run-request.json']
    for label,_,observed in read(P7A/'run-request.json')['cases']:
        old += [f'{label}/game-573307/{name}' for name in ['execution.log','combat.jsonl','command.json','validation-passed.json']]
        if observed:old.append(f'{label}/game-573307/choice.jsonl')
    write(R/'run-request.json',dict(runId=RUN,sourceBranch=BRANCH,sourceHead=head(),engineSHA256=a.REFERENCE_SHA,functionalEngineSHA256=a.EXPECTED,flags=FLAGS,
        sourceHashes={p:c.sha(S/p) for p in files if p.startswith(('tools/ai-evaluation/performance/','tools/ai-evaluation/parallel-v4/')) or p.endswith('SpeculativeRngTest.java')},
        compiledHashes={p.relative_to(R).as_posix():c.sha(p) for p in (R/'test-classes').rglob('*.class')},historicalHashes={p:c.sha(P7A/p) for p in old},helperHashes=read(P7/'run-request.json')['helperHashes'],
        initialGateSHA256=c.sha(R/'initial-gate.json'),fixtureDeckSHA256=c.sha(P7/'decks/Lathliss_R_Rebuild_V2_Throne.dck'),maximumGameWorkers=1,freeSpaceReserveGiB=3,heapGiB=3,processors=2,
        plan='Initial focused 10 tests, two repeat fixture JVMs, existing 38 Java regressions, one native timeout/failure probe suite, legacy independent-validator regressions, full 64-fixture unchanged-engine structured comparison. Zero new full games.',
        productionStatus='No engine class changed, no RNG-isolation flag implemented, no new validation policy adopted. MyRandom.withRandom used only inside explicit test fixtures.',
        safetyLimitation='A production seed scheme, complete advisory-boundary audit, direct Random references and every worker handoff have not been proven safe; therefore no production correction or fair faster-JVM performance comparison is authorised.'))
    verify();print('Frozen finite Phase 7B fixture-only plan')

def aggregate():
    DATA['performanceHistory']=dict(phase7='experimental historical throughput, not revalidated',phase7a='completed separate diagnostics, unchanged',phase7b='no production change or throughput comparison',cumulativeGain=None,fasterJvmEnabled=False)

def report():
    return '# Phase 7B — RNG ownership and speculative evaluation\n\n'+f"State **{DATA['state']}**, PID {os.getpid()}, updated {n.now()}.\n\n"+ \
        '**Classification: '+DATA['classification']+'**. No Phase 8, production RNG flag, source merge or JVM promotion.\n\n'+ \
        '## Ownership and exact native mechanism\n\nSimulateMatch seeds the process-wide MyRandom provider with java.util.Random. MyRandom.getRandom returns a current-thread override when present; otherwise all threads share the global provider. MyRandom.withRandom restores the previous thread-local provider in finally, including exceptions, but does not propagate to other threads. setRandom replaces the global provider only outside a thread-local scope.\n\n'+ \
        'AiEvaluationDiagnostics.boundedPrediction manages advisory depth and catches its private budget exception; it does not install or restore an RNG. ComputerUtil.predictNextCombatsRemainingLife builds hypothetical combats and invokes AiBlockController. Its native ComputerUtilCombat.lifeInDanger performs nextInt(80) and profile-dependent nextInt(100) draws. AiBlockController also uses probabilistic trade heuristics. CharmAi shuffles modes and TokenAi uses probabilistic willingness; these later committed decisions normally use the same global stream. DigAi uses game-state/phase/library conditions rather than a dedicated RNG. Existing copied-action evaluation uses withRandom in selected paths, which does not cover every bounded prediction.\n\n'+ \
        '## Preserved Phase 7A evidence\n\n```json\n'+json.dumps(read(R/'historical-evidence.json'),indent=2)+'\n```\n\n'+ \
        'The recorded window begins from the same seed state. Baseline 0 advances that provider by 60 primitive LCG steps, candidate 0 by 92. This is an interval deduction, not actual-operation instrumentation or proof of exclusive thread ownership. The old provider records cannot locate every actual draw. The original first draw that caused Atsushi to differ remains unproven; Phase 7 and 7A are not reclassified.\n\n'+ \
        '## Focused actual-draw, budget and cancellation tests\n\n```json\n'+json.dumps(dict(initial=read(R/'initial-gate.json'),repeats=DATA['nativeFixtures'],regressions=DATA['regressions']),indent=2)+'\n```\n\n'+ \
        'The controlled fixture counts actual protected Random.next(bits) calls and records primitive ordinal, bits, value, operation, source site and speculative/committed role. Recorder output is bounded to the first 128 primitive draws plus the next committed draw. Its sequence is checked against java.util.Random over controlled seeds. No random identifiers are used. Fixtures hold their small fully constructed combat context fixed and assert unchanged zones/characteristics/life and combat inputs. This is not a replay of the original hidden game state.\n\n'+ \
        '## Isolation investigation and behavioural limits\n\n'+DATA.get('recommendation','Fixture investigation pending.')+'\n\n'+ \
        'The only isolation prototype is the existing withRandom API used with an explicit private fixture seed, including explicit installation inside a worker. No engine class, production flag, AI heuristic, rules distribution or 500 ms / 2,000-work budget changes. Direct Random references captured before entering a private scope could bypass it; worker context does not inherit automatically. A complete production boundary audit and stable decision-identity seed design remain prerequisites. Restoring RNG state must never be done by rewinding a global stream shared with other threads.\n\n'+ \
        'Controlled native mode distributions are reported rather than asserted equal to historical global-stream choices. No revised parity policy is adopted. The unchanged-engine structured comparator remains exact with its existing simultaneous-damage exception; an isolation-driven future AI change requires a separate written validation justification.\n\n'+ \
        '## Performance and remaining uncertainty\n\nFixture time includes draw tracing and intentional wall-budget waits; it is not production overhead. No fresh full-game throughput or faster-JVM comparison is run, and the historical 12.8% result is not treated as newly validated. Global Random atomic updates can interleave across threads; a seed fixes a stream, not which subsystem consumes each draw. Synchronous completed/aborted predictions retain already consumed global draws. Cancellation tests await termination before asserting no subsequent draws; Future.cancel alone is never proof.\n\n'+ \
        ('Stopped reason:\n```\n'+DATA['failure']+'\n```\n\n' if DATA.get('failure') else '')+ \
        '[Measurements](measurements.json) · [Manifest](manifest.json) · [History](performance-history.json) · [Source](https://github.com/cwppt/forge/tree/'+BRANCH+'). Full hidden-zone evidence remains in the immutable Phase 7A local directory; this report publishes only RNG/caller summaries and constructed fixture state. Evidence root `'+str(R)+'`. Source '+read(R/'run-request.json')['sourceHead']+'.\n'

n.DATA=DATA;n.RUN=RUN;n.DEST='forge-performance/reports/'+RUN;n.URL='https://github.com/cwppt/MTG-deck-lists/tree/main/'+n.DEST;n.aggregate=aggregate;n.report=report;n.ACTIVE=ACTIVE
publish=n.publish

def integrity():
    d=R/'integrity-native';d.mkdir();cwd,env,home,temp=c.runtime(d)
    cp=';'.join(map(str,[R/'test-classes',R/'baseline.jar',a.NG,a.JC]));config=dict(java=str(e.JAVA),opts=[*FLAGS,*e.opts(home,temp)],classpath=cp,cwd=str(cwd),env=env,output=str(R/'worker-probes'))
    write(d/'worker-config.json',config);env.update(FORGE_EXPERIMENTS=str(a.EX),FORGE_WORKER_TEST_CONFIG=str(d/'worker-config.json'))
    c.launch(d,[Path(sys.executable).with_name('python.exe'),'-B','-m','unittest','test_integrity'],a.HERE,env,600);DATA['regressions']['nativeFailurePaths']=dict(passed=True,methods=7)
    d=R/'legacy-validator';d.mkdir();c.launch(d,[Path(sys.executable).with_name('python.exe'),'-B','-m','unittest','test_valid_commander_cohort'],S/'tools/ai-evaluation/parallel-v4',h.ENV,120);DATA['regressions']['independentValidator']=dict(passed=True,methods=18)

def execute():
    DATA['classification']='A — Shared RNG coupling proven in a controlled native speculative-combat fixture; exact original Atsushi draw origin still unresolved.'
    DATA['recommendation']='Retain the validated engine and keep the faster JVM disabled. Explicit fixture-local RNG isolation preserves the next gameplay draw across tested depths/aborts, but production ownership and seeding safety are incomplete. Do not introduce a production flag until both advisory call boundaries and worker/direct-Random ownership are audited. No new performance comparison or validation-policy adoption.'
    publish('running-bounded-native-fixture-verification')
    d=R/'offline-tests';d.mkdir();c.launch(d,[Path(sys.executable).with_name('python.exe'),'-B','-m','unittest','test_rng_evidence','test_structured_parity'],a.HERE,h.ENV,120);DATA['regressions']['analysisAndComparator']=dict(passed=True,methods=12)
    for i in range(2):
        d=testng('repeat-native-rng-'+str(i),'forge.ai.SpeculativeRngTest');assert h.xmlcounts(d)==dict(total=10,passed=10,failed=0,skipped=0)
        result=audit_native(d);initial=read(R/'initial-gate.json')['fixture']
        for key in ['smallGlobalSpeculativeDraws','largeGlobalSpeculativeDraws','nextCommittedDrawSmall','nextCommittedDrawLarge','privateFixtureCommittedDraw','nativeModeDistributions']:
            assert result[key]==initial[key],'Fixed-configuration fixture repeat changed: '+key
        DATA['nativeFixtures'][str(i)]=result;publish('native-fixture-repeat-'+str(i+1)+'-passed')
    d=testng('java-regressions',a.REGRESSION+',forge.ai.CardPropertyRulesTest');assert h.xmlcounts(d)==dict(total=38,passed=38,failed=0,skipped=0);DATA['regressions']['javaMethods']=38;publish('java-regressions-passed')
    integrity();publish('integrity-and-validator-passed')
    props=['-Dforge.bench.verify=true','-Dforge.bench.repeats=1'];d=a.testng('unchanged-full-parity','baseline',props,'forge.ai.BusyBoardBenchmark',1200);a.equal(P7/'full-parity-baseline',d)
    DATA['regressions']['unchanged64FixtureStructuredParity']=True;publish('completed');write(R/'completion.json',dict(passed=True,classification='A',productionCorrectionImplemented=False,fullGamesRun=0,pid=os.getpid(),utc=n.now(),reportUrl=n.URL))

def run():
    import msvcrt
    with (R/'supervisor.lock').open('a+b') as own:
        own.write(b'0');own.flush();own.seek(0);msvcrt.locking(own.fileno(),msvcrt.LK_NBLCK,1)
        with (a.H/'work/commander-batches/worker.lock').open('r+b') as worker:
            worker.seek(0);msvcrt.locking(worker.fileno(),msvcrt.LK_NBLCK,1);verify();h.admission(R,a.H)
            with (R/'started.json').open('x') as f:json.dump(dict(pid=os.getpid(),utc=n.now()),f)
            n.STOP=STOP;threading.Thread(target=n.heartbeat,daemon=True).start()
            try:execute()
            except BaseException:
                DATA['failure']=traceback.format_exc();write(R/'failure-run.json',dict(error=DATA['failure'],utc=n.now()))
                try:publish('failed-preserved')
                except Exception:write(R/'publication-failure.json',dict(error=traceback.format_exc()))
                raise
            finally:STOP.set();write(R/'heartbeat.json',dict(state='finished',pid=os.getpid(),utc=n.now()))

if __name__=='__main__':
    if '--prepare' in sys.argv or '--initial' in sys.argv:
        import msvcrt
        with (a.H/'work/commander-batches/worker.lock').open('r+b') as lock:
            lock.seek(0);msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1)
            prepare() if '--prepare' in sys.argv else initial()
    elif '--freeze' in sys.argv:freeze()
    elif '--verify-only' in sys.argv:verify();print('Phase 7B frozen inputs verified')
    else:run()
