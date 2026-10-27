from pathlib import Path
from types import SimpleNamespace
import json
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import CommanderBatch as api
import ExperimentContract as contract
import ParallelCommanderPool as pool


class ExperimentTests(unittest.TestCase):
    def setup(self,root):
        decks=root/'work/commander-smoke-decks';decks.mkdir(parents=True)
        r3='[metadata]\nName=R3 fixture\n[Commander]\n1 Rivaz of the Claw\n[Main]\n1 Reforge the Soul\n1 Mountain\n'
        for filename in ['Rivaz-R2.dck','Rivaz-R3.dck','Rivaz-R3-38L.dck']+api.OPPONENTS:(decks/filename).write_text(r3)
        (decks/'Rivaz-R4-A.dck').write_text(r3.replace('Name=R3 fixture','Name=R4-A fixture').replace('1 Reforge the Soul','1 Rakdos Charm'))
        spec=dict(candidateSet='R3-R4-A',aiProfile='bounded-advisory',maxParallelGames=5,gamesPerVariant=2,timeoutSeconds=300,
            candidates=[dict(name=n,file=f,sha256=api.sha(decks/f),landCount=37,validGames=2) for n,f in [('R3','Rivaz-R3.dck'),('R4-A','Rivaz-R4-A.dck')]],
            opponents=[dict(file=f,sha256=api.sha(decks/f)) for f in api.OPPONENTS])
        path=root/'spec.json';api.write(path,spec)
        args=SimpleNamespace(experiment_spec=str(path),candidate_set='R3-R4-A',ai_profile='bounded-advisory',games=2,max_parallel=5,timeout=300,seed=4500,max_attempts=6,publish=False,resume=False)
        return decks,spec,path,args

    def test_preview_exact_candidates_and_all_mismatches(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);decks,spec,path,args=self.setup(root)
            with patch.object(api,'ROOT',root),patch.object(api,'land_count',return_value=37):
                _,_,preview=contract.check(args,api,emit=False)
                self.assertEqual([c['name'] for c in preview['candidates']],['R3','R4-A']);self.assertEqual(preview['totalValidGames'],4)
                for key,bad in [('max_parallel',16),('games',100),('ai_profile','standard'),('timeout',180),('candidate_set','Legacy')]:
                    original=getattr(args,key);setattr(args,key,bad)
                    with self.assertRaisesRegex(RuntimeError,'mismatch'):contract.check(args,api,emit=False)
                    setattr(args,key,original)
                (decks/'Rivaz-R4-A.dck').write_text('changed')
                with self.assertRaisesRegex(RuntimeError,'candidate names, hashes'):contract.check(args,api,emit=False)

    def test_card_swap_is_exact(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);decks,_,_,_=self.setup(root);contract.verify_delta(decks)
            with (decks/'Rivaz-R4-A.dck').open('a') as stream:stream.write('1 Swamp\n')
            with self.assertRaisesRegex(RuntimeError,'exactly R3'):contract.verify_delta(decks)

    def test_sideboard_change_is_not_the_requested_swap(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);decks,_,_,_=self.setup(root)
            with (decks/'Rivaz-R4-A.dck').open('a') as stream:stream.write('[Sideboard]\n1 Swamp\n')
            with self.assertRaisesRegex(RuntimeError,'exactly R3'):contract.verify_delta(decks)

    def test_missing_spec_refuses_before_any_preparation(self):
        with tempfile.TemporaryDirectory() as tmp:
            store=Path(tmp)/'store'
            with patch.object(api,'STORE',store),self.assertRaisesRegex(RuntimeError,'explicit'):
                api.prepare(SimpleNamespace(experiment_spec=None))
            self.assertFalse(store.exists())

    def test_prepare_dynamic_two_variant_request_and_resume(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);_,spec,path,args=self.setup(root);store=root/'work/commander-batches'
            jar=root/'forge-gui-desktop/target/forge-gui-desktop-2.0.16-SNAPSHOT-jar-with-dependencies.jar';jar.parent.mkdir(parents=True);jar.write_text('fixture')
            with patch.object(api,'ROOT',root),patch.object(api,'STORE',store),patch.object(api,'VARIANTS',list(api.VARIANTS)),patch.object(api,'land_count',return_value=37),patch.object(api,'git',return_value='fixture'),patch.dict(api.os.environ,APPDATA=str(root/'appdata')):
                api.prepare(args);request=api.read(store/'seed4500/request.json')
                self.assertEqual(list(request['seedRanges']),['R3','R4-A']);self.assertEqual(request['seedRanges']['R4-A'],[4506,4511])
                self.assertEqual(request['parallelGames'],5);self.assertEqual(request['candidateSpecs'],spec['candidates'])
                args.resume=True;api.prepare(args)
                request['parallelGames']=16
                with self.assertRaisesRegex(RuntimeError,'explicitly requested'):api.verify(request)

    def test_coordinator_runs_only_two_candidates_at_exact_targets(self):
        with tempfile.TemporaryDirectory() as tmp:
            batch=Path(tmp);request=dict(seed=4500,target=3,maxAttempts=6,parallelGames=5)
            names=[('R3','Rivaz-R3.dck',37),('R4-A','Rivaz-R4-A.dck',37)]
            for name,_,_ in names:(batch/name).mkdir()
            seen=[];guard=threading.Lock()
            def execute(job,*args):
                with guard:seen.append((job['variant'],job['runSeed']))
                time.sleep(.02)
                item=pool.failure(job,0,'VALID','fixture');item.update(valid=True,draw=False,winner='fixture',recordedOutcome=dict(winner='fixture',draw=False,durationMs=20,finalTurnCount=1));return item
            with patch.object(api,'VARIANTS',names),patch.object(api,'verify'),patch.object(api,'process_snapshot',return_value={}):
                pool.run_batch(batch,request,api,execute)
            self.assertEqual(len(seen),6);self.assertEqual({v for v,_ in seen},{'R3','R4-A'})
            self.assertEqual(api.read(batch/'status.json')['overall']['target'],6)
            self.assertEqual(len({s for _,s in seen}),6)


if __name__=='__main__':unittest.main()
