"""Read-only Stage 14 strategic-review dossier. Writes only to a separate analysis directory."""
import argparse
import collections
import hashlib
import json
import pathlib
import statistics

def read(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8-sig').splitlines()]

def snapshot(root):
    return {str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(root.rglob('*')) if p.is_file()}

def context(row):
    return row.get('modelVisibleDecision') or dict(state=row.get('state',row.get('visibleState',{})),
        options=row.get('legalActions',row.get('internalActionDetails',[])))

def players(row):
    return context(row).get('state',{}).get('players',[])

def card(p):
    return p.get('permanent',p).get('card',p)

def label(p):
    c=card(p)
    text=c.get('name','?')
    if 'power' in c: text+=f" {c['power']}/{c['toughness']}"
    if c.get('markedDamage',c.get('damage',0)): text+=f" dmg={c.get('markedDamage',c.get('damage'))}"
    keywords=p.get('permanent',p).get('keywords',c.get('keywords',[]))
    if keywords: text+=' ['+', '.join(keywords)+']'
    if c.get('tapped'): text+=' tapped'
    return text

def creature_boards(row):
    return [{'seat':i,'life':p.get('life'),'creatures':[label(c) for c in p.get('battlefield',[]) if 'power' in card(c)]}
            for i,p in enumerate(players(row))]

def short(row):
    c=context(row); s=c.get('state',{}); mapping={}
    for p in s.get('players',[]):
        for permanent in p.get('battlefield',[]): mapping[permanent.get('permanentId')]=label(permanent)
    def assignment(a):
        if 'blockerId' in a: return mapping.get(a['blockerId'],a['blockerId'])+' -> '+mapping.get(a['attackerId'],a['attackerId'])
        return mapping.get(a['attackerId'],a['attackerId'])+' -> '+a.get('defenderId','?')
    result=dict(boards=creature_boards(row), attackers=[label(a) for a in s.get('attackers',[])],
                blockers=[label(b) for b in s.get('blockers',[])], options=[])
    for o in c.get('options',[]):
        result['options'].append(dict(id=o.get('optionId',o.get('actionId')),
            assignments=[assignment(a) for a in o.get('assignments',o.get('attackers',[]))],
            **{k:o[k] for k in ('sourceName','cost','targets','modes','xValue','effect','description','category') if k in o}))
    return result

def main(root,out):
    root=pathlib.Path(root).resolve(); out=pathlib.Path(out).resolve()
    if out==root or root in out.parents: raise ValueError('Analysis output must be outside evaluation evidence')
    out.mkdir(parents=True,exist_ok=True); before=snapshot(root)
    dossier=[]; games=[]; combats=[]; ids=set(); captures=[]
    for meta_path in sorted(root.glob('*.run.json')):
        meta=json.loads(meta_path.read_text(encoding='utf-8-sig')); audit=meta_path.with_name(meta_path.name.replace('.run.json','.jsonl'))
        rows=read(audit); summary=next(r for r in rows if r.get('recordType')=='FULL_GAME_SUMMARY')
        refs=[r for r in rows if r.get('recordType')=='FULL_GAME_DECISION' and r['providerAttempted']]
        detailed={r.get('decisionId',r.get('telemetry',{}).get('decisionId')):r for r in rows if r.get('decisionType') in
            ('MAIN_PHASE_ACTION','STACK_RESPONSE','COMBAT_ATTACKERS','COMBAT_BLOCKERS','COMBAT_DAMAGE_ORDER','COMBAT_ACTION')}
        sequences={r['combatSequenceId']:r for r in rows if r.get('decisionType')=='COMBAT_SEQUENCE'}
        for sequence in sequences.values():
            groups=collections.Counter(a['attackerRef'] for a in sequence['declaredBlockAssignments'])
            if max(groups.values(),default=0)>1:
                combats.append(dict(audit=audit.name,mode=meta['Mode'],externalSeat=meta['ExternalSeat'],
                    multiBlockAttackerRefs=[k for k,v in groups.items() if v>1],sequence=sequence))
        if meta['Mode']=='External':
            winner=summary.get('winner',''); outcome='draw' if summary['draw'] else 'win' if winner.startswith(f"Ai({meta['ExternalSeat']+1})-") else 'loss'
            counts=collections.Counter(r['surface'] for r in refs if not r['agreement'])
            games.append(dict(audit=audit.name,seed=meta['Seed'],matchup=meta['Matchup'],seat=meta['ExternalSeat'],
                outcome=outcome,calls=len(refs),turns=summary['turns'],durationMs=summary['durationMs'],
                disagreements=sum(counts.values()),bySurface=dict(counts)))
            for ref in refs:
                assert ref['decisionId'] not in ids; ids.add(ref['decisionId'])
                row=detailed.get(ref['decisionId'])
                if row is None: continue # Mulligans are compact refs plus captured requests.
                sequence=sequences.get(ref.get('combatSequenceId'))
                later=[]
                for nextref in refs:
                    if nextref['ordinal']<=ref['ordinal']:continue
                    nextrow=detailed.get(nextref['decisionId'])
                    if nextrow and players(nextrow):
                        later.append(dict(turn=nextref['turn'],phase=nextref['phase'],boards=creature_boards(nextrow)));break
                dossier.append(dict(audit=audit.name,seed=meta['Seed'],matchup=meta['Matchup'],seat=meta['ExternalSeat'],outcome=outcome,
                    ref=ref,view=short(row),context=context(row),
                    immediateCombat=sequence, nextPublicBoard=later[0] if later else None,
                    stackItems=row.get('stackItems',[])))
        capture_path=audit.with_name(audit.name.replace('.jsonl','.requests.jsonl'))
        if capture_path.exists():
            for capture in read(capture_path):
                payload=json.loads(capture['Request']['messages'][1]['content'])
                enum=capture['Request']['response_format']['json_schema']['schema']['properties']['optionId']['enum']
                captures.append(dict(audit=audit.name,decisionId=payload['decisionId'],surface=payload['decisionType'],
                    options=enum,passOffered='PASS' in enum,noAttackOffered='NO_ATTACK' in enum,noBlockOffered='NO_BLOCK' in enum))
    correlations={}
    for outcome in ('win','loss'):
        group=[g for g in games if g['outcome']==outcome]
        correlations[outcome]=dict(games=len(group),callsPerGame=statistics.mean(g['calls'] for g in group),
            turns=statistics.mean(g['turns'] for g in group),durationMs=statistics.mean(g['durationMs'] for g in group),
            disagreements=statistics.mean(g['disagreements'] for g in group),
            disagreementsBySurface={surface:statistics.mean(g['bySurface'].get(surface,0) for g in group)
                for surface in ('MULLIGAN_KEEP','MAIN_PHASE_ACTION','STACK_RESPONSE','COMBAT_ATTACKERS','COMBAT_BLOCKERS','COMBAT_ACTION')})
    counts=collections.Counter(d['ref']['surface'] for d in dossier if not d['ref']['agreement'])
    assert counts['COMBAT_BLOCKERS']==16 and counts['COMBAT_ATTACKERS']==28 and counts['MAIN_PHASE_ACTION']==1 and counts['STACK_RESPONSE']==2
    assert len([d for d in dossier if d['ref']['surface']=='COMBAT_ACTION'])==5
    result=dict(games=games,correlations=correlations,multiBlocks=combats,passCoverage=captures,decisions=dossier)
    (out/'dossier.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    for surface in ('COMBAT_BLOCKERS','COMBAT_ATTACKERS','MAIN_PHASE_ACTION','STACK_RESPONSE','COMBAT_ACTION'):
        items=[d for d in dossier if d['ref']['surface']==surface and (not d['ref']['agreement'] or surface=='COMBAT_ACTION')]
        lines=[]
        for i,d in enumerate(items,1):
            lines.append(f"\n{i}. {d['audit']} T{d['ref']['turn']} {d['ref']['phase']} {d['outcome']} {d['ref']['decisionId']}")
            lines.append('Forge='+d['ref']['heuristicOption']+' External='+d['ref']['selectedOption'])
            lines.append(json.dumps(d['view'],ensure_ascii=False))
            if d['stackItems']:lines.append('STACK '+json.dumps(d['stackItems']))
            seq=d['immediateCombat']
            if seq: lines.append('RESULT '+json.dumps({k:seq[k] for k in ('beforeState','declaredAttackers','declaredBlockers','declaredBlockAssignments','afterState','damageCompleted','completionReason','coherenceIssues')}))
            lines.append('NEXT '+json.dumps(d['nextPublicBoard']))
        (out/(surface+'.txt')).write_text('\n'.join(lines)+'\n',encoding='utf-8')
    after=snapshot(root); assert before==after,'Evaluation evidence changed during analysis'
    (out/'evidence-sha256.json').write_text(json.dumps(dict(root=str(root),files=before,unchanged=True),indent=2)+'\n')
    print(json.dumps(dict(games=len(games),providerCalls=len(captures),disagreements=counts,multiBlockCombats=len(combats),correlations=correlations),indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('evidence');parser.add_argument('output');args=parser.parse_args()
    main(args.evidence,args.output)
