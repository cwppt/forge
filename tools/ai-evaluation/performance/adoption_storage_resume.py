"""Finite disk-reserve continuation; original recovery remains immutable."""
from pathlib import Path
import copy,json,os,shutil,sys
sys.dont_write_bytecode=True
import adoption_recovery as r
p=r.p;a=r.a;c=r.c;h=r.h;R=r.R;S=r.S;n=r.n;D=r.D
PRIOR=R.parent/'recovery-01'
ID='20261028-native-performance-adoption-v7c-recovery-02'
FILES=['tools/ai-evaluation/performance/adoption_storage_resume.py','tools/ai-evaluation/performance/adoption_resume_plan.py','tools/ai-evaluation/performance/test_adoption_resume_plan.py']
from adoption_resume_plan import schedule,remaining

def evidence(directory):
    return {f.relative_to(PRIOR).as_posix():c.sha(f) for f in directory.rglob('*') if f.is_file() and 'runtime' not in f.relative_to(directory).parts}

base_verify=r.verify

def verify():
    q=base_verify()
    for name,digest in q['storageContinuation']['priorEvidence'].items():assert c.sha(PRIOR/name)==digest,'Prior recovery evidence changed: '+name
    return q
p.verify=verify;h.verify=verify

def prepare():
    assert not R.exists(),'Never overwrite a continuation'
    q=r.read(PRIOR/'run-request.json');state=r.read(PRIOR/'checkpoint.json')
    pending=remaining(state)
    assert len(state['benchmarks'])==11 and len(pending)==5
    assert '3 GiB disk reserve' in state.get('failure',''),'Only the demonstrated admission failure is authorised'
    # Every frozen source remains unchanged; only new continuation tooling is added.
    for name,digest in q['sourceHashes'].items():assert c.sha(S/name)==digest
    assert p.head()!=q['sourceHead']
    h.admission(PRIOR,a.H)
    saved={}
    for name in ['run-request.json','checkpoint.json','failure-run.json','status.json','heartbeat.json','publication.json','published-blobs.json']:
        saved[name]=c.sha(PRIOR/name)
    for label,entry in state['benchmarks'].items():
        d=PRIOR/label
        assert r.read(d/'execution.json')['exitCode']==0 and h.xmlcounts(d)==dict(total=1,passed=1,failed=0,skipped=0)
        assert a.rows(d)==entry['rows'] and len(entry['rows'])==24,'Incomplete or altered measured rows'
        command=r.read(d/'command.json')
        assert all(flag in command for flag in p.flags(entry['arm']))
        for prop in ['-Dforge.bench.cases=large-four-player,dragon-tokens,large-combat,late-100','-Dforge.bench.paths=block,attack','-Dforge.bench.warmup=1','-Dforge.bench.repeats=3']:assert prop in command
        saved.update(evidence(d))
    for label,arm,block in pending:
        path=PRIOR/label
        assert not path.exists() or not any(path.iterdir()),'Uncheckpointed child evidence; review before resuming'
    for arm in ['A','B','C','D','O']:
        d=PRIOR/('corrected-full-parity-'+arm)
        assert h.xmlcounts(d)==dict(total=1,passed=1,failed=0,skipped=0) and len(a.rows(d))==64
        if arm!='A':a.equal(PRIOR/'corrected-full-parity-A',d)
        saved.update(evidence(d))
    R.mkdir()
    # Copy evidence and binaries, without copying per-child runtime installations.
    for name in ['combined.jar','composition.json','build-provenance.json','initial-gate.json','historical-review.json','rng-final-assessment.json','storage-recovery.json','corrected-control.json','adapter-tests.json','test-class-delta.json','recovery-cause.json']:
        shutil.copy2(PRIOR/name,R/name)
    shutil.copytree(PRIOR/'test-classes',R/'test-classes')
    copyfiles=set(q['verificationEvidence'])|set(saved)
    for name in copyfiles:
        if name in ['run-request.json','checkpoint.json','failure-run.json','status.json','heartbeat.json','publication.json','published-blobs.json']:continue
        target=R/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(PRIOR/name,target)
    q=copy.deepcopy(q);q['runId']=ID;q['sourceHead']=p.head()
    q['sourceHashes'].update({name:c.sha(S/name) for name in FILES})
    q['storageContinuation']=dict(priorRun=str(PRIOR),priorEvidence=saved,remainingBlocks=[x[0] for x in pending],completedBlocksReplayed=False,completedGames=0,sourceEngineChanged=False)
    r.write(R/'run-request.json',q)
    r.write(R/'inherited-checkpoint.json',state)
    q['storageContinuation']['inheritedCheckpointSHA256']=c.sha(R/'inherited-checkpoint.json')
    r.write(R/'run-request.json',q)
    verify();print('Verified 11 preserved blocks and all parity gates; five blocks remain')

def execute():
    q=verify();state=r.read(R/'inherited-checkpoint.json')
    assert c.sha(R/'inherited-checkpoint.json')==q['storageContinuation']['inheritedCheckpointSHA256']
    pending=remaining(state);D.clear();D.update(copy.deepcopy(state));D['runId']=ID;D.pop('failure',None)
    D['recommendation']='Continuing only unfinished diagnostic measurements after restored disk reserve; no engine promotion.'
    D['storageContinuation']=q['storageContinuation']
    for arm in p.ARMS:D['arms'][arm].update(classification='EXPERIMENTAL',reason='All strict fixture gates passed; unfinished diagnostic comparisons resumed after disk admission stop.')
    p.publish('resumed-five-remaining-benchmark-blocks')
    for label,arm,block in pending:
        d=p.testng(label,arm,['-Dforge.bench.cases=large-four-player,dragon-tokens,large-combat,late-100','-Dforge.bench.paths=block,attack','-Dforge.bench.warmup=1','-Dforge.bench.repeats=3'],'forge.ai.BusyBoardBenchmark')
        D['benchmarks'][label]=dict(arm=arm,block=block,rows=a.rows(d));p.publish('storage-resume-'+label+'-completed')
    assert len(D['benchmarks'])==16
    p.publish('synthetic-four-arm-completed');p.cohort()

base_report=n.report
def report():
    return '# Disk-reserve continuation\n\nThe preceding [recovery-01](https://github.com/cwppt/MTG-deck-lists/tree/main/forge-performance/reports/20261028-native-performance-adoption-v7c/recovery-01) stopped safely at the 3 GiB C: reserve after 11 completed benchmark blocks. Its failure, checkpoint and measurements remain unchanged. This continuation revalidates their hashes, successful exits, complete rows, effective flags and all 64-case strict fixture gates. Only five unfinished benchmark blocks and the original three diagnostic seeds per arm run here. All engines, budgets, workloads, seeds and comparator are unchanged. User temporary storage and historical Codex indexes/Maven cache now reside on D:; the reserve remains enforced on actual output volumes. No new candidate or production cohort is introduced.\n\n'+base_report()
n.report=report;n.RUN=ID;n.DEST='forge-performance/reports/20261028-native-performance-adoption-v7c/recovery-02';n.URL='https://github.com/cwppt/MTG-deck-lists/tree/main/'+n.DEST
p.execute=execute
if __name__=='__main__':
    if '--prepare' in sys.argv:
        import msvcrt
        with (a.H/'work/commander-batches/worker.lock').open('r+b') as lock:
            lock.seek(0);msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1);prepare()
    elif '--verify-only' in sys.argv:verify();print('Continuation inputs verified')
    else:p.run()
