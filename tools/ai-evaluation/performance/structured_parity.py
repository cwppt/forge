"""Ordered comparison with one explicit simultaneous-combat damage exception."""
import collections,json

def key(row):return json.dumps(row,sort_keys=True,separators=(',',':'))
def tokens(rows):
    assert rows and rows[0]['type']=='BEGIN' and rows[-1]['type']=='END','Incomplete trace'
    batches=[];result=[];pending=[];pending_batch=None
    def flush():
        nonlocal pending,pending_batch
        if pending:result.append(('SIMULTANEOUS_DELIVERIES',pending_batch,dict(collections.Counter(map(key,pending)))))
        pending=[];pending_batch=None
    for index,raw in enumerate(rows):
        assert raw['eventIndex']==index,'Missing/duplicate trace event'
        r={k:v for k,v in raw.items() if k!='eventIndex'};t=r['type']
        if t=='BATCH_BEGIN':
            flush();assert r['batch'] is not None and r['batch'] not in [b[0] for b in batches]
            batches.append((r['batch'],r['data']['combat']));result.append(r)
        elif t=='BATCH_END':
            flush();assert batches and r['batch']==batches[-1][0];result.append(r);batches.pop()
        elif t=='DAMAGE' and batches and batches[-1][1]:
            assert r['batch']==batches[-1][0]
            if pending_batch!=r['batch']:flush()
            pending_batch=r['batch'];pending.append(r)
        else:
            flush();assert r['batch']==(batches[-1][0] if batches else None),'Invalid batch scope';result.append(r)
    flush();assert not batches,'Unclosed damage batch';return result

def compare(left,right):
    a=tokens(left);b=tokens(right)
    if a!=b:
        i=next((i for i,(x,y) in enumerate(zip(a,b)) if x!=y),min(len(a),len(b)))
        raise AssertionError(f'Structured rules-sensitive difference at token {i}: {str(a[i:i+1])[:600]} vs {str(b[i:i+1])[:600]}')
    return dict(passed=True,baselineRecords=len(left),candidateRecords=len(right),rawIdentical=left==right,
                exception='Only consecutive DAMAGE deliveries within an explicit simultaneous combat batch. Assignments, batch requests/replacements, triggers, resolution and state remain ordered/exact.')
