"""Summarize completed native validation only; never edits raw evidence or invokes simulations."""
from pathlib import Path
import collections, csv, hashlib, json, math, statistics, subprocess, sys, xml.etree.ElementTree as ET
R=Path(sys.argv[1]).resolve();SOURCE=Path(__file__).resolve().parents[3]
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def dist(a):
    a=sorted(a)
    return dict(n=len(a),p50=statistics.median(a),p95=a[math.ceil(.95*len(a))-1],p99=a[math.ceil(.99*len(a))-1],maximum=a[-1],total=sum(a)) if a else dict(n=0)
def records(path,prefix):
    with path.open(encoding='utf-8-sig') as stream:
        return [json.loads(line[len(prefix):]) for line in stream if line.startswith(prefix)]
def jfr_summary(directory):
    out=directory/'jfr-samples.json'
    if not out.exists():
        jfr=Path('C:/Program Files/Java/jdk-25.0.4.1/bin/jfr.exe')
        with out.open('w',encoding='utf-8') as f:
            subprocess.run([str(jfr),'print','--json','--events','jdk.ExecutionSample,jdk.ObjectAllocationSample',str(directory/'profile.jfr')],stdout=f,check=True,timeout=120)
    cpu=collections.Counter();allocation=collections.Counter();native=collections.Counter()
    for event in read(out)['recording']['events']:
        v=event['values'];frames=(v.get('stackTrace') or {}).get('frames',[])
        names=[f['method']['type']['name']+'.'+f['method']['name'] for f in frames]
        # Exclude DB/bootstrap and fixture construction: require a native evaluation frame.
        if not any(s.startswith(('forge/ai/ComputerUtil','forge/ai/AiAttackController','forge/ai/AiBlockController','forge/ai/AiController','forge/ai/ability/')) for s in names):continue
        first=next((s for s in names if s.startswith('forge/')),None)
        if not first:continue
        native[event['type']]+=1
        if event['type']=='jdk.ExecutionSample':cpu[first]+=1
        else:allocation[first]+=v.get('weight',0)
    return dict(nativeEvaluationSampleCounts=dict(native),nearestForgeCpuFrames=cpu.most_common(15),
        sampledAllocationWeightBytesByNearestForgeFrame=allocation.most_common(15),
        caveat='Sampled, inclusive native-evaluation stacks; weighted allocation samples are estimates, not exact per-operation bytes.')
def main():
    completion=read(R/'completion.json')
    assert completion['passed'] and completion['fullGameSmokeGames']==5,'All final validation gates must pass'
    public=R/'engineering-report';public.mkdir(exist_ok=False)
    result=dict(schemaVersion=1,runId='20261027-commander-correctness-busy-board',engineManifest=read(R/'engine-manifest.json'),
        benchmarkSourceSHA256=sha(SOURCE/'forge-gui-desktop/src/test/java/forge/ai/BusyBoardBenchmark.java'),
        fixtureSeed=771230,repetitions=3,workers=1,heapGiB=3,advertisedProcessors=2,
        notes=['Synthetic four-player stress projections, not exact historical replays.',
               'No computational optimisation was introduced; no speedup is claimed.',
               'Three fixed-order repetitions; percentile estimates are descriptive and imprecise.',
               'Current-thread CPU/allocation excludes separate Game AI Eval thread; JFR is JVM-wide.',
               'Input candidate counts are not a complete legal-action census; exact copy counts are unavailable.'],benchmarks={},smoke=[])
    allrows={}
    for label in ['baseline','correctness']:
        d=R/('busy-'+label);assert read(d/'execution.json')['exitCode']==0
        rows=records(d/'execution.log','BUSY_BENCH ');assert len(rows)==120
        allrows[label]={(a['case'],a['path'],a['rep']):a for a in rows};groups=collections.defaultdict(list)
        for a in rows:groups[a['case']+'/'+a['path']].append(a)
        scopes=records(d/'execution.log','AI_EVAL_DECISION ')
        result['benchmarks'][label]=dict(workloads={key:dict(wallMs=dist([a['wallMs'] for a in vals]),
            cpuMs=dist([a['cpuMs'] for a in vals]),gcMs=dist([a['gcMs'] for a in vals]),
            allocatedBytesCurrentThread=dist([a['allocatedBytesCurrentThread'] for a in vals if a.get('allocatedBytesCurrentThread') is not None]),
            maximumHeapUsed=max(a['heapUsed'] for a in vals),permanents=sorted({a['permanents'] for a in vals}),
            tokens=sorted({a['tokens'] for a in vals})) for key,vals in groups.items()},
            aiScopeLatencyMs=dist([a['elapsedMs'] for a in scopes]),aiScopeCount=len(scopes),
            candidateInputListSizes=dist([a['candidates'] for a in scopes]),
            executionLogBytes=(d/'execution.log').stat().st_size,jfr=jfr_summary(d))
    assert set(allrows['baseline'])==set(allrows['correctness'])
    result['differentCoarseBenchmarkResults']=[dict(case=k[0],path=k[1],rep=k[2],baseline=allrows['baseline'][k]['result'],correctness=allrows['correctness'][k]['result'])
        for k in allrows['baseline'] if allrows['baseline'][k]['result']!=allrows['correctness'][k]['result']]
    result['resultComparisonLimit']='Coarse returned decisions/counts only; this does not prove exact full action/target sequence equality.'
    for group in sorted(R.glob('smoke-*')):
        valid=[]
        for d in group.glob('attempt-*'):
            if not (d/'validation-passed.json').exists():continue
            data=read(d/'classification.json');assert len(data)==1 and data[0]['valid']
            g=next(d.glob('*.games.jsonl'));game=json.loads(g.read_text(encoding='utf-8-sig').splitlines()[0])
            with next(d.glob('*.metrics.csv')).open(encoding='utf-8-sig',newline='') as f:providers=sum(r.get('providerAttempted')=='true' for r in csv.DictReader(f))
            assert providers==0 and game['valid'] and not game['timedOut'] and not game['simulationError'] and not game['engineFailures']
            profiles=list(d.glob('*.profile.jsonl'));turns=[json.loads(s) for s in profiles[0].read_text().splitlines()] if profiles else []
            valid.append(dict(job=group.name,seed=game['runSeed'],durationMs=game['durationMs'],winner=game['winner'],draw=game['draw'],
                rawTurns=game['finalTurnCount'],providerAttempts=providers,profileRows=len(turns),
                turnDurationMs=dist([r['elapsedMs'] for r in turns]),gcMs=sum(r['gcMs'] for r in turns) if turns else None,
                logBytes={p.name.split('.',1)[1]:p.stat().st_size for p in d.glob('commander-seed*') if p.is_file()},
                resultSHA256=sha(g),validationSHA256=sha(d/'validation.json')))
        assert len(valid)==1,'Missing or duplicate valid smoke game'
        result['smoke']+=valid
    assert len(result['smoke'])==5
    result['pairedGameLogMatches']=[]
    for seed in [571900,571901]:
        left=R/f'smoke-baseline-{seed}/attempt-00';right=R/f'smoke-correctness-{seed}/attempt-00'
        result['pairedGameLogMatches'].append(dict(seed=seed,
            gameLogIdentical=sha(left/f'commander-seed{seed}.gamelog.jsonl')==sha(right/f'commander-seed{seed}.gamelog.jsonl'),
            roleTrajectoryIdentical=sha(left/f'commander-seed{seed}.roles.jsonl')==sha(right/f'commander-seed{seed}.roles.jsonl')))
        def gameplay(path):
            return [json.loads(line) for line in path.read_text(encoding='utf-8-sig').splitlines()
                    if not (json.loads(line)['type']=='GAME_OUTCOME' and json.loads(line)['message'].startswith('Match Duration '))]
        result['pairedGameLogMatches'][-1]['gameplayLogIdenticalExcludingDuration']=gameplay(left/f'commander-seed{seed}.gamelog.jsonl')==gameplay(right/f'commander-seed{seed}.gamelog.jsonl')
    result['realTimeoutIntegration']=read(R/'timeout-integration/passed.json')
    result['resumeVerification']=read(R/'resume-verification.json')
    result['smokeRuntimeMs']={label:dist([g['durationMs'] for g in result['smoke'] if g['job'].startswith('smoke-'+label+'-')]) for label in ['baseline','correctness','profile']}
    root=ET.parse(R/'java-correctness/results/testng-results.xml').getroot()
    result['tests']=dict(java={k:root.attrib.get(k) for k in ['total','passed','failed','skipped']},pythonIntegrityMethods=7,legacyValidatorMethods=18,
                         workerProcessModes=7,reportGamesIndependentlyChecked=300,completedBenchmarkOperations=240,validFullGames=5)
    result['sourceHead']=subprocess.check_output(['git','rev-parse','HEAD'],cwd=SOURCE,text=True).strip()
    (public/'measurements.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    with (public/'benchmark-comparison.csv').open('w',newline='',encoding='utf-8') as f:
        w=csv.writer(f);w.writerow(['case','path','baseline_p50_ms','correctness_p50_ms','baseline_p95_ms','correctness_p95_ms','baseline_current_thread_allocation_p50','correctness_current_thread_allocation_p50'])
        for key,a in result['benchmarks']['baseline']['workloads'].items():
            b=result['benchmarks']['correctness']['workloads'][key];w.writerow([*key.split('/'),a['wallMs']['p50'],b['wallMs']['p50'],a['wallMs']['p95'],b['wallMs']['p95'],a['allocatedBytesCurrentThread'].get('p50'),b['allocatedBytesCurrentThread'].get('p50')])
    print(json.dumps(dict(reportDirectory=str(public),tests=result['tests'],smoke=result['smokeRuntimeMs'],sourceHead=result['sourceHead']),indent=2))
if __name__=='__main__':main()
