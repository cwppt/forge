"""Audit only saved v2 evidence. Never launch native games or relax trajectory gates."""
from pathlib import Path
import collections,copy,hashlib,json,math,statistics,subprocess,sys,xml.etree.ElementTree as ET
sys.dont_write_bytecode=True
import cost_run
runner=cost_run.runner;api=cost_run.api;R=api.R

def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def canonical(x):return json.dumps(x,sort_keys=True,separators=(',',':'))

def audit():
    q=runner.verify();d=read(R/'checkpoint.json')
    assert d['state']=='failed-preserved' and d['differences']==[dict(seed=572101,kind='gameplay-trajectory')]
    assert not (R/'completion.json').exists()
    hashes={}
    def evidence(p):hashes[p.relative_to(R).as_posix()]=sha(p)
    for name in ['run-request.json','checkpoint.json','failure-run.json','baseline.jar','optimized.jar']:evidence(R/name)
    for name,entry in d['benchmarks'].items():
        folder=R/name;assert read(folder/'execution.json')['exitCode']==0
        assert [x for x in api.rows(folder) if not x.get('warmup')]==entry['rows']
        assert sha(folder/'execution.log')==entry['logSHA256']
        for file in ['execution.json','command.json','execution.log']:evidence(folder/file)
    runner.DATA=copy.deepcopy(d);runner.comparison();assert runner.DATA['comparison']==d['comparison']
    api.equal(R/'full-parity-baseline',R/'full-parity-optimized')
    for mode in ['baseline','optimized']:
        counts=runner.xmlcounts(R/('initial-regressions-'+mode));assert counts==dict(total=34,passed=34,failed=0,skipped=0)
        evidence(R/('initial-regressions-'+mode)/'results/testng-results.xml')
        integrity=R/('integrity-'+mode);assert read(integrity/'execution.json')['exitCode']==0
        assert 'Ran 7 tests' in (integrity/'execution.log').read_text() and (integrity/'execution.log').read_text().rstrip().endswith('OK')
        evidence(integrity/'execution.log')
        for name in ['normal','draw','timeout','interrupted','uncooperative','stack','engine']:
            p=R/('worker-probes-'+mode)/name;code=read(p/'execution.json')['exitCode']
            if name in ['normal','draw']:assert code==0 and (p/'final-outcome').exists()
            else:
                failure=read(p/'failure.jsonl');assert not (p/'final-outcome').exists()
                assert failure['valid'] is False and failure['winner'] is None and failure['draw'] is None
                assert code==(130 if name=='interrupted' else 70 if name=='engine' else 124)
                assert failure['simulationStatus']==('INTERRUPTED' if name=='interrupted' else 'ENGINE_FAILED' if name=='engine' else 'TIMEOUT')
                evidence(p/'failure.jsonl')
    legacy=R/'legacy-validator';assert read(legacy/'execution.json')['exitCode']==0
    assert 'Ran 18 tests' in (legacy/'execution.log').read_text();evidence(legacy/'execution.log')
    smoke=[]
    for row in d['smoke']:
        p=R/f"smoke-{row['mode']}-{row['seed']}";prefix=p/f"commander-seed{row['seed']}"
        assert read(p/'execution.json')['exitCode']==0 and read(p/'independent-validator/execution.json')['exitCode']==0
        result=read(Path(str(prefix)+'.games.jsonl'));assert result['valid'] is True and result['simulationStatus']=='VALID'
        assert not result['timedOut'] and not result['engineFailures'] and not result['simulationError']
        assert result['winner']==row['winner'] and result['finalTurnCount']==row['turns'] and result['draw']==row['draw']
        report=read(p/'validation.json');assert len(report['runs'])==1 and len(report['runs'][0]['games'])==1
        assert report['runs'][0]['games'][0]==result
        for suffix in ['games.jsonl','gamelog.jsonl','roles.jsonl','decisions.jsonl']:evidence(Path(str(prefix)+'.'+suffix))
        for f in ['validation.json','classification.json','execution.json','independent-validator/execution.json']:evidence(p/f)
        smoke.append(dict(row,decisionAuditRecords=len(Path(str(prefix)+'.decisions.jsonl').read_text().splitlines())))
    assert len(smoke)==4 and read(R/'smoke-pair-572100.json')['gameplayTrajectoriesIdentical']
    assert not (R/'smoke-baseline-572102').exists() and not (R/'smoke-optimized-572102').exists()
    diff=read(R/'trajectory-difference-572101.json');detail={}
    for key in ['roles','gameLog']:
        left=diff['baseline'][key];right=diff['optimized'][key];assert len(left)==len(right)
        positions=[i for i,(a,b) in enumerate(zip(left,right)) if a!=b]
        detail[key]=dict(recordCount=len(left),differentPositions=positions,records=[dict(index=i,baseline=left[i],candidate=right[i]) for i in positions])
    assert detail['roles']['differentPositions']==[1156,1157]
    a=diff['baseline']['roles'];b=diff['optimized']['roles'];assert a[1156]==b[1157] and a[1157]==b[1156]
    assert [a[i]['type'] for i in [1156,1157]]==['CARD_DAMAGE','PLAYER_DAMAGE']
    assert a[1156]['rawTurn']==a[1157]['rawTurn']==53 and a[1156]['phase']==a[1157]['phase']=='COMBAT_DAMAGE'
    assert detail['gameLog']['differentPositions']==[1619,1621,1632,1633]
    # Preserve exact differing records for human review; do not sort/accept gameplay events.
    evidence(R/'trajectory-difference-572101.json')
    metrics={}
    for key,v in d['comparison'].items():
        metrics[key]=dict(baseline=v['baseline'],candidate=v['optimized'],medianChangePercent=v['medianChangePercent'],jvmRoundMedianRatios=v['jvmRoundMedianRatios'],exploratoryJvmBlockRatio95=v['exploratoryJvmBlockRatio95'])
    out=dict(auditPassed=True,utc=runner.now(),scope='Saved evidence audit; original strict trajectory gate remains failed',
        sourceExperimentHead=q['sourceHead'],baselineSHA256=q['baselineSHA256'],candidateSHA256=q['treatmentSHA256'],
        regressions=d['regressions'],measurements=metrics,completedSmoke=smoke,trajectoryReview=detail,
        pendingSmokeSeeds=[572102],strictTrajectoryParity=False,readyForLargerCohorts=False,
        recommendation='Retain candidate off by default for research; revise trajectory verification before adoption. Modest synthetic timing/allocation reductions do not establish whole-game improvement.',
        evidenceSHA256=hashes)
    target=R/'audit-20261028';target.mkdir(exist_ok=False);runner.write(target/'review.json',out)
    original=(R/'public/REPORT.md').read_text(encoding='utf-8')
    text='\n\n## Final saved-evidence review (2026-10-28)\n\n'
    text+='The original supervisor PID 35232 has exited. It stopped intentionally at the strict seed-572101 trajectory gate after all benchmarks completed. No benchmarks or valid games were replayed. The original failed checkpoint and raw records remain unchanged. This review audits the saved evidence; it does not declare the experiment fully passed. Seed 572102 remains unrun because the gate was not waived.\n\n'
    text+='### Recomputed measurements\n\n12 observations per engine/workload, two excluded warmups in each of three alternating-order JVM blocks. Values below are baseline → candidate.\n\n'
    text+='| Blocker workload | Median ms | p95 ms | CPU median ms | Current-thread allocation GiB | Median latency reduction |\n|---|---:|---:|---:|---:|---:|\n'
    for key,v in metrics.items():
        if not key.endswith('/block'):continue
        a=v['baseline'];b=v['candidate']
        text+=f"| {key} | {a['wallMs']['median']:.2f} → {b['wallMs']['median']:.2f} | {a['wallMs']['p95']:.2f} → {b['wallMs']['p95']:.2f} | {a['cpuMs']['median']:.2f} → {b['cpuMs']['median']:.2f} | {a['allocationBytes']['median']/2**30:.3f} → {b['allocationBytes']['median']/2**30:.3f} | {-v['medianChangePercent']:.2f}% |\n"
    text+='\nAll four blocker workloads were faster in every JVM block; the primary workloads improved 4.32% and 2.65% in pooled median latency. These are modest measured synthetic reductions, below the predeclared 5% gate on both primary workloads. Three JVM blocks and exploratory bootstrap intervals limit confidence/generalisation. A failed adoption gate is not proof of zero effect. Current-thread allocation excludes other threads; JFR weights are sampled. CPU, p95/p99, GC, attacker timings and exact evidence hashes are in review.json and the original measurements.json.\n\n'
    text+='The separate counter pass recorded 338,496 adjust calls in each arm; the enabled candidate skipped 37,725,600 static-list rebuilds. Its JFR now prominently samples CardState.mayHaveStaticAbilityMode (55 nearest-Forge CPU samples), suggesting source checking itself is a remaining cost; this is a profiling lead, not proof of the next gain.\n\n'
    text+='### Correctness and stopped smoke\n\n34 Java methods per engine, 40 deterministic workload comparisons, both seven-mode process failure/integrity suites and the 18-method legacy validator suite passed. Saved failure journals, absence of failed final outcomes, engine/input hashes, raw timing rows and four independent smoke validations were checked again offline. The four completed games are valid, with matching winner and turn count within each seed pair. Whole-game duration: seed 572100 10,074 → 10,068 ms; seed 572101 29,688 → 31,138 ms. These two pairs do not demonstrate whole-game improvement.\n\n'
    text+='Seed 572100 passed the original exact trajectory comparison. Seed 572101 has 1,165 role records and 1,651 retained game-log records per arm. Only two adjacent role records swap (indices 1156/1157): the same 7 damage from Scourge of the Throne to Scourge of the Throne, and the same 7 damage from Dragon Token to the defending player, turn 53 COMBAT_DAMAGE. Four game-log positions differ: attacker display order, corresponding blocker display order, and these two damage lines. All subsequent records match. Exact differences are published in review.json; no event has been dropped or sorted to turn this into a pass.\n\n'
    text+='Source inspection: GameEventAttackersDeclared.convertMap and GameEventBlockersDeclared.convertMap use HashMultimap; GameLogFormatter iterates those unordered collections. CardDamageTable is backed by HashBasedTable. This explains why presentation/event order can vary and is consistent with this trace. It does not prove AI-policy equivalence or authorise arbitrary reordering of damage/trigger events. Strict native trajectory parity remains unresolved. Native-only decisions.jsonl files contain zero records, so they cannot independently certify every AI choice. Legal-pair matrices are not exhaustive legal-action sets.\n\n'
    text+='### Recommendation\n\nRetain the source branch and evidence, keep the fast path off by default, and revise verification before adoption. Next: add structured attacker/blocker assignments and damage-batch identifiers to native telemetry, with baseline repeatability tests and tests that detect changed targets, damage amounts and trigger order. Classify only proven presentation-order differences; preserve rule-sensitive event order. Then resume only seed 572102 if that gate can be justified. No engine merge, promotion, production cohort or new native process was performed by this review.\n'
    (target/'REPORT.md').write_text(original+text,encoding='utf-8')
    print(json.dumps(dict(auditPassed=True,pendingSmokeSeeds=[572102],strictTrajectoryParity=False,report=str(target/'REPORT.md')),indent=2))

def publish():
    import base64
    target=R/'audit-20261028';owned=read(R/'published-blobs.json');published={}
    for name in ['review.json','REPORT.md']:
        endpoint='repos/'+runner.REPO+'/contents/'+runner.DEST+'/'+name
        prior=runner.gh(endpoint);body=dict(message='Review saved cost-v2 measurements and unresolved trajectory gate',branch='main',content=base64.b64encode((target/name).read_bytes()).decode())
        if name=='REPORT.md':
            assert prior.returncode==0 and json.loads(prior.stdout)['sha']==owned[name]['sha'],'Report changed externally; refuse overwrite'
            body['sha']=owned[name]['sha']
        else:assert prior.returncode!=0 and '404' in prior.stderr,'Review already exists or preflight failed'
        result=runner.gh(endpoint,'PUT',body);assert result.returncode==0,result.stderr
        value=json.loads(result.stdout);published[name]=dict(sha=value['content']['sha'],commit=value['commit']['sha'])
    runner.write(target/'publication.json',dict(utc=runner.now(),url=runner.URL,files=published,originalPublicationJournalPreserved=True))
    print(json.dumps(published,indent=2))

if __name__=='__main__':
    if '--publish' in sys.argv:publish()
    else:audit()
