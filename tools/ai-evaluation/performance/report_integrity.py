"""Manifest-bound report correction. Never recomputes or substitutes statistical estimates."""
import copy, hashlib, itertools, json
from pathlib import Path

def unique_object(pairs):
    result={}
    for key,value in pairs:
        if key in result: raise ValueError('Duplicate JSON key: '+key)
        result[key]=value
    return result
def load(path): return json.loads(Path(path).read_text(encoding='utf-8-sig'),object_pairs_hook=unique_object)
def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def deck_hashes(manifest): return manifest.get('deckHashes',manifest.get('normalizedDeckHashes',{}))

def validate(summary, manifest, labels, allow_legacy_labels=False):
    if not __debug__: raise RuntimeError('Run report validation without Python optimisation')
    variants = manifest['productionVariants']
    assert len(variants) == len(set(variants)) == 3, 'Missing or duplicate manifest arms'
    assert set(labels) == set(variants) == set(summary['variants']), 'Mismatched report arms'
    assert len(set(labels.values())) == len(labels), 'Duplicate display names'
    assert len({summary['variants'][v]['label'] for v in variants})==3, 'Duplicate report display names'
    if not allow_legacy_labels:
        assert all(summary['variants'][v]['label']==labels[v] for v in variants), 'Wrong display labels'
    games = summary['games']; keys = [(r['variant'],r['seed']) for r in games]
    assert len(keys) == len(set(keys)), 'Duplicate game identity'
    assert all(v in variants for v,s in keys), 'Unknown game arm'
    if not allow_legacy_labels:
        assert all(r['build']==labels[r['variant']] for r in games), 'Game display label mismatch'
    for v in variants:
        assert deck_hashes(manifest).get(v+'.dck'), 'Missing frozen deck hash'
        rows = [r for r in games if r['variant']==v]
        assert {r['seed'] for r in rows} == set(manifest['seeds']), 'Missing or unexpected seeds'
        assert all(all(type(r[k]) is bool for k in ('win','loss','draw')) and
                   sum(r[k] for k in ('win','loss','draw'))==1 for r in rows), 'Contradictory outcome'
        stats = summary['variants'][v]
        assert stats['valid']==len(rows), 'Count mismatch'
        for flag,total in [('win','wins'),('loss','losses'),('draw','draws')]:
            assert stats[total]==sum(r[flag] for r in rows), 'Outcome count mismatch'
        assert stats['winRate']==stats['wins']/len(rows), 'Win rate mismatch'
    expected = {summary['variants'][b]['label']+' minus '+summary['variants'][a]['label']
                for a,b in itertools.combinations(variants,2)}
    assert set(summary['pairedComparisons'])==expected, 'Missing or mismatched comparisons'
    for a,b in itertools.combinations(variants,2):
        c=summary['pairedComparisons'][summary['variants'][b]['label']+' minus '+summary['variants'][a]['label']]
        c=c.get('pairedWinRateDifference',c)
        assert c['pairs']==len(manifest['seeds']), 'Comparison pair count'
        expected_difference=(summary['variants'][b]['wins']-summary['variants'][a]['wins'])/len(manifest['seeds'])
        assert abs(c['difference']-expected_difference)<1e-12, 'Comparison outcome mismatch'

def correct(root, output):
    root=Path(root);output=Path(output);output.mkdir(parents=True,exist_ok=False)
    summary=load(root/'public/summary.json');manifest=load(root/'public/manifest.json')
    labels=load(Path(__file__).with_name('variant-labels.json'))[manifest['runId']]
    validate(summary,manifest,labels,allow_legacy_labels=True)
    request=load(root/'request.json'); evidence=[]
    for row in summary['games']:
        d=root/row['rawDirectory'];a=load(d/'attempt.json');cmd=load(d/'command.json')
        assert a['valid'] is True and a['variant']==row['variant'] and a['runSeed']==row['seed'], 'Attempt identity'
        terminal=[json.loads(line) for line in (d/f'commander-seed{row["seed"]}.games.jsonl').read_text(encoding='utf-8-sig').splitlines() if line.strip()]
        assert len(terminal)==1, 'Missing or duplicate terminal game'
        game=terminal[0]
        assert game.get('valid') is True and not game.get('timedOut') and not game.get('simulationError') and not game.get('engineFailures'), 'Failed game record'
        assert game['winner']==a['winner'] and game['draw']==a['draw'] and game['runSeed']==row['seed'], 'Terminal outcome mismatch'
        assert not a.get('draw') and bool(a['winner'].startswith('Ai(1)-'))==row['win'] or a.get('draw') and row['draw'], 'Winner mismatch'
        assert cmd[cmd.index('-d')+1]==row['variant']+'.dck', 'Actual deck/variant mismatch'
        assert int(cmd[cmd.index('-s')+1])==row['seed'], 'Actual seed mismatch'
        deck=Path(cmd[cmd.index('-D')+1])/(row['variant']+'.dck')
        h=sha(deck);assert h==deck_hashes(manifest)[deck.name]==request['deckHashes'][deck.name], 'Deck hash mismatch'
        evidence.append(dict(variant=row['variant'],seed=row['seed'],deckSHA256=h,attemptSHA256=sha(d/'attempt.json')))
    fixed=copy.deepcopy(summary);old={v:summary['variants'][v]['label'] for v in labels}
    fixed['pairedComparisons']={labels[b]+' minus '+labels[a]:copy.deepcopy(summary['pairedComparisons'][old[b]+' minus '+old[a]])
        for a,b in itertools.combinations(manifest['productionVariants'],2)}
    for v,label in labels.items(): fixed['variants'][v]['label']=label
    for row in fixed['games']:row['build']=labels[row['variant']]
    validate(fixed,manifest,labels)
    provenance=dict(schemaVersion=1,runId=manifest['runId'],originalSummarySHA256=sha(root/'public/summary.json'),
        originalReportSHA256=sha(root/'public/REPORT.md'),manifestSHA256=sha(root/'public/manifest.json'),
        labelsSHA256=sha(Path(__file__).with_name('variant-labels.json')),labels=labels,
        policy='Display labels and comparison keys corrected only; original outcomes and numerical statistics unchanged.',games=evidence)
    report=(root/'public/REPORT.md').read_text(encoding='utf-8-sig')
    # Simultaneous replacement prevents one corrected label being replaced a second time.
    import re
    pattern=re.compile('|'.join(re.escape(old[v]) for v in sorted(old,key=lambda v:len(old[v]),reverse=True)))
    translation={old[v]:labels[v] for v in labels}
    report=pattern.sub(lambda m:translation[m[0]],report)
    report='# Correction notice — variant display labels\n\nCorrected 2026-10-27. Original results, confidence intervals and game records are unchanged. '
    report+='The original report and summary are preserved. See [CORRECTION.md](CORRECTION.md) and [summary.corrected.json](summary.corrected.json).\n\n'+pattern.sub(lambda m:translation[m[0]],(root/'public/REPORT.md').read_text(encoding='utf-8-sig'))
    (output/'REPORT.md').write_text(report,encoding='utf-8')
    (output/'REPORT.original.md').write_bytes((root/'public/REPORT.md').read_bytes())
    (output/'summary.corrected.json').write_text(json.dumps(fixed,indent=2)+'\n',encoding='utf-8')
    (output/'correction-provenance.json').write_text(json.dumps(provenance,indent=2)+'\n',encoding='utf-8')
    notice='# Variant-label correction\n\nThe previous generator zipped three hardcoded labels from the earlier AB experiment to the Throne variants. '
    notice+='Immutable variant IDs and game outcomes were correct; challenger display labels were wrong.\n\n| Variant | Correct label | Wins | Games |\n|---|---|---:|---:|\n'
    for v,label in labels.items():notice+=f'| {v} | {label} | {fixed["variants"][v]["wins"]} | {fixed["variants"][v]["valid"]} |\n'
    notice+='\nNumerical statistics were copied without resampling. All game identities, frozen deck hashes, win/loss totals and comparison directions were independently checked. Original REPORT.md is archived as REPORT.original.md; original summary.json remains untouched. Use summary.corrected.json for corrected display names. No new games or altered outcomes.\n'
    (output/'CORRECTION.md').write_text(notice,encoding='utf-8')
    return provenance

if __name__=='__main__':
    import sys
    print(json.dumps(correct(sys.argv[1],sys.argv[2]),indent=2))
