import json
import pathlib
import tempfile
import unittest
from evaluate_stage15 import HISTORY_PROMPT,analyze,memory_extra_chars,plausible_context
from evaluate_stage14_2 import write


class MemoryEvaluationTest(unittest.TestCase):
    def body(self,memory=None):
        payload=dict(decisionId='request',decisionType='MAIN_PHASE_ACTION',stateFingerprint='fingerprint',state={'turn':3},legalActions=[{'actionId':'ACTION_0'}])
        if memory is not None:payload['strategicMemory']=memory
        return dict(reasoning_effort='none',messages=[{'role':'system','content':'Choose one.'+(HISTORY_PROMPT if memory is not None else '')},
            {'role':'user','content':json.dumps(payload,separators=(',',':'),ensure_ascii=False)}],
            response_format={'json_schema':{'schema':{'properties':{'optionId':{'enum':['ACTION_0']}}}}})

    def view(self):
        return dict(knownOpponentCards=[],interactionObserved=[dict(category='INTERACTION_SHOWN',interaction='COUNTER',cardName='Counterspell',location=None)],currentTurnFacts=[],recentStrategicEvents=[])

    def test_exact_added_chars_include_prompt_and_escaped_view(self):
        view=self.view();view['interactionObserved'][0]['summary']='Observed "counter" \u00e9'
        encode=lambda v:json.dumps(v,separators=(',',':'),ensure_ascii=False)
        self.assertEqual(memory_extra_chars(self.body(view)),len(encode(self.body(view)))-len(encode(self.body())))

    def test_disabled_request_has_no_added_chars(self):
        self.assertEqual(memory_extra_chars(self.body()),0)

    def test_nonempty_history_is_not_automatically_strategically_relevant(self):
        view=self.view();self.assertTrue(plausible_context(view,{}))
        view['interactionObserved']=[];view['recentStrategicEvents']=[dict(category='COMBAT_EVENT',interaction='COMBAT',cardName=None)]
        self.assertFalse(plausible_context(view,{}))

    def cohort(self,root):
        write(root/'run.json',dict(MaxFacts=12))
        for mode in ('Control','Memory'):
            directory=root/mode;directory.mkdir();prefix=directory/'test'
            body=self.body(self.view() if mode=='Memory' else None)
            ref=dict(recordType='FULL_GAME_DECISION',surface='MAIN_PHASE_ACTION',decisionId='request',stateFingerprint='fingerprint',
                providerAttempted=True,selectedOption='ACTION_0',fallback=False,staleOrRevalidationFailure=False,duplicateRequest=False,
                providerLatencyMs=1000,agreement=True,turn=3,usage={'prompt_tokens':100})
            if mode=='Memory':ref['memory']=dict(view=self.view(),selectedFacts=1,buildNanos=100000)
            summary=dict(recordType='FULL_GAME_SUMMARY',winner='Ai(1)-test',draw=False,strategicMemory={'factsRecorded':1,'maxActiveFacts':1} if mode=='Memory' else {})
            prefix.with_suffix('.jsonl').write_text(json.dumps(ref)+'\n'+json.dumps(summary)+'\n',encoding='utf-8')
            prefix.with_suffix('.requests.jsonl').write_text(json.dumps({'Request':body})+'\n',encoding='utf-8')
            write(prefix.with_suffix('.run.json'),dict(ExternalSeat=0,Matchup='test',Seed=1))

    def test_analysis_joins_exact_current_states_and_validates_audit(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory);self.cohort(root);result=analyze(root)
            self.assertEqual(result['Memory']['avgFactsPerSupportedRequest'],1)
            self.assertEqual(result['Memory']['relevantMemoryDecisions'],1)
            self.assertEqual(result['Memory']['avgMemoryBuildMs'],0.1)
            evidence=json.loads((root/'analysis.json').read_text(encoding='utf-8'))
            self.assertEqual(len(evidence['exactVisibleStateComparisons']),1)

    def test_analysis_rejects_audit_view_that_differs_from_supplied_view(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory);self.cohort(root)
            p=root/'Memory/test.jsonl';rows=[json.loads(l) for l in p.read_text(encoding='utf-8').splitlines()]
            del rows[0]['memory']['view']['interactionObserved'][0]['location']
            p.write_text('\n'.join(json.dumps(r) for r in rows)+'\n',encoding='utf-8')
            with self.assertRaises(AssertionError):analyze(root)


if __name__=='__main__':unittest.main()
