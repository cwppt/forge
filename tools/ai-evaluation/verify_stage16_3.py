"""Verify Stage 16.3 captured stack-response requests and legal fixture results.
Usage: python tools/ai-evaluation/verify_stage16_3.py <run-directory>
"""
import base64
import hashlib
import json
import pathlib
import sys


def verify(directory):
    output = pathlib.Path(directory)
    results = json.loads((output / 'fixture-results.json').read_text(encoding='utf-8'))
    by_id = {r['decisionId']: r for r in results}
    checks = []
    for path in sorted(output.glob('*.requests.jsonl')):
        for line in path.read_text(encoding='utf-8').splitlines():
            record = json.loads(line); request = record['Request']
            raw = base64.b64decode(record['RequestUtf8Base64'])
            assert hashlib.sha256(raw).hexdigest().upper() == record['RequestSha256']
            assert json.loads(raw) == request
            assert request['model'] == 'qwen3:8b' and request['reasoning_effort'] == 'none'
            context = json.loads(request['messages'][1]['content'])
            assert context['decisionType'] == 'STACK_RESPONSE'
            assert len(context['legalActions']) == 1 and len(context['stackItems']) == 1
            option = context['legalActions'][0]; item = context['stackItems'][0]
            assert option['sourceName'] in {'Stifle', 'Rust', 'Disallow'}
            assert item['objectType'] == 'ACTIVATED_ABILITY' and item['sourceName'] == 'Rod of Ruin'
            assert item['controller'] and item['targets'] and 'damage' in item['rulesSummary'].lower()
            assert option['modes'] == [] and option['xValue'] is None
            assert len(option['targets']) == 1 and 'ACTIVATED_ABILITY Rod of Ruin' in option['targets'][0]
            assert option['actionId'].startswith('OPT_')
            assert all(s not in request['messages'][1]['content'] for s in ('Black Lotus', 'VERIFIED_SAFE', 'internalActionMapping', 'heuristicPosition'))
            enum = request['response_format']['json_schema']['schema']['properties']['optionId']['enum']
            assert set(enum) == {'PASS', option['actionId']}
            result = by_id[context['decisionId']]
            assert result['card'] == option['sourceName'] and result['selectedOption'] in enum
            assert not result['fallback'] and not result['stale'] and result['targetType'] == item['objectType']
            audits = [json.loads(line) for line in pathlib.Path(str(path).replace('.requests.jsonl', '.decisions.jsonl')).read_text(encoding='utf-8').splitlines()]
            matches = [e for e in audits if e.get('decisionType') == 'STACK_RESPONSE' and e.get('decisionId') == context['decisionId']]
            assert len(matches) == 1
            audit = matches[0]
            assert audit['fingerprint'] == context['stateFingerprint'] and audit['selectedResponseId'] == result['selectedOption']
            assert audit['responseAccepted'] and not audit['fallback']
            checks.append(dict(file=str(path), card=option['sourceName'], decisionId=context['decisionId'], hashVerified=True,
                               thinkingDisabled=True, passAvailable=True, targetType=item['objectType']))
    assert len(checks) == len(results) and len(checks) >= 5
    assert sum(r['counterExecuted'] for r in results) >= 3
    summary = dict(requestsVerified=len(checks), countersExecuted=sum(r['counterExecuted'] for r in results),
                   stifleSelections=sum(r['counterExecuted'] and r['card'] == 'Stifle' for r in results),
                   distinctExecutedCards=sorted({r['card'] for r in results if r['counterExecuted']}),
                   passSelections=sum(r['passSelected'] for r in results),
                   averageProviderLatencyMs=sum(r['providerLatencyMs'] for r in results) / len(results), checks=checks)
    (output / 'verification.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    print(f"Verified {len(checks)} requests, {summary['countersExecuted']} legally executed counters, zero fallback/stale failures.")


if __name__ == '__main__':
    if len(sys.argv) != 2: raise SystemExit(__doc__)
    verify(sys.argv[1])
