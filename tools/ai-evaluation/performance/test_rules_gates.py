import copy,unittest
import rules_run as r

class RulesGateTests(unittest.TestCase):
    def setUp(self):self.previous=copy.deepcopy(r.D)
    def tearDown(self):r.D.clear();r.D.update(self.previous)
    def target(self,ratio):
        r.D['benchmarks']={}
        for n in range(3):
            for mode,scale in [('functional',1.1),('baseline',1),('optimized',ratio)]:
                r.D['benchmarks'][f'target-{n}-{mode}']=dict(mode=mode,rows=[dict(warmup=False,wallMs=100*scale,cpuMs=100*scale,allocationBytes=100,gcMs=0)])
        r.target_comparison();return r.D['targetComparison']
    def test_method_gate_requires_twenty_percent(self):
        self.assertFalse(self.target(.85)['passed']);self.assertTrue(self.target(.7)['passed'])
    def test_direct_cumulative_ratio_is_not_added_history(self):
        x=self.target(.7);self.assertAlmostEqual(x['directCumulativeMethodCpuGainPercent'],100*(1-70/110))
    def whole(self,ratio):
        r.D['games']=[];r.D['pairs']=[]
        for i,deck in enumerate(r.native.VARIANTS):
            r.D['pairs'].append(dict(deck=deck,activeRatio=ratio))
            for mode,scale in [('baseline',1),('optimized',ratio)]:
                r.D['games'].append(dict(mode=mode,measurementElapsedSeconds=100*scale,durationMs=90000*scale,
                    analysis=dict(gcMsReportedTurns=100,maximumReportedHeapBytes=1000,evaluation=dict(scopesWithFallback=1,fallbackCheckpointCalls=55))))
        r.whole_comparison();return r.D['wholeGame']
    def test_whole_game_target_remains_ten_percent(self):
        self.assertFalse(self.whole(.95)['passed']);self.assertTrue(self.whole(.8)['passed'])
    def test_jvm_candidate_does_not_enable_old_experiments(self):
        flags=r.flags('optimized')
        self.assertIn('-XX:-DontCompileHugeMethods',flags)
        self.assertIn('-Dforge.blocker.lazyRequirementSnapshots=false',flags)
        self.assertIn('-Dforge.cost.liveSourceFilter=false',flags)
        self.assertNotIn('-XX:-DontCompileHugeMethods',r.flags('baseline'))
    def test_frozen_class_parser_has_real_method_size(self):
        value=r.property_bytecode();self.assertGreater(value['methodBytecodeBytes'],8000);self.assertEqual(len(value['classSHA256']),64)

if __name__=='__main__':unittest.main()
