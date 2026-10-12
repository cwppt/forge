"""Read-only, reproducible card support audit; run from repository root with Python 3.

Writes only tools/ai-evaluation/stage16. Static evidence is never interpreted as
proof of runtime legality. Counts refer to script records (first Name), not prints.
"""
import argparse
import collections
import csv
import hashlib
import json
import pathlib
import re

OUT = pathlib.Path('tools/ai-evaluation/stage16')
CARDS = pathlib.Path('forge-gui/res/cardsfolder')
AI = pathlib.Path('forge-ai/src/main/java/forge/ai')

# Reviewed exceptions: every entry is explained in Stage16AiCardSupportAudit.md.
REVIEWS = {
    'Swan Song': ('G', 'HIGH', 'STACK_RESPONSE', 'Focused scripted-provider probe verifies enumeration, exact target, payment, counter resolution and Bird token; warning remains broader than stack support.'),
    'Stalking Yeti': ('C', 'LOW', 'MAIN_PHASE_ACTION after preparation work', 'Direct cast preparation fails with no opposing ETB damage target; normal enumeration excludes it independently. Snow-cost return and mutual ETB damage need separate preparation proofs.'),
    'Earthbrawn': ('B', 'MEDIUM', 'COMBAT_ACTION', 'Focused direct preparation, legal target, payment and resolution pass; normal enumeration excludes whole card. Reinforce remains unverified; recovery must distinguish the plain pump ability.'),
    'Dizzy Spell': ('D', 'LOW', 'COMBAT_ACTION only after preparation proof', 'Card comment identifies transmute weakness and marker excludes ordinary pump too; direct combat probe produces no prepared option. Comment alone is insufficient evidence of recoverability.'),
    'Sylvan Library': ('C', 'NONE', 'No current prepared surface for draw-trigger subchoices', 'ChooseCard -> RepeatEach -> private hand ChangeZone/UnlessCost occurs during trigger resolution, outside prepared action selection.'),
    'Strionic Resonator': ('C', 'NONE', 'No current safe own-trigger response routing', 'Triggered.YouCtrl copy plus optional retargeting; normal enumeration excludes card and own-trigger response is outside existing external stack ownership.'),
    'Fire Covenant': ('C', 'LOW', 'STACK_RESPONSE / COMBAT_ACTION only after preparation work', 'Variable PayLife<X>, target count and divided allocation must be snapshotted together; current revalidation alone does not prove allocation/payment decisions fixed.'),
    'Firestorm': ('C', 'LOW', 'MAIN_PHASE_ACTION / STACK_RESPONSE after preparation work', 'Discard<X> determines X targets and damage; exact discard payment and target cardinality must be prepared together.'),
    'Blazing Shoal': ('C', 'LOW', 'COMBAT_ACTION after preparation work', 'Announce X with alternate ExileFromHand red card cmcEQX; no probe verifies immutable cost choice or alternate-cost execution.'),
    'Artillerize': ('C', 'LOW', 'STACK_RESPONSE / COMBAT_ACTION after preparation work', 'Additional sacrifice selection is a payment callback; target alone is insufficient evidence of a complete prepared action.'),
    'Cabal Coffers': ('D', 'NONE', 'Existing native mana callback only', 'Mana ability belongs to native payment planning, explicitly excluded from external action surfaces; warning does not prove native mana failure.'),
    'Everythingamajig': ('F', 'NONE', 'None for unsupported default variant', 'Default Oracle explicitly Unsupported Variant; variant C/G/H scripts exist. This classification is default-variant scoped, not a claim all variants fail.'),
    'Garbage Elemental': ('F', 'NONE', 'None for unsupported default variant', 'Default Oracle explicitly Unsupported Variant; implemented variant scripts must be assessed separately.'),
    'Sly Spy': ('F', 'NONE', 'None for unsupported default variant', 'Default Oracle explicitly Unsupported Variant; implemented variant scripts must be assessed separately.'),
}
CATEGORY = {'A':'STRATEGY_ONLY','B':'CANDIDATE_SELECTION','C':'PREPARATION_GAP',
            'D':'HEURISTIC_REFUSAL','E':'MECHANICAL_AI_GAP','F':'RULES_OR_SCRIPT_GAP',
            'G':'WARNING_ONLY / STALE_METADATA','H':'UNKNOWN'}

def parse_script(text):
    """Keep exact source lines, names/faces and variant-qualified abilities."""
    names = re.findall(r'^Name:(.*)$', text, re.M)
    markers = [(i, line.strip()) for i, line in enumerate(text.splitlines(), 1)
               if line.startswith('AI:RemoveDeck:') or re.search(r'\b(?:AILogic|UnlessAI)\$ Never(?:\s*\||\s*$)', line)
               or 'AICheckSVar' in line or 'Oracle:<Unsupported Variant>' in line]
    abilities = [(i, line.strip()) for i, line in enumerate(text.splitlines(), 1)
                 if re.search(r'(?:SP|AB|DB)\$\s*\w+', line)]
    apis = sorted(set(re.findall(r'(?:SP|AB|DB)\$\s*(\w+)', text)))
    return [s.strip() for s in names], markers, abilities, apis

def classify(name, all_marker, api_gaps, never, unsupported):
    if name in REVIEWS:
        return REVIEWS[name]
    if unsupported:
        return ('F','NONE','None for unsupported default variant','Explicit unsupported default Oracle; individual variants are not classified as broken.')
    if api_gaps:
        return ('E','NONE','No verified prepared surface','Explicit CannotPlayAi or unmapped API on script ability; mandatory triggers and other abilities may still run.')
    if all_marker or never:
        return ('D','LOW','Not verified; ordinary MAIN_PHASE_ACTION/COMBAT_ACTION enumeration excludes All',
                'Explicit AI refusal marker. Script is present, but rules execution, complete preparation and replacement of refusal are unverified; LOW is provisional.')
    return ('H','LOW','Not verified','Deck-construction/conditional metadata gives no runtime failure reason; per-card execution and preparation have not been tested; LOW is provisional.')

def write_csv(path, rows, fields=None):
    with path.open('w', encoding='utf-8-sig', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=fields or list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)

def run():
    OUT.mkdir(parents=True, exist_ok=True)
    mapping_text = (AI / 'SpellApiToAi.java').read_text(encoding='utf-8')
    mapping = dict(re.findall(r'\.put\(ApiType\.(\w+),\s*(\w+)\.class\)', mapping_text))
    rows, ability_rows, by_name = [], [], {}
    marker_counts = collections.Counter()
    scripts = sorted(CARDS.rglob('*.txt'))
    for path in scripts:
        text = path.read_text(encoding='utf-8-sig')
        names, markers, abilities, apis = parse_script(text)
        if not names:
            continue
        # Keyword-generated/implicit permanent spells do not appear in this API extraction.
        # ManaReflected is handled by ComputerUtilMana, not the action evaluator.
        # InternalIgnoreEffect is mechanical, not an externally selectable action.
        gaps = [a for a in apis if a not in ('ManaReflected','InternalIgnoreEffect')
                and mapping.get(a, 'UNMAPPED') in ('CannotPlayAi','UNMAPPED')]
        if not markers and not gaps:
            continue
        all_marker = bool(re.search(r'^AI:RemoveDeck:(?i:All)\s*$', text, re.M))
        for _, marker in markers:
            marker_counts[marker.split(':')[-1] if marker.startswith('AI:RemoveDeck:') else 'conditional/refusal/unsupported'] += 1
        category, score, surface, reason = classify(names[0], all_marker, gaps,
            any('AILogic$ Never' in m or 'UnlessAI$ Never' in m for _, m in markers), 'Oracle:<Unsupported Variant>' in text)
        handling = '; '.join(f'{a}:{mapping.get(a, "UNMAPPED -> CannotPlayAi")}' for a in apis)
        row = dict(card_name=names[0], other_face_names='; '.join(names[1:]), script_source=path.as_posix(),
            edition='All printings using this rules script; print-specific deck entries counted separately',
            warning_eligible=str(all_marker).lower(), marker='; '.join(f'L{i} {m}' for i,m in markers)
                + ('; API gap: ' + ','.join(gaps) if gaps else ''),
            affected_ability_mode='Card-level markers apply broadly; ability details in companion CSV',
            api_ai_class=handling or 'Implicit/keyword-generated abilities; runtime expansion not enumerated',
            rules_support='Script present; runtime unverified' if category != 'F' else 'Explicit unsupported default variant; see reason',
            ai_preparation='Verified STACK_RESPONSE fixture' if names[0]=='Swan Song' else
                'Direct pump preparation, payment and resolution verified; normal routing excludes' if names[0]=='Earthbrawn' else
                'No prepared pump option in tested combat fixture' if names[0]=='Dizzy Spell' else
                'Direct cast refused on empty opposing board' if names[0]=='Stalking Yeti' else 'Unverified',
            forge_refusal='Ordinary non-counter priority actions excluded; counters/mana/triggers/combat have separate paths'
                if all_marker else 'Ability-local CannotPlayAi' if gaps else 'Ability-local Never' if any('AILogic$ Never' in m for _,m in markers)
                else 'Payment callback refuses UnlessAI Never' if any('UnlessAI$ Never' in m for _,m in markers)
                else 'No blanket runtime refusal established',
            primary_category=category+' '+CATEGORY[category], recoverability=score,
            existing_surface=surface, evidence_reason=reason,
            source_sha256=hashlib.sha256(path.read_bytes()).hexdigest())
        rows.append(row)
        for name in names:
            by_name[name]=row
        for line, definition in abilities:
            ability_rows.append(dict(card_name=names[0],script_source=path.as_posix(),line=line,
                definition=definition,api_ai_class='; '.join(f'{a}:{mapping.get(a,"UNMAPPED -> CannotPlayAi")}'
                    for a in re.findall(r'(?:SP|AB|DB)\$\s*(\w+)', definition)),
                primary_category=row['primary_category'], classification_scope='Inherited card-level primary; individual variant/ability may differ, not separately verified'))
    write_csv(OUT/'ForgeAiCardSupportAudit.csv', rows)
    write_csv(OUT/'ForgeAiCardSupportAbilities.csv', ability_rows)
    # Index AI refusals, TODOs and named exceptions for independent inspection.
    evidence=[]
    for path in sorted(AI.rglob('*.java')):
        for i,line in enumerate(path.read_text(encoding='utf-8').splitlines(),1):
            kinds=[]
            if re.search(r'CantPlayAi|CantPlaySa|return false;',line): kinds.append('conditional refusal (context required)')
            if re.search(r'TODO|unsupported|not supported|not implemented',line,re.I): kinds.append('comment/diagnostic (not proof of failure)')
            if re.search(r'getName\(\).*equals|nameEquals|cardName.*equals',line): kinds.append('named exception (may be supported specialization)')
            if re.search(r'getRemAIDecks|isCardRemAIDeck|getRemRandomDecks|isCardRemRandomDeck',line): kinds.append('metadata filter')
            if kinds: evidence.append(dict(source=path.as_posix(),line=i,kind='; '.join(kinds),code=line.strip()))
    write_csv(OUT/'ForgeAiSupportCodeEvidence.csv',evidence)
    decks=[]
    # Bounded practical population: shipped quest precons, not adventure/constructed duplicates.
    for path in sorted(pathlib.Path('forge-gui/res/quest/precons').glob('*.dck')):
        section=''; warned=set(); warning_entries=set(); copies=0; total=0; affected=set()
        for line in path.read_text(encoding='utf-8-sig').splitlines():
            if line.startswith('['): section=line.strip().lower(); continue
            if section not in ('[main]','[sideboard]','[commander]'): continue
            match=re.match(r'^(\d+)\s+(.+)$',line)
            if not match: continue
            n=int(match[1]); parts=match[2].split('|'); name=parts[0].strip(); total+=n
            row=by_name.get(name)
            if row: affected.add(name)
            if row and row['warning_eligible']=='true':
                warned.add(name);warning_entries.add((section,match[2]));copies+=n
        decks.append(dict(deck_source=path.as_posix(),parsed_copies=total,warned_names=len(warned),
            warned_print_section_entries=len(warning_entries),warned_copies=copies,
            affected_names=len(affected),warning_cards='; '.join(sorted(warned)),
            structural_impact='Elevated exposure (>=4 warned copies); not a strength measurement' if copies>=4 else
                'Some exposure; severity unknown' if copies else 'No poor-AI warning marker'))
    write_csv(OUT/'ForgeAiDeckSupportAudit.csv',decks)
    # Mechanic counts overlap; presence identifies review clusters, never a proven failure.
    patterns={'modal':r'Charm|Choices\$', 'X/cost coupling':r'Announce\$ X|ManaCost:X|Cost\$[^\n|]*<X',
      'sacrifice/discard costs':r'Cost\$[^\n|]*(?:Sac<|Discard<)', 'copy/clone':r'(?:SP|AB|DB)\$ (?:CopyPermanent|CopySpellAbility|Clone)',
      'tutor/library selection':r'Origin\$ Library|ChoiceZone\$ Library', 'name/type/color choice':r'(?:SP|AB|DB)\$ Choose(?:CardName|Color|Type)',
      'graveyard selection':r'Origin\$ Graveyard|ChoiceZone\$ Graveyard|ExileFromGrave<',
      'alternate costs':r'Mode\$ AlternativeCost', 'damage distribution':r'DividedAsYouChoose\$',
      'replacement':r'^R:', 'control gain (includes temporary control)':r'(?:SP|AB|DB)\$ GainControl',
      'pump abilities':r'(?:SP|AB|DB)\$ Pump(?:\s|\|)',
      'blink/reanimation/change-zone':r'(?:SP|AB|DB)\$ ChangeZone', 'nested repeat choices':r'(?:SP|AB|DB)\$ RepeatEach'}
    clusters={}
    for key,pattern in patterns.items():
        members=[r['card_name'] for r in rows if re.search(pattern,pathlib.Path(r['script_source']).read_text(encoding='utf-8-sig'),re.M)]
        if members: clusters[key]={'affected_cards':len(members),'warning_eligible':sum(by_name[n]['warning_eligible']=='true' for n in members)}
    summary=dict(script_files_scanned=len(scripts),affected_cards=len(rows),warning_eligible_cards=sum(r['warning_eligible']=='true' for r in rows),
        categories=dict(collections.Counter(r['primary_category'] for r in rows)),recoverability=dict(collections.Counter(r['recoverability'] for r in rows)),
        warned_categories=dict(collections.Counter(r['primary_category'] for r in rows if r['warning_eligible']=='true')),
        warned_recoverability=dict(collections.Counter(r['recoverability'] for r in rows if r['warning_eligible']=='true')),
        markers=dict(marker_counts),explicit_cannot_play_apis=[a for a,c in mapping.items() if c=='CannotPlayAi'],
        api_gap_cards=sum('API gap:' in r['marker'] for r in rows),ability_definitions=len(ability_rows),code_evidence_lines=len(evidence),
        deck_scope='forge-gui/res/quest/precons/*.dck',decks_scanned=len(decks),decks_with_warned_cards=sum(int(d['warned_names'])>0 for d in decks),
        mean_warned_names_per_deck=sum(d['warned_names'] for d in decks)/len(decks),
        mean_warned_copies_per_deck=sum(d['warned_copies'] for d in decks)/len(decks),
        decks_with_four_or_more_warned_copies=sum(d['warned_copies']>=4 for d in decks),clusters=clusters)
    (OUT/'summary.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(summary,indent=2))

if __name__=='__main__':
    argparse.ArgumentParser(description=__doc__).parse_args()
    run()
