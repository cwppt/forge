"""Read-only real-game telemetry summaries; observations are not AI quality scores."""
import collections,json,math,statistics

def dist(values):
    values=sorted(values)
    return dict(n=len(values),median=statistics.median(values),p95=values[math.ceil(.95*len(values))-1],maximum=values[-1],total=sum(values),percentileCaveat='Nearest-rank descriptive p95; small samples have poor tail precision') if values else dict(n=0)

def records(path,prefix=''):
    with path.open(encoding='utf-8-sig') as f:
        return [json.loads(line[len(prefix):]) for line in f if line.strip() and line.startswith(prefix)]

def scope_records(directory,namespace):
    # Native IDs restart in every JVM; never correlate candidate events across games.
    def tagged(prefix):
        return [dict(x,id=str(namespace)+':'+str(x['id'])) for x in records(directory/'execution.log',prefix)]
    return tagged('AI_EVAL_DECISION '),tagged('AI_EVAL_CANDIDATE ')

def board_bin(size):return '0-24' if size<25 else '25-49' if size<50 else '50-74' if size<75 else '75-99' if size<100 else '100+'

def scopes_summary(rows,candidates=()):
    groups=collections.defaultdict(list)
    for row in rows:
        groups['context:'+row.get('context','UNLABELLED_DECISION_SCOPE')].append(row)
        groups['phase:'+row['phase']].append(row)
        groups['battlefield:'+board_bin(row['battlefield'])].append(row)
    def summary(items):
        return dict(scopes=len(items),scopesWithFallback=sum(x['fallbackCalls']>0 for x in items),fallbackCheckpointCalls=sum(x['fallbackCalls'] for x in items),
            predictionAborts=sum(x['predictionAborts'] for x in items),elapsedMs=dist([x['elapsedMs'] for x in items]),
            results=dict(collections.Counter(x['result'] for x in items)))
    evidence=collections.Counter()
    for x in rows:
        if not x['fallbackCalls']:continue
        work=x['workLimit']>0 and x['work']>x['workLimit']
        time=x['budgetMs']>0 and x['elapsedMs']>=x['budgetMs']
        evidence['both_limits_crossed_at_close' if work and time else 'work_limit_only_crossed_at_close' if work else 'time_limit_only_crossed_at_close' if time else 'neither_limit_crossed_at_close']+=1
    fallback_ids={x['id'] for x in rows if x['fallbackCalls']}
    correlated=collections.Counter((x['card'],x['api'],x['opinion']) for x in candidates if x['id'] in fallback_ids)
    return dict(**summary(rows),byContextPhaseAndBoard={k:summary(v) for k,v in sorted(groups.items())},
        withFallback=summary([x for x in rows if x['fallbackCalls']]),withoutFallback=summary([x for x in rows if not x['fallbackCalls']]),
        observedBudgetEvidenceScopeCounts=dict(evidence),candidateEventsInFallbackScopes=[dict(card=k[0],api=k[1],opinion=k[2],events=v) for k,v in correlated.most_common(12)],
        exactFallbackReasonByCall=None,totalNativeAiDecisions=None,
        limitations=['fallbackCalls counts checkpoint requests after a budget condition, not decisions or misplays.',
            'Scope-end limit evidence does not identify each checkpoint reason, especially when both limits crossed.',
            'Unlabelled scopes retain phase/result; no invented decision context. Candidate events are correlated, not causal.',
            'Prediction-stage counts cover all calls and cannot attribute fallback calls to stages.',
            'Scopes may nest across threads; summed scope time is not additive whole-game combat time.',
            'No counterfactual completed evaluation or decision-quality oracle: quality loss and acceptable coverage remain unresolved.',
            'Native decision audit may be empty. Evaluation scopes do not enumerate every AI decision.'])

def analyse_game(directory,seed):
    log=directory/'execution.log'
    rows=records(log,'AI_EVAL_DECISION ');candidates=records(log,'AI_EVAL_CANDIDATE ')
    turns=[x for x in records(directory/f'commander-seed{seed}.games.jsonl.profile.jsonl') if x.get('type')=='TURN']
    return dict(evaluation=scopes_summary(rows,candidates),turnLatencyMs=dist([x['elapsedMs'] for x in turns]),
        turnBoundaryBattlefield=dist([x['permanents'] for x in turns]),gcMsReportedTurns=sum(x['gcMs'] for x in turns),
        maximumReportedHeapBytes=max((x['heapUsed'] for x in turns),default=None),
        exactAllocationBytes=None,exactCombatSearchElapsedMs=None,logBytes=log.stat().st_size,
        measurementLimits='Turn-boundary heap/permanent samples may miss peaks. Native scope time is inclusive and not an exact combat breakdown; separate JFR estimates attribution.')

def classify_stack(names):
    if any('forge/ai/AiBlockController.' in x for x in names):return 'blocker-search'
    if any('forge/ai/AiAttackController.' in x for x in names):return 'attacker-search'
    if any('forge/ai/ComputerUtilCombat.' in x for x in names):return 'combat-prediction-other'
    if any(x.startswith(('forge/ai/AiController.','forge/ai/ability/')) for x in names):return 'other-ai-evaluation'
    if any(x.startswith(('forge/game/trigger/','forge/game/replacement/')) for x in names):return 'rules-triggers-replacements'
    if any(x.startswith('forge/game/') for x in names):return 'other-rules-game'
    return 'startup-or-other'

def jfr_summary(data):
    cpu=collections.Counter();allocation=collections.Counter();leaf=collections.Counter();combat=collections.Counter();events=collections.Counter()
    for event in data['recording']['events']:
        typ=event['type'];v=event['values'];events[typ]+=1
        names=[f['method']['type']['name']+'.'+f['method']['name'] for f in (v.get('stackTrace') or {}).get('frames',[])]
        category=classify_stack(names)
        if typ=='jdk.ExecutionSample':
            cpu[category]+=1
            if names:leaf[names[0]]+=1
            for name in set(names):
                if name.startswith(('forge/ai/AiBlockController.','forge/ai/ComputerUtilCombat.')):combat[name]+=1
        elif typ=='jdk.ObjectAllocationSample':allocation[category]+=v.get('weight',0)
    return dict(events=dict(events),exclusiveCpuSampleCategories=dict(cpu),cpuTopLeafFrames=leaf.most_common(20),
        inclusiveCombatFrames=combat.most_common(20),sampledAllocationWeightBytesByCategory=dict(allocation),
        caveat='One diagnostic replay, excluded from throughput. CPU samples estimate attribution, not exact elapsed time; inclusive method samples overlap. Allocation weights are sampled estimates, not exact allocation totals. Startup and telemetry costs remain included.')
