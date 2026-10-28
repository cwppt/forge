"""Direct four-arm comparisons; never add gains from historical experiments."""
import math,statistics,collections
ARMS=['A','B','C','D']
PAIRS=[('B','A'),('C','A'),('D','A'),('D','C'),('D','B')]
def dist(values):
    v=sorted(values)
    return dict(n=len(v),median=statistics.median(v),p95=v[math.ceil(.95*len(v))-1],total=sum(v)) if v else dict(n=0)
def synthetic(entries):
    grouped=collections.defaultdict(lambda:collections.defaultdict(list))
    for name,entry in entries.items():
        for row in entry['rows']:
            if row.get('warmup'):continue
            grouped[row['case']+'/'+row['path']][entry['arm']].append(row)
    result={}
    for key,arms in grouped.items():
        stats={arm:{label:dist([r[field] for r in rows]) for label,field in [('wallMs','wallMs'),('cpuMs','cpuMs'),('allocationBytes','allocatedBytesCurrentThread'),('gcMs','gcMs')]} for arm,rows in arms.items()}
        comparisons={}
        for candidate,control in PAIRS:
            if candidate not in stats or control not in stats:continue
            comparisons[candidate+' vs '+control]={metric:100*(1-stats[candidate][metric]['median']/stats[control][metric]['median']) if stats[control][metric]['median'] else None for metric in ['wallMs','cpuMs','allocationBytes','gcMs']}
        repeatability={}
        for candidate,control in PAIRS:
            ratios=[]
            for block in sorted({entry.get('block') for entry in entries.values() if entry.get('block') is not None}):
                values={arm:[r['wallMs'] for entry in entries.values() if entry.get('block')==block and entry['arm']==arm for r in entry['rows'] if not r.get('warmup') and r['case']+'/'+r['path']==key] for arm in [candidate,control]}
                if all(values.values()):ratios.append(statistics.median(values[candidate])/statistics.median(values[control]))
            repeatability[candidate+' vs '+control]=dict(blockMedianWallRatios=ratios,allMeasuredBlocksImprove=bool(ratios) and all(x<1 for x in ratios),observedRatioRange=[min(ratios),max(ratios)] if ratios else None,caveat='Four JVM blocks are limited descriptive evidence, not statistical proof.')
        result[key]=dict(arms=stats,directImprovementPercent=comparisons,repeatability=repeatability)
    return result

def whole(games,parity):
    out={}
    for arm in ARMS:
        rows=[x for x in games if x['arm']==arm];valid=[x for x in rows if x.get('valid')]
        active=sum(x.get('measurementElapsedSeconds',0) for x in rows);publication=sum(x.get('publicationSeconds',0) for x in rows)
        cpu=[x.get('cpuSeconds') for x in valid]
        out[arm]=dict(attempted=len(rows),validGames=len(valid),invalidOrTimeouts=sum(not x.get('valid') for x in rows),retries=0,complete=len(valid)==3,
            activeElapsedSeconds=active,allocatedPublicationSeconds=publication,validGamesPerHourActive=3600*len(valid)/active if active else None,
            validGamesPerHourWithAllocatedPublication=3600*len(valid)/(active+publication) if active+publication else None,
            gameDurationMs=dist([x['durationMs'] for x in valid]),cpuSeconds=sum(cpu) if cpu and all(x is not None for x in cpu) else None,
            gcMs=sum(x['analysis']['gcMsReportedTurns'] for x in valid),peakReportedHeapBytes=max((x['analysis']['maximumReportedHeapBytes'] for x in valid),default=None),
            peakReportedProcessWorkingSetBytes=max((x['processPeakBytes'] for x in valid if x.get('processPeakBytes') is not None),default=None),
            aiFallbackScopes=sum(x['analysis']['evaluation']['scopesWithFallback'] for x in valid),
            gameplayParity='reference' if arm=='A' else parity.get('A:'+arm,[]))
    direct={}
    for candidate,control in PAIRS:
        a=out[control];b=out[candidate]
        if not a['complete'] or not b['complete']:direct[candidate+' vs '+control]=dict(status='pending; incomplete matched cohort');continue
        key=':'.join(sorted([candidate,control]));checks=parity.get(key,[])
        direct[candidate+' vs '+control]=dict(activeElapsedReductionPercent=100*(1-b['activeElapsedSeconds']/a['activeElapsedSeconds']),
            throughputImprovementPercent=100*(b['validGamesPerHourActive']/a['validGamesPerHourActive']-1),
            sampledGameplayParity=len(checks)==3 and all(x['passed'] for x in checks),diagnosticOnly=True,
            caution='Three seeds; shared speculative RNG coupling unresolved. Changed trajectories confound speed-only attribution. No adoption inferred.')
    return dict(arms=out,directComparisons=direct,scope='Active includes admission/startup/game/independent validation/analysis; per-game publication separate. CPU includes engine plus validator processes where available, excludes supervisor/classifier CPU. Heap sampled at turn boundaries.')
