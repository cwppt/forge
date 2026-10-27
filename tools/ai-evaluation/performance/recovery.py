"""Conservative same-seed recovery plan; callers independently revalidate terminal evidence first."""
def plan(jobs, attempts, max_attempts=3):
    if len(jobs)!=len(set(jobs)):raise ValueError('Duplicate requested job')
    valid=set();seen=set();counts={}
    for a in attempts:
        job=(a['variant'],a['runSeed']);key=(*job,a['attemptIndex'])
        if job not in jobs or key in seen:raise ValueError('Unknown or duplicate attempt')
        seen.add(key);counts[job]=counts.get(job,0)+1
        if a['valid']:
            if job in valid:raise ValueError('Duplicate scored game')
            if a.get('independentlyValidated') is not True:raise ValueError('Unverified completed evidence')
            valid.add(job)
        elif a['simulationStatus']!='INTERRUPTED':
            raise ValueError('Gameplay/timeout/ambiguous failure requires review; preserve evidence')
        elif a.get('ownedWorkerAbsent') is not True:
            raise ValueError('Prior worker ownership unresolved')
    for job in jobs:
        if job not in valid and counts.get(job,0)>=max_attempts:raise ValueError('Retry ceiling reached')
    return [dict(variant=v,runSeed=s,attemptIndex=max([a['attemptIndex'] for a in attempts
        if (a['variant'],a['runSeed'])==(v,s)],default=-1)+1) for v,s in jobs if (v,s) not in valid]
