import copy
import json
import unittest
from evaluate_stage14_2 import comparison_request, validate_answer

class ReplayValidationTest(unittest.TestCase):
    def request(self):
        payload=dict(decisionId='stable',stateFingerprint='old',decisionType='COMBAT_BLOCKERS',state={'visible':True},options=[{'optionId':'NO_BLOCK'},{'optionId':'OPT_HASH'}])
        schema={'type':'json_schema','json_schema':{'schema':{'properties':{'decisionId':{'enum':['stable']},'stateFingerprint':{'enum':['old']},'optionId':{'enum':['NO_BLOCK','OPT_HASH']}}}}}
        return dict(model='qwen3:8b',reasoning_effort='none',messages=[{'content':'old prompt'},{'content':json.dumps(payload)}],response_format=schema)
    def test_revision_synchronizes_schema_and_payload_without_mutating_original(self):
        original=self.request();before=copy.deepcopy(original)
        entry={'revisedContext':{'stateFingerprint':'new','options':[{'optionId':'NO_BLOCK'},{'optionId':'OPT_HASH','tacticalConsequences':{'projectedDamagePrevented':3}}]}}
        revised,payload=comparison_request(original,entry,'Revised','qwen3:8b')
        self.assertEqual(original,before)
        self.assertEqual(payload['stateFingerprint'],revised['response_format']['json_schema']['schema']['properties']['stateFingerprint']['enum'][0])
        self.assertEqual(payload['state'],json.loads(original['messages'][1]['content'])['state'])
        self.assertEqual([o['optionId'] for o in payload['options']],['NO_BLOCK','OPT_HASH'])
        self.assertEqual(revised['response_format']['json_schema']['schema']['properties']['optionId']['enum'],['NO_BLOCK','OPT_HASH'])
    def test_original_transport_is_preserved(self):
        original=self.request();result,payload=comparison_request(original,{},'Original','qwen3:8b')
        self.assertEqual(result,original);self.assertEqual(payload['stateFingerprint'],'old')
    def test_identity_option_and_extra_fields_are_rejected_without_repair(self):
        payload=dict(decisionId='stable',stateFingerprint='correct')
        good=dict(decisionId='stable',stateFingerprint='correct',optionId='NO_BLOCK')
        def response(answer):return {'choices':[{'message':{'content':json.dumps(answer)}}]}
        self.assertEqual(validate_answer(response(good),payload,['NO_BLOCK']),'NO_BLOCK')
        for changes in ({'decisionId':'wrong'},{'stateFingerprint':'wrong'},{'optionId':'unknown'},{'explanation':'extra'}):
            with self.assertRaises(ValueError):validate_answer(response(dict(good,**changes)),payload,['NO_BLOCK'])

if __name__=='__main__':unittest.main()
