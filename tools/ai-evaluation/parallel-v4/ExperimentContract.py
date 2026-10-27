"""Explicit experiment intent, checked before preparation and again by the worker."""
from collections import Counter
from pathlib import Path
import json

SETS = {
    'Legacy': [('R2','Rivaz-R2.dck',36),('R3','Rivaz-R3.dck',37),('R3-38L','Rivaz-R3-38L.dck',38)],
    'R3-R4-A': [('R3','Rivaz-R3.dck',37),('R4-A','Rivaz-R4-A.dck',37)],
}


def cards(path):
    result=Counter();section=None
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        if line.startswith('['):section=line
        elif section in ('[Main]','[Commander]','[Sideboard]','[Companion]','[Scheme]','[Planes]','[Avatar]') and line.strip():
            quantity,name=line.split(' ',1);result[(section,name)]+=int(quantity)
    return result


def verify_delta(deckdir):
    before=cards(deckdir/'Rivaz-R3.dck');after=cards(deckdir/'Rivaz-R4-A.dck')
    if before-after!=Counter({('[Main]','Reforge the Soul'):1}) or after-before!=Counter({('[Main]','Rakdos Charm'):1}):
        raise RuntimeError('R4-A must be exactly R3 minus one Reforge the Soul plus one Rakdos Charm')


def check(args,api,emit=True):
    if not getattr(args,'experiment_spec',None):raise RuntimeError('New experiments require an explicit -ExperimentSpec')
    path=Path(args.experiment_spec).resolve();spec=api.read(path)
    selection=getattr(args,'candidate_set','Legacy')
    if selection not in SETS:raise RuntimeError('Unknown candidate set')
    deckdir=api.ROOT/'work/commander-smoke-decks';actual=[]
    for name,filename,lands in SETS[selection]:
        deck=deckdir/filename
        display=next((line[5:] for line in deck.read_text(encoding='utf-8-sig').splitlines() if line.startswith('Name=')),filename)
        actual.append(dict(name=name,file=filename,sha256=api.sha(deck),landCount=api.land_count(deck),validGames=args.games,deckName=display))
    preview=dict(candidateSet=selection,candidates=actual,aiProfile=getattr(args,'ai_profile','standard'),
        maxParallelGames=args.max_parallel,gamesPerVariant=args.games,totalValidGames=args.games*len(actual),timeoutSeconds=args.timeout,
        experimentSpec=str(path),experimentSpecSha256=api.sha(path))
    if emit:print(json.dumps(preview,indent=2))
    for key in ('candidateSet','aiProfile','maxParallelGames','gamesPerVariant','timeoutSeconds'):
        if preview[key]!=spec.get(key):raise RuntimeError('Requested configuration mismatch: '+key)
    expected=spec.get('candidates',[])
    normalized=[{k:c[k] for k in ('name','file','sha256','landCount','validGames')} for c in actual]
    if normalized!=expected:raise RuntimeError('Requested candidate names, hashes or targets differ from experiment specification')
    opponents=[dict(file=f,sha256=api.sha(deckdir/f)) for f in api.OPPONENTS]
    if opponents!=spec.get('opponents'):raise RuntimeError('Opponent deck hashes differ from experiment specification')
    if selection=='R3-R4-A':verify_delta(deckdir)
    return spec,path,preview


def activate(request,api):
    candidates=request.get('candidateSpecs')
    if not candidates:raise RuntimeError('v4 request has no frozen candidate configuration')
    api.VARIANTS=[(c['name'],c['file'],c['landCount']) for c in candidates]


def verify_frozen(request,api):
    spec=request.get('experimentSpec')
    if not spec:raise RuntimeError('Missing frozen experiment specification')
    if request['candidateSpecs']!=spec['candidates'] or request['parallelGames']!=spec['maxParallelGames'] or request['target']!=spec['gamesPerVariant'] or request['aiProfile']!=spec['aiProfile'] or request['timeoutSeconds']!=spec['timeoutSeconds'] or request.get('candidateSet')!=spec['candidateSet']:
        raise RuntimeError('Frozen experiment configuration differs from explicitly requested specification')
    frozen=api.STORE/f"seed{request['seed']}"/'experiment-spec.json'
    if api.read(frozen)!=spec: raise RuntimeError('Frozen request disagrees with saved experiment specification')
    if len({c['name'] for c in request['candidateSpecs']})!=len(request['candidateSpecs']):raise RuntimeError('Duplicate candidate names')
