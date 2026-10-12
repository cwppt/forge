"""Read-only structural screening of Stage 16 inventory; never updates the registry."""
import csv
import json
import pathlib
import re

REGISTERED = {'Earthbrawn', 'Resounding Roar', 'Righteousness', 'Hundred-Talon Strike'}
PROBED = REGISTERED | {'About Face', 'Strange Inversion', 'Transmutation', 'Dizzy Spell', 'Rouse'}
KEYS = {'SP', 'ValidTgts', 'NumAtt', 'NumDef', 'KW', 'Gains', 'Amount', 'IsCurse', 'SpellDescription', 'Cost', 'TgtPrompt'}
KEYWORDS = {'First Strike', 'Indestructible', 'Hexproof', 'Shroud', 'Flying', 'Trample', 'Reach', 'Vigilance', 'Lifelink'}

def screen(text, line):
    params = dict(part.strip().split('$', 1) for part in line[2:].split('|') if '$' in part)
    params = {k.strip(): v.strip() for k, v in params.items()}
    if params.get('SP') not in ('Pump', 'Protection', 'PreventDamage'): return 'API/effect not represented by current fixed-effect contract'
    if 'Mode$ AlternativeCost' in text: return 'Card declares alternate cost; outside this cluster'
    mana = re.search(r'^ManaCost:(.*)', text, re.M)[1]
    if re.search(r'\bX\b', mana): return 'X cost'
    if not re.fullmatch(r'Creature(?:\.[A-Za-z0-9+]+)?', params.get('ValidTgts', '')): return 'Not exactly a creature target'
    if not set(params) <= KEYS: return 'Extra mode/target-count/choice/subeffect/distribution/mechanic parameters: ' + ','.join(sorted(set(params)-KEYS))
    if any(k in params and not re.fullmatch(r'[+-]?\d+', params[k]) for k in ('NumAtt','NumDef','Amount')): return 'Nonconstant magnitude'
    if 'KW' in params and params['KW'] not in KEYWORDS: return 'Keyword/effect outside reviewed simple keyword shapes'
    if 'Gains' in params and re.search(r'Choice|Chosen', params['Gains']): return 'Resolution choice'
    if 'Cost' in params and re.search(r'[<>]', params['Cost']): return 'Additional sacrifice/discard/nonmana cost'
    return ''

def run():
    inventory = list(csv.DictReader(open('tools/ai-evaluation/stage16/ForgeAiCardSupportAudit.csv', encoding='utf-8-sig')))
    rows = []; examined = 0
    for card in inventory:
        if card['warning_eligible'] != 'true': continue
        examined += 1; text = pathlib.Path(card['script_source']).read_text(encoding='utf-8-sig')
        if not re.search(r'^Types:.*Instant', text, re.M): continue
        for line in text.splitlines():
            if not re.match(r'A:SP\$ (?:Pump|Protection|PreventDamage)\b', line) and card['card_name'] != 'Indestructible Aura': continue
            if not line.startswith('A:SP$'): continue
            reason = screen(text, line); name = card['card_name']
            outcome = 'REGISTERED: individually routed/prepared/paid/resolved; PASS/fallback/sibling/privacy tests'
            if name not in REGISTERED:
                outcome = 'REJECTED: native preparation produced no action in tested combat' if name in PROBED and name != 'Rouse' else 'NOT_ADMITTED: ' + reason
            decks = []
            for deck in pathlib.Path('forge-gui/res/quest/precons').glob('*.dck'):
                if re.search(r'^\d+ ' + re.escape(name) + r'(?:\||$)', deck.read_text(encoding='utf-8-sig'), re.M): decks.append(deck.name)
            rows.append(dict(card=name,script=card['script_source'],warning_retained=True,ordinary_ability=line,
                structurally_eligible=not bool(reason),structural_reason=reason or 'Candidate only; not automatic VERIFIED_SAFE',
                native_probe=name in PROBED,verification=outcome,registered=name in REGISTERED,
                surface='COMBAT_ACTION' if name in REGISTERED else 'None added',quest_precon_count=len(decks),quest_precon_examples='; '.join(sorted(decks)[:5])))
    out=pathlib.Path('tools/ai-evaluation/stage16/recovered-abilities.csv')
    with out.open('w',encoding='utf-8-sig',newline='') as f:
        w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
    summary=dict(warned_card_records_examined=examined,relevant_instant_abilities_screened=len(rows),
        structurally_eligible=sum(r['structurally_eligible'] for r in rows),eligible_cards=[r['card'] for r in rows if r['structurally_eligible']],
        native_probed=sorted(PROBED),individually_verified=len(REGISTERED),registered_total=len(REGISTERED),new_registry_entries=3,
        probe_candidates_not_admitted=sorted(PROBED-REGISTERED),verified_warning_protection_additions=0)
    pathlib.Path('work/stage16_2/candidate-summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps(summary,indent=2))

if __name__ == '__main__': run()
