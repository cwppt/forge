import unittest
from pathlib import Path
from rng_evidence import advances,historical,audit_native
ROOT=Path('D:/ForgeData/forge-experiments')
class EvidenceTests(unittest.TestCase):
    def test_lcg_advances_are_not_provider_requests(self):self.assertEqual(advances(25214903917,205749139540596),1)
    def test_capped_search_does_not_invent_count(self):self.assertIsNone(advances(1,999,limit=1));self.assertEqual(advances(1,1),0)
    def test_preserved_phase7a_intervals(self):
        x=historical(ROOT/'rules-parity-v7a-20261028/validation-01')
        self.assertEqual(x['cases']['observed-baseline-0']['primitiveStateAdvancesBetweenSnapshots'],60)
        self.assertEqual(x['cases']['observed-candidate-0']['primitiveStateAdvancesBetweenSnapshots'],92)
        self.assertFalse(x['cases']['observed-baseline-0']['actualDrawOperationsRecorded'])
    def test_native_draw_proof_is_context_matched(self):
        x=audit_native(ROOT/'rng-isolation-v7b-20261028/validation-01/initial-native-rng')
        self.assertEqual(x['classification'],'A');self.assertEqual(x['earliestProvenFixtureDrawDivergence']['primitiveOrdinal'],2)
        self.assertEqual(x['smallGlobalSpeculativeDraws'],1);self.assertEqual(x['largeGlobalSpeculativeDraws'],5);self.assertEqual(x['privateFixtureGlobalDraws'],0)
if __name__=='__main__':unittest.main()
