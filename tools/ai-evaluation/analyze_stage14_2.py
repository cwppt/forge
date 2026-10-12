"""Read-only audit reconciliation and conservative tactical-coverage comparison."""
import argparse
import collections
import json
import pathlib
from evaluate_stage14_2 import read_jsonl, write

def collect(root):
    games=[];entries=[]
    for audit in sorted(root.glob('*/*/*-External.jsonl')):
        rows=read_jsonl(audit);summaries=[r for r in rows if r.get('recordType')=='FULL_GAME_SUMMARY']
        if not summaries:continue
        summary=summaries[0];meta=json.loads(audit.with_name(audit.stem+'.run.json').read_text(encoding='utf-8-sig'))
        refs=[r for r in rows if r.get('recordType')=='FULL_GAME_DECISION' and r['providerAttempted']]
        blocks={r['modelVisibleDecision']['decisionId']:r for r in rows if r.get('decisionType')=='COMBAT_BLOCKERS'}
        captures=read_jsonl(audit.with_name(audit.stem+'.requests.jsonl'))
        assert len(captures)==len(refs),(str(audit),'capture/reference mismatch')
        for capture in captures:
            body=capture['Request'];payload=json.loads(body['messages'][1]['content'])
            reference=next(r for r in refs if r['decisionId']==payload['decisionId'])
            assert payload['stateFingerprint']==reference['stateFingerprint']
            schema=body['response_format']['json_schema']['schema']['properties']
            assert reference['selectedOption'] in schema['optionId']['enum']
            for field in ('decisionId','stateFingerprint'):
                if 'enum' in schema[field]:assert schema[field]['enum']==[payload[field]]
            assert body.get('reasoning_effort')=='none'
        outcome='draw' if summary['draw'] else 'win' if summary['winner'].startswith(f"Ai({meta['ExternalSeat']+1})-") else 'loss'
        games.append(dict(group=audit.parent.parent.name,variant=audit.parent.name,audit=str(audit),outcome=outcome,
            turns=summary['turns'],refs=refs,meta=meta))
        for ref in refs:
            if ref['surface']!='COMBAT_BLOCKERS':continue
            detail=blocks[ref['decisionId']];context=detail['modelVisibleDecision'];returned=detail['telemetry'].get('returnedOptionId')
            entries.append(dict(audit=str(audit),group=audit.parent.parent.name,variant=audit.parent.name,seed=meta['Seed'],
                seat=meta['ExternalSeat'],turn=ref['turn'],outcome=outcome,originalChoice=ref['selectedOption'],
                rawChoice=returned or ref['selectedOption'],forgeChoice=ref['heuristicOption'],targeted=False,context=context,
                ref=ref))
    return games,entries

def block_metrics(entries, reconstructed):
    result=collections.Counter(decisions=len(entries))
    for e in entries:
        result['noBlock']+=e['originalChoice']=='NO_BLOCK'
        result['disagreements']+=e['originalChoice']!=e['forgeChoice']
        context=e['context'] if e['variant']=='Revised' else reconstructed[e['context']['decisionId']]['revisedContext']
        options={o['optionId']:o for o in context['options']}
        selected=options[e['originalChoice']].get('tacticalConsequences')
        raw=options.get(e['rawChoice'],{}).get('tacticalConsequences')
        if selected:
            result['annotatedSelected']+=1
            result['unnecessaryLosingBlocks']+=selected['unnecessaryLosingBlock']
            result['lethalSavingBlocks']+=selected['preventsLethal']
            result['nearLethalProtectionSelections']+=selected['nearLethalProtection']
            result['killsAttackerSelections']+=selected['projectedCreatureLossesOpponent']>0
            result['killsWithoutOwnLossSelections']+=selected['projectedCreatureLossesOpponent']>0 and selected['projectedCreatureLossesSelf']==0
        else:result['unknownSelected']+=1
        saving=[o for o in options.values() if o.get('tacticalConsequences',{} ) and o['tacticalConsequences']['preventsLethal']]
        if saving:
            result['visibleLethalSavingOpportunities']+=1
            result['rawMissedLethalPrevention']+=e['rawChoice']=='NO_BLOCK' and raw is not None and raw['projectedLifeAfterCombat']<=0
    keys=('noBlock','disagreements','annotatedSelected','unnecessaryLosingBlocks','lethalSavingBlocks','nearLethalProtectionSelections',
          'killsAttackerSelections','killsWithoutOwnLossSelections','unknownSelected','visibleLethalSavingOpportunities','rawMissedLethalPrevention')
    for key in keys:result.setdefault(key,0)
    return dict(result,noBlockRate=result['noBlock']/len(entries) if entries else None)

def main(args):
    root=pathlib.Path(args.path).resolve();games,entries=collect(root)
    write(root/'live-projection-input.json',entries)
    if args.export_only:
        print('Prepared',len(entries),'live blocker states for Forge projection export');return
    reconstructed=json.loads((root/'live-projections.json').read_text())
    reconstructed={r['context']['decisionId']:r for r in reconstructed}
    assert len(reconstructed)==len(entries)
    summary={}
    for group in ('targeted','cohort'):
        summary[group]={}
        for variant in ('Original','Revised'):
            subset=[g for g in games if g['group']==group and g['variant']==variant]
            refs=[r for g in subset for r in g['refs']]
            blocks=[e for e in entries if e['group']==group and e['variant']==variant]
            summary[group][variant]=dict(games=len(subset),wins=sum(g['outcome']=='win' for g in subset),
                losses=sum(g['outcome']=='loss' for g in subset),draws=sum(g['outcome']=='draw' for g in subset),
                calls=len(refs),fallbacks=sum(r['fallback'] for r in refs),stale=sum(r['staleOrRevalidationFailure'] for r in refs),
                duplicates=sum(r['duplicateRequest'] for r in refs),blocker=block_metrics(blocks,reconstructed))
    write(root/'comparison-summary.json',summary);print(json.dumps(summary,indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('path');p.add_argument('--export-only',action='store_true');main(p.parse_args())
