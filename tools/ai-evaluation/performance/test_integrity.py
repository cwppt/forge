import copy, importlib.util, json, os, subprocess, tempfile, time, unittest
from pathlib import Path
from report_integrity import load, validate, correct
from recovery import plan

ROOT=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('cohort',ROOT/'tools/ai-evaluation/parallel-v4/Run-ValidCommanderCohort.py')
cohort=importlib.util.module_from_spec(spec);spec.loader.exec_module(cohort)

class OutcomeTests(unittest.TestCase):
    def test_recovery_preserves_valid_games_and_retries_only_confirmed_infrastructure(self):
        jobs=[('v',10),('v',11)]
        done=dict(variant='v',runSeed=10,attemptIndex=0,valid=True,independentlyValidated=True)
        interrupted=dict(variant='v',runSeed=11,attemptIndex=0,valid=False,
                         simulationStatus='INTERRUPTED',ownedWorkerAbsent=True)
        self.assertEqual(plan(jobs,[done,interrupted]),[dict(variant='v',runSeed=11,attemptIndex=1)])
        self.assertEqual(plan([('v',10)],[done]),[])
        for change in [dict(simulationStatus='TIMEOUT'),dict(ownedWorkerAbsent=False)]:
            with self.assertRaises(ValueError):plan(jobs,[done,{**interrupted,**change}])
        with self.assertRaises(ValueError):plan(jobs,[done,done])
    def test_failure_journal_wins_over_exit_zero_and_claimed_result(self):
        with tempfile.TemporaryDirectory() as tmp:
            d=Path(tmp)
            for status in ['TIMEOUT','INTERRUPTED','UNSTOPPED_WORKER','ENGINE_FAILED']:
                p=d/'commander-seed123.games.jsonl.failures.jsonl'
                p.write_text(json.dumps(dict(gameId=1,gameIndex=0,runSeed=123,valid=False,
                    simulationStatus=status,winner='Contradictory winner',draw=True))+'\n')
                for code in [0,124,130,None]:
                    a=cohort.classify_batch(d,123,1,False,code)[0]
                    self.assertFalse(a['valid']);self.assertIsNone(a['winner']);self.assertIsNone(a['draw'])
                    self.assertEqual(a['simulationStatus'],status)
                self.assertTrue(p.exists())
    def test_missing_or_contradictory_terminal_rejected(self):
        game=dict(gameId=1,gameIndex=0,runSeed=1,finalTurnCount=1,valid=True,
                  draw=False,winner=None,timedOut=False,simulationError=None)
        errors=cohort.telemetry_errors(game,[],[],[],False)
        self.assertIn('missing-winner',errors);self.assertIn('missing-game-outcome',errors)
        game.update(draw=True,winner='Impossible')
        self.assertIn('winner-draw',cohort.telemetry_errors(game,[],[],[],False))

class ReportTests(unittest.TestCase):
    def setUp(self):
        self.experiments=Path(os.environ['FORGE_EXPERIMENTS'])
        self.labels=load(Path(__file__).with_name('variant-labels.json'))
    def test_both_frozen_experiments_and_all_deck_hashes(self):
        for name in ['lathliss-r-rebuild-ab-20261026','lathliss-r-throne-20261026']:
            with self.subTest(name=name),tempfile.TemporaryDirectory() as tmp:
                root=self.experiments/name
                p=correct(root,Path(tmp)/'correction')
                self.assertEqual(len(p['games']),150)
                fixed=load(Path(tmp)/'correction/summary.corrected.json')
                wins=[x['wins'] for x in fixed['variants'].values()]
                self.assertEqual(wins,[18,13,16] if 'rebuild-ab' in name else [16,18,14])
    def test_reject_missing_duplicate_mismatched_arms_and_outcomes(self):
        root=self.experiments/'lathliss-r-throne-20261026'
        s=load(root/'public/summary.json');m=load(root/'public/manifest.json');labels=self.labels[m['runId']]
        old={v:s['variants'][v]['label'] for v in labels}
        import itertools
        s['pairedComparisons']={labels[b]+' minus '+labels[a]:s['pairedComparisons'][old[b]+' minus '+old[a]]
            for a,b in itertools.combinations(m['productionVariants'],2)}
        for v in labels:s['variants'][v]['label']=labels[v]
        for row in s['games']:row['build']=labels[row['variant']]
        validate(s,m,labels)
        for mutation in ['missing','duplicate','wrong','count','comparison','label','manifest-duplicate','missing-hash','game-label']:
            a=copy.deepcopy(s);b=copy.deepcopy(m);l=dict(labels)
            if mutation=='missing':a['variants'].pop(next(iter(a['variants'])))
            if mutation=='duplicate':a['games'].append(a['games'][0])
            if mutation=='wrong':a['games'][0]['variant']='Different'
            if mutation=='count':a['variants'][next(iter(a['variants']))]['wins']+=1
            if mutation=='comparison':a['pairedComparisons']={}
            if mutation=='label':l[next(iter(l))]=list(l.values())[1]
            if mutation=='manifest-duplicate':b['productionVariants'][1]=b['productionVariants'][0]
            if mutation=='missing-hash':b['deckHashes'].pop(b['productionVariants'][0]+'.dck')
            if mutation=='game-label':a['games'][0]['build']='Wrong'
            with self.subTest(mutation=mutation),self.assertRaises(AssertionError):validate(a,b,l)

class WorkerProcessTests(unittest.TestCase):
    def test_disposable_workers_never_export_failed_outcomes(self):
        config=load(os.environ['FORGE_WORKER_TEST_CONFIG'])
        for mode in ['normal','draw','timeout','interrupted','uncooperative','stack','engine']:
            d=Path(config['output'])/mode;d.mkdir(parents=True,exist_ok=False)
            cmd=[config['java'],*config['opts'],'-cp',config['classpath'],'forge.view.SimulationWorkerProbe',mode,str(d)]
            started=time.monotonic()
            with (d/'console.log').open('w') as log:
                p=subprocess.run(cmd,cwd=config['cwd'],env=config['env'],stdout=log,stderr=subprocess.STDOUT,timeout=120,
                                 creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
            (d/'execution.json').write_text(json.dumps(dict(exitCode=p.returncode,wallSeconds=time.monotonic()-started)))
            if mode in ['normal','draw']:
                self.assertEqual(p.returncode,0,(d/'console.log').read_text()[-1500:]);self.assertTrue((d/'final-outcome').exists())
            else:
                self.assertIn(p.returncode,[124,130],(d/'console.log').read_text()[-1500:])
                self.assertFalse((d/'final-outcome').exists())
                failure=load(d/'failure.jsonl');self.assertFalse(failure['valid']);self.assertIsNone(failure['winner']);self.assertIsNone(failure['draw'])
                self.assertEqual(failure['simulationStatus'],'INTERRUPTED' if mode=='interrupted' else 'ENGINE_FAILED' if mode=='engine' else 'TIMEOUT')
                if mode in ['uncooperative','stack']:
                    size=(d/'mutations').stat().st_size;time.sleep(.1);self.assertEqual((d/'mutations').stat().st_size,size)
                if mode=='stack':self.assertTrue((d/'resolution-entered').exists())

if __name__=='__main__':unittest.main()
