import copy,unittest
from adoption_resume_plan import schedule,remaining,validate_rows
class ResumeTests(unittest.TestCase):
    def state(self):
        return dict(state='failed-preserved',games=[],failedAttempts=[],regressions={'structured64-'+a:dict(passed=True) for a in 'ABCDO'},benchmarks={label:dict(arm=arm,block=block,rows=[{}]) for label,arm,block in schedule()[:11]})
    def test_completed_prefix_never_replayed(self):self.assertEqual(remaining(self.state()),schedule()[11:]);self.assertEqual(len(remaining(self.state())),5)
    def test_missing_gate_rejected(self):
        state=self.state();state['regressions']['structured64-C']['passed']=False
        with self.assertRaises(AssertionError):remaining(state)
    def test_partial_or_failed_game_rejected(self):
        for key in ['games','failedAttempts']:
            state=self.state();state[key]=[dict(seed=573400)]
            with self.assertRaises(AssertionError):remaining(state)
    def test_wrong_arm_and_nonprefix_rejected(self):
        state=self.state();state['benchmarks']['timing-0-A']['arm']='B'
        with self.assertRaises(AssertionError):remaining(state)
        state=self.state();del state['benchmarks']['timing-0-B']
        with self.assertRaises(AssertionError):remaining(state)
class RowTests(unittest.TestCase):
    def rows(self):return [dict(case=case,path=path,rep=rep,warmup=rep<0) for case in ['large-four-player','dragon-tokens','large-combat','late-100'] for path in ['block','attack'] for rep in [-1,0,1,2]]
    def test_exact_measured_and_warmup_matrix(self):validate_rows(self.rows())
    def test_missing_duplicate_or_mislabeled_rows_rejected(self):
        original=self.rows()
        for rows in [original[:-1],original[:-1]+original[:1],[dict(r,warmup=False) for r in original]]:
            with self.assertRaises(AssertionError):validate_rows(rows)
if __name__=='__main__':unittest.main()
