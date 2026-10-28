import tempfile,unittest
from pathlib import Path
from realgame_analysis import scopes_summary,records,scope_records,classify_stack,dist

class RealgameAnalysisTests(unittest.TestCase):
    def row(self,**kw):
        x=dict(id=1,phase='MAIN1',battlefield=55,fallbackCalls=55,predictionAborts=2,workLimit=2000,work=2055,budgetMs=500,elapsedMs=148,result='PASS');x.update(kw);return x
    def test_fallback_calls_are_not_decisions(self):
        x=scopes_summary([self.row(),self.row(id=2,fallbackCalls=0,work=3,predictionAborts=0)])
        self.assertEqual(x['scopesWithFallback'],1);self.assertEqual(x['fallbackCheckpointCalls'],55);self.assertIsNone(x['totalNativeAiDecisions'])
    def test_work_time_and_ambiguous_evidence_remain_separate(self):
        x=scopes_summary([self.row(),self.row(work=5,elapsedMs=700),self.row(elapsedMs=700)])
        self.assertEqual(x['observedBudgetEvidenceScopeCounts'],dict(work_limit_only_crossed_at_close=1,time_limit_only_crossed_at_close=1,both_limits_crossed_at_close=1));self.assertIsNone(x['exactFallbackReasonByCall'])
    def test_context_is_not_invented(self):
        x=scopes_summary([self.row()]);self.assertIn('context:UNLABELLED_DECISION_SCOPE',x['byContextPhaseAndBoard']);self.assertIn('battlefield:50-74',x['byContextPhaseAndBoard'])
    def test_nested_categories_are_exclusive(self):
        self.assertEqual(classify_stack(['forge/ai/ComputerUtilCombat.foo','forge/ai/AiBlockController.makeGoodBlocks']),'blocker-search')
    def test_empty_data_is_not_zero_decisions_claim(self):
        x=scopes_summary([]);self.assertEqual(x['scopes'],0);self.assertIsNone(x['totalNativeAiDecisions']);self.assertEqual(dist([]),dict(n=0))
    def test_malformed_evidence_fails_closed(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'log';p.write_text('AI_EVAL_DECISION {broken\n')
            with self.assertRaises(ValueError):records(p,'AI_EVAL_DECISION ')
    def test_restarted_jvm_ids_do_not_cross_correlate(self):
        import json
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);a=root/'a';b=root/'b';a.mkdir();b.mkdir()
            (a/'execution.log').write_text('AI_EVAL_DECISION '+json.dumps(self.row())+'\n')
            (b/'execution.log').write_text('AI_EVAL_DECISION '+json.dumps(self.row(fallbackCalls=0))+'\nAI_EVAL_CANDIDATE '+json.dumps(dict(id=1,card='unrelated',api='Draw',opinion='ok'))+'\n')
            ar,ac=scope_records(a,'seed-a');br,bc=scope_records(b,'seed-b')
            self.assertEqual(scopes_summary(ar+br,ac+bc)['candidateEventsInFallbackScopes'],[])

if __name__=='__main__':unittest.main()
