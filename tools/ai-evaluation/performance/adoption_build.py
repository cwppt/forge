"""Narrow source composition and frozen-class overlay; never rebuild the whole engine."""
from pathlib import Path
import difflib,hashlib,json,re,shutil,subprocess,zipfile
S=Path(__file__).resolve().parents[3]
WS=Path('C:/Users/Chris/Workspace')
V1=WS/'forge-commander-combat-performance-v1';V2=WS/'forge-commander-cost-performance-v2';V3=WS/'forge-commander-blocker-performance-v3';CORRECT=WS/'forge-commander-correctness-20261027'
FILES=['forge-game/src/main/java/forge/game/card/CardState.java','forge-game/src/main/java/forge/game/cost/CostAdjustment.java','forge-game/src/main/java/forge/game/combat/CombatUtil.java',
'forge-game/src/main/java/forge/game/card/BlockCostMetrics.java','forge-game/src/main/java/forge/game/cost/CostSourceMetrics.java','forge-game/src/main/java/forge/game/combat/BlockerSearchMetrics.java','forge-game/src/main/java/forge/game/combat/RequirementSnapshotMetrics.java']
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def method(text,signature):
    start=text.index(signature);brace=text.index('{',start);depth=1;pos=brace+1
    while depth:
        depth+=(text[pos]=='{')-(text[pos]=='}');pos+=1
    return start,pos,text[start:pos]
def compose(root,experiments):
    assert not (root/'composition.json').exists()
    provenance=[]
    def historical(checkout,run,paths):
        q=read(experiments/run/'run-request.json')
        for relative in paths:
            p=checkout/relative;assert sha(p)==q['sourceHashes'][relative],'Historical source differs from frozen request: '+str(p)
            provenance.append(dict(path=relative,checkout=str(checkout),sourceHead=q['sourceHead'],sha256=sha(p)))
    historical(V1,Path('commander-combat-performance-v1-20261027/candidate-02'),[FILES[0],FILES[2],FILES[3]])
    historical(V2,Path('commander-cost-performance-v2-20261027/validation-03'),[FILES[0],FILES[1],FILES[4]])
    historical(V3,Path('blocker-performance-v3-20261028/validation-02'),[FILES[2],FILES[5]])
    # Phase 2 and Phase 1 share the exact same independently tested live-mode helper.
    assert (V1/FILES[0]).read_text()==(V2/FILES[0]).read_text()
    current=(S/FILES[2]).read_text();phase1=(V1/FILES[2]).read_text();phase3=(V3/FILES[2]).read_text()
    assert 'RequirementSnapshotMetrics.FAST' in current and 'hasBlockingCostSource' not in current
    first,last,_=method(current,'public static Cost getBlockCost(');_,_,replacement=method(phase1,'public static Cost getBlockCost(')
    current=current[:first]+replacement+current[last:]
    first,last,body=method(current,'public static boolean mustBlockAnAttacker(')
    anchor='        final CardCollection requirementCards = new CardCollection();'
    assert body.count(anchor)==1
    body=body.replace(anchor,'        BlockerSearchMetrics.count(3);\n        final boolean checkCosts = !BlockerSearchMetrics.FAST || hasBlockingCostSource(blocker.getGame());\n\n'+anchor)
    body=body.replace('        for (final Card attacker : attackers) {','        for (final Card attacker : attackers) {\n            BlockerSearchMetrics.count(4);\n            if (!checkCosts) BlockerSearchMetrics.count(13);',1)
    needle='if (getBlockCost(blocker.getGame(), blocker, attacker) != null)'
    assert needle in body;body=body.replace(needle,'if (checkCosts && getBlockCost(blocker.getGame(), blocker, attacker) != null)',1)
    current=current[:first]+body+current[last:]
    _,_,helper=method(phase3,'private static boolean hasBlockingCostSource(')
    marker='    private static boolean attackerLureSatisfied('
    assert current.count(marker)==1;current=current.replace(marker,'    '+helper+'\n\n'+marker)
    (S/FILES[2]).write_text(current,encoding='utf-8')
    for relative,origin in [(FILES[0],V2),(FILES[1],V2),(FILES[3],V1),(FILES[4],V2),(FILES[5],V3)]:shutil.copy2(origin/relative,S/relative)
    for name,origin in [('BlockCostSourceRegressionTest.java',V1),('CostSourceRegressionTest.java',V2)]:
        shutil.copy2(origin/'forge-gui-desktop/src/test/java/forge/ai'/name,S/'forge-gui-desktop/src/test/java/forge/ai'/name)
    result=dict(allFourSourceFeaturesComposed=True,historicalSources=provenance,composedHashes={p:sha(S/p) for p in FILES},
        mergeRecipe='Phase 5 CombatUtil plus Phase 1 getBlockCost body and Phase 3 invocation-local tax proof; shared Phase 1/2 CardState helper; Phase 2 CostAdjustment including frozen Lamia diagnostics. No other phase-3 observational wrappers or abandoned EmptyTrigger candidate.',
        rngIsolation=False)
    (root/'composition.json').write_text(json.dumps(result,indent=2)+'\n');return result

def bytecode(java,cp,klass):
    p=subprocess.run([str(java.with_name('javap.exe')),'-c','-p','-classpath',str(cp),klass],capture_output=True,text=True,check=True,timeout=120,creationflags=subprocess.CREATE_NO_WINDOW)
    return canonical_javap(p.stdout)
def canonical_javap(text):
    result=[]
    for line in text.splitlines():
        code,separator,comment=line.partition('//')
        code=re.sub(r'#\d+','#',code).rstrip()
        # javap pads instructions differently when constant-pool indices gain digits.
        # Preserve symbolic comments, string constants, offsets and branch targets.
        result.append(code+(' //'+comment if separator else ''))
    return '\n'.join(result)
def blocks(text):
    result={};current='HEADER';result[current]=[]
    for line in text.splitlines():
        if re.match(r'^  (?:public|protected|private|static).*\([^;]*\).*;$',line) or line=='  static {};':current=line;result[current]=[]
        result[current].append(line)
    return {k:'\n'.join(v).strip() for k,v in result.items()}

def build(root,reference,java):
    control=root/'control-source';control.mkdir();classes=root/'control-classes';classes.mkdir()
    for relative in FILES[:3]:
        source=(CORRECT/relative).read_text()
        if relative.endswith('CostAdjustment.java'):
            old='''        while (!reduceAbilities.isEmpty()) {
            StaticAbility choice = activator.getController().chooseSingleStaticAbility(reduceAbilities);
            reduceAbilities.remove(choice);
            sumGeneric += applyReduceCostAbility(choice, sa, cost, sumGeneric);
        }'''
            new='''        LamiaObservation.start(sa, test);
        while (!reduceAbilities.isEmpty()) {
            StaticAbility choice = activator.getController().chooseSingleStaticAbility(reduceAbilities);
            reduceAbilities.remove(choice);
            int beforeGeneric = cost.getUnpaidShards(ManaCostShard.GENERIC);
            int reduction = applyReduceCostAbility(choice, sa, cost, sumGeneric);
            LamiaObservation.record(sa, choice, test, beforeGeneric, sumGeneric, reduction, !setAbilities.isEmpty());
            sumGeneric += reduction;
        }'''
            assert old in source;source=source.replace(old,new)
        (control/Path(relative).name).write_text(source,encoding='utf-8')
    def compile(out,files,label):
        p=subprocess.run(list(map(str,[java.with_name('javac.exe'),'--release','17','-cp',reference,'-d',out,*files])),capture_output=True,text=True,timeout=120,creationflags=subprocess.CREATE_NO_WINDOW)
        (root/label).write_text(p.stdout+p.stderr);assert p.returncode==0,p.stderr
    compile(classes,list(control.glob('*.java')),'control-compile.log')
    parity={}
    for file in classes.rglob('*.class'):
        klass=file.relative_to(classes).with_suffix('').as_posix().replace('/','.')
        actual=bytecode(java,classes,klass);expected=bytecode(java,reference,klass);parity[klass]=actual==expected
        if actual!=expected:(root/(klass+'.control.diff')).write_text('\n'.join(difflib.unified_diff(expected.splitlines(),actual.splitlines())))
    (root/'control-parity.json').write_text(json.dumps(parity,indent=2));assert all(parity.values()),'Frozen source control mismatch; all-inclusive build blocked'
    out=root/'combined-classes';out.mkdir();compile(out,[S/p for p in FILES],'combined-compile.log')
    return finish(root,reference,java)

def finish(root,reference,java):
    out=root/'combined-classes'
    allowed={'forge.game.card.CardState':{'mayHaveStaticAbilityMode'},'forge.game.cost.CostAdjustment':{'adjust'},'forge.game.combat.CombatUtil':{'getBlockCost','mustBlockAnAttacker','attackerLureSatisfied','blockersForLure','hasBlockingCostSource'}}
    unchanged={}
    for klass,names in allowed.items():
        old=blocks(bytecode(java,reference,klass));new=blocks(bytecode(java,out,klass))
        def affected(header):return any(re.search(r'\b'+name+r'\(',header) for name in names)
        for header,value in old.items():
            if affected(header):continue
            unchanged[klass+'::'+header]=new.get(header)==value
    (root/'unaffected-method-parity.json').write_text(json.dumps(unchanged,indent=2));assert all(unchanged.values()),'Unexpected changes outside approved feature methods'
    entries={p.relative_to(out).as_posix():p.read_bytes() for p in out.rglob('*.class')};retained=[]
    # Preserve original bytes for every generated nested class after instruction-equivalence checks.
    with zipfile.ZipFile(reference) as jar:
        for name in list(entries):
            if '$' in name:
                assert name in jar.namelist();klass=name[:-6].replace('/','.');assert bytecode(java,out,klass)==bytecode(java,reference,klass)
                retained.append(name);del entries[name]
        with zipfile.ZipFile(root/'combined.jar','w',zipfile.ZIP_DEFLATED) as dest:
            for info in jar.infolist():dest.writestr(info,entries.get(info.filename,jar.read(info)))
            for name,data in entries.items():
                if name not in jar.namelist():dest.writestr(name,data)
    with zipfile.ZipFile(reference) as old,zipfile.ZipFile(root/'combined.jar') as new:
        assert all(old.read(name)==new.read(name) for name in old.namelist() if name not in entries)
        critical=[name for name in old.namelist() if name.startswith(('forge/ai/','forge/util/MyRandom','forge/view/SimulateMatch','forge/util/TimeLimitedCodeBlock')) or name in ['forge/game/GameAction.class','forge/game/trigger/TriggerHandler.class','forge/game/zone/MagicStack.class']]
        assert all(old.read(name)==new.read(name) for name in critical)
    result=dict(referenceSHA256=sha(reference),combinedSHA256=sha(root/'combined.jar'),changedEntries=list(entries),retainedNestedOriginalBytes=retained,
        everyOtherEntryIdentical=True,preservedCriticalClasses=len(critical),allFourImplemented=True)
    (root/'build-provenance.json').write_text(json.dumps(result,indent=2));return result
