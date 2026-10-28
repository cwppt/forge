import copy,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import incremental_run as r
h=r.host

class ProgrammeTests(unittest.TestCase):
    def setUp(self):
        self.previous=copy.deepcopy(h.DATA)
        # Unit fixtures must not depend on argv or a real experiment's engine.
        self.directory=tempfile.TemporaryDirectory()
        root=Path(self.directory.name)
        (root/'optimized.jar').write_bytes(b'unit-test candidate provenance')
        self.root_patch=patch.object(r,'R',root)
        self.root_patch.start()
        self.addCleanup(self.directory.cleanup)
        self.addCleanup(self.root_patch.stop)
    def tearDown(self):h.DATA.clear();h.DATA.update(self.previous)
    def samples(self,ratios,allocation=1):
        h.DATA['benchmarks']={};h.DATA['regressions']={}
        for n,ratio in enumerate(ratios):
            for mode,scale in [('functional',1.1),('baseline',1),('optimized',ratio)]:
                rows=[dict(case=case,path='block',wallMs=1000*scale,cpuMs=900*scale,allocatedBytesCurrentThread=1000*(allocation if mode=='optimized' else 1),gcMs=0) for case in ['large-four-player','dragon-tokens'] for _ in range(4)]
                h.DATA['benchmarks'][f'timing-{n}-{mode}']=dict(mode=mode,rows=rows)
    def compare(self):
        original=r.read
        def read(path):return dict(sourceHead='unit-test') if path.name=='run-request.json' else original(path)
        with patch.object(r,'read',read):r.comparison()
    def test_small_repeatable_gain_is_experimental_not_promotion(self):
        self.samples([.97,.97,.97]);self.compare();self.assertTrue(h.DATA['performanceImprovementDemonstrated']);self.assertFalse(h.DATA['promotionPerformanceGate'])
    def test_large_gain_meets_only_performance_promotion_gate(self):
        self.samples([.8,.8,.8]);self.compare();self.assertTrue(h.DATA['promotionPerformanceGate']);self.assertFalse(h.DATA['readyForLargerCohorts'])
    def test_negligible_or_noisy_gain_fails(self):
        for ratios in [[.99,.99,.99],[.9,.9,1.01],[.9,.9]]:
            with self.subTest(ratios=ratios):self.samples(ratios);self.compare();self.assertFalse(h.DATA['performanceImprovementDemonstrated'])
    def test_allocations_are_a_gate(self):self.samples([.9,.9,.9],1.06);self.compare();self.assertFalse(h.DATA['performanceImprovementDemonstrated'])
    def test_cumulative_gain_comes_from_direct_ratio_not_added_history(self):
        self.samples([.97,.97,.97]);self.compare();v=h.DATA['performanceHistory']['currentCandidate']['synthetic']['large-four-player/block']
        self.assertAlmostEqual(v['incrementalImprovementPercent'],3);self.assertAlmostEqual(v['cumulativeAgainstFunctionalOriginalPercent'],100*(1-970/1100))
    def test_previous_experimental_features_are_disabled(self):
        flags=r.api.flags('optimized');self.assertIn('-Dforge.cost.liveSourceFilter=false',flags);self.assertIn('-Dforge.blocker.hoistCostPresence=false',flags);self.assertIn('-Dforge.blocker.lazyRequirementSnapshots=true',flags)

if __name__=='__main__':unittest.main()
