import json
import threading
import time
import tempfile
from types import SimpleNamespace
import unittest
from pathlib import Path
from unittest.mock import patch
import CommanderBatch as api
import ParallelCommanderPool as pool
from test_commander_batch import valid_fixture

class PoolTests(unittest.TestCase):
    def setUp(self):
        snapshot=patch.object(api,'process_snapshot',return_value={})
        snapshot.start(); self.addCleanup(snapshot.stop)

    def setup_batch(self, root, parallel=5, target=3, budget=6):
        for name,_,_ in api.VARIANTS: (root/name).mkdir()
        return dict(seed=100,target=target,maxAttempts=budget,parallelGames=parallel)

    def exercise(self, parallel, fail_first=False):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); request=self.setup_batch(root,parallel)
            lock=threading.Lock(); running=0; maximum=0; seeds=[]; paths=[]; finished=[]
            barrier=threading.Barrier(5) if parallel==5 else None
            def engine(job,batch,request,active,stop,api):
                nonlocal running,maximum
                with lock:
                    running+=1; maximum=max(maximum,running); seeds.append(job['runSeed']); paths.append(job['rawDirectory']); first=len(seeds)<=5
                active.update(job,pid=123,phase='RUNNING')
                if barrier and first: barrier.wait(timeout=5)
                time.sleep(.01*(5-job['runSeed']%5))
                directory=Path(job['rawDirectory']); valid_fixture(directory,job['runSeed'])
                code=7 if fail_first and job['runSeed']==100 else 0
                api.write(directory/'exit.json',dict(code=code))
                item=api.classify(directory,job['runSeed'],code)[0]
                item.update(attemptIndex=job['attemptIndex'],rawDirectory=str(directory))
                with lock: running-=1; finished.append(job['runSeed'])
                return item
            with patch.object(api,'verify'):
                pool.run_batch(root,request,api,engine)
            self.assertEqual(maximum,parallel)
            self.assertEqual(len(seeds),len(set(seeds))); self.assertEqual(len(paths),len(set(paths)))
            self.assertEqual(len(seeds),10 if fail_first else 9)
            for name,_,_ in api.VARIANTS:
                ledger=api.cohort.load_jsonl(root/name/'attempts.jsonl')
                self.assertEqual(sum(a['valid'] for a in ledger),3)
                self.assertEqual([a['attemptIndex'] for a in ledger], sorted(a['attemptIndex'] for a in ledger))
            self.assertEqual(api.read(root/'status.json')['activeGameCount'],0)
            if parallel==5: self.assertNotEqual(finished,sorted(finished))
    def test_one_worker_compatibility(self): self.exercise(1)
    def test_five_workers_unique_and_out_of_order_exact_targets(self): self.exercise(5)
    def test_single_failure_gets_fresh_seed(self): self.exercise(5,True)

    def test_attempt_cap(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); request=self.setup_batch(root,5,1,2)
            def engine(job,*args): return pool.failure(job,7,'PROCESS_FAILED','fixture failure')
            with patch.object(api,'verify'), self.assertRaisesRegex(RuntimeError,'Attempt limit'):
                pool.run_batch(root,request,api,engine)
            self.assertEqual(sum(len(api.cohort.load_jsonl(root/n/'attempts.jsonl')) for n,_,_ in api.VARIANTS),6)

    def test_duplicate_completion_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); request=self.setup_batch(root)
            job=pool.reserve(root,request,0,0,api); item=pool.failure(job,7,'PROCESS_FAILED','fixture')
            state=dict(attempts=[],batches=[],output=root/'R2')
            pool.accept(state,job,item,request,api)
            with self.assertRaisesRegex(RuntimeError,'Duplicate'): pool.accept(state,job,item,request,api)
            self.assertEqual(len(api.cohort.load_jsonl(root/'R2/attempts.jsonl')),1)

    def test_multiple_inflight_recovery_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); request=self.setup_batch(root)
            for index in range(3):
                for ordinal in range(2):
                    job=pool.reserve(root,request,index,ordinal,api)
                    directory=Path(job['rawDirectory'])
                    if ordinal==0:
                        valid_fixture(directory,job['runSeed']); api.write(directory/'exit.json',dict(code=0))
                    else: (directory/f"commander-seed{job['runSeed']}.log").write_text('Simulation Attempt: gameIndex=0 gameId=1 runSeed='+str(job['runSeed'])+'\n')
            with patch.object(api,'assert_child_stopped'), patch.object(api,'independent_validation',return_value=dict(status='PASS')):
                states=pool.recover(root,request,api)
                again=pool.recover(root,request,api)
            for name,_,_ in api.VARIANTS:
                self.assertEqual(states[name]['attempts'],again[name]['attempts'])
                self.assertEqual(sum(a['valid'] for a in again[name]['attempts']),1)
                self.assertEqual(again[name]['nextOrdinal'],2)
                self.assertEqual(again[name]['attempts'][1]['simulationStatus'],'INTERRUPTED')
            self.assertEqual(len((root/'reconciliation.jsonl').read_text().splitlines()),6)

    def test_bad_range_rejected_before_ledger_write(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); request=self.setup_batch(root)
            pool.reserve(root,request,0,0,api)
            (root/'R3/seed-100').mkdir()
            with patch.object(api,'assert_child_stopped'), self.assertRaisesRegex(RuntimeError,'outside'):
                pool.recover(root,request,api)
            self.assertFalse((root/'R2/attempts.jsonl').exists())

    def test_live_orphan_blocks_recovery(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); request=self.setup_batch(root); pool.reserve(root,request,0,0,api)
            with patch.object(api,'assert_child_stopped',side_effect=RuntimeError('Orphan process')), self.assertRaisesRegex(RuntimeError,'Orphan'):
                pool.recover(root,request,api)
            self.assertFalse((root/'R2/attempts.jsonl').exists())

    def test_empty_reservation_crash_consumes_budget(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); request=self.setup_batch(root); (root/'R2/seed-100').mkdir()
            with patch.object(api,'assert_child_stopped'):
                states=pool.recover(root,request,api)
            self.assertEqual(states['R2']['nextOrdinal'],1)
            self.assertEqual(states['R2']['attempts'][0]['simulationStatus'],'INTERRUPTED')

    def test_frozen_concurrency_and_preferences(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); store=root/'work/commander-batches'; decks=root/'work/commander-smoke-decks'; decks.mkdir(parents=True)
            jar=root/'forge-gui-desktop/target/forge-gui-desktop-2.0.16-SNAPSHOT-jar-with-dependencies.jar'; jar.parent.mkdir(parents=True); jar.write_text('fixture')
            for filename in [v[1] for v in api.VARIANTS]+api.OPPONENTS: (decks/filename).write_text('fixture')
            args=SimpleNamespace(seed=1000,games=1,max_attempts=3,max_parallel=5,publish=False,resume=False,timeout=30)
            lands={f:n for _,f,n in api.VARIANTS}
            with patch.object(api,'ROOT',root),patch.object(api,'STORE',store),patch.object(api,'git',return_value='fixture'),patch.object(api,'land_count',side_effect=lambda p:lands[p.name]),patch.dict(api.os.environ,APPDATA=str(root/'appdata')):
                api.prepare(args)
                request=api.read(store/'seed1000/request.json'); self.assertEqual(request['parallelGames'],5)
                self.assertEqual(request['seedRanges']['R3'],[1003,1005])
                args.resume=True; args.max_parallel=1
                with self.assertRaisesRegex(RuntimeError,'concurrency'): api.prepare(args)
                args.max_parallel=5; api.prepare(args)
                (store/'seed1000/preferences-snapshot/unexpected').write_text('changed')
                with self.assertRaisesRegex(RuntimeError,'Preference snapshot'): api.verify(request)

    def test_profile_and_runtime_isolation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); (root/'forge-gui/res').mkdir(parents=True)
            batch=root/'batch'; batch.mkdir(); (batch/'preferences-snapshot').mkdir()
            (batch/'preferences-snapshot/forge.preferences').write_text('fixture=true')
            views=[]
            with patch.object(api,'ROOT',root):
                for i in range(2):
                    directory=batch/str(i); directory.mkdir()
                    cwd,env,home,temp=pool.isolated_runtime(directory,batch,api)
                    self.assertEqual((cwd/'res').resolve(),(root/'forge-gui/res').resolve())
                    self.assertTrue((directory/'runtime/user/preferences/forge.preferences').exists())
                    self.assertNotIn('JAVA_TOOL_OPTIONS',env)
                    views.append((cwd,home,temp,env['APPDATA']))
            self.assertNotEqual(views[0],views[1])

    def test_invalid_independent_exports_never_count(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); request=self.setup_batch(root); request.update(java='fake',jar='fake',heapGiB=4,activeProcessorCount=2,timeoutSeconds=30)
            job=pool.reserve(root,request,0,0,api); directory=Path(job['rawDirectory']); valid_fixture(directory,100)
            class Process:
                pid=123; returncode=0
                def poll(self): return 0
            with patch.object(pool,'isolated_runtime',return_value=(directory,{},directory,directory)), patch.object(pool.subprocess,'Popen',return_value=Process()), patch.object(api,'completed_attempt',side_effect=RuntimeError('bad independent exports')):
                item=pool.execute(job,root,request,pool.ActiveGames(),threading.Event(),api)
            self.assertFalse(item['valid']); self.assertEqual(item['simulationStatus'],'TELEMETRY_FAILED')

if __name__=='__main__': unittest.main()
