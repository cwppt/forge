"""Local fixed-attempt R4 comparisons; reuse the established isolated parallel pool."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import threading

HERE=Path(__file__).resolve().parent
ROOT=HERE.parent.parent
NAMES=['R4-control','R4-warping','R4-endless-sands']
REMOVE=['Archwing Dragon','Rapacious Dragon','Purphoros, Bronze-Blooded','Terror of Mount Velus','Goldlust Triad','Blade of Selves','Reforge the Soul','Bolt Bend','Voyager Staff','Drakuseth, Maw of Flames','Dragonspeaker Shaman','Path of Ancestry','Mountain']
ADD=['Boneyard Scourge','Ruthless Technomancer','Apprentice Necromancer','Scavenger Regent','Morbid Opportunist','Wishclaw Talisman','Final Parting','Malakir Rebirth',"Colfenor's Urn",'Goblin Bombardment','Braids, Arisen Nightmare','Haunted Ridge','Swamp']

def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec)
    sys.modules[name]=m;spec.loader.exec_module(m);return m

def cards(path):
    sections={};section=None
    for raw in path.read_text(encoding='utf-8-sig').splitlines():
        line=raw.strip()
        if not line or line.startswith('#'):continue
        if line.startswith('['):section=line.lower();sections.setdefault(section,Counter());continue
        if section=='[metadata]':continue
        qty,name=line.split(' ',1)
        if int(qty)<1 or section is None:raise RuntimeError('Invalid deck entry')
        sections[section][name.split('|')[0]]+=int(qty)
    return sections

def validate_inputs():
    source=ROOT/'experiment';expected=cards(source/'Rivaz-R3.dck')['[main]'].copy()
    expected.subtract(REMOVE);expected.update(ADD);expected=+expected
    for i,name in enumerate(NAMES):
        if i==1:expected.subtract({"Tibalt's Trickery":1});expected.update({'Warping Wail':1})
        if i==2:expected.subtract({'Terramorphic Expanse':1});expected.update({'Endless Sands':1})
        parsed=cards(source/(name+'.dck'))
        if parsed['[main]']!=+expected:raise RuntimeError('Unexpected R3/R4 card delta in '+name)
        if parsed.get('[commander]')!=Counter({'Rivaz of the Claw':1}) or sum(parsed['[main]'].values())!=99:raise RuntimeError('Wrong commander/count: '+name)
        if any(v for k,v in parsed.items() if k not in ('[metadata]','[main]','[commander]')):raise RuntimeError('Unexpected card section')
        for n,q in (parsed['[main]']+parsed['[commander]']).items():
            if q>1 and n not in ('Mountain','Swamp'):raise RuntimeError('Nonbasic duplicate: '+n)
        for n in ['Knollspine Dragon','Sarkhan, Fireblood','Unexpected Windfall',"Colfenor's Urn",'Goblin Bombardment','Braids, Arisen Nightmare','Morbid Opportunist']:
            if parsed['[main]'][n]!=1:raise RuntimeError('Missing protected card: '+n)
        if cards(ROOT/'work/commander-smoke-decks'/(name+'.dck'))!=parsed:raise RuntimeError('Smoke and experiment copies differ: '+name)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--games',type=int,default=2);p.add_argument('--seed',type=int,required=True)
    p.add_argument('--timeout',type=int,default=180);p.add_argument('--workers',type=int,default=2)
    p.add_argument('--prepare-only',action='store_true')
    p.add_argument('--java',default=r'C:\Program Files\Java\jdk-25.0.4.1\bin\java.exe')
    args=p.parse_args()
    if not 1<=args.games<=1000 or not 1<=args.workers<=4 or not 30<=args.timeout<=3600:p.error('Invalid bounded experiment settings')
    api=module('r4_api',HERE/'parallel-v4/CommanderBatch.py')
    pool=module('r4_pool',HERE/'parallel-v4/ParallelCommanderPool.py')
    validate_inputs()
    batch=ROOT/f'work/commander-comparisons/seed{args.seed}'
    if batch.exists() or (ROOT/f'work/commander-batches/seed{args.seed}').exists():raise RuntimeError('Seed exists; refusing to overwrite results')
    with api.WorkerLock():
        batch.mkdir();(batch/'decks').mkdir()
        protected=[ROOT/'experiment/Rivaz-R3.dck',ROOT/'work/commander-smoke-decks/Rivaz-R3.dck',ROOT/'work/commander-smoke-decks/Rivaz-R3-38L.dck']
        before={str(f):api.sha(f) for f in protected}
        for f in [n+'.dck' for n in NAMES]+api.OPPONENTS:shutil.copy2(ROOT/'work/commander-smoke-decks'/f,batch/'decks'/f)
        jar=ROOT/'forge-gui-desktop/target/forge-gui-desktop-2.0.16-SNAPSHOT-jar-with-dependencies.jar'
        request=dict(seed=args.seed,jar=str(jar),java=args.java,heapGiB=4,activeProcessorCount=2,timeoutSeconds=args.timeout,
            aiProfile='standard',javaOptions=[],preferenceHashes={},gamesPerVariant=args.games,maxParallelGames=args.workers,
            candidates=NAMES,externalAIEnabled=False,publishOnCompletion=False,jarSha256=api.sha(jar),
            forgeCommit=api.git(ROOT,'rev-parse','HEAD'),forgeDirty=bool(api.git(ROOT,'status','--porcelain')),
            deckHashes={f.name:api.sha(f) for f in (batch/'decks').iterdir()},protectedBefore=before,
            sourceHashes={str(f):api.sha(f) for f in [Path(__file__),HERE/'Run-R4-Experiment.ps1',HERE/'R4DeckProbe.java',HERE/'parallel-v4/ParallelCommanderPool.py',HERE/'parallel-v4/CommanderBatch.py',HERE/'parallel-v4/Run-ValidCommanderCohort.py',HERE/'parallel-v4/Validate-CommanderTelemetry.py']},
            seatPolicy='candidate seat0; fixed opponent order',seedPolicy='baseSeed + attemptOrdinal shared across variants; divergent RNG possible',
            sampling='fixed attempts, no replacements',preferencePolicy='private default profiles')
        api.write(batch/'manifest.json',request)
        preflight=batch/'preflight';preflight.mkdir();cwd,env,home,temp=pool.isolated_runtime(preflight,batch,api)
        classes=preflight/'classes';classes.mkdir()
        subprocess.run([str(Path(args.java).parent/'javac.exe'),'-cp',str(jar),'-d',str(classes),str(HERE/'R4DeckProbe.java')],check=True,capture_output=True,text=True,timeout=30)
        command=[args.java,'-Xmx4g','-XX:ActiveProcessorCount=2','-Djava.awt.headless=true',f'-Duser.home={home}',f'-Djava.io.tmpdir={temp}',
            '-cp',str(classes)+os.pathsep+str(jar),'R4DeckProbe',str(batch/'forge-deck-verification.json'),*[str(batch/'decks'/(n+'.dck')) for n in NAMES]]
        api.write(preflight/'command.json',command)
        with (preflight/'forge.log').open('w',encoding='utf-8') as log:
            checked=subprocess.run(command,cwd=cwd,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=120)
        if checked.returncode:raise RuntimeError('Forge card recognition/conformance failed; inspect '+str(preflight/'forge.log'))
        verified=api.read(batch/'forge-deck-verification.json')
        if not all(r['pass'] and r['creaturesMain']==30 and r['dragonsMain']==21 and r['conventionalLands']==37 and r['recognizedMain'].get('Malakir Rebirth')==1 for r in verified):raise RuntimeError('Unexpected Forge deck audit')
        print(f'Forge deck recognition and Commander conformance PASS. Results: {batch}',flush=True)
        if args.prepare_only:return
        jobs=[]
        for ordinal in range(args.games):
            for i,name in enumerate(NAMES):
                directory=batch/name/f'seed-{args.seed+ordinal}';directory.mkdir(parents=True)
                job=dict(variant=name,variantIndex=i,filename=name+'.dck',runSeed=args.seed+ordinal,attemptIndex=ordinal,rawDirectory=str(directory))
                api.write(directory/'attempt.json',job);jobs.append(job)
        active=pool.ActiveGames();stop=threading.Event();results=[]
        with ThreadPoolExecutor(max_workers=args.workers) as executor:
            futures={executor.submit(pool.execute,j,batch,request,active,stop,api):j for j in jobs}
            try:
                for future in as_completed(futures):
                    job=futures[future];r=future.result();r['variant']=job['variant'];results.append(r)
                    api.write(batch/'attempts.json',results)
                    print(f'{job["variant"]} seed={job["runSeed"]}: {r["simulationStatus"]} valid={r["valid"]}',flush=True)
            except KeyboardInterrupt:
                stop.set();raise
        summary={}
        for name in NAMES:
            rows=[r for r in results if r['variant']==name];valid=[r for r in rows if r['valid']]
            display=next(l[5:] for l in (batch/'decks'/(name+'.dck')).read_text(encoding='utf-8-sig').splitlines() if l.startswith('Name='))
            summary[name]=dict(attempts=len(rows),valid=len(valid),invalid=len(rows)-len(valid),
                timeouts=sum(r.get('simulationStatus')=='TIMEOUT' or any(f.get('category','').endswith('TimeoutException') for f in r.get('failures',[])) for r in rows),
                wins=sum(display in (r.get('winner') or '') for r in valid))
        api.write(batch/'summary.json',summary)
        after={str(f):api.sha(f) for f in protected};api.write(batch/'protected-after.json',after)
        if before!=after:raise RuntimeError('Protected baseline changed')
        if any(api.sha(batch/'decks'/f)!=h for f,h in request['deckHashes'].items()):raise RuntimeError('Frozen deck changed')
        print(json.dumps(summary,indent=2),flush=True)
        if any(s['valid']!=args.games for s in summary.values()):sys.exit(2)

if __name__=='__main__':main()
