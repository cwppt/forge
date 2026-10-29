"""Pure checkpoint scheduling rules; no process launch or parity-policy changes."""
ARMS=['A','B','C','D']
def schedule():
    return [(f'timing-{block}-{arm}',arm,block) for block in range(4) for arm in ARMS[block:]+ARMS[:block]]
def remaining(state):
    assert state['state']=='failed-preserved'
    assert not state.get('games') and not state.get('failedAttempts'),'Native attempts require separate review'
    for arm in ['A','B','C','D','O']:assert state['regressions']['structured64-'+arm]['passed']
    plan=schedule();done=list(state['benchmarks'])
    assert done==[x[0] for x in plan[:len(done)]],'Completed measurements must be an exact rotation-order prefix'
    for label,arm,block in plan[:len(done)]:
        row=state['benchmarks'][label];assert row['arm']==arm and row['block']==block and row['rows']
    return plan[len(done):]

def validate_rows(rows):
    expected={(case,path,rep,rep<0) for case in ['large-four-player','dragon-tokens','large-combat','late-100'] for path in ['block','attack'] for rep in [-1,0,1,2]}
    assert len(rows)==len(expected) and {(r['case'],r['path'],r['rep'],r['warmup']) for r in rows}==expected,'Incomplete warmup/measurement matrix'
