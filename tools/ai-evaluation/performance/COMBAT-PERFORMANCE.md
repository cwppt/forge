# Native combat performance v1

Baseline: correctness commit `418dac868f3f48349ff0b5d35e9197c65008d6e6`.
Never rebuild or overwrite the validated reference engine. The preparation script
compiles the original CardState and CombatUtil against that engine and compares
normalised javap instructions before overlaying only the candidate classes.
Other JAR entries remain byte-identical; local baseline AI dependencies survive.

The candidate filters `CombatUtil.getBlockCost` sources by live static-ability
mode and constructs an initial zero Cost lazily. Changed traits, text changes,
split states and custom Card implementations fall back to the original path.
No cross-decision cache is introduced. Enable with
`-Dforge.combat.blockCostFastPath=true`; default is off.
Optional aggregate counters: `-Dforge.combat.blockCostMetrics=true`.
Neither switch changes search budgets or legal actions.

An earlier empty-trigger candidate passed initial parity but showed no cold
timing gain; its evidence is preserved outside Git and its production change was
reverted. This is the second and final optimisation attempt for this run.
Initial cold samples are not performance claims.

## Reproduction and detached operation

On the configured Windows host, with the original harness runtime/resources and
frozen experiment decks available, choose a fresh output folder on D:.
Run the following sequentially using Python 3.12:

```
combat_performance.py <output> prepare
combat_performance.py <output> initial
```

Commit the source so the request records its exact HEAD, then run:

```
combat_run.py <output> --freeze
```

The offline tests import the run directory from argv[1], so invoke them with:
`python -B -c "import sys,unittest; sys.argv=['tests',r'<output>']; import test_combat_run; unittest.main(module=test_combat_run,argv=['tests'])"`
from this directory. This performs no Java or GitHub work.

Start `pythonw.exe -B combat_run.py <output>` with PowerShell
`Start-Process -WindowStyle Hidden -PassThru`. Check started.json, heartbeat.json,
status.json and publication.json once to confirm ownership and launch.
Do not launch a second supervisor. The exclusive started marker also refuses
blind restart after interruption; inspect preserved children/evidence first.

The finite supervisor holds the established global worker lock, checks 7 GiB
available memory before every worker and the 3 GiB reserve on C: and resolved
output volumes. Native child watchdogs kill and reap expired processes. No
external model calls are enabled. A failure stops subsequent stages and publishes
the failure checkpoint. Completed outcomes are not replaced or replayed.

Before timing: rerun process-level failure/outcome/report/recovery tests and
ten-fixture canonical parity. Legal attacker/blocker pair sets are compared,
not exhaustive multi-block combinations. Selected assignments, resolved combat,
triggers, stack and state are also compared. A later matched three-seed native
smoke compares independent outcomes and gameplay trajectories.

Focused timings use four workloads, two warm-ups, four measurements and three
alternating-order JVM blocks. Full ten-fixture timings and separate bounded
JFR/counter passes supplement them. Current-thread allocation excludes the AI
worker thread; JFR allocation weights are sampled. A conservative descriptive
gain gate and exploratory block bootstrap prevent noisy single samples being
labelled success; three JVM blocks still limit statistical certainty.

GitHub publication updates only files owned by this new run at
`forge-performance/reports/20261027-native-combat-performance-v1/` in
`cwppt/MTG-deck-lists`. External changes stop publication rather than overwriting
them. Source and test/helper hashes are rechecked before each native stage.
No automated merge, production promotion or larger cohort follows this run.
