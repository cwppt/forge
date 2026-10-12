"""Focused evaluator checks, independent of Forge, Ollama and third-party libraries."""
import contextlib
import base64
import hashlib
import io
import json
import pathlib
import tempfile
import unittest
import analyze_stage14

class Stage14AnalysisTest(unittest.TestCase):
    def test_wire_verification_accepts_existing_schema_and_detects_audit_leak(self):
        with tempfile.TemporaryDirectory() as temp:
            root=pathlib.Path(temp)
            (root/'run.json').write_text(json.dumps(dict(Endpoint='http://localhost:11434/v1/chat/completions')))
            (root/'game.jsonl').write_text(json.dumps(dict(recordType='FULL_GAME_DECISION',decisionId='a',
                stateFingerprint='fp',accepted=True,selectedOption='KEEP'))+'\n')
            payload=dict(decisionId='a',stateFingerprint='fp',decisionType='MULLIGAN_KEEP',state={})
            request=dict(model='qwen3:8b',reasoning_effort='none',messages=[{},dict(content=json.dumps(payload))],
                response_format=dict(json_schema=dict(schema=dict(properties=dict(decisionId=dict(type='string'),
                stateFingerprint=dict(type='string'),optionId=dict(type='string',enum=['KEEP','MULLIGAN']))))))
            def capture():
                body=json.dumps(request).encode()
                (root/'game.requests.jsonl').write_text(json.dumps(dict(Request=request,
                    RequestUtf8Base64=base64.b64encode(body).decode(),RequestSha256=hashlib.sha256(body).hexdigest().upper()))+'\n')
            capture()
            self.assertEqual(analyze_stage14.verify_requests(root)['passed'],1)
            payload['state']['debug']=dict(externalAiGameId='forbidden')
            request['messages'][1]['content']=json.dumps(payload); capture()
            result=analyze_stage14.verify_requests(root)
            self.assertEqual(result['passed'],0)
            self.assertIn('audit correlation leaked',result['failures'][0]['errors'])

    def test_nearest_rank_percentiles_and_empty_sample(self):
        self.assertEqual(analyze_stage14.percentile([], .95),0)
        self.assertEqual(analyze_stage14.percentile([400,100,300,200],.5),200)
        self.assertEqual(analyze_stage14.percentile([400,100,300,200],.95),400)

    def test_matching_mirrors_outcomes_and_zero_call_control(self):
        with tempfile.TemporaryDirectory() as temp:
            root=pathlib.Path(temp)
            for seat in (0,1):
                for mode in ('Control','External'):
                    label=f'fixture-1401-seat{seat}-{mode}'
                    meta=dict(Label=label,Matchup='fixture',Seed=1401,ExternalSeat=seat,Mode=mode,
                              Completed=True,Crash=False,TimedOut=False)
                    (root/f'{label}.run.json').write_text(json.dumps(meta),encoding='utf-8')
                    d=dict(recordType='FULL_GAME_DECISION',externalAiGameId=label,turnSequenceId=f'{label}_T3',
                           turn=3,decisionId=label,surface='MAIN_PHASE_ACTION',providerAttempted=mode=='External',
                           providerLatencyMs=100 if mode=='External' else 0,accepted=mode=='External',
                           fallback=False,staleOrRevalidationFailure=False,agreement=True,passSelection=False,
                           noAttackSelection=False,noBlockSelection=False,duplicateRequest=False,failureReason='')
                    s=dict(recordType='FULL_GAME_SUMMARY',draw=False,winner='Ai(2)-second',decks=['Ai(1)-first','Ai(2)-second'],
                           durationMs=200,turns=4,metrics=dict(externalFullGameProviderCalls=int(mode=='External'),
                           externalFullGameProviderLatencyMs=100 if mode=='External' else 0))
                    (root/f'{label}.jsonl').write_text(json.dumps(d)+'\n'+json.dumps(s)+'\n',encoding='utf-8')
            with contextlib.redirect_stdout(io.StringIO()): report=analyze_stage14.analyze(root)
            self.assertEqual(report['matchedPairs'],2)
            self.assertEqual(report['externalOutcomes'],dict(win=1,loss=1))
            self.assertEqual(report['mirroredSeats']['0'],dict(loss=1))
            self.assertEqual(report['mirroredSeats']['1'],dict(win=1))
            self.assertEqual(report['controlProviderCalls'],0)
            self.assertEqual(report['externalFullGameAvgCallsPerGame'],1)
            self.assertEqual(report['externalFullGameAvgLatencyPerGame'],100)
            self.assertEqual(len(report['bySurface']),7)
            self.assertEqual(report['bySurface']['STACK_RESPONSE']['providerCalls'],0)

if __name__=='__main__': unittest.main()
