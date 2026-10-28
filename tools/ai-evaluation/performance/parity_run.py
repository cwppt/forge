"""Finite combat-parity follow-up. No benchmark entry point; frozen engines never change."""
from pathlib import Path
import base64,collections,copy,hashlib,importlib.util,json,msvcrt,os,shutil,subprocess,sys,threading,traceback,zipfile
sys.dont_write_bytecode=True
import cost_run
from structured_parity import compare
a=cost_run.api;c=a.core;e=a.e;host=cost_run.runner;R=a.R;S=a.SOURCE
OLD=Path('D:/ForgeData/forge-experiments/commander-cost-performance-v2-20261027/validation-03')
CLASSES=['forge.game.GameAction','forge.game.trigger.TriggerHandler','forge.game.zone.MagicStack','forge.ai.decision.CommanderRoleTelemetry']
FILES=[('forge-ai' if '.ai.' in x else 'forge-game')+'/src/main/java/'+x.replace('.','/')+'.java' for x in CLASSES]
NEW='forge-game/src/main/java/forge/game/CombatParityTrace.java'
STATUS=dict(state='prepared',baselineRepeatability=[],structured=[],remainingSeed=[],rulesParity=False,readyForPromotion=False)
ACTIVE={};STOP=threading.Event()
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(p):return c.sha(p)
def write(p,x):return c.write(p,x)
def rows(p):return [json.loads(x) for x in p.read_text(encoding='utf-8-sig').splitlines()]
def gh(path,method='GET',payload=None):return host.gh(path,method,payload)
def checked(cmd,cwd=S):
    p=subprocess.run(list(map(str,cmd)),cwd=cwd,capture_output=True,text=True,encoding='utf-8',creationflags=subprocess.CREATE_NO_WINDOW)
    if p.returncode:raise RuntimeError(p.stderr[-3000:]+p.stdout[-1000:])
    return p.stdout
def prepare():
    R.mkdir(exist_ok=False);q=read(OLD/'run-request.json')
    for mode,field in [('baseline','baselineSHA256'),('optimized','treatmentSHA256')]:assert sha(OLD/(mode+'.jar'))==q[field]
    controls=R/'control-source';controls.mkdir();out=R/'control-classes';out.mkdir()
    source=[]
    for rel in FILES:
        target=controls/Path(rel).name;target.write_text(checked(['git','show','HEAD:'+rel]),encoding='utf-8');source.append(target)
    checked([e.JAVA.with_name('javac.exe'),'--release','17','-cp',OLD/'baseline.jar','-d',out,*source])
    parity={}
    for p in out.rglob('*.class'):
        klass=p.relative_to(out).with_suffix('').as_posix().replace('/','.')
        parity[klass]=a.bytecode(OLD/'baseline.jar',klass)==a.bytecode(out,klass)
    assert parity and all(parity.values()),'Control source differs from frozen engine'
    write(R/'control-bytecode-parity.json',parity)
    classes=R/'observer-classes';classes.mkdir()
    checked([e.JAVA.with_name('javac.exe'),'--release','17','-cp',OLD/'baseline.jar','-d',classes,*[S/f for f in FILES+[NEW]]])
    entries={p.relative_to(classes).as_posix():p.read_bytes() for p in classes.rglob('*.class')}
    for mode in ['baseline','optimized']:
        with zipfile.ZipFile(OLD/(mode+'.jar')) as z,zipfile.ZipFile(R/('observed-'+mode+'.jar'),'w',zipfile.ZIP_DEFLATED) as dest:
            names=z.namelist()
            for info in z.infolist():dest.writestr(info,entries.get(info.filename,z.read(info)))
            for n,b in entries.items():
                if n not in names:dest.writestr(n,b)
        with zipfile.ZipFile(OLD/(mode+'.jar')) as x,zipfile.ZipFile(R/('observed-'+mode+'.jar')) as y:
            assert all(x.read(n)==y.read(n) for n in x.namelist() if n not in entries)
    shutil.copytree(OLD/'test-classes',R/'test-classes')
    cp=';'.join(map(str,[R/'observed-baseline.jar',R/'test-classes',a.NG,a.JC]))
    checked([e.JAVA.with_name('javac.exe'),'--release','17','-cp',cp,'-d',R/'test-classes',S/'forge-gui-desktop/src/test/java/forge/ai/CombatParityTraceTest.java'])
    shutil.copytree(OLD/'decks',R/'decks')
    for name,h in q['deckHashes'].items():assert sha(R/'decks'/name)==h
    base=gh('repos/'+host.REPO+'/contents/'+host.DEST+'/REPORT.md');assert base.returncode==0,base.stderr
    blob=json.loads(base.stdout);(R/'report-base.md').write_bytes(base64.b64decode(blob['content']))
    write(R/'ownership.json',{'REPORT.md':blob['sha']})
    write(R/'engines.json',dict(originalBaselineSHA256=q['baselineSHA256'],originalCandidateSHA256=q['treatmentSHA256'],
        observedBaselineSHA256=sha(R/'observed-baseline.jar'),observedCandidateSHA256=sha(R/'observed-optimized.jar'),
        observerEntries=list(entries),allOtherEntriesIdentical=True,controlBytecodeParity=parity,deckHashes=q['deckHashes']))
    print('Prepared diagnostic derivatives; original engines unchanged')
def freeze():
    assert not (R/'request.json').exists()
    files=FILES+[NEW,'forge-gui-desktop/src/test/java/forge/ai/CombatParityTraceTest.java']+['tools/ai-evaluation/performance/'+n for n in ['parity_run.py','structured_parity.py','test_structured_parity.py']]
    write(R/'request.json',dict(sourceHead=checked(['git','rev-parse','HEAD']).strip(),sourceHashes={f:sha(S/f) for f in files},
        priorRequestSHA256=sha(OLD/'run-request.json'),priorCheckpointSHA256=sha(OLD/'checkpoint.json'),engines=read(R/'engines.json'),
        helperHashes={f:sha(S/f) for f in ['tools/ai-evaluation/performance/cost_performance.py','tools/ai-evaluation/performance/combat_run.py','tools/ai-evaluation/performance/validate.py']},
        oldRuntimeHashes=read(OLD/'run-request.json')['helperHashes'],
        testsSHA256={p.relative_to(R).as_posix():sha(p) for p in (R/'test-classes').rglob('*.class')}))
def verify():
    q=read(R/'request.json');assert sha(OLD/'run-request.json')==q['priorRequestSHA256']
    assert sha(OLD/'checkpoint.json')==q['priorCheckpointSHA256'],'Original experiment checkpoint changed'
    for f,h in dict(q['sourceHashes'],**q['helperHashes']).items():assert sha(S/f)==h,'Source changed: '+f
    for f,h in q['testsSHA256'].items():assert sha(R/f)==h
    for f,h in q['oldRuntimeHashes'].items():assert sha(a.H/f)==h
    engines=q['engines']
    for p,field in [(OLD/'baseline.jar','originalBaselineSHA256'),(OLD/'optimized.jar','originalCandidateSHA256'),(R/'observed-baseline.jar','observedBaselineSHA256'),(R/'observed-optimized.jar','observedCandidateSHA256')]:assert sha(p)==engines[field]
    for name,h in engines['deckHashes'].items():assert sha(R/'decks'/name)==h
def heartbeat():
    while not STOP.wait(5):write(R/'heartbeat.json',dict(pid=os.getpid(),utc=host.now(),stage=STATUS['state'],active=ACTIVE))
def checkpoint(stage):
    STATUS['state']=stage;STATUS['utc']=host.now();write(R/'checkpoint.json',STATUS)
    payload=copy.deepcopy(STATUS);payload['engines']=read(R/'engines.json');payload['sourceHead']=read(R/'request.json')['sourceHead']
    write(R/'combat-parity.json',payload)
    text='\n\n## Combat parity follow-up\n\nState: **'+stage+'**. '+STATUS.get('conclusion','Bounded repeatability and structured verification are pending. No benchmark operations are repeated.')+'\n\n'
    text+='Original engines remain frozen. Diagnostic derivatives overlay only the observer and emission hooks; their source controls matched original engine bytecode. These games are correctness tests, not new performance measurements. Raw traces retain every event in native order. The comparator treats only consecutive damage deliveries inside an explicit simultaneous combat batch as a multiset. Requested/replaced damage maps, assignments and blocker order, trigger creation, stack/resolution, zone transitions and boundary states remain ordered/exact. Noncombat damage remains ordered. No global event sorting.\n\n'
    text+='```json\n'+json.dumps(STATUS,indent=2)+'\n```\n\n'
    text+='The preserved seed-572101 display records pass through HashMultimap in GameEventAttackersDeclared/GameEventBlockersDeclared. GameAction delivers simultaneous damage by iterating CardDamageTable (HashBasedTable). Presentation order is not evidence of a selected-assignment change; trigger or resulting-state order changes remain failures. Full trace and mismatch evidence is retained locally; telemetry captures battlefield counters, damage, control, type, power/toughness, life, zone events, trigger contexts and stack lifecycle. Unexpanded context types, if any, are disclosed in the follow-up JSON and limit conclusions.\n\n'
    text+='No merge or promotion. Recommend retaining the optimisation disabled until all bounded gates pass and reviewing the modest prior speed signal separately. Next performance target: blocker candidate enumeration/combat-prediction complexity, using counts and profiles; avoid further cost-adjustment changes without stronger evidence.\n'
    (R/'REPORT.md').write_text((R/'report-base.md').read_text(encoding='utf-8')+text,encoding='utf-8')
    owned=read(R/'ownership.json')
    for name in ['combat-parity.json','REPORT.md']:
        endpoint='repos/'+host.REPO+'/contents/'+host.DEST+'/'+name;prior=gh(endpoint)
        body=dict(message='Combat parity verification: '+stage,branch='main',content=base64.b64encode((R/name).read_bytes()).decode())
        if prior.returncode==0:
            remote=json.loads(prior.stdout);assert owned.get(name)==remote['sha'],'Report modified externally';body['sha']=remote['sha']
        else:assert name not in owned and '404' in prior.stderr,'Publication preflight failed'
        result=gh(endpoint,'PUT',body);assert result.returncode==0,result.stderr
        owned[name]=json.loads(result.stdout)['content']['sha'];write(R/'ownership.json',owned)
    write(R/'publication.json',dict(utc=host.now(),state=stage,url=host.URL,files=owned))
def launch(label,jar,seed=572101,observed=False):
    verify();host.admission(R,a.H);d=R/label;d.mkdir();cwd,env,home,temp=c.runtime(d);prefix=d/f'commander-seed{seed}'
    q=read(a.EX/'lathliss-r-throne-20261026/request.json');assert q['deckHashes']==read(R/'engines.json')['deckHashes']
    flags=a.FLAGS+(['-Dforge.parity.trace='+str(d/'combat.jsonl')] if observed else [])
    cmd=[e.JAVA,*flags,*e.opts(home,temp),'-Dforge.simulation.profile=true','-jar',jar,'sim','-d','Lathliss_R_Rebuild_V2_Throne.dck',*q['pod'],'-D',R/'decks','-n','1','-s',str(seed),'-f','Commander','-c','900','-q','--capture-simulation-failures','--external-ai-mulligan-disabled']
    for opt,suffix in [('metrics-csv','metrics.csv'),('decision-audit-jsonl','decisions.jsonl'),('game-results-jsonl','games.jsonl'),('game-log-jsonl','gamelog.jsonl'),('commander-role-jsonl','roles.jsonl')]:cmd+=['--'+opt,str(prefix)+'.'+suffix]
    ACTIVE.update(label=label)
    try:c.launch(d,cmd,cwd,env,990)
    finally:ACTIVE.clear()
    shutil.copy2(d/'execution.log',Path(str(prefix)+'.log'))
    v=d/'independent-validator';v.mkdir();c.launch(v,[Path(sys.executable).with_name('python.exe'),'-B',S/'tools/ai-evaluation/parallel-v4/Validate-CommanderTelemetry.py',d,'--games-per-seed','1','--expected-seeds','1','--require-clean-runtime','--report',d/'validation.json'],S,env)
    game=read(Path(str(prefix)+'.games.jsonl'));assert game['valid'] and game['simulationStatus']=='VALID' and not game['engineFailures'] and not game['timedOut']
    write(d/'validated.json',dict(passed=True,resultSHA256=sha(Path(str(prefix)+'.games.jsonl')),traceSHA256=sha(d/'combat.jsonl') if observed else None))
    return d,game
def role(d,seed=572101):return [{k:v for k,v in x.items() if k!='eventIndex'} for x in rows(d/f'commander-seed{seed}.roles.jsonl') if x['type']!='CANDIDATE']
def log(d,seed=572101):return [{k:v for k,v in x.items() if k!='eventIndex'} for x in rows(d/f'commander-seed{seed}.gamelog.jsonl') if not (x['type']=='GAME_OUTCOME' and x['message'].startswith('Match Duration '))]
def outcome(g):return {k:v for k,v in g.items() if k not in ['durationMs','candidateExposure']}
def variation(left,right):
    positions=[i for i,(x,y) in enumerate(zip(left,right)) if x!=y]
    return dict(rawIdentical=left==right,baselineRecords=len(left),repeatRecords=len(right),differentPositions=positions[:40],
        differingTypes=dict(collections.Counter(x['type'] for i,x in enumerate(left) if i in positions)),
        entireRecordMultisetEqual=collections.Counter(map(host.json.dumps,left))==collections.Counter(map(host.json.dumps,right)))
def regressions():
    d=R/'comparator-tests';d.mkdir();c.launch(d,[Path(sys.executable).with_name('python.exe'),'-B','-m','unittest','test_structured_parity'],a.HERE,os.environ.copy())
    regression='forge.ai.CombatParityTraceTest,forge.ai.CostSourceRegressionTest,forge.ai.BlockCostSourceRegressionTest,forge.ai.EmptyTriggerRegressionTest,forge.ai.BlockerTest,forge.ai.ThroneFixtures,forge.view.SimulationGameResultsJsonlTest,forge.view.SimulationTurnMetricsTest'
    for mode in ['baseline','optimized']:
        verify();host.admission(R,a.H);d=R/('regressions-'+mode);d.mkdir();cwd,env,home,temp=c.runtime(d)
        cp=';'.join(map(str,[R/'test-classes',R/('observed-'+mode+'.jar'),a.NG,a.JC]))
        c.launch(d,[e.JAVA,*a.FLAGS,*e.opts(home,temp),'-Dforge.parity.testdir='+str(d),'-Dfixture.deck='+str(R/'decks/Lathliss_R_Rebuild_V2_Throne.dck'),'-cp',cp,'org.testng.TestNG','-testclass',regression,'-d',d/'results'],cwd,env,600)
        counts=host.xmlcounts(d);assert counts==dict(total=35,passed=35,failed=0,skipped=0);STATUS.setdefault('regressions',{})[mode]=counts
        t=R/('integrity-'+mode);t.mkdir();wc,ev,ho,te=c.runtime(t)
        config=dict(java=str(e.JAVA),opts=[*a.FLAGS,*e.opts(ho,te)],classpath=cp,cwd=str(wc),env=ev,output=str(R/('worker-probes-'+mode)))
        write(t/'worker-config.json',config);ev.update(FORGE_EXPERIMENTS=str(a.EX),FORGE_WORKER_TEST_CONFIG=str(t/'worker-config.json'))
        c.launch(t,[Path(sys.executable).with_name('python.exe'),'-B','-m','unittest','test_integrity'],a.HERE,ev,600)
    STATUS['comparatorTests']=8;STATUS['integrityTestsPerEngine']=7
def run():
    with (a.H/'work/commander-batches/worker.lock').open('r+b') as lock:
        lock.seek(0);msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1);host.admission(R,a.H)
        with (R/'started.json').open('x') as f:json.dump(dict(pid=os.getpid(),utc=host.now()),f)
        verify();threading.Thread(target=heartbeat,daemon=True).start()
        try:
            checkpoint('running-regressions');regressions();checkpoint('regressions-passed')
            oldrole=role(OLD/'smoke-baseline-572101');oldlog=log(OLD/'smoke-baseline-572101');oldgame=read(OLD/'smoke-baseline-572101/commander-seed572101.games.jsonl')
            for n in range(3):
                d,g=launch(f'frozen-baseline-repeat-{n+1}',OLD/'baseline.jar');assert outcome(g)==outcome(oldgame),'Baseline outcome not repeatable'
                diff=variation(oldrole,role(d));diff['gameLog']=variation(oldlog,log(d));STATUS['baselineRepeatability'].append(diff);write(d/'repeatability.json',diff)
            STATUS['baselineOrderingVariationReproduced']=any(not x['rawIdentical'] or not x['gameLog']['rawIdentical'] for x in STATUS['baselineRepeatability'])
            checkpoint('baseline-repeatability-completed')
            paths=[]
            for label,mode in [('observed-baseline-1','baseline'),('observed-baseline-2','baseline'),('observed-candidate-1','optimized')]:
                d,g=launch(label,R/('observed-'+mode+'.jar'),observed=True);assert outcome(g)==outcome(oldgame),'Observed engine changes the preserved outcome'
                paths.append(d)
            traces=[rows(p/'combat.jsonl') for p in paths]
            for trace in traces:
                assert {'ATTACK_ASSIGNMENTS','BLOCK_ASSIGNMENTS','BATCH_BEGIN','BATCH_REPLACED','DAMAGE','BATCH_END','TRIGGER_READY','STACK_ADD','RESOLVE_START','RESOLVED','ZONE','STATE'} <= {x['type'] for x in trace},'Required native telemetry missing'
                unknown=collections.Counter()
                def visit(x):
                    if isinstance(x,dict):
                        if 'unexpandedType' in x:unknown[x['unexpandedType']]+=1
                        for value in x.values():visit(value)
                    elif isinstance(x,list):
                        for value in x:visit(value)
                visit(trace);STATUS.setdefault('unexpandedContextTypes',[]).append(dict(unknown))
            STATUS['structured']=[compare(traces[0],traces[1]),compare(traces[0],traces[2])]
            # Full ordered trigger/resolution/state parity is mandatory, not just matching winners.
            STATUS['structuredEventCounts']=dict(collections.Counter(x['type'] for x in traces[0]));STATUS['rulesParity']=True
            assert not any(STATUS['unexpandedContextTypes']),'Unexpanded trigger context prevents complete structured verification'
            checkpoint('structured-parity-passed')
            # Only the unattempted seed uses the unmodified frozen JARs. No valid prior game is replayed for recovery.
            assert not (OLD/'smoke-baseline-572102').exists() and not (OLD/'smoke-optimized-572102').exists(),'Original remaining seed already has evidence; refuse replay'
            ds=[];gs=[]
            for mode in ['baseline','optimized']:
                d,g=launch('remaining-'+mode+'-572102',OLD/(mode+'.jar'),seed=572102);ds.append(d);gs.append(g)
                STATUS['remainingSeed'].append(dict(mode=mode,valid=True,seed=572102,winner=g['winner'],turns=g['finalTurnCount'],resultSHA256=sha(d/'commander-seed572102.games.jsonl')))
            assert outcome(gs[0])==outcome(gs[1]),'Remaining seed outcome differs'
            assert role(ds[0],572102)==role(ds[1],572102),'Remaining frozen seed ordered role trajectory differs'
            assert log(ds[0],572102)==log(ds[1],572102),'Remaining frozen seed ordered game-log trajectory differs'
            STATUS['remainingSeedOrderedRoleParity']=True;STATUS['behaviourPreservingForTestedWorkloads']=True
            STATUS['conclusion']='Bounded structured seed-572101 parity and remaining frozen seed passed. Presentation/simultaneous delivery ordering is distinguished from ordered assignments, triggers and state. This is bounded evidence, not universal equivalence. Retain experimental/default-off pending review; no automatic promotion. Target blocker search/prediction complexity next.'
            checkpoint('completed');write(R/'completion.json',dict(passed=True,utc=host.now()))
        except BaseException:
            STATUS['failure']=traceback.format_exc();STATUS['rulesParity']=False;STATUS['conclusion']='Stopped at a required parity gate. Optimisation remains disabled. Exact divergence is preserved; no further seed or benchmark runs are authorised by this script.'
            write(R/'failure.json',dict(error=STATUS['failure']))
            try:checkpoint('failed-preserved')
            except BaseException:write(R/'publication-failure.json',dict(error=traceback.format_exc()))
            raise
        finally:STOP.set();write(R/'heartbeat.json',dict(pid=os.getpid(),utc=host.now(),state='finished',active={}))
if __name__=='__main__':
    if '--prepare' in sys.argv:prepare()
    elif '--freeze' in sys.argv:freeze()
    else:
        try:run()
        except BaseException:write(R/'bootstrap-or-run-error.json',dict(error=traceback.format_exc()));raise
