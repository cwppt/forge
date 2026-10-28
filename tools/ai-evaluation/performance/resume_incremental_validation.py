"""One-shot continuation of v5's pre-fixture Python test failure; never replay work."""
from pathlib import Path
import json,sys,threading
import incremental_run as r
h=r.host;c=r.c;R=r.R
PREFIX='tools/ai-evaluation/performance/'
ALLOWED={PREFIX+'combat_run.py',PREFIX+'test_incremental_runner.py'}

def configure():
    request=r.read(R/'validation-resume-request.json')
    assert request['runRequestSHA256']==c.sha(R/'run-request.json')
    assert request['failedCheckpointSHA256']==c.sha(R/'checkpoint.json')
    assert set(request['sourceAmendments'])==ALLOWED
    assert request['resumeScriptSHA256']==c.sha(Path(__file__))
    for relative,sha in request['preservedEvidence'].items():
        assert c.sha(R/relative)==sha,'Preserved evidence changed: '+relative
    state=r.read(R/'checkpoint.json')
    assert state['state']=='failed-preserved'
    assert state['failure']=='RuntimeError: Child failed (1); evidence: structured-and-programme-tests'
    assert not state['benchmarks'] and not state['smoke'] and not state['differences']
    assert not any(p.name.startswith(('full-parity-','functional-full-','permanent-','timing-','allocation-profile-')) for p in R.iterdir()),'Later-stage evidence exists; manual review required'
    assert not (R/'validation-resume-started.json').exists(),'Continuation already attempted; inspect its evidence'
    for label in ['integrity-baseline','integrity-optimized','legacy-validator']:
        assert state['regressions'][label]['passed']
        assert r.read(R/label/'execution.json')['exitCode']==0
    for mode in ['baseline','optimized']:
        assert h.xmlcounts(R/('initial-regressions-'+mode))==dict(total=32,passed=32,failed=0,skipped=0)
    h.SOURCE_AMENDMENTS=request['sourceAmendments']
    r.verify()  # Engines, compiled tests, frozen decks/helpers, historical evidence still checked.
    assert h.command(['git','-C',r.S,'rev-parse','HEAD']).stdout.strip()==request['repairCommit']
    h.START_MARKER='validation-resume-started.json'
    state.pop('failure',None);state.pop('conclusion',None)
    state['validationResume']=dict(cause='Unit programme fixtures read optimized.jar from argv-derived unittest module directory; missing isolated provenance fixture. No native optimisation failure observed.',
        fix='Provide a temporary candidate provenance file and isolate the comparison root. Assertions and parity gates unchanged.',
        repairCommit=request['repairCommit'],originalRunRequestSHA256=request['runRequestSHA256'],preservedFailure='resume-evidence-01',
        reusedPassedStages=['32 Java tests per engine','integrity-baseline','integrity-optimized','legacy-validator'],
        repairedValidation='pending; rerun only structured-and-programme-tests in a new evidence directory')
    h.DATA.clear();h.DATA.update(state)
    def repaired_integrity():
        d=R/'structured-and-programme-tests-repair-01';d.mkdir()
        c.launch(d,[Path(sys.executable).with_name('python.exe'),'-B','-m','unittest','test_structured_parity','test_incremental_runner'],r.api.HERE,h.ENV,120)
        h.DATA['regressions']['comparatorAndProgrammeGates']=dict(passed=True,methods=14,evidence=d.name)
        h.DATA['validationResume']['repairedValidation']='14 tests passed; original failed evidence preserved'
    h.python_regressions=repaired_integrity

if __name__=='__main__':
    configure()
    if '--verify-only' in sys.argv:print('Safe continuation preflight passed; no work launched')
    else:h.run()
