"""Stage 14.2: frozen public-request comparisons followed by matched full games.

Original behavior runs from an isolated copy of pre-change forge-ai classes. No
production flag, candidate reordering, response repair, or ai-eval input mutation.
"""
import argparse
import copy
import datetime
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
DECKS = {'Aurochs': ['Aurochs Stampede', 'Selesnya Surge'], 'Gatecrasher': ['Gatecrasher', 'Air Forces']}

def read_jsonl(path):
    return [json.loads(l) for l in path.read_text(encoding='utf-8-sig').splitlines() if l.strip()]

def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2)+'\n', encoding='utf-8')

def snapshot(root):
    return {str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(root.rglob('*')) if p.is_file()}

def revised_prompt():
    source=(ROOT/'forge-ai/src/main/java/forge/ai/decision/external/OpenAiCompatibleDecisionProvider.java').read_text()
    block=source.split('private static final String BLOCK_SYSTEM_PROMPT =',1)[1].split(';',1)[0]
    return ''.join(json.loads('"'+s+'"') for s in re.findall(r'"((?:[^"\\]|\\.)*)"',block))

def comparison_request(body, entry, variant, model):
    request=copy.deepcopy(body);request['model']=model
    payload=json.loads(request['messages'][1]['content'])
    if variant=='Revised':
        revised=entry['revisedContext'];payload['stateFingerprint']=revised['stateFingerprint']
        request['response_format']['json_schema']['schema']['properties']['stateFingerprint']['enum']=[revised['stateFingerprint']]
        payload['options']=copy.deepcopy(revised['options'])
        for option in payload['options']:option.setdefault('tacticalConsequences',None)
        request['messages'][0]['content']=revised_prompt()
        request['messages'][1]['content']=json.dumps(payload,separators=(',',':'),ensure_ascii=False)
    assert request['reasoning_effort']=='none'
    return request,payload

def validate_answer(response, payload, allowed):
    answer=json.loads(response['choices'][0]['message']['content'])
    if set(answer)!={'decisionId','stateFingerprint','optionId'}:raise ValueError('Unexpected response fields')
    if answer['decisionId']!=payload['decisionId']:raise ValueError('Wrong decisionId')
    if answer['stateFingerprint']!=payload['stateFingerprint']:raise ValueError('Wrong fingerprint')
    if answer['optionId'] not in allowed:raise ValueError('Unknown option')
    return answer['optionId']

def frozen(evidence, projections, output, endpoint, model):
    captures={}
    for p in evidence.glob('*.requests.jsonl'):
        for c in read_jsonl(p):
            payload=json.loads(c['Request']['messages'][1]['content'])
            if payload['decisionType']=='COMBAT_BLOCKERS':captures[payload['decisionId']]=c['Request']
    results=[]
    for entry in projections:
        original=entry['context']; body=captures[original['decisionId']]
        for variant in ('Original','Revised'):
            request,payload=comparison_request(body,entry,variant,model)
            started=time.perf_counter();error=None;response=None;selected=None
            try:
                req=urllib.request.Request(endpoint,data=json.dumps(request).encode(),headers={'Content-Type':'application/json'})
                with urllib.request.urlopen(req,timeout=90) as r:response=json.load(r)
                allowed=request['response_format']['json_schema']['schema']['properties']['optionId']['enum']
                selected=validate_answer(response,payload,allowed)
            except Exception as exc:error=repr(exc)
            options={o['optionId']:o for o in entry['revisedContext']['options']}
            result=dict(audit=entry['audit'],turn=entry['turn'],seat=entry['seat'],variant=variant,targeted=entry['targeted'],
                historicalChoice=entry['originalChoice'],forgeChoice=entry['forgeChoice'],selected=selected,error=error,
                latencyMs=round((time.perf_counter()-started)*1000),request=request,response=response,
                annotations=options.get(selected,{}).get('tacticalConsequences'))
            results.append(result);write(output/'frozen-results.json',results)
            print('Frozen',entry['audit'],'T'+str(entry['turn']),variant,selected or error,flush=True)
            if error:raise RuntimeError('Frozen provider validation failed: '+error)
    return results

def run_game(output, group, variant, matchup, seed, seat, original_classes, endpoint, model, runtime):
    directory=output/group/variant;directory.mkdir(parents=True,exist_ok=True)
    label=f'{matchup}-{seed}-seat{seat}-External';prefix=directory/label
    ai=original_classes if variant=='Original' else runtime/'forge-ai'
    classpath=';'.join(map(str,[runtime/'forge-gui-desktop',ai,runtime/'forge-gui',
                               runtime/'forge-game',runtime/'forge-core',runtime/'dependencies/*']))
    args=[shutil.which('java'),'-Dforge.ai.fullGame.audit=true',f'-Dforge.ai.fullGame.runId={label}',
        f'-Dforge.ai.fullGame.externalSeats={seat}','-Dforge.ai.external.combat.materialGuard=false',
        '-Dforge.ai.external.combat.materialGuardRaceAware=false',f'-Dforge.ai.combat.requestCapture={prefix}.requests.jsonl',
        '-Dforge.ai.combatAction.diagnostics=true','-cp',classpath,'forge.view.Main','sim','-D',str(ROOT/'forge-gui/res/quest/precons'),
        '-d',*[d+'.dck' for d in DECKS[matchup]],'-n','1','-s',str(seed),'-c','1800','-a','Default','Default',
        '--legacy-order-combatants','--external-ai-combat-integrated-disabled','--external-ai-model',model,
        '--external-ai-endpoint',endpoint,'--external-ai-timeout','60','--external-ai-response-format','JSON_SCHEMA',
        '--metrics-csv',str(prefix)+'.csv','--decision-audit-jsonl',str(prefix)+'.jsonl',
        '--external-ai-request-diagnostics','--external-ai-full-game-enabled','--external-ai-seats',str(seat)]
    write(directory/'run.json',dict(Group=group,Variant=variant,Model=model,Endpoint=endpoint,LegacyCombatOrder=True,ExperimentalGuards=False))
    write(pathlib.Path(str(prefix)+'.command.json'),args)
    print('Game',group,variant,label,flush=True);start=time.perf_counter()
    with open(str(prefix)+'.stdout.log','w',encoding='utf-8') as stdout,open(str(prefix)+'.stderr.log','w',encoding='utf-8') as stderr:
        process=subprocess.run(args,cwd=ROOT/'forge-gui-desktop',stdout=stdout,stderr=stderr,timeout=1920,
                               creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    rows=read_jsonl(pathlib.Path(str(prefix)+'.jsonl'))
    completed=any(r.get('recordType')=='FULL_GAME_SUMMARY' for r in rows)
    write(pathlib.Path(str(prefix)+'.run.json'),dict(Label=label,Matchup=matchup,Seed=seed,ExternalSeat=seat,
        Mode='External',Treatment=variant,Decks=DECKS[matchup],ExitCode=process.returncode,TimedOut=False,Crash=process.returncode!=0,
        Completed=completed,WallTimeMs=round((time.perf_counter()-start)*1000)))
    if process.returncode or not completed:raise RuntimeError('Game failed; evidence retained: '+str(prefix))
    return dict(group=group,variant=variant,label=label,completed=True)

def main(args):
    evidence=pathlib.Path(args.evidence).resolve();classes=pathlib.Path(args.original_classes).resolve()
    if not (classes/'forge/ai/ExternalCombatBlockers.class').exists():raise ValueError('Missing pre-change compiled AI snapshot')
    projections=json.loads((ROOT/'work/stage14-2/frozen-projections.json').read_text())
    before=snapshot(evidence);write(ROOT/'work/stage14-2/input-sha256.json',dict(root=str(evidence),files=before))
    output=ROOT/'ai-eval/stage14-2'/datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    output.mkdir(parents=True,exist_ok=False)
    # Test/export builds may replace target/classes. Games load only this fixed runtime.
    runtime=ROOT/'work/stage14-2'/('runtime-'+output.name)
    for module in ('forge-gui-desktop','forge-ai','forge-gui','forge-game','forge-core'):
        shutil.copytree(ROOT/module/'target/classes',runtime/module)
    (runtime/'dependencies').mkdir()
    for jar in (ROOT/'forge-gui-desktop/target').glob('*.jar'):
        shutil.copy2(jar,runtime/'dependencies'/jar.name)
    build_hashes=snapshot(runtime)
    write(output/'build-identities.json',dict(Runtime=str(runtime),Files=build_hashes,OriginalAiFiles=snapshot(classes)))
    write(ROOT/'work/stage14-2/latest-run.json',dict(output=str(output)))
    write(output/'run.json',dict(Model=args.model,Endpoint=args.endpoint,OriginalClasses=str(classes),Evidence=str(evidence),
        Note='Matched original/revised external seats; not a comparison against wholly native Forge. Six targeted pairs then eight cohort pairs, partly overlapping the targeted seed schedule.'))
    frozen(evidence,projections,output,args.endpoint,args.model)
    if not args.frozen_only:
        targeted=sorted({(e['audit'].split('-')[0],e['seed'],e['seat']) for e in projections if e['targeted']})
        assert len(targeted)==6
        cohort=[(m,s,seat) for m in DECKS for s in (1401,1402) for seat in (0,1)]
        progress=[]
        for group,games in [('targeted',targeted),('cohort',cohort)]:
            for i,(m,s,seat) in enumerate(games):
                # Alternate treatment order across pairs; do not run competing model calls concurrently.
                variants=('Original','Revised') if i%2==0 else ('Revised','Original')
                for variant in variants:
                    progress.append(run_game(output,group,variant,m,s,seat,classes,args.endpoint,args.model,runtime))
                    write(output/'progress.json',progress)
    assert before==snapshot(evidence),'Original Stage 14 evidence changed'
    assert build_hashes==snapshot(runtime),'Evaluation runtime changed'
    write(output/'input-verification.json',dict(files=len(before),unchanged=True))
    print('Stage 14.2 evidence:',output,flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--evidence',default=str(ROOT/'ai-eval/stage14/20261008-230210-574219ba'))
    parser.add_argument('--original-classes',default=str(ROOT/'work/stage14-2/original-ai'))
    parser.add_argument('--model',default='qwen3:8b');parser.add_argument('--endpoint',default='http://localhost:11434/v1/chat/completions')
    parser.add_argument('--frozen-only',action='store_true');main(parser.parse_args())
