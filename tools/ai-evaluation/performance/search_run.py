"""Finite Phase-4 observation of frozen native bytecode. No speculative result cache."""
from pathlib import Path
import base64,collections,copy,json,math,msvcrt,os,shutil,statistics,subprocess,sys,threading,traceback,zipfile
sys.dont_write_bytecode=True
import blocker_run as prior
a=prior.api;c=a.core;e=a.e;h=prior.host;R=a.R;S=a.SOURCE
P3=Path('D:/ForgeData/forge-experiments/blocker-performance-v3-20261028/validation-02')
BASE=P3/'baseline.jar';BB=Path('C:/Users/Chris/.m2/repository/net/bytebuddy/byte-buddy/1.18.14/byte-buddy-1.18.14.jar')
RUN='20261028-native-combat-search-v4';DEST='forge-performance/reports/'+RUN;URL='https://github.com/cwppt/MTG-deck-lists/tree/main/'+DEST
DATA=dict(runId=RUN,state='prepared',optimisation=None,candidateImplemented=False,exactEquivalentStatesEstablished=False,readyForPromotion=False,regressions={},profiles={},limitations=[])
STOP=threading.Event();ACTIVE={}
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def write(p,v):c.write(p,v)
def checked(cmd,cwd=S):
    p=subprocess.run(list(map(str,cmd)),cwd=cwd,capture_output=True,text=True,encoding='utf-8',creationflags=subprocess.CREATE_NO_WINDOW,timeout=120)
    assert p.returncode==0,p.stdout[-1000:]+p.stderr[-3000:];return p.stdout
def prepare():
    R.mkdir(exist_ok=False);q=read(P3/'run-request.json');assert c.sha(BASE)==q['baselineSHA256'];assert read(P3/'completion.json')['passed'];assert not read(P3/'completion.json')['performanceImprovementDemonstrated']
    d=read(P3/'checkpoint.json');tmp=copy.deepcopy(h.DATA);h.DATA=copy.deepcopy(d);prior.comparison();assert h.DATA['comparison']==d['comparison'] and not h.DATA['performanceImprovementDemonstrated'];h.DATA=tmp
    write(R/'phase3-audit.json',dict(passed=True,gatePassed=False,smokeSkipped=d['smokeGate'],sourceRequestSHA256=c.sha(P3/'run-request.json'),blockerMedianChanges={k:v['medianChangePercent'] for k,v in d['comparison'].items() if k.endswith('/block')}))
    classes=R/'agent-classes';classes.mkdir();files=list((S/'tools/ai-evaluation/performance/search-probe').rglob('*.java'))
    checked([e.JAVA.with_name('javac.exe'),'--release','17','-cp',str(BB)+';'+str(BASE),'-d',classes,*files])
    with zipfile.ZipFile(R/'search-agent.jar','w',zipfile.ZIP_DEFLATED) as jar,zipfile.ZipFile(BB) as bb:
        jar.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\r\nPremain-Class: forge.perf.SearchAgent\r\nCan-Redefine-Classes: false\r\nCan-Retransform-Classes: false\r\n\r\n')
        for name in bb.namelist():
            if name.upper().startswith('META-INF/') and (name.upper().endswith(('.SF','.RSA','.DSA')) or name.upper()=='META-INF/MANIFEST.MF'):continue
            jar.writestr(name,bb.read(name))
        for p in classes.rglob('*.class'):jar.write(p,p.relative_to(classes).as_posix())
    shutil.copytree(P3/'test-classes',R/'test-classes')
    checked([e.JAVA.with_name('javac.exe'),'--release','17','-cp',';'.join(map(str,[BASE,R/'test-classes',a.NG,a.JC])),'-d',R/'test-classes',S/'forge-gui-desktop/src/test/java/forge/ai/BusyBoardBenchmark.java'])
    for label,cap in [('self-test',1024),('self-test-cap',1)]:
        out=R/(label+'.jsonl');log=checked([e.JAVA,'-Xmx256m','-Djava.awt.headless=true','-javaagent:'+str(R/'search-agent.jar'),'-Dforge.search.output='+str(out),'-Dforge.search.signatureCap='+str(cap),'-cp',str(R/'search-agent.jar')+';'+str(BASE),'forge.perf.ProbeFixture'])
        (R/(label+'.log')).write_text(log);assert 'SEARCH_PROBE_SELF_TEST_PASS' in log
        rows=[json.loads(l) for l in out.read_text().splitlines()];assert rows[-1]['type']=='AGENT_END' and not rows[-1]['errors'];window=rows[0]
        pred=next(v for k,v in window['methods'].items() if '.predict(' in k);assert pred['calls']==130 and pred['exceptions']==1
        filtered=next(v for k,v in window['methods'].items() if '.getPossibleBlockers(' in k);assert filtered['filterInputPairs']==3 and filtered['filterAcceptedPairs']==1
        if cap==1:assert window['signatureCoverageCapped']
        else:assert pred['sampledRepeatedArgumentEpochSignatures']>0
    write(R/'agent-tests.json',dict(passed=True,returnAndExceptionPreservation=True,exactCounts=True,signatureCap=True,probeDependencySHA256=c.sha(BB)))
    print('Agent compiled; return/exception/count/cap self-tests passed')
def freeze():
    assert not (R/'request.json').exists()
    sources=list((S/'tools/ai-evaluation/performance').rglob('*.py'))+list((S/'tools/ai-evaluation/performance/search-probe').rglob('*.java'))+[S/'forge-gui-desktop/src/test/java/forge/ai/BusyBoardBenchmark.java']
    write(R/'request.json',dict(sourceHead=checked(['git','rev-parse','HEAD']).strip(),sourceHashes={p.relative_to(S).as_posix():c.sha(p) for p in sources},
        engine=str(BASE),engineSHA256=c.sha(BASE),functionalBaselineSHA256=a.EXPECTED,agentSHA256=c.sha(R/'search-agent.jar'),dependencySHA256=c.sha(BB),
        phase3RequestSHA256=c.sha(P3/'run-request.json'),phase3CheckpointSHA256=c.sha(P3/'checkpoint.json'),
        helperHashes=read(P3/'run-request.json')['helperHashes'],testHashes={p.relative_to(R).as_posix():c.sha(p) for p in (R/'test-classes').rglob('*.class')},
        flags=a.flags('baseline'),sampleEvery=32,signatureCap=1024,
        equivalence='Proxy only. No exact-equivalence or caching claim is permitted without all rule state, private zones, delayed effects, RNG, budgets and hidden hypothetical mutations.',
        profilingPlan='Two alternating JVM blocks of plain/probed large-four-player and dragon-token blocking; 1 warmup, 2 observations. Additional four-board observed search counters: 1 warmup, 2 observations. No Phase-3 benchmark replay.',
        conditionalFutureAcceptance='If a separately justified cache candidate is ever implemented: >=15% median on both primary workloads, 3 paired JVM blocks, p95/allocations <=+5%, exact structured parity; small smoke only after gates. This profiling-only run has no candidate comparison or smoke cohort.'))
def verify():
    q=read(R/'request.json');assert c.sha(BASE)==q['engineSHA256'] and c.sha(R/'search-agent.jar')==q['agentSHA256'] and c.sha(BB)==q['dependencySHA256']
    assert c.sha(P3/'run-request.json')==q['phase3RequestSHA256'] and c.sha(P3/'checkpoint.json')==q['phase3CheckpointSHA256']
    for f,hsh in q['sourceHashes'].items():assert c.sha(S/f)==hsh,'Source changed: '+f
    for f,hsh in q['testHashes'].items():assert c.sha(R/f)==hsh
    for f,hsh in q['helperHashes'].items():assert c.sha(a.H/f)==hsh
    assert c.sha(a.BASE)==a.EXPECTED
def native(label,probe=False,props=(),classes='forge.ai.BusyBoardBenchmark',limit=1200):
    verify();h.admission(R,a.H);d=R/label;d.mkdir();cwd,env,home,temp=c.runtime(d);options=list(props)
    if '-Dforge.bench.verify=true' in options:
        t=d/'traces';t.mkdir();options+=['-Dforge.bench.traceDir='+str(t)]
    if classes==a.REGRESSION:options+=['-Dforge.parity.testdir='+str(d),'-Dfixture.deck='+str(a.EX/'lathliss-r-throne-20261026/decks/Lathliss_R_Rebuild_V2_Throne.dck')]
    cp=';'.join(map(str,[R/'test-classes',BASE,a.NG,a.JC]));cmd=[e.JAVA,*a.flags('baseline'),*e.opts(home,temp)]
    if probe:cmd+=['-javaagent:'+str(R/'search-agent.jar'),'-Dforge.search.output='+str(d/'search.jsonl'),'-Dforge.search.probe=true']
    cmd+=options+['-cp',cp,'org.testng.TestNG','-testclass',classes,'-d',d/'results'];ACTIVE.update(label=label)
    try:c.launch(d,cmd,cwd,env,limit)
    finally:ACTIVE.clear()
    if probe:
        observed=[json.loads(l) for l in (d/'search.jsonl').read_text().splitlines()];assert observed[-1]['type']=='AGENT_END' and not observed[-1]['errors'],'Agent transform failed'
        if classes=='forge.ai.BusyBoardBenchmark':assert {'forge.ai.AiBlockController','forge.ai.ComputerUtilCombat'} <= set(observed[-1]['transformedClasses'])
    return d
def collect(d,probe):
    rows=[x for x in a.rows(d) if not x.get('warmup')]
    item=dict(probed=probe,rows=rows,logBytes=(d/'execution.log').stat().st_size,logSHA256=c.sha(d/'execution.log'))
    if probe:
        item['rawSearchSHA256']=c.sha(d/'search.jsonl')
        item['transformedClasses']=json.loads((d/'search.jsonl').read_text().splitlines()[-1])['transformedClasses']
        item['windows']=[json.loads(l) for l in (d/'search.jsonl').read_text().splitlines() if json.loads(l)['type']=='WINDOW' and json.loads(l)['rep']>=0]
        for window in item['windows']:
            for method in window['methods'].values():
                histogram=method.pop('latencyLog2NsHistogram');method.pop('changedResultExamples',None)
                for quantile in [.5,.95]:
                    total=sum(histogram);target=math.ceil(total*quantile);seen=0;bound=None
                    for bucket,count in enumerate(histogram):
                        seen+=count
                        if total and seen>=target:bound=2**(bucket+1);break
                    method['sampledLatencyP'+str(int(quantile*100))+'UpperNs']=bound
    DATA['profiles'][d.name]=item
def publish(stage):
    DATA.update(state=stage,utc=h.now());write(R/'checkpoint.json',DATA)
    public=R/'public';public.mkdir(exist_ok=True);payload=copy.deepcopy(DATA)
    for entry in payload['profiles'].values():
        totals=collections.defaultdict(lambda:collections.Counter())
        for window in entry.get('windows',[]):
            for method,stats in window['methods'].items():
                for field,value in stats.items():
                    if isinstance(value,(int,float)) and not field.startswith('sampledLatency'):totals[method][field]+=value
            window.pop('methods')
        if totals:entry['summedPerWindowMethodObservations']={k:dict(v) for k,v in totals.items()}
    write(public/'measurements.json',payload);write(public/'manifest.json',read(R/'request.json'))
    text='# Native Commander combat-search v4\n\nState: **'+stage+'**. Profiling-only: no optimiser/cache or full-game cohort has been introduced.\n\n'
    text+='Phase 3 failed its predeclared gate (2.94% / 15.29% primary gains), remained disabled, and skipped smoke. Its completed measurements are audited and never replayed here.\n\n'
    text+='This agent wraps the actual frozen AiBlockController, ComputerUtilCombat, CombatUtil, ComputerUtilCard, GameCopier, CardCopyService, Game and AiCache methods as loaded. It does not recompile them, replace return values, suppress original exceptions, or remove budget/cache code. All earlier optimisation flags remain false. Source branch: [codex/commander-combat-search-v4](https://github.com/cwppt/forge/tree/codex/commander-combat-search-v4), commit `'+read(R/'request.json')['sourceHead']+'`. Engine SHA256 `'+read(R/'request.json')['engineSHA256']+'`; frozen functional baseline SHA256 `'+a.EXPECTED+'`.\n\n'
    text+='Counters distinguish actual method entries (including overloads), candidate-filter input/accepted pairs, game-clone copyGameState calls, LKI copy calls and last-state snapshots. Never sum wrapper overloads as independent predictions or label snapshot copies full-game clones. Full multi-block combination counts are not available. CPU/latency/allocation are sampled every 32nd invocation; nested inclusive measurements overlap and include observer overhead. The uninstrumented/probed comparison quantifies diagnostic overhead, not an optimisation speedup.\n\n'
    text+='Argument signatures use method, receiver/argument identities and primitive/list values, thread/decision and observed mutation epoch. They are bounded proxies, not complete game-state equivalence. Silent hypothetical mutations, private state, stack, delayed effects, RNG, budgets and caches are not exhaustively encoded. Exact-state and proven-equivalent-repeat counts remain null. Changed scalar results within one proxy signature disprove its suitability; unchanged results do not prove cache safety. No reuse is implemented from proxy matches.\n\n'
    text+='## Results\n\n```json\n'+json.dumps({k:v for k,v in DATA.items() if k!='profiles'},indent=2)+'\n```\n\n'
    text+='Raw per-method counts, bounded latency histograms, sampled argument-repeat/result-change counts, CPU/allocation observations, overhead controls and hashes are in measurements.json. Raw native logs and structured traces remain local. No historical engine or result has been overwritten.\n\n'
    text+='The accepted structured comparator is reused: only consecutive damage deliveries inside an explicit simultaneous combat batch may permute. Assignments, blocker order, damage maps, triggers, stack resolution and states remain exact/ordered. An instrumentation-induced divergence stops the run. Existing timeout/failure/result-validation regressions are rerun against the observed frozen engine.\n\n'
    text+='A future isolated candidate must first establish all relevant state and an explicit decision-scoped lifetime, then meet the predefined >=15% primary median criterion with p95/allocation <=+5% and full parity before any small smoke cohort. This run does not auto-generate code or enable caches. No merge or promotion.\n'
    (public/'REPORT.md').write_text(text,encoding='utf-8');owned=read(R/'published.json') if (R/'published.json').exists() else {}
    for name in ['REPORT.md','measurements.json','manifest.json']:
        endpoint='repos/cwppt/MTG-deck-lists/contents/'+DEST+'/'+name;old=h.gh(endpoint);body=dict(message='Combat search v4: '+stage,branch='main',content=base64.b64encode((public/name).read_bytes()).decode())
        if old.returncode==0:assert owned.get(name)==json.loads(old.stdout)['sha'],'Report changed externally';body['sha']=owned[name]
        else:assert name not in owned and '404' in old.stderr,'GitHub preflight failed'
        result=h.gh(endpoint,'PUT',body);assert result.returncode==0,result.stderr;owned[name]=json.loads(result.stdout)['content']['sha'];write(R/'published.json',owned)
    write(R/'publication.json',dict(state=stage,utc=h.now(),url=URL,files=owned))
def heartbeat():
    while not STOP.wait(5):write(R/'heartbeat.json',dict(pid=os.getpid(),utc=h.now(),state=DATA['state'],active=ACTIVE))
def run():
    with (a.H/'work/commander-batches/worker.lock').open('r+b') as lock:
        lock.seek(0);msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1);h.admission(R,a.H)
        with (R/'started.json').open('x') as f:json.dump(dict(pid=os.getpid(),utc=h.now()),f)
        verify();threading.Thread(target=heartbeat,daemon=True).start()
        try:
            publish('running-correctness-gates')
            for probe in [False,True]:
                d=native('regressions-'+str(probe),probe,classes=a.REGRESSION,limit=600);counts=h.xmlcounts(d);assert counts==dict(total=26,passed=26,failed=0,skipped=0);DATA['regressions'][d.name]=counts
            props=['-Dforge.bench.verify=true','-Dforge.bench.repeats=1','-Dforge.bench.cases='+a.CASES+','+a.RULES]
            plain=native('parity-plain',False,props);observed=native('parity-observed',True,props);a.equal(plain,observed);DATA['regressions']['structuredParity']=dict(passed=True,workloads=len(a.rows(plain)))
            # No observer or candidate is authorised to alter timeout/result semantics.
            d=R/'integrity-observed';d.mkdir();cwd,env,home,temp=c.runtime(d);cp=';'.join(map(str,[R/'test-classes',BASE,a.NG,a.JC]))
            config=dict(java=str(e.JAVA),opts=[*a.flags('baseline'),*e.opts(home,temp),'-javaagent:'+str(R/'search-agent.jar'),'-Dforge.search.output='+str(d/'worker-{pid}.jsonl')],classpath=cp,cwd=str(cwd),env=env,output=str(R/'worker-probes'))
            write(d/'worker-config.json',config);env.update(FORGE_EXPERIMENTS=str(a.EX),FORGE_WORKER_TEST_CONFIG=str(d/'worker-config.json'))
            c.launch(d,[Path(sys.executable).with_name('python.exe'),'-B','-m','unittest','test_integrity','test_structured_parity'],a.HERE,env,600);DATA['regressions']['failureAndComparator']=dict(passed=True,integrityMethods=7,comparatorMethods=8,agentEnabledForFailureProbes=True)
            publish('correctness-gates-passed')
            focus='large-four-player,dragon-tokens';props=['-Dforge.bench.cases='+focus,'-Dforge.bench.paths=block','-Dforge.bench.warmup=1','-Dforge.bench.repeats=2']
            for round in range(2):
                for probe in ([False,True] if round==0 else [True,False]):
                    d=native(f'overhead-{round}-{probe}',probe,props);collect(d,probe)
                publish('overhead-round-'+str(round+1))
            overhead=collections.defaultdict(lambda:collections.defaultdict(list))
            for name,entry in DATA['profiles'].items():
                if name.startswith('overhead-'):
                    for row in entry['rows']:overhead[row['case']]['probed' if entry['probed'] else 'plain'].append(row['wallMs'])
            DATA['diagnosticOverhead']={case:{mode:dict(n=len(values),medianMs=statistics.median(values),p95Ms=sorted(values)[math.ceil(.95*len(values))-1]) for mode,values in modes.items()} for case,modes in overhead.items()}
            d=native('search-counts-four-boards',True,['-Dforge.bench.cases=large-four-player,dragon-tokens,large-combat,late-100','-Dforge.bench.paths=block','-Dforge.bench.warmup=1','-Dforge.bench.repeats=2']);collect(d,True)
            summary=collections.defaultdict(lambda:dict(calls=0,timedSamples=0,sampledCpuNs=0,sampledBytes=0,signatureRepeats=0,changedResults=0,inputPairs=0,acceptedPairs=0))
            for w in DATA['profiles']['search-counts-four-boards']['windows']:
                for name,v in w['methods'].items():
                    s=summary[name]
                    for out,field in [('calls','calls'),('timedSamples','timedSamples'),('sampledCpuNs','sampledInclusiveCpuNs'),('sampledBytes','sampledInstrumentedThreadAllocationBytes'),('signatureRepeats','sampledRepeatedArgumentEpochSignatures'),('changedResults','sampledChangedResultsWithinSignature'),('inputPairs','filterInputPairs'),('acceptedPairs','filterAcceptedPairs')]:s[out]+=v[field]
            stages=collections.defaultdict(lambda:dict(actualMethodEntries=0,sampledInclusiveCpuNs=0,sampledInstrumentedBytes=0))
            for name,v in summary.items():
                stage='candidate-filtering' if 'getPossibleBlockers' in name else 'prediction' if 'ComputerUtilCombat' in name else 'legality' if '.CombatUtil.' in name else 'candidate-scoring' if 'ComputerUtilCard' in name else 'copying-and-construction' if 'Copy' in name or '.Game.<init>' in name else 'cache' if 'AiCache' in name else 'board-traversal' if 'getCardsIn' in name else 'search-control'
                stages[stage]['actualMethodEntries']+=v['calls'];stages[stage]['sampledInclusiveCpuNs']+=v['sampledCpuNs'];stages[stage]['sampledInstrumentedBytes']+=v['sampledBytes']
                v['meanSampledCpuNs']=v['sampledCpuNs']/v['timedSamples'] if v['timedSamples'] else None
            DATA['stageSummaryInclusiveOverlapping']=dict(stages)
            DATA['methodSummary']=dict(sorted(summary.items(),key=lambda item:item[1]['sampledCpuNs'],reverse=True));DATA['limitations']=['Signature proxies do not establish exact equivalence; no cache/pruning optimisation is justified from them alone.','Counts of wrapper overloads and constructor chains overlap; time/allocations are sampled inclusive and contain instrumentation overhead. Stage sums are not additive wall-time percentages.','Whole combination counts and complete private/silent state capture are not implemented. Copy counters cover named endpoints, not every possible state-construction mechanism.']
            DATA['conclusion']='Actual frozen search/copy/prediction calls are measured. Repeated argument signatures are quantified, but complete equivalent-state/invalidation proof is absent. No speculative optimisation, candidate benchmark or smoke cohort was implemented. Review dominant concrete method signatures and stage costs; narrow a future immutable subcalculation before proposing reuse.'
            publish('completed-profiling-no-optimisation');write(R/'completion.json',dict(passed=True,profilingCompleted=True,optimisationImplemented=False,utc=h.now()))
        except BaseException:
            DATA['failure']=traceback.format_exc();DATA['conclusion']='Stopped at a required instrumentation/parity gate. No performance or equivalence claim.';write(R/'failure.json',dict(error=DATA['failure']))
            try:publish('failed-preserved')
            except BaseException:write(R/'publication-failure.json',dict(error=traceback.format_exc()))
            raise
        finally:STOP.set();write(R/'heartbeat.json',dict(pid=os.getpid(),utc=h.now(),state='finished',active={}))
if __name__=='__main__':
    if '--prepare' in sys.argv:prepare()
    elif '--freeze' in sys.argv:freeze()
    else:
        try:run()
        except BaseException:write(R/'bootstrap-or-run-error.json',dict(error=traceback.format_exc()));raise
