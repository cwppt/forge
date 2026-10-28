"""Read-only RNG evidence. Primitive LCG advances are not provider-lookup counts."""
import json,hashlib
from pathlib import Path

def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def records(path,prefix=''):
    with path.open(encoding='utf-8-sig') as stream:return [json.loads(line[len(prefix):]) for line in stream if line.strip() and line.startswith(prefix)]
def advances(before,after,limit=100000):
    state=before
    for ordinal in range(limit+1):
        if state==after:return ordinal
        state=(state*25214903917+11)&((1<<48)-1)
    return None

def historical(root):
    data=read(root/'checkpoint.json');cases={}
    for name,case in data['caseSummaries'].items():
        window=case.get('precedingBudgetWindow',[]);ends=[x for x in window if x['type'] in ['BUDGET_BEGIN','BUDGET_END']]
        lookups=[x for x in window if x['type']=='BUDGET_RNG']
        if not ends:continue
        before=ends[0]['data']['rng']['seed48'];after=ends[-1]['data']['rng']['seed48']
        cases[name]=dict(entrySeed48=before,exitSeed48=after,primitiveStateAdvancesBetweenSnapshots=advances(before,after),
            providerRequests=ends[-1]['data']['providerRequests'],work=ends[-1]['data']['budget']['work'],elapsedNs=ends[-1]['data']['budget']['elapsedNs'],
            sampledProviderRecords=lookups,actualDrawOperationsRecorded=False,
            caveat='LCG distance describes this shared provider interval. Provider requests are not operations/draws and interval state alone does not establish thread ownership.')
    a=cases['observed-baseline-0']['sampledProviderRecords'];b=cases['observed-candidate-0']['sampledProviderRecords']
    index=next((i for i,(x,y) in enumerate(zip(a,b)) if x['data']['rng']!=y['data']['rng'] or x['data']['callers']!=y['data']['callers']),None)
    return dict(phase7aOutcome=data['outcome'],cases=cases,firstDifferingProviderRecordOrdinal=index+1 if index is not None else None,
        commonSampledPrefixLength=index if index is not None else min(len(a),len(b)),
        limitation='Exact original first mismatched draw was not recorded. First differing provider record, if any, is not claimed as first draw. Original Atsushi cause remains unproven.')

def audit_native(directory):
    rows=records(directory/'execution.log','RNG_CASE ');assert rows
    cases={x['case']:x for x in rows};small=cases['global-small'];large=cases['global-large']
    assert small['contextBefore']==large['contextBefore'] and small['contextAfter']==large['contextAfter']
    assert small['speculativeGlobalDraws']>0 and large['speculativeGlobalDraws']>small['speculativeGlobalDraws']
    assert small['nextCommittedDraw']!=large['nextCommittedDraw']
    left=small['actualGlobalDrawTrace'];right=large['actualGlobalDrawTrace']
    mismatch=next(i for i,(x,y) in enumerate(zip(left,right)) if any(x[k]!=y[k] for k in ['bits','value','role','operation']))
    earliest=dict(primitiveOrdinal=mismatch+1,small=left[mismatch],large=right[mismatch],interpretation='First differing fixture consumer: short search commits while longer search continues speculating. Not claimed as the first original full-game divergence.')
    p=cases['private-small'];q=cases['private-large'];assert p['speculativeGlobalDraws']==q['speculativeGlobalDraws']==0 and p['nextCommittedDraw']==q['nextCommittedDraw']
    for row in rows:assert row['contextBefore']==row['contextAfter']
    cancellations=records(directory/'execution.log','RNG_CANCELLATION ');assert len(cancellations)==2 and all(x['workerTerminated'] and x['noPostTerminationDraws'] for x in cancellations)
    distribution=records(directory/'execution.log','RNG_CHOICE_DISTRIBUTIONS ');assert len(distribution)==1
    return dict(classification='A',mechanism='boundedPrediction does not install/restore an RNG; native lifeInDanger draws advance the shared gameplay provider when no thread-local scope exists.',
        earliestProvenFixtureDrawDivergence=earliest,
        smallGlobalSpeculativeDraws=small['speculativeGlobalDraws'],largeGlobalSpeculativeDraws=large['speculativeGlobalDraws'],
        nextCommittedDrawSmall=small['nextCommittedDraw'],nextCommittedDrawLarge=large['nextCommittedDraw'],
        committedContextHash=hashlib.sha256(small['contextBefore'].encode()).hexdigest(),contextsUnchanged=True,
        privateFixtureCommittedDraw=p['nextCommittedDraw'],privateFixtureGlobalDraws=0,seedOwnership='Explicit fixture game seed 573307 and private seed 0x7b12345; not a production seeding scheme.',
        scenarios=[{k:x[k] for k in ['case','isolatedFixture','depth','exit','completedSearch','speculativeGlobalDraws','speculativeLocalDraws','nextCommittedDraw','wallNsIncludingTracing']} for x in rows],
        cancellation=cancellations,nativeModeDistributions=distribution[0],actualDrawEvidence='RNG_CASE actualGlobalDrawTrace/actualLocalDrawTrace in local execution.log; bounded first 128 primitive draws plus next committed draw.',
        performanceLimit='Fixture wall time includes diagnostic draw tracing and intentional wall-budget waits. No production overhead/throughput or faster-JVM result measured.')
