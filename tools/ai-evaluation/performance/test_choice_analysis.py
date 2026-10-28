import unittest
import choice_run as r

class ChoiceAnalysisTests(unittest.TestCase):
    def test_seed_zero_shuffle_is_explained_without_drawing(self):
        before=25214903917;after=205749139540596
        result=r.shuffle_proof(before,after,['Dig','Token']);self.assertTrue(result['oneJavaLcgAdvanceMatches']);self.assertTrue(result['orderMatches'])
    def test_wrong_rng_or_order_is_not_hidden(self):
        self.assertFalse(r.shuffle_proof(25214903917,1,['Dig','Token'])['oneJavaLcgAdvanceMatches'])
        self.assertFalse(r.shuffle_proof(25214903917,205749139540596,['Token','Dig'])['orderMatches'])
    def test_unavailable_rng_is_not_inferred(self):self.assertEqual(r.shuffle_proof(None,None,['Dig','Token']),dict(available=False))
    def test_original_failure_remains_1699_and_public_prefix_only(self):
        x=r.original_audit();self.assertEqual(x['firstDifferentStructuredToken'],1699);self.assertTrue(x['observablePrefixEqual']);self.assertTrue(x['publicStateEqual']);self.assertIn('hidden',x['limitations'])

if __name__=='__main__':unittest.main()
