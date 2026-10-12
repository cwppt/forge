# Stage 14.1 strategic review

Analysis date: 2026-10-08. The existing evidence shows a repeated local combat weakness: unnecessary losing blocks at safe life totals. This is sufficient to justify a narrowly scoped follow-up, but not a claim that tuning will improve full-game win rate. No strategic behavior was changed for this review.

Primary cohort: `C:/Users/Chris/Workspace/forge/ai-eval/stage14/20261008-230210-574219ba/`. Sources: original JSONL/CSV, captured requests, stdout game events, `review-index.json`, `run.json`, `aggregate.json`, and `tools/ai-evaluation/Stage14Closeout.md`. The earlier smoke and interrupted run are excluded from all statistics below. There are 40 games: 20 external and 20 controls. Repeated controls for the two seat orientations are not 20 independent seed/matchup draws.

All 158 external calls were accepted, with zero fallback, timeout, stale failure, duplicate request or crash. This review examines all 16 blocker disagreements, all 28 attacker disagreements, the single main-phase disagreement, both stack disagreements and all five combat actions. Eventual results are descriptive labels, not tactical grading criteria. Actual outcomes are not counterfactual replays of Forge's alternative.

## 1. Every blocker disagreement

Classes: **A** clearly better; **B** defensible/plausibly better; **C** roughly equivalent; **D** plausibly worse; **E** clearly worse locally on the visible evidence; **F** indeterminate. An E label concerns the local exchange, not proven game-level expected value. Counts: **7 E, 2 D, 4 B, 3 C, 0 A/F**.

Games abbreviate Aurochs Stampede / Selesnya Surge as Aurochs, Gatecrasher / Air Forces as Gatecrasher. Seats are zero-based. Life is external defender / opponent. Duplicate creature numbers below follow the battlefield request order and identify exact supplied mappings; they are review labels, not model option IDs. Every row offers NO_BLOCK and exactly the block mappings listed.

| Case / game / turn / eventual result | Life | Attackers; available blockers | All supplied mappings; Forge → external | Immediate result: damage, deaths, remaining combatants | Class / tactical reason |
|---|---|---|---|---|---|
| B01 Aurochs 1401 s0 T9 loss | 17/18 | Centaur Courser 3/3 tapped, Centaur Courser 3/3 tapped; blockers: Earthen Goo 2/2 [trample] | NO_BLOCK; Earthen Goo#1 → Centaur Courser#1; Earthen Goo#1 → Centaur Courser#2. Forge NO_BLOCK → external Earthen Goo#1 → Centaur Courser#1 | 3 damage → 14 life; deaths own Earthen Goo; enemy none recorded; attackers/blockers still on battlefield 2/0 | E — Earthen Goo dies saving 3 life at 17. NO_BLOCK leaves 11 life and retains the only creature; no profitable trade or imminent lethal in the supplied state. |
| B02 Aurochs 1401 s0 T13 loss | 2/18 | Centaur Courser 3/3 tapped, Centaur Token 3/3 tapped, Centaur Courser 3/3 tapped; blockers: Bull Aurochs 2/1 [trample] | Bull Aurochs#1 → Centaur Courser#1; NO_BLOCK; Bull Aurochs#1 → Centaur Token#1; Bull Aurochs#1 → Centaur Courser#2. Forge Bull Aurochs#1 → Centaur Courser#1 → external Bull Aurochs#1 → Centaur Courser#2 | 6 damage → -4 life; deaths own none recorded; enemy none recorded; attackers/blockers still on battlefield 3/1 (terminal snapshot before lethal creature cleanup) | C — At 2 life, every supplied single block still lets at least 6 damage through. Swapping identical Coursers changes no escape; this is already forced lethal within the available options. |
| B03 Aurochs 1402 s0 T10 loss | 14/19 | Brushstrider 3/1 [vigilance], Vitu-Ghazi Guildmage 2/2 tapped; blockers: Woolly Mammoths 3/2 [trample] | NO_BLOCK; Woolly Mammoths#1 → Brushstrider#1; Woolly Mammoths#1 → Vitu-Ghazi Guildmage#1. Forge NO_BLOCK → external Woolly Mammoths#1 → Vitu-Ghazi Guildmage#1 | 3 damage → 11 life; deaths own Woolly Mammoths; enemy Vitu-Ghazi Guildmage; attackers/blockers still on battlefield 1/0 | B — Mammoths trades with Vitu-Ghazi Guildmage, removing a repeatable token/populate engine. Blocking Brushstrider instead saves one more life, but Guildmage value makes this defensible. |
| B04 Aurochs 1402 s1 T5 win | 20/20 | Bull Aurochs 2/1 [trample] tapped; blockers: Brushstrider 3/1 [vigilance] | NO_BLOCK; Brushstrider#1 → Bull Aurochs#1. Forge NO_BLOCK → external Brushstrider#1 → Bull Aurochs#1 | 1 damage → 19 life; deaths own Brushstrider; enemy Bull Aurochs; attackers/blockers still on battlefield 0/0 | D — Brushstrider trades with Bull and still takes 1 trample damage. NO_BLOCK costs only 2 life at 20 and retains a 3-power vigilant creature; removing an Aurochs synergy piece supplies a possible justification. |
| B05 Aurochs 1403 s0 T7 loss | 20/20 | Bird Token 2/2 [flying] tapped, Seller of Songbirds 1/2 tapped; blockers: Boreal Druid 1/1 | NO_BLOCK; Boreal Druid#1 → Seller of Songbirds#1. Forge NO_BLOCK → external Boreal Druid#1 → Seller of Songbirds#1 | 2 damage → 18 life; deaths own Boreal Druid; enemy none recorded; attackers/blockers still on battlefield 2/0 | E — Druid cannot block the flyer; it dies to Seller to save just 1 life at 20. NO_BLOCK retains a mana creature at 17 life. Evasion legality was represented correctly. |
| B06 Aurochs 1403 s1 T10 loss | 19/20 | Aurochs 8/9 [trample] tapped; blockers: Centaur's Herald 0/1 | NO_BLOCK; Centaur's Herald#1 → Aurochs#1. Forge NO_BLOCK → external Centaur's Herald#1 → Aurochs#1 | 7 damage → 12 life; deaths own Centaur's Herald; enemy none recorded; attackers/blockers still on battlefield 1/0 | E — Herald absorbs only 1 trample damage at 19 life and dies. NO_BLOCK leaves 11 life and retains the potential 3/3 token activation. |
| B07 Aurochs 1403 s1 T16 loss | 8/20 | Boreal Druid 1/1 tapped, Aurochs 12/12 [trample] tapped, Aurochs 3/3 [trample] tapped; blockers: Vitu-Ghazi Guildmage 2/2 | Vitu-Ghazi Guildmage#1 → Aurochs#1; NO_BLOCK; Vitu-Ghazi Guildmage#1 → Boreal Druid#1; Vitu-Ghazi Guildmage#1 → Aurochs#2. Forge Vitu-Ghazi Guildmage#1 → Aurochs#1 → external Vitu-Ghazi Guildmage#1 → Boreal Druid#1 | 15 damage → -7 life; deaths own none recorded; enemy none recorded; attackers/blockers still on battlefield 3/1 (terminal snapshot before lethal creature cleanup) | C — All supplied choices are lethal at 8 life. External blocks Druid instead of the large trampler, taking 15 rather than the visible 14 with Forge, but neither survives. |
| B08 Aurochs 1404 s1 T10 loss | 17/17 | Bull Aurochs 2/1 [trample] tapped; blockers: Centaur's Herald 0/1 | NO_BLOCK; Centaur's Herald#1 → Bull Aurochs#1. Forge NO_BLOCK → external Centaur's Herald#1 → Bull Aurochs#1 | 0 damage → 17 life; deaths own none recorded; enemy Bull Aurochs; attackers/blockers still on battlefield 0/1 | B — Not a naked 0/1 chump: stdout lines 263–272 show native Savage Surge giving Herald +2/+2 after blocking. Bull dies, Herald lives, no life lost. The blocker request omitted own hand, so it does not prove the model anticipated the trick. |
| B09 Aurochs 1405 s0 T11 loss | 20/20 | Centaur Courser 3/3 tapped; blockers: Aurochs 2/3 [trample] | NO_BLOCK; Aurochs#1 → Centaur Courser#1. Forge NO_BLOCK → external Aurochs#1 → Centaur Courser#1 | 0 damage → 20 life; deaths own Aurochs; enemy none recorded; attackers/blockers still on battlefield 1/0 | E — Aurochs 2/3 dies to Courser 3/3 for no kill, saving 3 life at 20. NO_BLOCK leaves 17 with Aurochs available. |
| B10 Aurochs 1405 s0 T13 loss | 20/20 | Centaur Courser 3/3 tapped, Centaur Token 3/3 tapped, Centaur Token 3/3 tapped; blockers: Aurochs 2/3 [trample] | NO_BLOCK; Aurochs#1 → Centaur Courser#1; Aurochs#1 → Centaur Token#2; Aurochs#1 → Centaur Token#1. Forge NO_BLOCK → external Aurochs#1 → Centaur Courser#1 | 6 damage → 14 life; deaths own Aurochs; enemy none recorded; attackers/blockers still on battlefield 3/0 | E — Aurochs 2/3 dies to Courser for no kill, saving 3 life at 20. NO_BLOCK leaves 11 rather than 14 and retains a creature; two other 3/3 attackers remain either way. |
| B11 Gatecrasher 1401 s0 T12 loss | 18/20 | Llanowar Sentinel 2/3 tapped, Mistmoon Griffin 2/2 [flying] tapped; blockers: Striped Bears 2/2 | NO_BLOCK; Striped Bears#1 → Llanowar Sentinel#1. Forge NO_BLOCK → external Striped Bears#1 → Llanowar Sentinel#1 | 2 damage → 16 life; deaths own Striped Bears; enemy none recorded; attackers/blockers still on battlefield 2/0 | E — Bears dies to Sentinel without killing it to save 2 life at 18. Griffin still deals 2 flying damage. NO_BLOCK leaves 14 and retains Bears. |
| B12 Gatecrasher 1401 s1 T11 loss | 20/16 | Llanowar Sentinel 2/3 tapped; blockers: Mistmoon Griffin 2/2 [flying] | NO_BLOCK; Mistmoon Griffin#1 → Llanowar Sentinel#1. Forge NO_BLOCK → external Mistmoon Griffin#1 → Llanowar Sentinel#1 | 0 damage → 20 life; deaths own Mistmoon Griffin; enemy none recorded; attackers/blockers still on battlefield 1/0 | B — Griffin death triggers a return of the earlier Fog Elemental (same engine card 80 in stdout lines 250–368). Saves 2 life and converts Griffin into a 4/4 flyer. Defensible, not a simple card-for-life chump; graveyard context was absent from the request. |
| B13 Gatecrasher 1401 s1 T17 loss | 7/12 | Stampeding Wildebeests 5/4 [trample] tapped, Llanowar Sentinel 2/3 tapped, Llanowar Sentinel 2/3 tapped; blockers: Benalish Knight 2/2 [first strike] | Benalish Knight#1 → Llanowar Sentinel#1; NO_BLOCK; Benalish Knight#1 → Stampeding Wildebeests#1; Benalish Knight#1 → Llanowar Sentinel#2. Forge Benalish Knight#1 → Llanowar Sentinel#1 → external Benalish Knight#1 → Stampeding Wildebeests#1 | 7 damage → 0 life; deaths own none recorded; enemy none recorded; attackers/blockers still on battlefield 3/1 (terminal snapshot before lethal creature cleanup) | C — At 7 life, all single-block options still take at least 7 from the 5/4 trampler and two 2/3s. Knight first-strikes for 2 but cannot kill Wildebeests; no supplied surviving line is missed. |
| B14 Gatecrasher 1402 s1 T13 loss | 16/20 | Llanowar Sentinel 2/3 tapped; blockers: Benalish Knight 2/2 [first strike] | NO_BLOCK; Benalish Knight#1 → Llanowar Sentinel#1. Forge NO_BLOCK → external Benalish Knight#1 → Llanowar Sentinel#1 | 0 damage → 16 life; deaths own Benalish Knight; enemy none recorded; attackers/blockers still on battlefield 1/0 | E — Knight first-strikes for 2 into 3 toughness, fails to kill Sentinel, then dies to 2 return damage. Saves only 2 life at 16; NO_BLOCK retains Knight at 14. |
| B15 Gatecrasher 1402 s1 T15 loss | 16/20 | Llanowar Sentinel 2/3 tapped; blockers: Mistmoon Griffin 2/2 [flying] | NO_BLOCK; Mistmoon Griffin#1 → Llanowar Sentinel#1. Forge NO_BLOCK → external Mistmoon Griffin#1 → Llanowar Sentinel#1 | 0 damage → 16 life; deaths own Mistmoon Griffin; enemy none recorded; attackers/blockers still on battlefield 1/0 | B — Griffin death returns the Knight that died on turn 13: stdout turn 15 shows the trigger, and MAIN2 already shows Knight 73 before any intervening external deployment. Saves 2 life while replacing Griffin with a first-striker. Value comparison is debatable, not clearly worse. |
| B16 Gatecrasher 1402 s1 T19 loss | 14/20 | Aboroth 8/8 tapped; blockers: Alabaster Dragon 4/4 [flying] | NO_BLOCK; Alabaster Dragon#1 → Aboroth#1. Forge NO_BLOCK → external Alabaster Dragon#1 → Aboroth#1 | 0 damage → 14 life; deaths own Alabaster Dragon; enemy none recorded; attackers/blockers still on battlefield 1/0 | D — Dragon dies to Aboroth, saving 8 life at 14. NO_BLOCK leaves 6 and keeps a 4/4 flyer against a creature shrinking to cumulative upkeep. Sacrifice may buy needed time; a full future counterfactual is unavailable. |

The audit's after-state is a combat observer snapshot, not a complete final battlefield. Deaths are event records; on lethal combat the game can stop before state-based creature deaths. End-of-combat sacrifices can occur after the snapshot. Therefore empty death lists in B02/B07/B13 are not evidence that lethally damaged creatures survived. The following case records preserve the starting creature roster and next actual public roster, with its timestamp. Do not treat a later roster as an immediate postcombat snapshot. For immediate board changes, subtract the table's recorded deaths and account for the confirmed Griffin returns in B12/B15; no unobserved complete permanent state is invented.

<details>
<summary>Exact blocker request references, option IDs, and resulting-board evidence for all 16 cases</summary>


**B01** — `Aurochs-1401-seat0-External.jsonl`; decision `f4def8d8-d9f6-45bf-b3a6-09a3bf15c7ba`; combat `G1-I0-T9-P1-C9`.

Request creature roster: seat 0: Earthen Goo 2/2 [trample]; seat 1: Centaur Courser 3/3 tapped, Centaur Courser 3/3 tapped, Centaur's Herald 0/1.

Exact options: `NO_BLOCK` = NO_BLOCK; `OPT_1E7CBC4DBF634169F340D411` = Earthen Goo#1 → Centaur Courser#1; `OPT_093A17114152BFC79A047595` = Earthen Goo#1 → Centaur Courser#2.

Forge `NO_BLOCK`; external `OPT_1E7CBC4DBF634169F340D411`.

Resulting creature roster reconstructed from the request, recorded deaths and confirmed returns: seat 0: no creatures; seat 1: Centaur Courser, Centaur Courser, Centaur's Herald. This is not an independently captured full battlefield; temporary P/T, marked damage and any unrecorded changes are not inferred.

Next observed roster: T13 COMBAT_DECLARE_BLOCKERS: seat 0: Bull Aurochs 2/1 [trample]; seat 1: Centaur Courser 3/3 tapped, Centaur Courser 3/3 tapped, Centaur Token 3/3 tapped.

**B02** — `Aurochs-1401-seat0-External.jsonl`; decision `2f687e61-f694-484d-997c-b2fedb91bf41`; combat `G1-I0-T13-P1-C13`.

Request creature roster: seat 0: Bull Aurochs 2/1 [trample]; seat 1: Centaur Courser 3/3 tapped, Centaur Courser 3/3 tapped, Centaur Token 3/3 tapped.

Exact options: `OPT_EC9B4BDD2B9BECB59F5FC98E` = Bull Aurochs#1 → Centaur Courser#1; `NO_BLOCK` = NO_BLOCK; `OPT_71B72B4119D46D46A06CED80` = Bull Aurochs#1 → Centaur Token#1; `OPT_757A8A3188500F0040232543` = Bull Aurochs#1 → Centaur Courser#2.

Forge `OPT_EC9B4BDD2B9BECB59F5FC98E`; external `OPT_757A8A3188500F0040232543`.

Resulting board: game ended during combat; the recorded surviving-count snapshot precedes creature cleanup, so a final living-creature roster is not inferred.

Next observed roster: no later provider state; terminal/final-combat evidence only.

**B03** — `Aurochs-1402-seat0-External.jsonl`; decision `0134a8c2-d25e-4a6d-ada1-12f72a645914`; combat `G1-I0-T10-P1-C10`.

Request creature roster: seat 0: Woolly Mammoths 3/2 [trample]; seat 1: Brushstrider 3/1 [vigilance], Vitu-Ghazi Guildmage 2/2 tapped.

Exact options: `NO_BLOCK` = NO_BLOCK; `OPT_550D198876F1BA41DFF18FA5` = Woolly Mammoths#1 → Brushstrider#1; `OPT_BA410782FB3E0C8AEB6B21E6` = Woolly Mammoths#1 → Vitu-Ghazi Guildmage#1.

Forge `NO_BLOCK`; external `OPT_BA410782FB3E0C8AEB6B21E6`.

Resulting creature roster reconstructed from the request, recorded deaths and confirmed returns: seat 0: no creatures; seat 1: Brushstrider. This is not an independently captured full battlefield; temporary P/T, marked damage and any unrecorded changes are not inferred.

Next observed roster: T13 COMBAT_DECLARE_ATTACKERS: seat 0: Bull Aurochs 2/1 [trample]; seat 1: Brushstrider 3/1 [vigilance].

**B04** — `Aurochs-1402-seat1-External.jsonl`; decision `83948afe-f2a5-4eeb-9b59-4e3e11098cec`; combat `G1-I0-T5-P0-C5`.

Request creature roster: seat 0: Bull Aurochs 2/1 [trample] tapped; seat 1: Brushstrider 3/1 [vigilance].

Exact options: `NO_BLOCK` = NO_BLOCK; `OPT_08E4B9D858401AA8780FAA3D` = Brushstrider#1 → Bull Aurochs#1.

Forge `NO_BLOCK`; external `OPT_08E4B9D858401AA8780FAA3D`.

Resulting creature roster reconstructed from the request, recorded deaths and confirmed returns: seat 0: no creatures; seat 1: no creatures. This is not an independently captured full battlefield; temporary P/T, marked damage and any unrecorded changes are not inferred.

Next observed roster: T6 MAIN2: seat 0: no creatures; seat 1: no creatures.

**B05** — `Aurochs-1403-seat0-External.jsonl`; decision `bc9841f9-9c86-4eda-adaf-b46ab8dd9b47`; combat `G1-I0-T7-P1-C7`.

Request creature roster: seat 0: Boreal Druid 1/1; seat 1: Bird Token 2/2 [flying] tapped, Centaur's Herald 0/1, Phantom General 2/3, Seller of Songbirds 1/2 tapped.

Exact options: `NO_BLOCK` = NO_BLOCK; `OPT_01CEDE48D2CE2894572FF538` = Boreal Druid#1 → Seller of Songbirds#1.

Forge `NO_BLOCK`; external `OPT_01CEDE48D2CE2894572FF538`.

Resulting creature roster reconstructed from the request, recorded deaths and confirmed returns: seat 0: no creatures; seat 1: Bird Token, Centaur's Herald, Phantom General, Seller of Songbirds. This is not an independently captured full battlefield; temporary P/T, marked damage and any unrecorded changes are not inferred.

Next observed roster: T9 COMBAT_DECLARE_BLOCKERS: seat 0: Aurochs 2/3 [trample]; seat 1: Bird Token 2/2 [flying] tapped, Centaur's Herald 0/1, Phantom General 2/3, Seller of Songbirds 1/2 tapped.

**B06** — `Aurochs-1403-seat1-External.jsonl`; decision `41c6adb9-af0e-4865-bc1b-877a74970397`; combat `G1-I0-T10-P0-C10`.

Request creature roster: seat 0: Aurochs 8/9 [trample] tapped, Boreal Druid 1/1 tapped; seat 1: Centaur's Herald 0/1.

Exact options: `NO_BLOCK` = NO_BLOCK; `OPT_9288E686CE8944F579C58AB0` = Centaur's Herald#1 → Aurochs#1.

Forge `NO_BLOCK`; external `OPT_9288E686CE8944F579C58AB0`.

Resulting creature roster reconstructed from the request, recorded deaths and confirmed returns: seat 0: Aurochs, Boreal Druid; seat 1: no creatures. This is not an independently captured full battlefield; temporary P/T, marked damage and any unrecorded changes are not inferred.

Next observed roster: T15 MAIN2: seat 0: Aurochs 2/3, Aurochs 10/11 tapped, Boreal Druid 1/1 tapped; seat 1: no creatures.

**B07** — `Aurochs-1403-seat1-External.jsonl`; decision `d6ce9ff0-7e5d-412c-902b-880c7304c620`; combat `G1-I0-T16-P0-C16`.

Request creature roster: seat 0: Aurochs 12/12 [trample] tapped, Aurochs 3/3 [trample] tapped, Boreal Druid 1/1 tapped; seat 1: Vitu-Ghazi Guildmage 2/2.

Exact options: `OPT_FFDA1DDE40CB944832E54886` = Vitu-Ghazi Guildmage#1 → Aurochs#1; `NO_BLOCK` = NO_BLOCK; `OPT_D402C1FB983A1A38E6E8563C` = Vitu-Ghazi Guildmage#1 → Boreal Druid#1; `OPT_606300382E637129DC2E15FA` = Vitu-Ghazi Guildmage#1 → Aurochs#2.

Forge `OPT_FFDA1DDE40CB944832E54886`; external `OPT_D402C1FB983A1A38E6E8563C`.

Resulting board: game ended during combat; the recorded surviving-count snapshot precedes creature cleanup, so a final living-creature roster is not inferred.

Next observed roster: no later provider state; terminal/final-combat evidence only.

**B08** — `Aurochs-1404-seat1-External.jsonl`; decision `74895a08-6e75-4fd4-83b8-f14208667afb`; combat `G1-I0-T10-P0-C10`.

Request creature roster: seat 0: Bull Aurochs 2/1 [trample] tapped; seat 1: Centaur Courser 3/3 tapped, Centaur's Herald 0/1.

Exact options: `NO_BLOCK` = NO_BLOCK; `OPT_0F1193FEEB91E045D62DEB66` = Centaur's Herald#1 → Bull Aurochs#1.

Forge `NO_BLOCK`; external `OPT_0F1193FEEB91E045D62DEB66`.

Resulting creature roster reconstructed from the request, recorded deaths and confirmed returns: seat 0: no creatures; seat 1: Centaur Courser, Centaur's Herald. This is not an independently captured full battlefield; temporary P/T, marked damage and any unrecorded changes are not inferred.

Next observed roster: T11 COMBAT_DECLARE_ATTACKERS: seat 0: Deadly Insect 6/1; seat 1: Centaur Courser 3/3, Centaur's Herald 0/1.

**B09** — `Aurochs-1405-seat0-External.jsonl`; decision `fb368348-82fa-4879-ba13-497d73950f91`; combat `G1-I0-T11-P1-C11`.

Request creature roster: seat 0: Aurochs 2/3 [trample]; seat 1: Centaur Courser 3/3 tapped.

Exact options: `NO_BLOCK` = NO_BLOCK; `OPT_C088C7E2E759E9979A517958` = Aurochs#1 → Centaur Courser#1.

Forge `NO_BLOCK`; external `OPT_C088C7E2E759E9979A517958`.

Resulting creature roster reconstructed from the request, recorded deaths and confirmed returns: seat 0: no creatures; seat 1: Centaur Courser. This is not an independently captured full battlefield; temporary P/T, marked damage and any unrecorded changes are not inferred.

Next observed roster: T12 MAIN2: seat 0: no creatures; seat 1: Centaur Courser 3/3 tapped, Centaur Token 3/3, Centaur Token 3/3.

**B10** — `Aurochs-1405-seat0-External.jsonl`; decision `49ae8955-7c8b-463c-9272-bb926c26755b`; combat `G1-I0-T13-P1-C13`.

Request creature roster: seat 0: Aurochs 2/3 [trample]; seat 1: Centaur Courser 3/3 tapped, Centaur Token 3/3 tapped, Centaur Token 3/3 tapped.

Exact options: `NO_BLOCK` = NO_BLOCK; `OPT_D47C4B591261E8EE33069EC2` = Aurochs#1 → Centaur Courser#1; `OPT_8286A46281641F4CEE979E0D` = Aurochs#1 → Centaur Token#2; `OPT_A69BA6BC97C85E9A2D3BC00C` = Aurochs#1 → Centaur Token#1.

Forge `NO_BLOCK`; external `OPT_D47C4B591261E8EE33069EC2`.

Resulting creature roster reconstructed from the request, recorded deaths and confirmed returns: seat 0: no creatures; seat 1: Centaur Courser, Centaur Token, Centaur Token. This is not an independently captured full battlefield; temporary P/T, marked damage and any unrecorded changes are not inferred.

Next observed roster: T15 COMBAT_DECLARE_BLOCKERS: seat 0: Earthen Goo 2/2 [trample]; seat 1: Axebane Stag 6/7 tapped, Centaur Courser 3/3 tapped, Centaur Token 3/3 tapped, Centaur Token 3/3 tapped.

**B11** — `Gatecrasher-1401-seat0-External.jsonl`; decision `8bb5b26e-38c8-4f38-bb29-6028a557a6d6`; combat `G1-I0-T12-P1-C12`.

Request creature roster: seat 0: Striped Bears 2/2; seat 1: Llanowar Sentinel 2/3 tapped, Mistmoon Griffin 2/2 [flying] tapped.

Exact options: `NO_BLOCK` = NO_BLOCK; `OPT_37DC2232E444D8A1EAD6FD4D` = Striped Bears#1 → Llanowar Sentinel#1.

Forge `NO_BLOCK`; external `OPT_37DC2232E444D8A1EAD6FD4D`.

Resulting creature roster reconstructed from the request, recorded deaths and confirmed returns: seat 0: no creatures; seat 1: Llanowar Sentinel, Mistmoon Griffin. This is not an independently captured full battlefield; temporary P/T, marked damage and any unrecorded changes are not inferred.

Next observed roster: T19 COMBAT_DECLARE_ATTACKERS: seat 0: Llanowar Sentinel 1/2; seat 1: Benalish Knight 2/2 [first strike], Man-o'-War 2/2, Serrated Biskelion 1/1 tapped.

**B12** — `Gatecrasher-1401-seat1-External.jsonl`; decision `93e2c53f-63ed-4a47-9e9f-53b99ecb9e3e`; combat `G1-I0-T11-P0-C11`.

Request creature roster: seat 0: Llanowar Sentinel 2/3 tapped; seat 1: Llanowar Sentinel 2/3 tapped, Mistmoon Griffin 2/2 [flying].

Exact options: `NO_BLOCK` = NO_BLOCK; `OPT_C3816AFBE675A954BCC51501` = Mistmoon Griffin#1 → Llanowar Sentinel#1.

Forge `NO_BLOCK`; external `OPT_C3816AFBE675A954BCC51501`.

Resulting creature roster reconstructed from the request, recorded deaths and confirmed returns: seat 0: Llanowar Sentinel; seat 1: Llanowar Sentinel, Fog Elemental. This is not an independently captured full battlefield; temporary P/T, marked damage and any unrecorded changes are not inferred.

Next observed roster: T12 COMBAT_DECLARE_ATTACKERS: seat 0: Llanowar Sentinel 2/3 tapped, Stampeding Wildebeests 5/4 [trample], Wall of Roots 0/5 [defender]; seat 1: Fog Elemental 4/4 [flying], Llanowar Sentinel 2/3.

**B13** — `Gatecrasher-1401-seat1-External.jsonl`; decision `45d6b38b-9469-49c5-8a92-568a72f9f1ec`; combat `G1-I0-T17-P0-C17`.

Request creature roster: seat 0: Llanowar Sentinel 2/3 tapped, Llanowar Sentinel 2/3 tapped, Stampeding Wildebeests 5/4 [trample] tapped; seat 1: Benalish Knight 2/2 [first strike].

Exact options: `OPT_BBB2A6CBB624964E57AAF6FE` = Benalish Knight#1 → Llanowar Sentinel#1; `NO_BLOCK` = NO_BLOCK; `OPT_CE04D72DC89A8C1981B14366` = Benalish Knight#1 → Stampeding Wildebeests#1; `OPT_D666A4AEA3B39EA072AFB079` = Benalish Knight#1 → Llanowar Sentinel#2.

Forge `OPT_BBB2A6CBB624964E57AAF6FE`; external `OPT_CE04D72DC89A8C1981B14366`.

Resulting board: game ended during combat; the recorded surviving-count snapshot precedes creature cleanup, so a final living-creature roster is not inferred.

Next observed roster: no later provider state; terminal/final-combat evidence only.

**B14** — `Gatecrasher-1402-seat1-External.jsonl`; decision `2662e318-5de9-4b91-952b-b1ad10fe149c`; combat `G1-I0-T13-P0-C13`.

Request creature roster: seat 0: Llanowar Sentinel 2/3 tapped, Striped Bears 2/2, Wall of Roots 0/3 [defender]; seat 1: Benalish Knight 2/2 [first strike].

Exact options: `NO_BLOCK` = NO_BLOCK; `OPT_469F96D578551CCA7F75841E` = Benalish Knight#1 → Llanowar Sentinel#1.

Forge `NO_BLOCK`; external `OPT_469F96D578551CCA7F75841E`.

Resulting creature roster reconstructed from the request, recorded deaths and confirmed returns: seat 0: Llanowar Sentinel, Striped Bears, Wall of Roots; seat 1: no creatures. This is not an independently captured full battlefield; temporary P/T, marked damage and any unrecorded changes are not inferred.

Next observed roster: T15 COMBAT_DECLARE_BLOCKERS: seat 0: Llanowar Sentinel 2/3 tapped, Striped Bears 2/2, Wall of Roots 0/2 [defender], Wall of Roots 0/5 [defender]; seat 1: Mistmoon Griffin 2/2 [flying].

**B15** — `Gatecrasher-1402-seat1-External.jsonl`; decision `a17e91b8-772a-4d36-a325-a7e6cce732b3`; combat `G1-I0-T15-P0-C15`.

Request creature roster: seat 0: Llanowar Sentinel 2/3 tapped, Striped Bears 2/2, Wall of Roots 0/2 [defender], Wall of Roots 0/5 [defender]; seat 1: Mistmoon Griffin 2/2 [flying].

Exact options: `NO_BLOCK` = NO_BLOCK; `OPT_34FDD10E15A17640B949D8F5` = Mistmoon Griffin#1 → Llanowar Sentinel#1.

Forge `NO_BLOCK`; external `OPT_34FDD10E15A17640B949D8F5`.

Resulting creature roster reconstructed from the request, recorded deaths and confirmed returns: seat 0: Llanowar Sentinel, Striped Bears, Wall of Roots, Wall of Roots; seat 1: Benalish Knight. This is not an independently captured full battlefield; temporary P/T, marked damage and any unrecorded changes are not inferred.

Next observed roster: T15 MAIN2: seat 0: Llanowar Sentinel 2/3 dmg=2 tapped, Striped Bears 2/2, Wall of Roots 0/1, Wall of Roots 0/5; seat 1: Benalish Knight 2/2.

**B16** — `Gatecrasher-1402-seat1-External.jsonl`; decision `99af9733-f838-4f06-a338-d744836ab201`; combat `G1-I0-T19-P0-C19`.

Request creature roster: seat 0: Aboroth 8/8 tapped, Llanowar Sentinel 2/3, Striped Bears 2/2, Wall of Roots 0/5 [defender]; seat 1: Alabaster Dragon 4/4 [flying].

Exact options: `NO_BLOCK` = NO_BLOCK; `OPT_E7551BBB211B613FEAD7DE96` = Alabaster Dragon#1 → Aboroth#1.

Forge `NO_BLOCK`; external `OPT_E7551BBB211B613FEAD7DE96`.

Resulting creature roster reconstructed from the request, recorded deaths and confirmed returns: seat 0: Aboroth, Llanowar Sentinel, Striped Bears, Wall of Roots; seat 1: no creatures. This is not an independently captured full battlefield; temporary P/T, marked damage and any unrecorded changes are not inferred.

Next observed roster: no later provider state; terminal/final-combat evidence only.

</details>

## 2. Repeated blocker patterns

**Early losing blocks to save small amounts of life: seven cases across six games.** B01, B05, B06, B09, B10, B11 and B14 sacrifice a creature without killing an attacker while at 16–20 life. They save only 1–3 life each. NO_BLOCK is present in every request; the projected unblocked damage is nonlethal and leaves 11–17 life. Thus this is a selection weakness, not a missing alternative. Druid/Herald also have visible mana/token value. B14 is particularly direct arithmetic: 2 first-strike damage does not kill a 2/3.

**Three disagreements in already lethal positions are not evidence of a missed saving block:** B02, B07 and B13 have no surviving supplied assignment. B07 loses one more life than Forge, but the result remains forced lethal. Do not count these as under-blocking errors or use them to justify a lethal guard.

**Special effects make three apparent chumps defensible:** B08 uses native Savage Surge after the external block; B12/B15 recur a creature through Mistmoon Griffin. Neither a universal “never chump at high life” rule nor grading purely by dead blocker count is supported.

No repeated under-blocking, over-preservation of creatures, missed available trade-up, illegal evasion handling or inefficient multi-blocking is established. All 16 disagreements involve one available blocker. B06 demonstrates poor life-for-card value against trample, but the cohort does not separately establish a general trample-rules failure. B04/B16 remain plausible value/tempo disagreements rather than definitive errors.

## 3. The five indexed strategic sequences

Only provider-attempted records are external decisions; native-seat and bypass records must not be attributed to the model. All five external hands were kept in agreement with Forge. Combat attack/block projections omit own hand and graveyard, so this review does not assume that the model planned subsequent native interaction.

| Indexed game / focal turn | Earlier external choices | Focal chain and later events | Local versus cross-surface conclusion |
|---|---|---|---|
| Gatecrasher 1401 s1 / T17 | KEEP: three Islands, Disrupt, Fog Elemental, Abduction, Vodalian Illusionist. T3 Disrupt on Rampant Growth agrees with Forge. T8 Fog attacks and sacrifices at end of combat. T10 Sentinel attacks for no damage. T11 Griffin blocks, returning Fog. T12 adds Sentinel to the flying attack; Sentinel dies to Wildebeests while Fog deals 4. | T17 Memory Lapse on Rampant Growth agrees with Forge, then flashes Knight before blockers. At 7 life against 5/4 trample plus two 2/3s, external blocks Wildebeests rather than a Sentinel. Either assignment still takes 7 and loses immediately. No external main-phase call in this game. | T12 is a local avoidable ground-creature loss and plausibly worsens later defense. T17 cannot be rescued by supplied blocks; blaming the final flash/block disagreement for the loss is unsound. Countering late ramp at 7 life is questionable resource use, but no evidence that declining Lapse makes a saving play payable/available. No demonstrated need for cross-turn memory. |
| Aurochs 1405 s1 / T13 | KEEP: two Coursers, Grove of the Guardian, four Plains; the opponent mulligans separately. T9 one Courser and T11 two Coursers attack, both agreeing with Forge. No external main/stack/block call occurs. | T13 all four 3/3s attack in agreement with Forge. Native Bull and Shaman block two. External chooses Chorus of Might on the Shaman-blocked Courser over Forge PASS: +4/+4 and trample. Six unblocked damage plus six trample is 12 against 11 life, ending the game. | Positive attack → prepared trick alignment. PASS would deal only 6 on the visible board, so this is a locally meaningful improvement, independent of the eventual win label. |
| Gatecrasher 1402 s1 / T13 | KEEP: Knight, Island, two Plains, Pacifism, Illusionist, Waterspout Djinn. T9 Disrupt against Incinerate differs from Forge PASS; spell resolves and Biskelion dies, but Disrupt draws. T10 MAIN2 chooses Djinn over Biskelion in agreement. | T13 flashes Knight, then sacrifices it into Sentinel 2/3 at 16 life (B14). T15 Griffin blocks the same Sentinel and returns Knight; Memory Lapse on Aboroth agrees with Forge. T16 Knight attacks into a 0/5 Wall without effect; T18 it attacks into Aboroth 9/9 and dies. T19 Dragon blocks Aboroth and dies; loss T21. | Flashing a creature is a legal deployment; the following bad block is locally visible without remembering the flash. The sequence spends resources and then wastes the deployed creature, but the root demonstrated failure is local combat evaluation. Later Knight recurrence makes B15 defensible. No proof T9 Disrupt caused T13 weakness. |
| Aurochs 1401 s1 / T13 | KEEP: Courser, Herald, Chorus, two Forests, Guildmage, Wayfaring Temple. T3 sends 0/1 Herald into Shaman and loses it for no damage; a local bad attack. T7 Courser attacks; T9 chooses one Courser rather than two; T11 both attack. No external stack/block call. | T13 attacks with both Coursers and pumps the blocked one +2/+2 trample, agreeing with Forge. Deals 7 rather than visible 3 without the pump, leaving opponent at 1. T15 native triple-blocks: one Courser and all three native blockers die. T17 remaining Courser attacks, then MAIN2 chooses Guildmage over Temple. T19 Courser/Guildmage/Temple attack; win T19. | Trick timing aligns with attackers and increases pressure, but is not immediate lethal and is not uniquely superior to conserving a card. Early Herald loss remains a mistake despite the eventual win. Later deployment/attacks are coherent on recorded choices. |
| Aurochs 1404 s0 / T24 | KEEP: Herd, Incinerate, three snow Forests, snow Mountain, Tinder Wall. T8 Bull dies attacking into Courser; T12 Insect trades with Brushstrider. T15 blocker agrees with Forge; T18/T20 one Herd attacks, T22 both attack. No external main/stack call. | T24 both Herds and Bull attack in agreement. At the action request they are 6/4, 6/4 and 4/1 after Aurochs triggers. Native 3/3s block the Herds. External Resize on unblocked Bull agrees with Forge, dealing 13 against 9. | PASS already represents 3+3 trample plus 4 unblocked = 10 lethal absent further interaction. Resize provides extra margin, not a required lethal discovery. One redundant action in a terminal position is insufficient to establish systematic trick waste. No incoherent target/timing. |

The sequence index is turn-scoped; the surrounding-game reconstruction above uses provider ordinals and combat IDs. Native casting, upkeep and other mechanical decisions are not new external surfaces. A local error can have later consequences without establishing a missing historical context mechanism.

## 4. Attacker findings

All **28/80 disagreements** were inspected, not only losing games. The table below grades the local observed exchange conservatively. Numbers A01–A28 follow audit filename, then decision ordinal, as reproduced by the analysis helper. NO_ATTACK was available in all 80 attacker requests.

| Cases / game / turns | Evidence and assessment |
|---|---|
| A01–A02 Aurochs 1401 s0 T6/T8 | Bull plus Shaman into Courser/Herald loses Bull for 1 damage; later lone Shaman attacks Courser and dies for zero. A01 plausibly worse; A02 clearly worse locally. |
| A03–A04 Aurochs 1401 s1 T3/T9 | Herald 0/1 attacks Shaman and dies for zero (clear local error, despite eventual win). Later attacks one Courser instead of two for 3 damage; holding a blocker is defensible, so no firm missed-free-attack claim. |
| A05 Aurochs 1402 s0 T7 | Bull into Healer looks bad in the request alone, but stdout confirms native Balduvian Rage X=3; both die and 2 trample hits. Defensible trade/pressure, not a plain suicide. |
| A06 Aurochs 1402 s1 T12 | Adds Healer to Guildmage attack. Healer and Mammoths trade; Guildmage deals 2. Value/tempo trade is defensible; no clear error. |
| A07–A08 Aurochs 1404 s0 T8/T12 | Bull dies to Courser for no damage (clear local error). Insect trades with Brushstrider; removing a blocker at the cost of a 6-power fragile threat is ambiguous/plausibly worse. |
| A09–A12 Aurochs 1404 s1 T11/T13/T15/T17 | Courser then Brushstrider get through Insect for 3 each, defensible attacks despite Forge caution. Later Brush plus token loses Brush to Herd for 3 damage, plausibly worse. Lone token then dies to Herd for zero, a clear local error. |
| A13 Aurochs 1405 s0 T8 | Druid into Courser is supported by native Balduvian Rage X=3; both die. Defensible removal trade, not proof of bare 1/1 aggression. |
| A14–A16 Gatecrasher 1401 s0 T7/T19/T21 | Sentinel dies attacking into Fog, but Fog sacrifices at end of combat; not a costless opponent win. Later 1/2 Sentinel dies to Man-o'-War for zero (clear local error). Last Sentinel attacks for 2 at own 4 life; defense/race is plausibly worse but later counterfactual uncertain. |
| A17–A18 Gatecrasher 1401 s1 T10/T12 | Sentinel into Sentinel does no damage and loses no creatures; approximately equivalent/tempo uncertain. Then adds ground Sentinel to Forge's Fog-only flying attack: Sentinel dies to Wildebeests, same 4 flying damage; clear avoidable local loss. |
| A19–A20 Gatecrasher 1402 s0 T19/T21 | Adds shrinking Aboroth to Sentinel; Aboroth dies to Knight while Sentinel hits for 2. Cumulative upkeep limits its future value, so ambiguous/plausibly worse. Later adds Sentinel to Behemoth at opponent 2 life; Sentinel dies to Dragon and Knight dies blocking Behemoth, no damage. Nontrivial block/pump options prevent a confident grade. |
| A21–A22 Gatecrasher 1402 s1 T16/T18 | Knight attacks into 0/5 Wall without effect; unnecessary tap, not proven material loss. Later Knight dies attacking 9/9 Aboroth for zero; clear local error. |
| A23–A24 Gatecrasher 1403 s1 T17/T21 | Adds Sentinel to Forge's Djinn-only attack; Sentinel dies to Behemoth for unchanged 4 flying damage (clear local error). Later Djinn attacks at own 5 life, lowering opponent 8→4 and leaving crackback danger; plausibly worse, but broader future tactics are not fully replayed. |
| A25–A28 Gatecrasher 1405 s1 T9/T11/T13/T17 | Twice Biskelion attacks into Roots for zero/no deaths, tapping a useful activated creature; tempo concern, not certain loss. Adds Knight to Biskelion against Roots/Wildebeests: Knight dies for zero. Later lone Biskelion dies to Efreet for zero. Last two are clear local losses. |

There are **10 clear local negative cases**: A02/A03/A07/A12/A15/A18/A22/A23/A27/A28, across nine games (both wins and losses). This is a conservative count, not a grade that every other attack is sound. Two particularly comparable examples, A18/A23, add a doomed ground attacker while preserving exactly the damage of Forge's flying-only option. Both requests contain the safer supplied option and sufficient visible blocker stats.

The supported tendency is excessive aggression / failure to value likely visible blocks, not excessive caution. Do not infer general lethal recognition failure, inability to use evasion, or universal “attack everything”: some smaller attacks are defensible, and two apparent suicides are backed by native tricks. The attacker concern merits a later isolated experiment, after the more tightly identified blocker issue.

## 5. Main-phase findings

The sole disagreement is **Gatecrasher 1404 s0 T15 MAIN2**, decision `4eb922d5-b39e-4da7-97c0-00dc1e242176`. At 1 life with no own creatures, opponent has Sentinel 2/2 with 1 damage marked and Waterspout Djinn 4/4 flying. Forge prefers Arctic Wolves; alternatives are Wall of Roots and Savage Twister X=4. External chooses the prepared Twister, clearing the flying threat that neither ground creature can block. This is **A, clearly better locally**, assuming no new interaction; the later public board confirms the Djinn is gone. The eventual win does not determine this grade.

One disagreement out of 25 calls cannot establish repeated sequencing, mana efficiency, precombat deployment or second-main delay problems. Forge agreement is not strategic correctness: the review's better external sweeper choice is a concrete counterexample to treating Forge as ground truth. No main-phase tuning is justified here.

## 6. Stack findings

Both disagreements among seven calls were reviewed:

| Game / turn | Forge / external | Actual consequence / assessment |
|---|---|---|
| Gatecrasher 1402 s1 T9 DRAW | PASS / Disrupt on Incinerate targeting Biskelion | stdout lines 265–272 confirms Incinerate resolves and Biskelion dies; Disrupt draws a card. This is not successful protection, but a one-mana cantrip with a tax can be defensible. No repeated counter-waste conclusion; grade B/ambiguous value comparison. |
| Gatecrasher 1403 s1 T18 MAIN1 | PASS / Dissipate on Fire Whip | Counter removes a repeatable damage source. Later danger is not evidence the counter was wasted. The stack summary exposes only the name “Fire Whip”, not its full rules, and the prepared target is “Prepared game target”; information adequacy is limited. Strategic superiority remains F without a full counterfactual. |

Agreement cases also deserve context: Gatecrasher 1401 s1 counters Rampant Growth on T3 with Disrupt and T17 with Memory Lapse. The latter is questionable at 7 life, but both Forge and external choose it. There is no proved alternative spell/payment line that survives that turn. Seven called states, and two disagreements, are far too few to infer a counter/removal policy.

## 7. All five combat actions

All action requests include PASS; each has one prepared non-PASS action. None offers a model target choice. Targets and X are Forge-prepared. Action effects below use the current action request, not declaration-time power before Aurochs triggers.

| Game / turn / window | Forge / external | Visible comparison with PASS; timing and alignment |
|---|---|---|
| Aurochs 1401 s1 T13 after blockers | Chorus / same | +2/+2 trample on blocked Courser. 7 damage rather than 3; opponent 8→1. Material pressure, no immediate lethal. Defensible card spend; sensible after blocks and consistent with attacking two Coursers. |
| Aurochs 1404 s0 T24 after blockers | Resize / same | +3/+3 on unblocked Bull increases 10 already-lethal damage to 13 against 9. Extra margin against possible interaction, but unnecessary on the visible static board. One win-more action, not repeated evidence for changing PASS policy. |
| Aurochs 1405 s1 T13 after blockers | PASS / Chorus | +4/+4 trample on Shaman-blocked Courser raises damage 6→12 against 11. A clear tactical improvement over supplied PASS; target/timing align with the four-creature attack. |
| Gatecrasher 1401 s1 T17 after attackers | flash Knight / same | Adds a blocker but at own 7 against 9 incoming including trample, every supplied block still takes at least 7. PASS also loses. Deployment is legal and reasonable as an attempted defense, but it does not save the position. No evidence a different supplied action wins. |
| Gatecrasher 1402 s1 T13 after attackers | flash Knight / same | Adds a useful first-striker at own 16 against Sentinel. Subsequent B14 wastes it to save 2 life; the action itself is defensible deployment. This is a local blocker error after a correctly exposed flash action, not an action preparation/routing defect. |

Three post-block and two pre-block calls; no first-strike-window call. One improvement over Forge PASS, one visible redundant lethal pump, one pressure pump and two flash defenders. This sample does not demonstrate repeated waste or premature use of tricks. Balduvian Rage and Savage Surge also occur through native behavior without an external COMBAT_ACTION request; absence from the five called actions is not absence from the game, and this analysis does not enlarge the surface's supported preparation boundary.

## 8. Descriptive outcome correlations

These means are over **external games only**, partitioned by eventual result. Disagreements count provider calls, including mulligan. No draws. Length is engine turn number (individual player turns), not stdout's final round count. Durations exclude JVM startup.

| Metric per external game | Wins (8) | Losses (12) |
|---|---:|---:|
| All disagreements | 1.500 | 3.417 |
| Provider calls | 8.875 | 7.250 |
| Game turns | 19.750 | 18.000 |
| Engine duration (seconds) | 27.507 | 21.614 |
| MULLIGAN_KEEP disagreements | 0.250 | 0.250 |
| MAIN_PHASE_ACTION disagreements | 0.125 | 0.000 |
| STACK_RESPONSE disagreements | 0.000 | 0.167 |
| COMBAT_ATTACKERS disagreements | 0.875 | 1.750 |
| COMBAT_BLOCKERS disagreements | 0.125 | 1.250 |
| COMBAT_ACTION disagreements | 0.125 | 0.000 |

Fifteen of 16 blocker disagreements occur in losses, spread over **nine of 12 losing games**; one occurs in a win. Twenty-one of 28 attacker disagreements occur in losses and seven in wins. Losses therefore have more combat disagreements, while wins actually have more provider calls and longer games. These are associations, not causal effect estimates: difficult boards create more blocker choices, surface exposure is conditional, deck/seat/seed cluster observations, and repeated decisions within a game are dependent. The seven clearest early chumps happen in six losing games, but that does not establish those chumps caused six losses.

The cohort's external 8/20 wins versus matched native seats' 10/20 is strategically inconclusive. No significance or win-rate improvement claim follows from this review. There are only five seeds per matchup and orientation, and no replayed counterfactual for any graded choice.

## 9. Why damage-order had zero calls

**Category E: one multi-block occurred, but there was no materially distinct damage-order choice.** `Aurochs-1401-seat1-External.jsonl`, T15, combat `G1-I0-T15-P1-C15`: external attacks with two Coursers; native blocks one with Gorilla Shaman 1/1 plus Bull Aurochs 2/1, and the other with Boreal Druid 1/1. This is the sole multiple-blocker assignment across all 40 games. The deciding attacker is controlled by the external seat, so seat ownership is not the explanation.

Legacy ordering **was enabled**: cohort `run.json` has `LegacyCombatOrder: true`, and the evaluator supplies `--legacy-order-combatants`. `ExternalCombatDamageOrder.prepare` rejects when the sum of lethal recipient requirements is at most available damage. Courser's 3 damage can kill both 1-toughness blockers in either order (1+1 ≤ 3). The actual combat kills both and trades the Courser, matching the static arithmetic. The other Courser has only one blocker.

Thus A (“no multi-block at all”) and B (“mode disabled”) are false; no D integration/config bug is demonstrated. Native blockers produced the only multi-block, and all external blocker disagreements had one blocker, so it is not established that external behavior deliberately avoided viable multi-blocks. No ordering change is warranted. A future coverage fixture should use insufficient damage to kill every recipient, rather than merely two blockers.

## 10. PASS coverage

Counts below come from the actual captured structured-response enums, not guessed from prompts. Selection counts come from accepted FULL_GAME_DECISION records.

| Surface | Requests | Hold option actually offered | Hold selected |
|---|---:|---|---:|
| STACK_RESPONSE | 7 | literal PASS in 7/7 | 0 |
| COMBAT_ACTION | 5 | literal PASS in 5/5 | 0 |
| COMBAT_ATTACKERS | 80 | NO_ATTACK in 80/80; no literal PASS | 0 |
| COMBAT_BLOCKERS | 21 | NO_BLOCK in 21/21; no literal PASS | 0 |
| MAIN_PHASE_ACTION | 25 | no literal PASS in any captured enum | not offered |
| MULLIGAN_KEEP | 20 | KEEP/MULLIGAN; PASS inapplicable | not offered |
| COMBAT_DAMAGE_ORDER | 0 | no request | not observed |

PASS coverage is **offered but never selected**, not missing, for stack/combat action. Each of those 12 requests offers only one prepared non-PASS action alongside PASS. 2 of the seven stack requests and one of the five combat-action requests have Forge PASS while the model acts. The two stack cases are Gatecrasher 1402 T9 Disrupt and Gatecrasher 1403 T18 Dissipate; the combat case is Aurochs 1405 T13 lethal Chorus.

Routing only calls on eligible prepared choices and does not call routine no-choice passes. Therefore the cohort cannot estimate the model's general willingness to pass all priority windows. Main-phase calls are action-selection requests without a PASS enum; no main-phase PASS failure can be inferred. Repeated refusal of NO_ATTACK/NO_BLOCK coincides with concrete bad exchanges, but zero literal PASS in 12 selected states alone does not establish irrational action bias. Do not bias prompts toward PASS on this evidence.

## 11. Information, candidate and context limitations

Taxonomy here is separate from the A–F tactical grades in section 1: **A** model strategy error; **B** candidate limitation; **C** projection limitation; **D** Forge possibly worse; **E** ambiguous/equivalent; **F** cross-surface context limitation.

| Significant issue | Taxonomy | What is / is not demonstrated |
|---|---|---|
| Seven safe-life chumps | A, high confidence | Correct P/T, keywords, life and NO_BLOCK are present. No missing candidate or prior-history fact is needed to see the losing exchange. Hidden future draws prevent universal outcome claims, not this local comparison. |
| A18/A23 extra ground attacker dies for unchanged flying damage | A, high confidence | Flying-only Forge option exists, visible ground blocker exists, and evasion is shown. This is not missing legal alternatives. Other excessive attacks also have NO_ATTACK. |
| B08 Savage Surge support; A05/A13 Balduvian Rage trades | C/E | Own noncandidate tricks are absent from attack/block projections; native interaction changes resolution. Evidence proves the play chain, not that the external model anticipated it. Do not implement suppression that would destroy these legitimate lines. |
| B12/B15 Griffin recursion | C/E | Griffin rules text is visible but own graveyard/top creature is not. Logs and same card incarnations establish actual returns. Two real information omissions relevant to judging these blocks; not enough to claim a better projection improves model play. |
| Fire Whip response | C/E | Stack rules summary is only a name and prepared counter target is generic. Single instance, strategic effect uncertain; no implementation justified on LOW confidence. |
| Twister and lethal Chorus over Forge alternatives | D | Two concrete locally better external decisions show Forge is a comparison baseline, not a correctness oracle. Both better actions were already candidates. |
| Terminal block choices B02/B07/B13 | E | No supplied line survives; no evidence a safe omitted legal assignment would. “Candidate limitation” cannot be inferred just because every option loses. |
| Flash then waste Knight; early Sentinel loss then thin defense | A with later consequences; F unproven | The later bad block/attack is explainable from the current request. Persistent memory is not demonstrated necessary. No causal attribution of the whole loss to the chain. |

No proven candidate generation defect emerges. Bounding may omit alternatives in general, but this evidence does not establish a missing safe action would fix the significant cases. No prompt, guard, schema, ranking, strategic heuristic, Java code or decision surface was changed.

## 12. Ranked evidence-backed findings

| Rank | Finding / evidence count | Severity / surface | Likely cause / confidence | Is a change justified? |
|---|---|---|---|---|
| 1 | Early nontrading blocks for 1–3 life at 16–20 life: 7 cases, 6 games | High local material cost; blockers | Selection misvalues life versus board and sometimes basic combat arithmetic; HIGH | A targeted follow-up is justified. Scope only the demonstrated losing exchanges; preserve trick support, death triggers, profitable trades and lethal survival. No change implemented here. |
| 2 | Adds doomed ground attacker while flyer damage stays identical: 2 cases A18/A23; broader 10 clear local negative attacker choices across 9 games | High local material cost; attackers | Inadequate visible block/value evaluation; HIGH | Evidence supports a later separate controlled experiment, not simultaneous combat tuning in the next step. |
| 3 | Useful own graveyard/trick context absent in 3 blocker cases, with 2 additional attacker trick examples | Medium review/decision-context limitation; attack/block projections | Projection scope; HIGH for omission, MEDIUM for benefit of changing it | No redesign yet. Preserve these counterexamples in any targeted block validation; benefit needs a controlled comparison. |
| 4 | Hold option never chosen: 12/12 literal PASS offers, plus 101 NO_ATTACK/NO_BLOCK offers | Medium concern; stack/action/attack/block | Possible action preference, mixed with selection bias and good active choices; MEDIUM | No general PASS prompt change. Measure on targeted ambiguous states; one lethal Chorus is better than Forge PASS. |
| 5 | Fire Whip stack text/target summary weak: 1 disagreement | Low/uncertain strategic impact; stack | Projection detail; LOW for explaining model error | No implementation recommendation; insufficient repeated outcome evidence. |

## 13. Recommended next action

Treat the safe-life, nontrading blocker exchanges as the single next issue. In a separately authorized implementation/evaluation task, compare the existing model with one narrowly targeted intervention on those seven frozen cases, plus Savage Surge, Griffin recursion, profitable trades, trample and forced-lethal counterexamples. Require improved local exchange choices without suppressing justified blocks, then repeat paired full games. Do not tune attackers, PASS and context simultaneously or infer a win-rate gain before that comparison.

Stage 14.1 itself only adds this report and the read-only evidence analysis helper. The helper creates a separate dossier, validates cohort counts/identity joins, hashes all evidence before and after, and rejects an output path inside the evidence directory. Validation results are recorded below; no Java tests are claimed to have been rerun for an analysis-only change.

Validation: 225 primary-cohort files match their SHA-256 evidence baseline; all 2,653 snapshotted Java files remain unchanged. Cohort reconciliation confirms 158 unique provider decisions, 16 blocker / 28 attacker / 1 main / 2 stack disagreements and 5 combat-action calls; all 13 report sections and 16 blocker rows are present. The existing evaluator's 3 unit tests pass; the review helper compiles; `git diff --check` passes (existing line-ending warnings only). No Java test suite was rerun.

Reproduce the evidence dossier from the repository root:

```powershell
C:\Python312\python.exe tools/ai-evaluation/review_stage14_evidence.py ai-eval/stage14/20261008-230210-574219ba work/stage14-1-review
C:\Python312\python.exe -m unittest discover -s tools/ai-evaluation -p test_stage14_analysis.py
git diff --check
```

Analysis outputs: `C:/Users/Chris/Workspace/forge/work/stage14-1-review/dossier.json`, per-surface review text, `evidence-sha256.json`, and `validation.json`. The permanent additions are `C:/Users/Chris/Workspace/forge/tools/ai-evaluation/review_stage14_evidence.py` and this report. All original `ai-eval/` outputs are preserved.

**B. TARGETED FIX JUSTIFIED — unnecessary nontrading blocks at safe life totals, supported by seven cases across six games.**
