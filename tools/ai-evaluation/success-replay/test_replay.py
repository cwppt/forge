import contextlib
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch
import ReplayFailures as replay


class ReplayTests(unittest.TestCase):
    def test_matched_cells_preserve_attribution_and_bounds(self):
        with tempfile.TemporaryDirectory() as temp:
            batch=Path(temp);request=dict(selection=[dict(variant=v,seed=s) for v,s in replay.SELECTION],cells=replay.CELLS)
            replay.api.write(batch/'request.json',request)
            lock=threading.Lock();running=0;maximum=0;seen=[]
            def game(job,batch,request,active,stop,api):
                nonlocal running,maximum
                with lock:running+=1;maximum=max(maximum,running);seen.append((request['timeoutSeconds'],job['runSeed'],job['rawDirectory']))
                time.sleep(.02)
                api.write(Path(job['rawDirectory'])/'exit.json',dict(wallSeconds=.02))
                with lock:running-=1
                return dict(valid=True,simulationStatus='COMPLETED',telemetryErrors=[])
            with patch.object(replay,'verify'),patch.object(replay.api,'WorkerLock',contextlib.nullcontext),patch.object(replay.pool,'execute',game):
                replay.run(batch)
            rows=replay.api.cohort.load_jsonl(batch/'results.jsonl')
            self.assertEqual(len(rows),15);self.assertEqual(maximum,5)
            self.assertEqual(len({p for _,_,p in seen}),15)
            for label,_,_ in replay.CELLS:
                self.assertEqual({r['seed'] for r in rows if r['cell']==label},{s for _,s in replay.SELECTION})
            self.assertEqual(replay.api.read(batch/'status.json')['state'],'COMPLETE')
            with patch.object(replay.api,'WorkerLock',contextlib.nullcontext),self.assertRaisesRegex(RuntimeError,'already attempted'):
                replay.run(batch)


if __name__=='__main__':unittest.main()
