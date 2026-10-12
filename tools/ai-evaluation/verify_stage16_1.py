"""Verify captured Earthbrawn fixture evidence without another provider call.

Usage from repo root: python tools/ai-evaluation/verify_stage16_1.py <run-directory>
"""
import base64
import hashlib
import json
import pathlib
import sys


def verify(directory):
    output = pathlib.Path(directory)
    checks = []
    for path in sorted(output.glob('*.requests.jsonl')):
        for line in path.read_text(encoding='utf-8').splitlines():
            record = json.loads(line)
            request = record['Request']
            raw = base64.b64decode(record['RequestUtf8Base64'])
            assert hashlib.sha256(raw).hexdigest().upper() == record['RequestSha256']
            assert json.loads(raw) == request
            assert request['model'] == 'qwen3:8b' and request['reasoning_effort'] == 'none'
            context = json.loads(request['messages'][1]['content'])
            assert context['decisionType'] == 'COMBAT_ACTION' and context['passAvailable']
            assert len(context['options']) == 1
            option = context['options'][0]
            assert option['category'] == 'SPELL' and option['sourceName'] == 'Earthbrawn'
            assert option['cost'] == '{1}{G}' and option['optionalCosts'] == [] and option['xValue'] is None
            assert option['optionId'].startswith('OPT_') and any('Runeclaw Bear' in t for t in option['targets'])
            assert all(s not in request['messages'][1]['content'] for s in ('Reinforce', 'Black Lotus', 'VERIFIED_SAFE'))
            enum = request['response_format']['json_schema']['schema']['properties']['optionId']['enum']
            assert set(enum) == {'PASS', option['optionId']}
            support_path = pathlib.Path(str(path).replace('.requests.jsonl', '.eligibility.jsonl'))
            support = [json.loads(line) for line in support_path.read_text(encoding='utf-8').splitlines()]
            event = next(e for e in support if e.get('kind') == 'ABILITY_SUPPORT')
            assert any(a['spell'] and a['support'] == 'VERIFIED_SAFE' and a['hostWarning'] for a in event['abilities'])
            assert any(not a['spell'] and a['support'] == 'UNSUPPORTED' and 'Reinforce' in a['description'] for a in event['abilities'])
            checks.append(dict(file=str(path), decisionId=context['decisionId'], optionId=option['optionId'],
                               requestHashVerified=True, thinkingDisabled=True, reinforceRejected=True, hiddenHandAbsent=True))
    results = json.loads((output / 'fixture-results.json').read_text(encoding='utf-8'))
    assert checks and len(checks) == len(results)
    assert all(not r['fallback'] and not r['stale'] for r in results)
    assert any(r['pumpExecuted'] for r in results)
    summary = dict(requestsVerified=len(checks), pumpsExecuted=sum(r['pumpExecuted'] for r in results),
                   passSelections=sum(r['passSelected'] for r in results),
                   averageProviderLatencyMs=sum(r['providerLatencyMs'] for r in results) / len(results), checks=checks)
    (output / 'verification.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    print(f"Verified {len(checks)} requests, {summary['pumpsExecuted']} legally executed pumps, zero fallback/stale failures.")


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    verify(sys.argv[1])
