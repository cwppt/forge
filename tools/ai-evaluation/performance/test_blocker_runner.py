import copy,unittest
import blocker_run as runner
h=runner.host

class GateTests(unittest.TestCase):
    def setUp(self):self.saved=copy.deepcopy(h.DATA)
    def tearDown(self):h.DATA.clear();h.DATA.update(self.saved)
    def samples(self,ratios,allocation=1):
        h.DATA['benchmarks']={}
        for n,ratio in enumerate(ratios):
            for mode,scale in [('baseline',1),('optimized',ratio)]:
                rows=[dict(case=case,path='block',wallMs=1000*scale,cpuMs=900*scale,allocatedBytesCurrentThread=1000*(allocation if mode=='optimized' else 1),gcMs=0) for case in ['large-four-player','dragon-tokens'] for _ in range(4)]
                h.DATA['benchmarks'][f'timing-{n}-{mode}']=dict(mode=mode,rows=rows)
    def test_consistent_gain_passes(self):self.samples([.8,.81,.79]);runner.comparison();self.assertTrue(h.DATA['performanceImprovementDemonstrated'])
    def test_small_noisy_or_incomplete_gain_fails(self):
        for ratios in [[.86,.86,.86],[.8,.8,1.01],[.8,.8]]:
            with self.subTest(ratios=ratios):self.samples(ratios);runner.comparison();self.assertFalse(h.DATA['performanceImprovementDemonstrated'])
    def test_allocation_regression_fails(self):self.samples([.8,.8,.8],1.06);runner.comparison();self.assertFalse(h.DATA['performanceImprovementDemonstrated'])
    def test_p95_regression_fails(self):
        self.samples([.8,.8,.8])
        for name,entry in h.DATA['benchmarks'].items():
            if entry['mode']=='optimized':
                for row in entry['rows'][:2]:row['wallMs']=1100
        runner.comparison();self.assertFalse(h.DATA['performanceImprovementDemonstrated'])

if __name__=='__main__':unittest.main()
