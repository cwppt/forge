"""Phase 7: one explicit, default-off JVM compilation experiment; unchanged rules bytecode."""
from pathlib import Path
import base64,collections,hashlib,json,math,os,shutil,statistics,struct,sys,threading,time,traceback,xml.etree.ElementTree as ET,zipfile
sys.dont_write_bytecode=True
import realgame_run as native
api=native.api;h=native.h;c=native.c;e=native.e;R=native.R;S=native.S
from realgame_analysis import records,dist,analyse_game
from structured_parity import compare
V5=api.EX/'incremental-performance-v5-01-20261028/validation-02'
V6=api.EX/'realgame-performance-v6-20261028/validation-01'
RUN='20261028-native-rules-performance-v7';BRANCH='codex/commander-rules-performance-v7'
BASE_FLAGS=api.flags('baseline');TREATMENT=['-XX:-DontCompileHugeMethods']
PLAN=[dict(seed=573300+i,deck=native.VARIANTS[i%3]) for i in range(9)]
GATES=dict(targetMethodMedianCpuGainPercent=20,wholeGameActiveElapsedGainPercent=10,
    primaryPairs=3,maximumPairs=9,extensionRequiresFirstThreePairsImprove=True,
    maximumCombatMedianP95CpuAllocationRegressionPercent=5,maximumHeapMedianRegressionPercent=5,
    gcMaterialRegression='More than 5% AND more than 100 ms in median game GC',
    finalRepeatability='All first three paired active ratios <1; at least eight of nine if extended; all variant paired median ratios <1.',
    blockerSpecificHistoricalPromotionTargetPercent=15,
    promotion='No automatic adoption. Engine-wide 10% real-game gate plus full parity; retain/report blocker-specific 15% gate separately. Small cohorts preliminary.')
D=dict(schemaVersion=1,runId=RUN,state='pending',regressions={},benchmarks={},games=[],pairs=[],differences=[],candidateRuntimeFlag=TREATMENT,
    performanceImprovementDemonstrated=False,readyForLargerCohorts=False)
ACTIVE={};STOP=threading.Event()
def read(p):return native.read(p)
def write(p,v):return c.write(p,v)
def flags(mode):return BASE_FLAGS+(TREATMENT if mode=='optimized' else [])
api.flags=flags
def head():return native.head()

def property_bytecode():
    with zipfile.ZipFile(api.REFERENCE) as jar:data=jar.read('forge/game/card/CardProperty.class')
    pos=8
    def number(fmt):
        nonlocal pos
        value=struct.unpack_from('>'+fmt,data,pos)[0];pos+=struct.calcsize(fmt);return value
    pool={};count=number('H');i=1
    while i<count:
        tag=number('B')
        if tag==1:
            length=number('H');pool[i]=data[pos:pos+length].decode('utf-8',errors='replace');pos+=length
        elif tag in (5,6):pos+=8;i+=1
        else:pos+={3:4,4:4,7:2,8:2,9:4,10:4,11:4,12:4,15:3,16:2,17:4,18:4,19:2,20:2}[tag]
        i+=1
    pos+=6;interfaces=number('H');pos+=2*interfaces
    for kind in ['fields','methods']:
        for _ in range(number('H')):
            number('H');name=pool[number('H')];number('H')
            for _ in range(number('H')):
                attribute=pool[number('H')];length=number('I');end=pos+length
                if kind=='methods' and name=='cardHasProperty' and attribute=='Code':
                    number('H');number('H');size=number('I')
                    return dict(classSHA256=hashlib.sha256(data).hexdigest(),methodBytecodeBytes=size)
                pos=end
    raise AssertionError('Expected frozen method Code attribute')

def investigate():
    profile=next(V6.glob('diagnostic-*/jfr-samples.json'));data=read(profile)
    caller=collections.Counter();chains=collections.Counter();leaf=collections.Counter();frame_types=collections.Counter();lines=collections.Counter();allocation=collections.Counter();total=exclusive=inclusive=0
    for event in data['recording']['events']:
        frames=(event['values'].get('stackTrace') or {}).get('frames',[])
        names=[f['method']['type']['name']+'.'+f['method']['name'] for f in frames]
        if event['type']=='jdk.ExecutionSample':total+=1
        for i,name in enumerate(names):
            if name!='forge/game/card/CardProperty.cardHasProperty':continue
            if event['type']=='jdk.ExecutionSample':
                inclusive+=1;exclusive+=int(i==0);frame_types[frames[i]['type']]+=1
                caller[names[i+1] if i+1<len(names) else '<truncated>']+=1;chains[' -> '.join(names[i+1:i+7])]+=1
                leaf[names[0]]+=1;lines[str(frames[i]['lineNumber'])]+=1
            else:allocation[names[0] if names else '<truncated>']+=event['values'].get('weight',0)
            break
    return dict(source=str(profile),sourceSHA256=c.sha(profile),frozenMethod=property_bytecode(),totalCpuSamples=total,inclusivePropertySamples=inclusive,exclusivePropertySamples=exclusive,
        inclusiveSamplePercent=100*inclusive/total,exclusiveSamplePercent=100*exclusive/total,frameTypes=dict(frame_types),directSampledCallers=caller.most_common(12),
        sampledCallerChains=chains.most_common(12),propertyInclusiveLeafFrames=leaf.most_common(15),sampledLineCounts=lines.most_common(20),sampledAllocationWeightsByLeaf=allocation.most_common(15),
        rootCause='Large linear property dispatcher remains interpreted in every observed stack. JVM DontCompileHugeMethods defaults true; test disabling this one compiler exclusion, without caching or modifying any engine class.',
        semantics='Evaluates name/control/identity, combat/history, dynamic characteristics, zones and state-dependent restrictions, falling back to CardStateProperty. Phasing, LKI, external continuous effects and trigger/replacement callers remain live. No repeated-state equivalence assumed.',
        limits='Sampled caller counts are not invocation counts. CPU samples do not measure exact elapsed time. No property-frequency or string-parse count inferred. Allocation weights sampled/incomplete; only three allocation leaves occurred in property-inclusive samples.')

def verify():
    q=read(R/'run-request.json');assert q['sourceHead']==head() and q['acceptance']==GATES
    assert q['baselineFlags']==flags('baseline') and q['candidateFlags']==flags('optimized')
    for name in ['baseline.jar','optimized.jar']:assert c.sha(R/name)==api.REFERENCE_SHA
    assert c.sha(api.BASE)==api.EXPECTED
    for field,root in [('sourceHashes',S),('compiledHashes',R),('helperHashes',api.H),('phase6Hashes',V6)]:
        for path,digest in q[field].items():assert c.sha(root/path)==digest,'Frozen input changed: '+path
    for path,digest in q['deckHashes'].items():assert c.sha(R/'decks'/path)==digest
    assert read(R/'initial-gate.json')['passed'];return q
h.verify=verify

def prepare():
    assert not (R/'baseline.jar').exists();R.mkdir(parents=True,exist_ok=True);h.admission(R,api.H)
    assert c.sha(api.REFERENCE)==api.REFERENCE_SHA
    for name in ['baseline.jar','optimized.jar']:shutil.copy2(api.REFERENCE,R/name)
    shutil.copytree(V5/'test-classes',R/'test-classes');shutil.copytree(V5/'decks',R/'decks')
    cp=';'.join(map(str,[R/'test-classes',R/'baseline.jar',api.NG,api.JC]))
    files=[S/'forge-gui-desktop/src/test/java/forge/ai'/name for name in ['CardPropertyRulesTest.java','CardPropertyBenchmark.java']]
    result=api.java([e.JAVA.with_name('javac.exe'),'--release','17','-cp',cp,'-d',R/'test-classes',*files]);(R/'test-compile.log').write_text(result.stdout+result.stderr);assert result.returncode==0,result.stderr
    javap=h.command([e.JAVA.with_name('javap.exe'),'-c','-p','-classpath',R/'baseline.jar','forge.game.card.CardProperty']);assert javap.returncode==0
    (R/'CardProperty-bytecode.txt').write_text(javap.stdout,encoding='utf-8')
    vm=h.command([e.JAVA,'-XX:+PrintFlagsFinal','-version']);(R/'jvm-default-flags.txt').write_text(vm.stdout+vm.stderr,encoding='utf-8');assert vm.returncode==0 and 'DontCompileHugeMethods' in vm.stdout
    write(R/'hotspot-evidence.json',investigate());print('Prepared unchanged-engine JVM candidate; no source engine overlay')

def testng(label,mode,props,classes,limit=1200):return api.testng(label,mode,props,classes,limit)
def initial():
    regression=api.REGRESSION+',forge.ai.CardPropertyRulesTest'
    for mode in ['baseline','optimized']:
        d=R/('focused-'+mode)
        testng(d.name,mode,['-Dforge.parity.testdir='+str(d),'-Dfixture.deck='+str(R/'decks/Lathliss_R_Rebuild_V2_Throne.dck')],regression,600)
        counts=h.xmlcounts(d);assert counts['failed']==0 and counts['skipped']==0 and counts['passed']==38,counts
    props=['-Dforge.bench.verify=true','-Dforge.bench.cases=large-four-player','-Dforge.bench.paths=block','-Dforge.bench.repeats=1']
    a=testng('initial-parity-baseline','baseline',props,'forge.ai.BusyBoardBenchmark');b=testng('initial-parity-candidate','optimized',props,'forge.ai.BusyBoardBenchmark');api.equal(a,b)
    write(R/'initial-gate.json',dict(passed=True,javaMethodsPerArm=38,structuredCombatParity=True,engineBytecodeIdentical=True))

def freeze():
    assert not (R/'run-request.json').exists() and read(R/'initial-gate.json')['passed']
    paths=h.command(['git','-C',S,'ls-files']).stdout.splitlines()
    hashes={p:c.sha(S/p) for p in paths if p.startswith(('tools/ai-evaluation/performance/','tools/ai-evaluation/parallel-v4/')) or p.endswith(('CardPropertyRulesTest.java','CardPropertyBenchmark.java'))}
    historic=['checkpoint.json','completion.json','run-request.json']+[p.relative_to(V6).as_posix() for p in V6.glob('diagnostic-*/jfr-samples.json')]
    write(R/'run-request.json',dict(schemaVersion=1,runId=RUN,sourceBranch=BRANCH,sourceHead=head(),baselineEngineSHA256=api.REFERENCE_SHA,candidateEngineSHA256=api.REFERENCE_SHA,functionalEngineSHA256=api.EXPECTED,
        baselineFlags=flags('baseline'),candidateFlags=flags('optimized'),acceptance=GATES,plan=PLAN,
        pod=read(V6/'run-request.json')['pod'],deckHashes=read(V6/'run-request.json')['deckHashes'],sourceHashes=hashes,
        compiledHashes={p.relative_to(R).as_posix():c.sha(p) for p in (R/'test-classes').rglob('*.class')},
        helperHashes=read(V6/'run-request.json')['helperHashes'],phase6Hashes={p:c.sha(V6/p) for p in historic},
        heapGiB=3,processors=2,maximumGameWorkers=1,freeSpaceReserveGiB=3,storageRecovery=read(R/'storage-recovery.json') if (R/'storage-recovery.json').exists() else None,
        candidateScope='JVM compiler policy only: disabling default DontCompileHugeMethods permits ALL huge methods to compile, not only CardProperty. No engine entry or AI setting changes. Separate compiler logs establish actual compilation; parity guards wall-clock-budget effects.',
        cumulativeRule='Direct raw-functional/candidate ratios reported only for targeted microbenchmark. No raw-functional whole-game cohort, therefore cumulative whole-game improvement unknown. Historical flags remain disabled.'))
    verify();print('Acceptance gates and all inputs frozen')

def aggregate():
    failed=D.get('failedAttempts',[])
    D['failureCounts']=dict(invalidOrUnvalidatedAttempts=sum(not x['independentlyValidated'] for x in failed),
        timeoutAttempts=sum(x.get('exitCode')==124 or x.get('timedOut',False) for x in failed),
        analysisFailuresAfterValidEngine=sum(x['independentlyValidated'] for x in failed),retries=0)
    D['performanceHistory']=dict(schemaVersion=3,functionalReference=api.EXPECTED,observationReference=api.REFERENCE_SHA,candidateEngine=api.REFERENCE_SHA,candidateJvmFlags=TREATMENT,
        priorPhases=dict(phase2='20261028-native-cost-performance-v2',phase3='20261028-native-blocker-performance-v3',phase4='20261028-native-combat-search-v4',phase5='20261028-native-incremental-performance-v5-01',phase6='20261028-native-realgame-performance-v6'),
        priorOptimisationsActive=False,targetedComparison=D.get('targetComparison'),combatComparison=D.get('combatComparison'),wholeGame=D.get('wholeGame'),
        cumulativeWholeGameImprovementPercent=None,rule='No summing unrelated percentages; whole-game comparison is observation-reference/candidate only. No raw-functional throughput measured.',correctness=D['regressions'])

def report():
    return '# Phase 7 — Rules dispatch and JVM compilation\n\n'+f"State **{D['state']}**, PID {os.getpid()}, updated {native.now()}.\n\n"+ \
        'Candidate is **default off** and changes only `-XX:-DontCompileHugeMethods`. Both arms run the identical frozen engine. This permits compilation of all huge JVM methods, so attribution cannot be restricted to CardProperty alone. No caching, rules rewrite, heuristic or budget change. Faster execution can affect wall-clock budget checks; unexplained native trajectory divergence stops the run.\n\n'+ \
        '## Existing real-game hotspot evidence\n\n```json\n'+json.dumps(read(R/'hotspot-evidence.json'),indent=2)+'\n```\n\n'+ \
        'The 2,000-line property method has roughly 14 KB bytecode. Its giant ordered string dispatcher was interpreted in all observed stacks. Dynamic checks and repeated parsing/traversal may be unavoidable; no repeated-state reuse is assumed. The candidate tests compilation, not a property cache.\n\n'+ \
        '## Frozen acceptance and correctness\n\n```json\n'+json.dumps(dict(acceptance=GATES,regressions=D['regressions'],compilerEvidence=D.get('compilerEvidence')),indent=2)+'\n```\n\n'+ \
        '## Before/after measurements\n\n```json\n'+json.dumps({k:D.get(k) for k in ['targetComparison','combatComparison','blockerSpecific15PercentGate','wholeGame','pairs','failureCounts','performanceImprovementDemonstrated']},indent=2)+'\n```\n\n'+ \
        'Primary timings exclude compiler logging/JFR. Microbenchmarks use explicitly fixed local properties, not a measured real-game property-frequency distribution. CPU/allocation are current-thread microbenchmark observations; exact game allocation is unavailable. Whole-game elapsed includes admission, startup, game, independent validation and analysis; publication excluded from matched comparisons and reported separately. Small cohorts provide preliminary evidence. Both arms have identical structured observer/native diagnostic overhead.\n\n'+ \
        '## Decision and limitations\n\n'+D.get('conclusion','Correctness/performance gates pending; no improvement established.')+'\n\n'+ \
        ('Stopped reason:\n```\n'+D['failure']+'\n```\n\n' if D.get('failure') else '')+ \
        'No automatic merge, engine promotion or Phase 8. The historical 15% blocker-specific target is retained and reported separately from this predeclared 10% engine-wide whole-game gate. No rejected experiment is active. Direct cumulative raw-functional comparisons cover only the method workload; cumulative whole-game gain remains unknown.\n\n'+ \
        '[Source branch](https://github.com/cwppt/forge/tree/'+BRANCH+') · [Manifest](manifest.json) · [Measurements](measurements.json) · [Performance history](performance-history.json). Source commit '+(read(R/'run-request.json')['sourceHead'] if (R/'run-request.json').exists() else head())+'. Raw evidence: `'+str(R)+'`.\n'

def publish_blocked():
    assert not (R/'started.json').exists(),'Do not overwrite an active experiment'
    R.mkdir(parents=True,exist_ok=True);write(R/'hotspot-evidence.json',investigate())
    free=shutil.disk_usage(api.H).free
    D.update(state='blocked-disk-reserve-before-native-tests',supervisorRunning=False,supervisorPid=None,
        storage=dict(cFreeBytes=free,requiredFreeBytes=3*1024**3,additionalRequiredBytes=max(0,3*1024**3-free)),
        conclusion='Native test preparation stopped before copying engines or launching a test/game: C: is below the preserved 3 GiB reserve. JVM candidate is configured but unvalidated and remains disabled. No supervisor launched, no performance or cumulative gain established. Free adequate space on C: before --prepare/--initial/--freeze and detached execution. Original harness and historical engines/reports untouched.')
    D['regressions']['pythonAnalysisAndGateTests']=dict(passed=True,methods=20,evidence='offline-verification/execution.log')
    aggregate();write(R/'checkpoint.json',D)
    write(R/'status.json',dict(stage=D['state'],utc=native.now(),pid=None,reportUrl=native.URL))
    public=R/'public';public.mkdir(exist_ok=True)
    write(public/'measurements.json',D);write(public/'performance-history.json',D['performanceHistory'])
    write(public/'manifest.json',dict(configurationFrozenForNativeExecution=False,sourceHead=head(),sourceBranch=BRANCH,
        engineSHA256=api.REFERENCE_SHA,functionalSHA256=api.EXPECTED,baselineFlags=flags('baseline'),candidateFlags=flags('optimized'),acceptance=GATES,plan=PLAN))
    (public/'REPORT.md').write_text(report().replace('PID '+str(os.getpid()),'no supervisor running'),encoding='utf-8')
    owned=read(R/'published-blobs.json') if (R/'published-blobs.json').exists() else {}
    for name in ['REPORT.md','measurements.json','manifest.json','performance-history.json']:
        endpoint='repos/'+native.REPO+'/contents/'+native.DEST+'/'+name;remote=h.gh(endpoint)
        payload=dict(message='Phase 7 hotspot evidence; native validation blocked by disk reserve',branch='main',content=base64.b64encode((public/name).read_bytes()).decode())
        if remote.returncode==0:
            prior=json.loads(remote.stdout);assert name in owned and prior['sha']==owned[name]['sha'],'Report changed externally';payload['sha']=prior['sha']
        else:assert '404' in remote.stderr or 'Not Found' in remote.stderr,remote.stderr[-500:]
        result=h.gh(endpoint,'PUT',payload);assert result.returncode==0,result.stderr[-700:]
        info=json.loads(result.stdout);owned[name]=dict(sha=info['content']['sha'],commit=info['commit']['sha']);write(R/'published-blobs.json',owned)
    write(R/'publication.json',dict(url=native.URL,state=D['state'],utc=native.now(),supervisorPid=None,files=owned))

native.DATA=D;native.RUN=RUN;native.BRANCH=BRANCH;native.DEST='forge-performance/reports/'+RUN;native.URL='https://github.com/cwppt/MTG-deck-lists/tree/main/'+native.DEST
native.aggregate=aggregate;native.report=report;native.ACTIVE=ACTIVE
publish=native.publish

def target_comparison():
    modes={mode:[row for label,entry in D['benchmarks'].items() if label.startswith('target-') and entry['mode']==mode for row in entry['rows'] if not row['warmup']] for mode in ['functional','baseline','optimized']}
    out={m:{k:dist([x[k] for x in rows]) for k in ['wallMs','cpuMs','allocationBytes','gcMs']} for m,rows in modes.items()}
    ratios=[]
    for n in range(3):
        def med(mode):return statistics.median([x['cpuMs'] for x in D['benchmarks'][f'target-{n}-{mode}']['rows'] if not x['warmup']])
        ratios.append(med('optimized')/med('baseline'))
    out['jvmBlockCpuRatios']=ratios
    out['incrementalMedianCpuGainPercent']=100*(1-out['optimized']['cpuMs']['median']/out['baseline']['cpuMs']['median'])
    out['directCumulativeMethodCpuGainPercent']=100*(1-out['optimized']['cpuMs']['median']/out['functional']['cpuMs']['median'])
    out['passed']=out['incrementalMedianCpuGainPercent']>=20 and all(x<1 for x in ratios) and out['optimized']['allocationBytes']['median']<=1.05*out['baseline']['allocationBytes']['median']
    D['targetComparison']=out

def measured_test(label,mode,props,klass='forge.ai.BusyBoardBenchmark',target=False):
    verify();ACTIVE.update(stage=label,mode=mode)
    try:d=testng(label,mode,props,klass)
    finally:ACTIVE.clear()
    rows=records(d/'execution.log','RULES_BENCH ' if target else 'BUSY_BENCH ');assert rows
    D['benchmarks'][label]=dict(mode=mode,rows=rows)
    return d

def matched_game(spec,mode):
    arm=R/mode;arm.mkdir(exist_ok=True)
    if not (arm/'engine.jar').exists():shutil.copy2(api.REFERENCE,arm/'engine.jar')
    if not (arm/'decks').exists():shutil.copytree(R/'decks',arm/'decks')
    assert c.sha(arm/'engine.jar')==api.REFERENCE_SHA
    for name,digest in verify()['deckHashes'].items():assert c.sha(arm/'decks'/name)==digest
    native.R=arm;native.FLAGS=flags(mode);native.verify=verify
    try:
        row=native.play(spec);d=arm/row['directory'];started=time.monotonic();row['analysis']=analyse_game(d,spec['seed']);row['measurementElapsedSeconds']+=time.monotonic()-started
        row['mode']=mode;row['directory']=d.relative_to(R).as_posix();D['games'].append(row)
        return d,row
    except BaseException:
        d=arm/('game-'+str(spec['seed']));result=d/f"commander-seed{spec['seed']}.games.jsonl"
        raw=records(result) if result.exists() else []
        D.setdefault('failedAttempts',[]).append(dict(**spec,mode=mode,directory=str(d),independentlyValidated=(d/'validation-passed.json').exists(),
            exitCode=read(d/'execution.json')['exitCode'] if (d/'execution.json').exists() else None,
            timedOut=any(x.get('timedOut',False) for x in raw),error=traceback.format_exc()))
        raise
    finally:native.R=R

def whole_comparison():
    groups={m:[x for x in D['games'] if x['mode']==m] for m in ['baseline','optimized']}
    out={m:dict(validGames=len(a),activeElapsedSeconds=sum(x['measurementElapsedSeconds'] for x in a),gameDurationMs=dist([x['durationMs'] for x in a]),
        gcMs=dist([x['analysis']['gcMsReportedTurns'] for x in a]),peakHeap=dist([x['analysis']['maximumReportedHeapBytes'] for x in a]),
        fallbackScopes=sum(x['analysis']['evaluation']['scopesWithFallback'] for x in a),fallbackCalls=sum(x['analysis']['evaluation']['fallbackCheckpointCalls'] for x in a)) for m,a in groups.items()}
    for x in out.values():x['validGamesPerHourActive']=3600*x['validGames']/x['activeElapsedSeconds'] if x['activeElapsedSeconds'] else None
    ratios=[x['activeRatio'] for x in D['pairs']]
    if ratios:
        a=out['baseline'];b=out['optimized'];gain=100*(1-b['activeElapsedSeconds']/a['activeElapsedSeconds']);variant={deck:[x['activeRatio'] for x in D['pairs'] if x['deck']==deck] for deck in native.VARIANTS}
        no_gc_regression=not(b['gcMs']['median']>a['gcMs']['median']*1.05 and b['gcMs']['median']>a['gcMs']['median']+100)
        out.update(activeElapsedGainPercent=gain,throughputGainPercent=100*(b['validGamesPerHourActive']/a['validGamesPerHourActive']-1),pairActiveRatios=ratios,
            passed=gain>=10 and all(x<1 for x in ratios[:3]) and (len(ratios)<=3 or sum(x<1 for x in ratios)>=8) and all(statistics.median(v)<1 for v in variant.values() if v) and b['gameDurationMs']['p95']<=1.05*a['gameDurationMs']['p95'] and b['peakHeap']['median']<=1.05*a['peakHeap']['median'] and no_gc_regression,
            invalidOrTimeoutAttempts=sum(not x['independentlyValidated'] for x in D.get('failedAttempts',[])),percentileCaveat='Three to nine matched pairs are preliminary. No failed attempts are replaced; required-gate failure stops subsequent pairs.')
    D['wholeGame']=out

def execute():
    verify();D['regressions']['initial']=read(R/'initial-gate.json');publish('running-integrity-and-parity')
    h.DATA=D;h.python_regressions()
    unit=R/'offline-tests';unit.mkdir();c.launch(unit,[Path(sys.executable).with_name('python.exe'),'-B','-m','unittest','test_realgame_analysis','test_structured_parity'],api.HERE,h.ENV,120)
    props=['-Dforge.bench.verify=true','-Dforge.bench.repeats=1']
    a=testng('full-parity-baseline','baseline',props,'forge.ai.BusyBoardBenchmark');b=testng('full-parity-optimized','optimized',props,'forge.ai.BusyBoardBenchmark');api.equal(a,b)
    D['regressions']['full64StructuredParity']=True;publish('correctness-gates-passed')
    for n,order in enumerate([['functional','baseline','optimized'],['baseline','optimized','functional'],['optimized','functional','baseline']]):
        for mode in order:measured_test(f'target-{n}-{mode}',mode,[],'forge.ai.CardPropertyBenchmark',True)
        for mode in order:
            rows=D['benchmarks'][f'target-{n}-{mode}']['rows'];ref=D['benchmarks'][f'target-{n}-baseline']['rows'];assert [(x['rep'],x['calls'],x['checksum']) for x in rows]==[(x['rep'],x['calls'],x['checksum']) for x in ref]
        publish('method-round-'+str(n+1)+'-completed')
    target_comparison()
    # Logging is separate from primary observations. Preserve actual compilation outcomes.
    comp={}
    for mode in ['baseline','optimized']:
        label='compiler-diagnostic-'+mode;d=testng(label,mode,['-XX:+UnlockDiagnosticVMOptions','-XX:+LogCompilation','-XX:LogFile='+str(R/label/'jit.xml')],'forge.ai.CardPropertyBenchmark')
        xml=ET.parse(d/'jit.xml');comp[mode]=[x.attrib for x in xml.iter('nmethod') if 'forge.game.card.CardProperty cardHasProperty ' in x.attrib.get('method','')]
    D['compilerEvidence']=comp;publish('target-measurements-completed')
    if not D['targetComparison']['passed'] or not comp['optimized']:
        D['conclusion']='Method CPU/compilation gate failed. Retain the JVM candidate disabled; no matched-game cohort authorised by this evidence.';return
    for n in range(3):
        for mode in (['baseline','optimized'] if n%2==0 else ['optimized','baseline']):
            measured_test(f'timing-{n}-{mode}',mode,['-Dforge.bench.cases=large-four-player,dragon-tokens,large-combat,late-100','-Dforge.bench.paths=block,attack','-Dforge.bench.warmup=2','-Dforge.bench.repeats=4'])
        publish('combat-round-'+str(n+1)+'-completed')
    h.comparison();D['combatComparison']=D.pop('comparison');D['blockerSpecific15PercentGate']=all(D['combatComparison'][key]['medianChangePercent']<=-15 for key in ['large-four-player/block','dragon-tokens/block'])
    combat_ok=all(v['medianChangePercent']<=5 and v['optimized']['wallMs']['p95']<=1.05*v['baseline']['wallMs']['p95'] and v['optimized']['cpuMs']['median']<=1.05*v['baseline']['cpuMs']['median'] and v['optimized']['allocationBytes']['median']<=1.05*v['baseline']['allocationBytes']['median'] for v in D['combatComparison'].values())
    D['performanceImprovementDemonstrated']=False
    D['conclusion']='Target method and deterministic gates passed. Whole-game benefit remains pending; no adoption claim.'
    if not combat_ok:D['conclusion']='Combat regression gate failed; JVM candidate remains disabled. No native comparison launched.';return
    for i,spec in enumerate(PLAN):
        paths={};values={}
        for mode in (['baseline','optimized'] if i%2==0 else ['optimized','baseline']):
            paths[mode],values[mode]=matched_game(spec,mode);publish('native-'+str(spec['seed'])+'-'+mode+'-validated')
        left=paths['baseline'];right=paths['optimized']
        try:
            parity=compare(records(left/'combat.jsonl'),records(right/'combat.jsonl'))
            ga=records(left/f"commander-seed{spec['seed']}.games.jsonl")[0];gb=records(right/f"commander-seed{spec['seed']}.games.jsonl")[0]
            assert all(ga[k]==gb[k] for k in ['winner','draw','finalTurnCount','simulationStatus'])
            assert records(left/f"commander-seed{spec['seed']}.roles.jsonl")==records(right/f"commander-seed{spec['seed']}.roles.jsonl"),'Native role/decision trajectory changed'
        except AssertionError:
            D['differences'].append(dict(seed=spec['seed'],baseline=str(left),candidate=str(right),error=traceback.format_exc()));raise
        D['pairs'].append(dict(**spec,activeRatio=values['optimized']['measurementElapsedSeconds']/values['baseline']['measurementElapsedSeconds'],structuredParity=parity))
        whole_comparison();publish('matched-pairs-'+str(i+1)+'-completed')
        if i==2 and not D['wholeGame']['passed']:
            D['conclusion']='Three-pair whole-game gate failed. No extension; leave the candidate disabled and preserve the preliminary observations.';return
    D['performanceImprovementDemonstrated']=D['wholeGame']['passed']
    D['conclusion']='Matched nine-pair whole-game gate '+('passed provisionally; retain opt-in for engineering review, no automatic promotion.' if D['wholeGame']['passed'] else 'failed; leave disabled.')+' Blocker-specific historical 15% gate: '+str(D['blockerSpecific15PercentGate'])+'. No Phase 8 started.'

def run():
    import msvcrt
    with (R/'supervisor.lock').open('a+b') as own:
        own.write(b'0');own.flush();own.seek(0);msvcrt.locking(own.fileno(),msvcrt.LK_NBLCK,1)
        with (api.H/'work/commander-batches/worker.lock').open('r+b') as worker:
            worker.seek(0);msvcrt.locking(worker.fileno(),msvcrt.LK_NBLCK,1);verify();h.admission(R,api.H)
            with (R/'started.json').open('x') as f:json.dump(dict(pid=os.getpid(),utc=native.now()),f)
            native.STOP=STOP;threading.Thread(target=native.heartbeat,daemon=True).start()
            try:execute();publish('completed');write(R/'completion.json',dict(passed=True,pid=os.getpid(),utc=native.now(),performanceImprovementDemonstrated=D['performanceImprovementDemonstrated'],readyForLargerCohorts=False))
            except BaseException:
                D['failure']=traceback.format_exc();D['performanceImprovementDemonstrated']=False;D['conclusion']='Stopped at a required gate; candidate disabled. No failed attempt scored or replaced.'
                write(R/'failure-run.json',dict(error=D['failure'],utc=native.now()))
                try:publish('failed-preserved')
                except Exception:write(R/'publication-failure.json',dict(error=traceback.format_exc()))
                raise
            finally:STOP.set();write(R/'heartbeat.json',dict(state='finished',pid=os.getpid(),utc=native.now()))

if __name__=='__main__':
    if '--prepare' in sys.argv or '--initial' in sys.argv:
        import msvcrt
        with (api.H/'work/commander-batches/worker.lock').open('r+b') as lock:
            lock.seek(0);msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1)
            prepare() if '--prepare' in sys.argv else initial()
    elif '--publish-blocked' in sys.argv:publish_blocked()
    elif '--freeze' in sys.argv:freeze()
    elif '--verify-only' in sys.argv:verify();print('Frozen Phase 7 inputs verified')
    else:run()
