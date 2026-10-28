import unittest
from adoption_build import canonical_javap
from adoption_results import whole,synthetic
class AdoptionTests(unittest.TestCase):
    def test_javap_padding_is_not_a_code_change(self):self.assertEqual(canonical_javap('  0: invokevirtual #12       // Method x:()V'),canonical_javap('  0: invokevirtual #1234     // Method x:()V'))
    def test_literal_and_target_changes_are_preserved(self):
        self.assertNotEqual(canonical_javap(' 0: ldc #1 // String #123'),canonical_javap(' 0: ldc #2 // String #456'))
        self.assertNotEqual(canonical_javap(' 0: goto 12'),canonical_javap(' 0: goto 13'))
    def test_incomplete_cohort_never_invents_gain(self):self.assertEqual(whole([], {})['directComparisons']['D vs A']['status'],'pending; incomplete matched cohort')
    def test_direct_ratios_and_publication_are_separate(self):
        games=[]
        for arm,elapsed in [('A',100),('B',95),('C',80),('D',75)]:
            for seed in range(3):games.append(dict(arm=arm,seed=seed,valid=True,durationMs=elapsed*900,measurementElapsedSeconds=elapsed,publicationSeconds=5,cpuSeconds=10,processPeakBytes=1000,analysis=dict(gcMsReportedTurns=10,maximumReportedHeapBytes=500,evaluation=dict(scopesWithFallback=1))))
        out=whole(games,{})
        self.assertEqual(out['directComparisons']['D vs A']['activeElapsedReductionPercent'],25)
        self.assertAlmostEqual(out['directComparisons']['D vs C']['activeElapsedReductionPercent'],6.25)
        self.assertAlmostEqual(out['directComparisons']['B vs A']['activeElapsedReductionPercent'],5)
        self.assertTrue(out['directComparisons']['B vs A']['diagnosticOnly']);self.assertFalse(out['directComparisons']['D vs A']['sampledGameplayParity'])
        self.assertLess(out['arms']['A']['validGamesPerHourWithAllocatedPublication'],out['arms']['A']['validGamesPerHourActive'])
    def test_invalid_attempt_not_counted_as_valid_game(self):
        out=whole([dict(arm='D',valid=False,measurementElapsedSeconds=20)],{})['arms']['D'];self.assertEqual(out['validGames'],0);self.assertEqual(out['invalidOrTimeouts'],1);self.assertFalse(out['complete'])
    def test_synthetic_combination_is_direct_not_sum(self):
        entries={}
        for arm,cost in [('A',100),('B',98),('C',20),('D',18)]:entries[arm]=dict(arm=arm,rows=[dict(case='fixture',path='block',wallMs=cost,cpuMs=cost,allocatedBytesCurrentThread=100,gcMs=0)])
        result=synthetic(entries)['fixture/block']['directImprovementPercent'];self.assertAlmostEqual(result['D vs A']['wallMs'],82);self.assertAlmostEqual(result['D vs C']['wallMs'],10)
    def test_own_child_cpu_counter_preserves_return_value(self):
        import tempfile,subprocess,sys,json,os
        from pathlib import Path
        from adoption_process import launch_with_counters
        with tempfile.TemporaryDirectory(dir='D:/ForgeData/forge-experiments/performance-adoption-v7c-20261028/validation-01') as tmp:
            directory=Path(tmp)
            def launch(d,command,cwd,env,limit):
                process=subprocess.Popen(command,cwd=cwd,env=env)
                (d/'process.json').write_text(json.dumps(dict(pid=process.pid)))
                return process.wait(timeout=limit)
            result=launch_with_counters(launch,directory,[sys.executable,'-c','import time;time.sleep(.2);sum(range(3000000))'],directory,os.environ.copy(),10)
            self.assertEqual(result,0);self.assertGreater(json.loads((directory/'process-counters.json').read_text())['cpuSeconds'],0)
if __name__=='__main__':unittest.main()
