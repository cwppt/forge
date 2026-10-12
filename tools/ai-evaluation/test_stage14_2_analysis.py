import unittest
from analyze_stage14_2 import block_metrics

class TacticalComparisonTest(unittest.TestCase):
    def entry(self,variant='Revised',choice='NO_BLOCK',raw=None):
        hold={'projectedLifeAfterCombat':0,'preventsLethal':False,'nearLethalProtection':False,'unnecessaryLosingBlock':False,
              'projectedCreatureLossesSelf':0,'projectedCreatureLossesOpponent':0}
        block=dict(hold,projectedLifeAfterCombat=3,preventsLethal=True,projectedCreatureLossesSelf=1)
        context={'decisionId':'x','options':[{'optionId':'NO_BLOCK','tacticalConsequences':hold},{'optionId':'OPT_HASH','tacticalConsequences':block}]}
        return {'variant':variant,'context':context,'originalChoice':choice,'rawChoice':raw or choice,'forgeChoice':'OPT_HASH'}
    def test_fallback_does_not_hide_raw_missed_survival(self):
        e=self.entry(choice='OPT_HASH',raw='NO_BLOCK');r=block_metrics([e],{})
        self.assertEqual(r['lethalSavingBlocks'],1);self.assertEqual(r['rawMissedLethalPrevention'],1)
        self.assertEqual(r['noBlock'],0)
    def test_unknown_projection_is_not_classified_as_safe_or_bad(self):
        e=self.entry();e['context']['options'][0]['tacticalConsequences']=None
        r=block_metrics([e],{});self.assertEqual(r['unknownSelected'],1);self.assertEqual(r['annotatedSelected'],0)
        self.assertEqual(r['unnecessaryLosingBlocks'],0)
    def test_original_uses_same_forge_projection_but_revised_uses_live_snapshot(self):
        original=self.entry(variant='Original');revised=self.entry()
        export={'x':{'revisedContext':original['context']}}
        self.assertEqual(block_metrics([original],export),block_metrics([revised],export))

if __name__=='__main__':unittest.main()
