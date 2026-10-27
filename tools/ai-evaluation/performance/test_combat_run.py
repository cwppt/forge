"""Offline tests for conservative conclusions and publication ownership."""
import copy,json,tempfile,unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import combat_run as run

class SupervisorTests(unittest.TestCase):
    def setUp(self):
        self.previous=copy.deepcopy(run.DATA)
        run.DATA['benchmarks']={}
    def tearDown(self):
        run.DATA.clear();run.DATA.update(self.previous)
    def samples(self,ratios):
        for n,ratio in enumerate(ratios):
            for mode,scale in [('baseline',1),('optimized',ratio)]:
                rows=[dict(case=case,path='block',wallMs=1000*scale,cpuMs=900*scale,
                           allocatedBytesCurrentThread=1000000,gcMs=0)
                      for case in ['large-four-player','dragon-tokens'] for _ in range(4)]
                run.DATA['benchmarks'][f'timing-{n}-{mode}']=dict(mode=mode,rows=rows)
    def test_consistent_gain_requires_all_jvm_blocks(self):
        self.samples([.8,.82,.79]);run.comparison()
        self.assertTrue(run.DATA['performanceImprovementDemonstrated'])
        self.assertEqual(run.DATA['comparison']['large-four-player/block']['baseline']['wallMs']['n'],12)
    def test_noisy_or_incomplete_gain_is_not_success(self):
        for ratios in [[.8,.8,1.02],[.99,.99,.99],[.8,.8]]:
            with self.subTest(ratios=ratios):
                run.DATA['benchmarks']={};self.samples(ratios);run.comparison()
                self.assertFalse(run.DATA['performanceImprovementDemonstrated'])
    def test_changed_compiled_input_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder,patch.object(run,'R',Path(folder)):
            p=Path(folder);(p/'test.class').write_bytes(b'changed')
            (p/'run-request.json').write_text(json.dumps(dict(sourceHashes={},inputHashes={'test.class':'frozen'})))
            with self.assertRaisesRegex(AssertionError,'compiled test'):run.verify()
    def test_existing_unowned_report_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder);(p/'run-request.json').write_text(json.dumps(dict(baselineSHA256='base',treatmentSHA256='candidate',sourceHead='commit')))
            calls=[]
            def remote(path,method='GET',payload=None):
                calls.append(method);return SimpleNamespace(returncode=0,stdout='{"sha":"existing"}',stderr='')
            with patch.object(run,'R',p),patch.object(run,'PUBLIC',p/'public'),patch.object(run,'gh',remote):
                with self.assertRaisesRegex(AssertionError,'not owned'):run.publish()
            self.assertEqual(calls,['GET'])

if __name__=='__main__':unittest.main()
