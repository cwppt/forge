import ast,unittest,subprocess
from pathlib import Path
SOURCE=Path(__file__).resolve().parents[3]
OLD='e85ec717206c51182b5cac6393407be2aece14b8'
class RecoveryTests(unittest.TestCase):
    def test_exact_benchmark_change_is_only_optional_metrics(self):
        path='forge-gui-desktop/src/test/java/forge/ai/BusyBoardBenchmark.java'
        old=subprocess.check_output(['git','-C',str(SOURCE),'show',OLD+':'+path],text=True,encoding='utf-8-sig')
        expected=old.replace('long[] countsBefore=forge.game.combat.RequirementSnapshotMetrics.snapshot();','boolean metrics=Boolean.getBoolean("forge.blocker.snapshotMetrics");\n            long[] countsBefore=BenchmarkOptionalMetrics.read("forge.game.combat.RequirementSnapshotMetrics",metrics);')
        expected=expected.replace('long[] countsAfter=forge.game.combat.RequirementSnapshotMetrics.snapshot();\n            row.put("requirementSnapshotQueries",countsAfter[0]-countsBefore[0]);row.put("requirementSnapshotsAvoided",countsAfter[1]-countsBefore[1]);','long[] countsAfter=BenchmarkOptionalMetrics.read("forge.game.combat.RequirementSnapshotMetrics",metrics);\n            if ((countsBefore==null)!=(countsAfter==null)) throw new IllegalStateException("Optional benchmark counter capability changed within workload");\n            row.put("requirementSnapshotQueries",countsBefore==null?null:countsAfter[0]-countsBefore[0]);row.put("requirementSnapshotsAvoided",countsBefore==null?null:countsAfter[1]-countsBefore[1]);')
        self.assertEqual((SOURCE/path).read_text(encoding='utf-8-sig'),expected)
    def test_strict_comparator_is_unchanged(self):
        path='tools/ai-evaluation/performance/structured_parity.py'
        old=subprocess.check_output(['git','-C',str(SOURCE),'show',OLD+':'+path],text=True,encoding='utf-8-sig')
        self.assertEqual((SOURCE/path).read_text(encoding='utf-8-sig'),old)
    def test_no_engine_source_changed(self):
        files=subprocess.check_output(['git','-C',str(SOURCE),'diff',OLD,'--name-only'],text=True).splitlines()
        self.assertFalse(any(p.startswith(('forge-game/','forge-ai/','forge-core/')) for p in files))
    def test_recovery_script_has_no_compile_engine_step(self):
        path=SOURCE/'tools/ai-evaluation/performance/adoption_recovery.py';ast.parse(path.read_text(encoding='utf-8-sig'))
        text=path.read_text(encoding='utf-8-sig');self.assertNotIn('adoption_build',text);self.assertIn('COMBINED_SHA',text)
if __name__=='__main__':unittest.main()
