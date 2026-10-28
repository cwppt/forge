"""Phase 7A: six bounded seed-573307 diagnostics, never historical replacements."""
from pathlib import Path
import collections,hashlib,json,os,shutil,sys,threading,time,traceback,zipfile
sys.dont_write_bytecode=True
import realgame_run as n
from realgame_analysis import records,analyse_game
from structured_parity import tokens,compare
a=n.api;c=n.c;h=n.h;e=n.e;R=n.R;S=n.S
OLD=a.EX/'rules-performance-v7-20261028/validation-01'
BB=Path('C:/Users/Chris/.m2/repository/net/bytebuddy/byte-buddy/1.18.14/byte-buddy-1.18.14.jar')
AGENT=R/'choice-agent-v2.jar'
BASE_FLAGS=a.flags('baseline');RUN='20261028-native-rules-parity-v7a'
CASES=[('observed-baseline-0','baseline',True),('observed-candidate-0','optimized',True),('observed-baseline-1','baseline',True),('observed-candidate-1','optimized',True),('plain-baseline-0','baseline',False),('plain-baseline-1','baseline',False)]
DATA=dict(schemaVersion=1,runId=RUN,state='prepared',games=[],diagnostics=[],candidateDisabled=True,comparisons={},outcome='C: pending diagnostic evidence',recommendation='Leave JVM candidate disabled. Original Phase 7 parity failure remains a failure; no merge, promotion or Phase 8.')
ACTIVE={};STOP=threading.Event()
def read(p):return n.read(p)
def write(p,v):return c.write(p,v)
def digest(value):return hashlib.sha256(json.dumps(value,sort_keys=True).encode()).hexdigest()
def flags(mode):return BASE_FLAGS+(['-XX:-DontCompileHugeMethods'] if mode=='optimized' else [])
a.flags=flags
def original_audit():
    traces={m:records(OLD/m/'game-573307/combat.jsonl') for m in ['baseline','optimized']};ts={m:tokens(v) for m,v in traces.items()}
    i=next(i for i,(x,y) in enumerate(zip(ts['baseline'],ts['optimized'])) if x!=y)
    states={m:next(x['data']['state'] for x in reversed(v[:i]) if isinstance(x,dict) and 'state' in x.get('data',{})) for m,v in ts.items()}
    raw_scopes={m:[x for x in records(OLD/m/'game-573307/execution.log','AI_EVAL_DECISION ') if x['turn']<=40] for m in traces}
    scopes={m:[{k:v for k,v in x.items() if k not in ['id','elapsedMs','longestCandidateMs','longestCandidate']} for x in rows] for m,rows in raw_scopes.items()}
    first=next((j for j,(x,y) in enumerate(zip(scopes['baseline'],scopes['optimized'])) if x!=y),None)
    return dict(firstDifferentStructuredToken=i,observablePrefixEqual=ts['baseline'][:i]==ts['optimized'][:i],firstDifference={m:ts[m][i] for m in ts},
        lastPublicStateSHA256={m:digest(v) for m,v in states.items()},publicStateEqual=states['baseline']==states['optimized'],
        controller3=states['baseline']['players']['3'],earliestScopeDifferenceIndex=first,
        earliestScopeDifference={m:scopes[m][first] for m in scopes} if first is not None else None,
        earliestScopeTimingMs={m:raw_scopes[m][first]['elapsedMs'] for m in scopes} if first is not None else None,
        provenance='Same frozen JAR; candidate JVM adds only -XX:-DontCompileHugeMethods. No original RNG state or complete hidden/continuous state was recorded.',
        limitations='Public prefix equality is not proof of identical hidden state/RNG. Scope differences may be observational or budget-related and do not alone explain the mode. Longest-candidate identity is a timing diagnostic and excluded from the scope-work comparison, not from any gameplay comparator.')

def verify():
    q=read(R/'run-request.json');assert q['sourceHead']==n.head()
    for key,root in [('sourceHashes',S),('originalEvidence',OLD),('helperHashes',a.H),('compiledHashes',R)]:
        for p,sha in q[key].items():assert c.sha(root/p)==sha,'Frozen input changed: '+p
    assert c.sha(a.REFERENCE)==a.REFERENCE_SHA and c.sha(AGENT)==q['agentSHA256']
    assert c.sha(R/'fixture-gate.json')==q['fixtureGateSHA256'] and read(R/'fixture-gate.json')['passed']
    assert q['baselineFlags']==flags('baseline') and q['candidateFlags']==flags('optimized') and q['cases']==[list(x) for x in CASES]
    return q

def prepare():
    assert not AGENT.exists();R.mkdir(parents=True,exist_ok=True);h.admission(R,a.H)
    assert c.sha(a.REFERENCE)==a.REFERENCE_SHA
    shutil.copytree(OLD/'test-classes',R/'test-classes')
    for name in ['baseline.jar','optimized.jar']:shutil.copy2(a.REFERENCE,R/name)
    classes=R/'agent-classes';classes.mkdir();sources=list((S/'tools/ai-evaluation/performance/choice-probe').rglob('*.java'))
    p=a.java([e.JAVA.with_name('javac.exe'),'--release','17','-cp',str(BB)+';'+str(a.REFERENCE),'-d',classes,*sources]);(R/'agent-compile.log').write_text(p.stdout+p.stderr);assert p.returncode==0,p.stderr
    with zipfile.ZipFile(AGENT,'w',zipfile.ZIP_DEFLATED) as jar,zipfile.ZipFile(BB) as bb:
        jar.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\r\nPremain-Class: forge.parity.ChoiceAgent\r\nCan-Retransform-Classes: false\r\n\r\n')
        for name in bb.namelist():
            if name.upper()=='META-INF/MANIFEST.MF' or name.upper().endswith(('.SF','.RSA','.DSA')):continue
            jar.writestr(name,bb.read(name))
        for f in classes.rglob('*.class'):jar.write(f,f.relative_to(classes).as_posix())
    p=a.java([e.JAVA.with_name('javac.exe'),'--release','17','-cp',';'.join(map(str,[R/'test-classes',a.REFERENCE,a.NG])),'-d',R/'test-classes',S/'forge-gui-desktop/src/test/java/forge/ai/AtsushiChoiceFixture.java']);(R/'fixture-compile.log').write_text(p.stdout+p.stderr);assert p.returncode==0,p.stderr
    write(R/'original-audit.json',original_audit())
    for klass in ['forge.ai.ability.CharmAi','forge.ai.PlayerControllerAi','forge.ai.SpellAbilityAi','forge.ai.ability.TokenAi','forge.ai.ability.DigAi','forge.util.MyRandom']:
        out=h.command([e.JAVA.with_name('javap.exe'),'-c','-p','-classpath',a.REFERENCE,klass]);assert out.returncode==0;(R/(klass+'.bytecode.txt')).write_text(out.stdout,encoding='utf-8')
    print('Diagnostic agent and targeted native fixture compiled; original divergence audited read-only')

def agent_flags(directory):return ['--add-opens=java.base/java.util=ALL-UNNAMED','-javaagent:'+str(AGENT),'-Dforge.choice.output='+str(directory/'choice.jsonl')]
def agent_evidence(directory):
    rows=records(directory/'choice.jsonl');assert rows[-1]['type']=='AGENT_END' and not rows[-1]['data']['eventCapReached']
    assert not any(x['type']=='ERROR' for x in rows)
    transformed={x['data'] for x in rows if x['type']=='TRANSFORM'}
    assert {'forge.util.MyRandom','forge.ai.ability.CharmAi','forge.ai.SpellAbilityAi','forge.ai.PlayerControllerAi','forge.ai.AiEvaluationDiagnostics'}<=transformed,transformed
    return rows

def fixtures():
    matrices={}
    for label,mode,observed in [('fixture2-baseline-plain','baseline',False),('fixture2-baseline-observed','baseline',True),('fixture2-candidate-plain','optimized',False),('fixture2-candidate-observed','optimized',True)]:
        d=R/label;props=agent_flags(d)+['-Dforge.choice.scopeTurn=1','-Dforge.choice.scopeSeat=0'] if observed else []
        a.testng(label,mode,props,'forge.ai.AtsushiChoiceFixture',600)
        matrices[label]=records(d/'execution.log','ATSUSHI_FIXTURE ');assert len(matrices[label])==32
        if observed:
            evidence=agent_evidence(d);assert any(x['type']=='BUDGET_BEGIN' for x in evidence) and any(x['type']=='BUDGET_END' for x in evidence)
    assert all(v==matrices['fixture2-baseline-plain'] for v in matrices.values()),'Controlled native choices differ across JVM/observer configurations'
    write(R/'fixture-gate.json',dict(passed=True,nativeChoicesPerConfiguration=32,configurations=4,methodsPerConfiguration=2,budgetObservationPreservesRng=True,repeatedSeeds=16,modeDistribution=dict(collections.Counter(x['mode'] for x in matrices['fixture2-baseline-plain'])),
        instrumentationReturnPreservation=True,limitation='Constructed native trigger fixture with fixed local RNG, not replay of original hidden state. Full-game observer effects remain possible.'))

def freeze():
    assert read(R/'fixture-gate.json')['passed'] and not (R/'run-request.json').exists()
    source=h.command(['git','-C',S,'ls-files']).stdout.splitlines()
    original=['run-request.json','checkpoint.json','failure-run.json','baseline.jar','optimized.jar']
    for mode in ['baseline','optimized']:
        d=OLD/mode/'game-573307'
        original += [f.relative_to(OLD).as_posix() for f in d.iterdir() if f.is_file()]
    q=read(OLD/'run-request.json')
    spec=next(x for x in q['plan'] if x['seed']==573307)
    write(R/'run-request.json',dict(runId=RUN,sourceHead=n.head(),sourceBranch='codex/commander-rules-performance-v7',engineSHA256=a.REFERENCE_SHA,agentSHA256=c.sha(AGENT),
        sourceHashes={p:c.sha(S/p) for p in source if p.startswith('tools/ai-evaluation/performance/') or p.endswith('AtsushiChoiceFixture.java')},
        originalEvidence={p:c.sha(OLD/p) for p in original},helperHashes=q['helperHashes'],compiledHashes={p.relative_to(R).as_posix():c.sha(p) for p in (R/'test-classes').rglob('*.class')},
        fixtureGateSHA256=c.sha(R/'fixture-gate.json'),cases=CASES,spec=spec,pod=q['pod'],deckHashes=q['deckHashes'],baselineFlags=flags('baseline'),candidateFlags=flags('optimized'),
        protocol='Four separately instrumented diagnostic games (two each JVM), then two uninstrumented baseline diagnostics. One fixed seed only. No historical replacements, performance scoring, changed AI budgets, acceptance criteria, comparator or engine promotion.',
        instrumentation='Atsushi choice scopes emit hidden-zone snapshots, RNG seed/provider, shuffled order, native willingness and budget state. The preceding turn-40 seat-1 COMBAT_BEGIN scope also emits budget/RNG endpoints and up to 64 RNG-provider call sites. No RNG draws/reseeding/results changed. Global getRandom provider-request counts are not RNG draw counts. Reflection requires add-opens on observed diagnostics only.',
        limitations='Observer work can affect wall-clock budgets. Snapshots contain ordered major zones and selected card properties, not a complete checkpoint of every engine dependency. No original hidden state/RNG reconstruction is claimed.'))
    verify();print('Phase 7A six-run diagnostic plan frozen')

def choice_summary(directory,observed):
    trace=records(directory/'combat.jsonl')
    actual=[x for x in trace if x['type']=='STACK_ADD' and x['turn']==40 and 'When Atsushi' in str(x['data']) and x['data'].get('host')==355]
    result=dict(actualTurn40StackChoices=actual,originalChoicePointReached=bool(actual))
    if not observed:return result
    rows=agent_evidence(directory);begins=[x for x in rows if x['type']=='CHOICE_BEGIN' and x['data'].get('parent') is None and x['data']['turn']==40 and x['data']['phase']=='COMBAT_DAMAGE' and x['data']['host']==355 and not x['data']['rng']['threadLocal']]
    result['precedingBudgetWindow']=[x for x in rows if x['type'] in ['BUDGET_BEGIN','BUDGET_END','BUDGET_RNG']]
    result['precedingBudgetRngEndpoints']=[dict(type=x['type'],thread=x['thread'],rng=x['data']['rng'],budget=x['data']['budget']) for x in rows if x['type'] in ['BUDGET_BEGIN','BUDGET_END']]
    result['choiceScopes']=[]
    for row in begins:
        data=row['data'];ids={data['id']}
        for b in rows:
            if b['type']=='CHOICE_BEGIN' and b['data'].get('parent') in ids:ids.add(b['data']['id'])
        events=[]
        for event in rows:
            if event.get('scope') not in ids:continue
            if event['type']=='CHOICE_BEGIN':
                event=dict(type='CHOICE_CONTEXT',scope=event['scope'],data={k:v for k,v in event['data'].items() if k not in ['players','rngProviderRequestsByThread']})
            events.append(event)
        before={k:v for k,v in data.items() if k not in ['players','rngProviderRequestsByThread']}
        requests=data['rngProviderRequestsByThread'];before['rngProviderRequestSummary']=dict(total=sum(requests.values()),threads=len(requests))
        player=next(p for p in data['players'] if p['id']==data['activatingPlayer'])
        relevant=dict(life=player['life'],zoneCounts={z:len(v) for z,v in player['zones'].items()},
            hand=[x['name'] for x in player['zones']['Hand']],libraryTopTwo=[x['name'] for x in player['zones']['Library'][:2]])
        proofs=[]
        for context in events:
            if context['type']!='CHOICE_CONTEXT' or context['data']['kind']!='CHARM':continue
            for order in events:
                if order['type']=='SHUFFLED_ORDER' and order['scope']==context['scope']:
                    proofs.append(shuffle_proof(context['data']['rng'].get('seed48'),order['data']['rngAfterShuffle'].get('seed48'),[x['api'] for x in order['data']['options']]))
        active_budgets=[before['budgetBeforeSnapshot']]+[x['data']['budgetBeforeSnapshot'] for x in events if x['type']=='CHOICE_CONTEXT']+[x['data']['budget'] for x in events if x['type']=='CHOICE_END']
        result['choiceScopes'].append(dict(before=before,hiddenZoneSnapshotSHA256=digest(data['players']),relevantPlayer=relevant,events=events,shuffleEvidence=proofs,
            anyActiveEvaluationBudgetObserved=any(x['active'] for x in active_budgets),
            fullSnapshotEvidence='choice.jsonl; ordered zones and selected properties only, not a full engine checkpoint'))
    return result

def shuffle_proof(seed_before,seed_after,order):
    if seed_before is None or seed_after is None or sorted(order)!=['Dig','Token']:return dict(available=False)
    expected=(seed_before*25214903917+11)&((1<<48)-1)
    j=(2*(expected>>(48-31)))>>31
    predicted=['Token','Dig'] if j==0 else ['Dig','Token']
    return dict(available=True,oneJavaLcgAdvanceMatches=expected==seed_after,expectedNextInt2=j,predictedOrder=predicted,observedOrder=order,orderMatches=predicted==order,
        caveat='Two native Atsushi options in script order, one java.util.Random nextInt(2); no RNG draw performed by this analysis.')

def aggregate():
    DATA['performanceHistory']=dict(schemaVersion=1,parentReport='20261028-native-rules-performance-v7',originalAcceptanceUnchanged=True,originalFailureReclassified=False,
        candidateDisabled=True,diagnosticGamesNotScored=True,performanceGain=None,cumulativeGain=None,originalAuditSHA256=c.sha(R/'original-audit.json'))

def report():
    return '# Phase 7A — Atsushi gameplay-parity investigation\n\n'+f"State **{DATA['state']}**, supervisor PID {os.getpid()}, updated {n.now()}.\n\n"+ \
        'The Phase 7 candidate remains disabled. Original token-1699 failure is preserved and not reclassified. No Phase 8, merge or promotion.\n\n'+ \
        '## Original divergence and code path\n\n```json\n'+json.dumps(read(R/'original-audit.json'),indent=2)+'\n```\n\n'+ \
        'Native path: CharmEffect.makeChoices -> PlayerControllerAi.chooseModeForAbility -> cached choice or AiController.doTrigger -> SpellAbilityAi -> CharmAi.checkApiLogic. CharmAi shuffles modes using MyRandom, then accepts heuristic-willing modes in shuffled order, with mandatory-trigger fallback passes. TokenAi also makes a probabilistic acceptance check from the AI profile. DigAi uses library/hand/phase and targeting conditions. This is a randomized heuristic, not a proof either mode is optimal. Frozen bytecode excerpts are retained locally.\n\n'+ \
        '## Focused tests and separate diagnostics\n\n```json\n'+json.dumps(dict(fixture=read(R/'fixture-gate.json'),cases=DATA.get('caseSummaries',{}),comparisons=DATA['comparisons']),indent=2)+'\n```\n\n'+ \
        'Only seed 573307 is replayed, in separately named diagnostic attempts. Each completed game requires failure classification, independent result validation and no provider attempts. No attempt replaces or scores an original game. Same-configuration comparisons remain as strict as cross-configuration comparisons; differences are findings, never sorted away.\n\n'+ \
        '## Outcome, AI quality and remaining evidence\n\n'+DATA['outcome']+'\n\n'+DATA['recommendation']+'\n\n'+ \
        'Before the original choice, controller 3 had life 20 and 15 battlefield permanents, including Nim Deathmantle, one untapped Treasure and tapped lands. Treasure can provide immediately usable mana; exile can provide cards through the next turn. These facts do not rank the options: original library/hand/RNG state was not captured, and generic heuristics do not establish optimality. Diagnostic hidden zones permit case-specific inspection but are not retroactive original evidence. No demonstrated quality loss is inferred from the option alone.\n\n'+ \
        'Instrumentation observes raw Random seed state without advancing it, choice order, willingness, major ordered zones and budget fields. Global provider-request counts are not random-draw counts. Original diagnostics cannot exclude prior RNG drift, hidden-state or time-budget differences. Diagnostic snapshots are incomplete engine checkpoints and can perturb wall-clock budgets. The controlled fixture tests observer preservation in fixed state, not zero overhead in full games.\n\n'+ \
        ('Stopped reason:\n```\n'+DATA['failure']+'\n```\n\n' if DATA.get('failure') else '')+ \
        '[Manifest](manifest.json) · [Measurements](measurements.json) · [History](performance-history.json) · [Source](https://github.com/cwppt/forge/tree/codex/commander-rules-performance-v7). Evidence root `'+str(R)+'`.\n'

n.DATA=DATA;n.RUN=RUN;n.DEST='forge-performance/reports/'+RUN;n.URL='https://github.com/cwppt/MTG-deck-lists/tree/main/'+n.DEST;n.aggregate=aggregate;n.report=report;n.ACTIVE=ACTIVE
publish=n.publish

def play(label,mode,observed):
    verify();folder=R/label;folder.mkdir();shutil.copy2(a.REFERENCE,folder/'engine.jar');shutil.copytree(OLD/'decks',folder/'decks')
    for name,digest_ in verify()['deckHashes'].items():assert c.sha(folder/'decks'/name)==digest_
    n.R=folder;n.FLAGS=flags(mode)+(agent_flags(folder/'game-573307') if observed else []);n.verify=verify
    try:
        row=n.play(verify()['spec']);directory=folder/row['directory'];row['case']=label;row['mode']=mode;row['observed']=observed;DATA['games'].append(row)
        summary=choice_summary(directory,observed);write(directory/'choice-summary.json',summary);DATA.setdefault('caseSummaries',{})[label]=summary
    finally:n.R=R

def comparisons():
    DATA['comparisons']={}
    for left,right in [('observed-baseline-0','observed-baseline-1'),('observed-candidate-0','observed-candidate-1'),('observed-baseline-0','observed-candidate-0'),('observed-baseline-1','observed-candidate-1'),('plain-baseline-0','plain-baseline-1')]:
        if left not in DATA.get('caseSummaries',{}) or right not in DATA['caseSummaries']:continue
        try:out=compare(records(R/left/'game-573307/combat.jsonl'),records(R/right/'game-573307/combat.jsonl'))
        except AssertionError as ex:out=dict(passed=False,firstDifference=str(ex))
        def signature(label):return [(x['turn'],x['phase'],x['data'].get('host'),x['data'].get('controller'),x['data']['description']) for x in DATA['caseSummaries'][label]['actualTurn40StackChoices']]
        out['atsushiStackChoicesEqual']=signature(left)==signature(right)
        scope_pairs=[]
        for x,y in zip(DATA['caseSummaries'][left].get('choiceScopes',[]),DATA['caseSummaries'][right].get('choiceScopes',[])):
            scope_pairs.append(dict(scopeKinds=[x['before']['kind'],y['before']['kind']],recordedZoneSnapshotsEqual=x['hiddenZoneSnapshotSHA256']==y['hiddenZoneSnapshotSHA256'],rngAtScopeEntryEqual=x['before']['rng']==y['before']['rng'],
                rngAtEntry={left:x['before']['rng'],right:y['before']['rng']},budgetAtEntry={left:x['before']['budgetBeforeSnapshot'],right:y['before']['budgetBeforeSnapshot']},
                anyDirectBudgetObserved={left:x['anyActiveEvaluationBudgetObserved'],right:y['anyActiveEvaluationBudgetObserved']},
                orders={left:[e['data'] for e in x['events'] if e['type']=='SHUFFLED_ORDER'],right:[e['data'] for e in y['events'] if e['type']=='SHUFFLED_ORDER']}))
        out['observedChoiceComparisons']=scope_pairs
        out['precedingBudgetRngEndpoints']={label:DATA['caseSummaries'][label].get('precedingBudgetRngEndpoints') for label in [left,right]}
        DATA['comparisons'][left+' vs '+right]=out
    plain=DATA['comparisons'].get('plain-baseline-0 vs plain-baseline-1')
    if plain and not plain['passed']:
        DATA['outcome']='B: Existing full-game nondeterminism reproduced with two unchanged uninstrumented baseline runs at the same seed. Whether this is the identical Atsushi divergence: '+str(not plain['atsushiStackChoicesEqual'])+'. The original earliest RNG/hidden-state cause is not retroactively proven.'
        DATA['recommendation']='Keep the JVM candidate disabled and the original parity failure unchanged. Before matched deck/performance claims, diagnose the earliest same-engine RNG/action divergence, isolate advisory RNG streams and decision-boundary state, then repeat a separately approved verification plan with strict rules/state validation. Do not weaken the comparator.'
    elif len(DATA['comparisons'])==5:
        DATA['outcome']='C: Cause of the original divergence remains unresolved. Native mode logic is a randomized heuristic; focused fixed-state/RNG results and bounded repeat diagnostics are published, but they do not reconstruct the original hidden/RNG state or prove a configuration-specific defect.'
        DATA['recommendation']='Leave the JVM candidate disabled. Inspect the captured turn-40 RNG/hidden-zone snapshots and native willingness records for the first prior RNG/budget divergence. A full original decision checkpoint or earlier RNG draw provenance is required before attributing the original difference or revising validation.'

def run():
    import msvcrt
    with (R/'supervisor.lock').open('a+b') as own:
        own.write(b'0');own.flush();own.seek(0);msvcrt.locking(own.fileno(),msvcrt.LK_NBLCK,1)
        with (a.H/'work/commander-batches/worker.lock').open('r+b') as worker:
            worker.seek(0);msvcrt.locking(worker.fileno(),msvcrt.LK_NBLCK,1);verify();h.admission(R,a.H)
            with (R/'started.json').open('x') as f:json.dump(dict(pid=os.getpid(),utc=n.now()),f)
            n.STOP=STOP;threading.Thread(target=n.heartbeat,daemon=True).start()
            try:
                publish('running-bounded-choice-diagnostics')
                for label,mode,observed in CASES:
                    play(label,mode,observed);comparisons();publish(label+'-validated')
                publish('completed');write(R/'completion.json',dict(passed=True,pid=os.getpid(),candidateDisabled=True,utc=n.now(),reportUrl=n.URL))
            except BaseException:
                DATA['failure']=traceback.format_exc();write(R/'failure-run.json',dict(error=DATA['failure'],utc=n.now()))
                try:publish('failed-preserved')
                except Exception:write(R/'publication-failure.json',dict(error=traceback.format_exc()))
                raise
            finally:STOP.set();write(R/'heartbeat.json',dict(state='finished',pid=os.getpid(),utc=n.now()))

if __name__=='__main__':
    if '--prepare' in sys.argv or '--fixtures' in sys.argv:
        import msvcrt
        with (a.H/'work/commander-batches/worker.lock').open('r+b') as lock:
            lock.seek(0);msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1)
            prepare() if '--prepare' in sys.argv else fixtures()
    elif '--freeze' in sys.argv:freeze()
    elif '--verify-only' in sys.argv:verify();print('Frozen Phase 7A inputs verified')
    else:run()
