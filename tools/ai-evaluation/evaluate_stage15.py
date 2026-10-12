"""Matched memory-off/on full games; fixed compiled runtime, serial local provider calls."""
import argparse,datetime,hashlib,json,pathlib,shutil,subprocess,time
from evaluate_stage14_2 import ROOT,DECKS,read_jsonl,write,snapshot

HISTORY_PROMPT=' Relevant observed game history: strategicMemory contains observations from this game. Current visible state is authoritative. Do not assume unlisted hidden cards or current availability from past observations.'

def memory_extra_chars(body):
    payload=body['messages'][1]['content'];marker=',"strategicMemory":'
    if marker not in payload:return 0
    import copy
    without=copy.deepcopy(body)
    without['messages'][1]['content']=payload[:payload.index(marker)]+'}'
    without['messages'][0]['content']=without['messages'][0]['content'].removesuffix(HISTORY_PROMPT)
    encode=lambda x:json.dumps(x,separators=(',',':'),ensure_ascii=False)
    return len(encode(body))-len(encode(without))

def plausible_context(view, payload):
    """Separate supplied history from facts that could plausibly affect this choice."""
    options=json.dumps(payload.get('options',payload.get('legalActions',[])),ensure_ascii=False)
    for values in view.values():
        for fact in values:
            if fact['category'] in ('INTERACTION_SHOWN','RESOURCE_USED'):return True
            if fact['category']=='KNOWN_CARD' and (fact.get('knowledgeStatus')=='CURRENTLY_KNOWN'
                    or fact.get('interaction')!='OTHER' or fact.get('cardName') and fact['cardName'] in options):return True
            if fact['category']=='TURN_ACTION' and fact.get('interaction')!='OTHER':return True
    return False

def analyze(output):
    import collections,statistics
    result={};state_groups={};examples=[]
    max_facts=json.loads((output/'run.json').read_text(encoding='utf-8'))['MaxFacts']
    for mode in ('Control','Memory'):
        stats=collections.Counter();latencies=[];facts=[];growth=[];build=[];tokens=[];categories=collections.Counter();fallback_reasons=collections.Counter();surfaces=collections.Counter()
        for audit in sorted((output/mode).glob('*.jsonl')):
            if audit.name.endswith('.requests.jsonl'):continue
            rows=read_jsonl(audit);summary=next((r for r in rows if r.get('recordType')=='FULL_GAME_SUMMARY'),None)
            if summary is None:continue
            meta=json.loads(audit.with_name(audit.stem+'.run.json').read_text(encoding='utf-8'))
            refs=[r for r in rows if r.get('recordType')=='FULL_GAME_DECISION' and r['providerAttempted']]
            captures=read_jsonl(audit.with_name(audit.stem+'.requests.jsonl'))
            assert len(refs)==len(captures)
            stats['games']+=1;stats['wins' if not summary['draw'] and summary['winner'].startswith(f"Ai({meta['ExternalSeat']+1})-") else 'draws' if summary['draw'] else 'losses']+=1
            for key,value in summary.get('strategicMemory',{}).items():
                if key=='maxActiveFacts':stats[key]=max(stats[key],value)
                else:stats[key]+=value
            for capture in captures:
                body=capture['Request'];payload=json.loads(body['messages'][1]['content'])
                ref=next(r for r in refs if r['decisionId']==payload['decisionId'])
                assert body.get('reasoning_effort')=='none'
                assert payload['stateFingerprint']==ref['stateFingerprint']
                assert ref['selectedOption'] in body['response_format']['json_schema']['schema']['properties']['optionId']['enum']
                stats['calls']+=1;stats['fallbacks']+=ref['fallback'];stats['stale']+=ref['staleOrRevalidationFailure'];stats['duplicates']+=ref['duplicateRequest']
                surfaces[ref['surface']]+=1
                if ref['fallback']:fallback_reasons[ref['failureReason']]+=1
                latencies.append(ref['providerLatencyMs'])
                supported=ref['surface'] in ('MAIN_PHASE_ACTION','STACK_RESPONSE','COMBAT_ATTACKERS','COMBAT_BLOCKERS','COMBAT_ACTION')
                visible=payload.get('strategicMemory')
                assert (visible is not None)==(mode=='Memory' and supported)
                if visible is not None:
                    flattened=[f for values in visible.values() for f in values]
                    assert len(flattened)<=min(max_facts,32)
                    assert ref['memory']['selectedFacts']==len(flattened)
                    assert ref['memory']['view']==visible
                    facts.append(len(flattened));growth.append(memory_extra_chars(body));build.append(ref['memory']['buildNanos']/1e6)
                    stats['emptyMemoryDecisions' if not flattened else 'nonEmptyMemoryDecisions']+=1
                    stats['relevantMemoryDecisions']+=plausible_context(visible,payload)
                    stats['declarationOrOtherKnownCardOnlyDecisions']+=bool(flattened) and not plausible_context(visible,payload)
                    stats['memoryPresentDisagreements']+=bool(flattened) and not ref['agreement'] and not ref['fallback']
                    stats['relevantMemoryDisagreements']+=plausible_context(visible,payload) and not ref['agreement'] and not ref['fallback']
                    for f in flattened:categories[f['category']]+=1
                    if flattened and (any(f['category']=='INTERACTION_SHOWN' for f in flattened) or any(f['interaction'] in ('COUNTER','PUMP_OR_DEBUFF','PROTECTION') for f in flattened)):
                        examples.append(dict(audit=str(audit.relative_to(output)),decisionId=ref['decisionId'],surface=ref['surface'],turn=ref['turn'],choice=ref['selectedOption'],agreement=ref['agreement'],memory=visible))
                if ref.get('usage',{}).get('prompt_tokens') is not None:tokens.append(ref['usage']['prompt_tokens'])
                state={k:v for k,v in payload.items() if k not in ('decisionId','stateFingerprint','strategicMemory')}
                key=hashlib.sha256(json.dumps(state,sort_keys=True,separators=(',',':')).encode()).hexdigest()
                state_groups.setdefault((meta['Matchup'],meta['Seed'],meta['ExternalSeat'],key),[]).append(dict(mode=mode,decisionId=ref['decisionId'],choice=ref['selectedOption'],fallback=ref['fallback'],memory=visible,promptTokens=ref.get('usage',{}).get('prompt_tokens')))
        avg=lambda xs:statistics.mean(xs) if xs else 0
        result[mode]=dict(stats,avgProviderLatencyMs=avg(latencies),avgFactsPerSupportedRequest=avg(facts),maxFactsPerRequest=max(facts,default=0),
                avgAddedRequestChars=avg(growth),maxAddedRequestChars=max(growth,default=0),avgMemoryBuildMs=avg(build),maxMemoryBuildMs=max(build,default=0),
                avgPromptTokens=avg(tokens),categories=dict(categories),bySurface=dict(surfaces),fallbackReasons=dict(fallback_reasons))
    comparisons=[dict(matchup=key[0],seed=key[1],seat=key[2],decisions=values) for key,values in state_groups.items()
        if {'Control','Memory'}<={x['mode'] for x in values} and any(x['memory'] and any(x['memory'].values()) for x in values)]
    write(output/'analysis.json',dict(summary=result,exactVisibleStateComparisons=comparisons,relevantExamples=examples,
        note='Descriptive matched games, not evidence of superiority. Comparisons match sanitized current state and options; history/fingerprint are excluded from the matching key. No hypothetical outcomes generated.'))
    token_deltas=[];changed=0
    for group in comparisons:
        control=next(d for d in group['decisions'] if d['mode']=='Control')
        memory=next(d for d in group['decisions'] if d['mode']=='Memory')
        changed+=control['choice']!=memory['choice']
        if control['promptTokens'] is not None and memory['promptTokens'] is not None:token_deltas.append(memory['promptTokens']-control['promptTokens'])
    write(output/'effect-analysis.json',dict(exactCurrentStateGroups=len(comparisons),differentRecordedFinalOptions=changed,
        matchedPromptTokenPairs=len(token_deltas),meanMatchedPromptTokenDelta=statistics.mean(token_deltas) if token_deltas else None,
        medianMatchedPromptTokenDelta=statistics.median(token_deltas) if token_deltas else None,
        note='First observed control/memory pair per identical model-visible current-state/options group. Token differences include ordinary request identifier tokenization. Recorded final selections retain fallback flags in analysis.json. No claim of causal improvement.'))
    print(json.dumps(result,indent=2),flush=True);return result

def run_game(output,runtime,mode,matchup,seed,seat,endpoint,model,max_facts):
    directory=output/mode;directory.mkdir(exist_ok=True);label=f'{matchup}-{seed}-seat{seat}-External';prefix=directory/label
    cp=';'.join(str(runtime/m) for m in ('forge-gui-desktop','forge-ai','forge-gui','forge-game','forge-core','dependencies/*'))
    args=[shutil.which('java'),'-Dforge.ai.fullGame.audit=true',f'-Dforge.ai.fullGame.runId={label}',f'-Dforge.ai.fullGame.externalSeats={seat}',
        '-Dforge.ai.external.combat.materialGuard=false','-Dforge.ai.external.combat.materialGuardRaceAware=false',
        f'-Dforge.ai.combat.requestCapture={prefix}.requests.jsonl','-cp',cp,'forge.view.Main','sim','-D',str(ROOT/'forge-gui/res/quest/precons'),
        '-d',*[d+'.dck' for d in DECKS[matchup]],'-n','1','-s',str(seed),'-c','1800','-a','Default','Default','--legacy-order-combatants',
        '--external-ai-full-game-enabled','--external-ai-seats',str(seat),'--external-ai-combat-integrated-disabled',
        '--external-ai-memory-enabled' if mode=='Memory' else '--external-ai-memory-disabled','--external-ai-memory-max-facts',str(max_facts),
        '--external-ai-model',model,'--external-ai-endpoint',endpoint,'--external-ai-timeout','60','--external-ai-response-format','JSON_SCHEMA',
        '--external-ai-request-diagnostics','--metrics-csv',str(prefix)+'.csv','--decision-audit-jsonl',str(prefix)+'.jsonl']
    write(pathlib.Path(str(prefix)+'.command.json'),args);print('Game',mode,label,flush=True);start=time.perf_counter()
    with open(str(prefix)+'.stdout.log','w',encoding='utf-8') as stdout,open(str(prefix)+'.stderr.log','w',encoding='utf-8') as stderr:
        proc=subprocess.run(args,cwd=ROOT/'forge-gui-desktop',stdout=stdout,stderr=stderr,timeout=1920,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    complete=any(r.get('recordType')=='FULL_GAME_SUMMARY' for r in read_jsonl(pathlib.Path(str(prefix)+'.jsonl')))
    write(pathlib.Path(str(prefix)+'.run.json'),dict(Matchup=matchup,Seed=seed,ExternalSeat=seat,Mode=mode,ExitCode=proc.returncode,Completed=complete,WallTimeMs=round((time.perf_counter()-start)*1000)))
    if proc.returncode or not complete:raise RuntimeError('Incomplete game; evidence retained: '+str(prefix))
    return dict(mode=mode,matchup=matchup,seed=seed,seat=seat)

def main(args):
    if args.analyze:analyze(pathlib.Path(args.analyze));return
    output=ROOT/'ai-eval/stage15'/datetime.datetime.now().strftime('%Y%m%d-%H%M%S');output.mkdir(parents=True)
    runtime=ROOT/'work/stage15'/('runtime-'+output.name)
    for module in ('forge-gui-desktop','forge-ai','forge-gui','forge-game','forge-core'):shutil.copytree(ROOT/module/'target/classes',runtime/module)
    (runtime/'dependencies').mkdir()
    for jar in (ROOT/'forge-gui-desktop/target').glob('*.jar'):shutil.copy2(jar,runtime/'dependencies'/jar.name)
    before=snapshot(runtime);write(output/'runtime-identities.json',dict(Runtime=str(runtime),Files=before))
    write(ROOT/'work/stage15/latest-run.json',dict(output=str(output)))
    evidence={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for d in ('stage14','stage14-2') for p in (ROOT/'ai-eval'/d).rglob('*') if p.is_file()}
    write(output/'run.json',dict(Model=args.model,Endpoint=args.endpoint,Seeds=args.seeds,MaxFacts=args.max_facts,Matchups=DECKS,ExternalSeats=[0,1],Note='Same compiled policy for both modes, with Stage 14.2 fix. Only memory toggle differs.'))
    progress=[]
    for i,(matchup,seed,seat) in enumerate((m,s,p) for m in DECKS for s in args.seeds for p in (0,1)):
        for mode in (('Control','Memory') if i%2==0 else ('Memory','Control')):
            progress.append(run_game(output,runtime,mode,matchup,seed,seat,args.endpoint,args.model,args.max_facts));write(output/'progress.json',progress)
    assert before==snapshot(runtime),'Compiled evaluation runtime changed'
    assert evidence=={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for d in ('stage14','stage14-2') for p in (ROOT/'ai-eval'/d).rglob('*') if p.is_file()},'Earlier evidence changed'
    write(output/'input-verification.json',dict(files=len(evidence),unchanged=True));analyze(output);print('Stage 15 evidence:',output,flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--model',default='qwen3:8b');p.add_argument('--endpoint',default='http://localhost:11434/v1/chat/completions')
    p.add_argument('--seeds',nargs='+',type=int,default=[1501,1502,1503,1504,1505]);p.add_argument('--max-facts',type=int,default=12);p.add_argument('--analyze')
    main(p.parse_args())
