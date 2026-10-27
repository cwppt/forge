# Bounded advisory-search development replay

Separate five-game follow-up to success-replay. The same five failed seeds ran with five workers and a 300-second game limit, adding only these explicit JVM properties:

```
-Dforge.ai.evalDiagnostics=true
-Dforge.ai.evalBudgetMs=500
-Dforge.ai.evalWorkLimit=2000
```

These activate the existing JAR's conservative advisory-search fallback; they do not change game rules or disable validation. AI choices and strength can change, so this profile is not pooled with Standard results.

All five games passed independent validation with zero provider attempts. Total wall time: 221.4 seconds. Outputs remain under work/commander-advisory-replays/20261013-124426. The worker exited 0 and its temporary scheduled task was removed. Original seed 2500 report/ledger hashes remain unchanged. No full cohort, Git commit or publication was launched.

Twenty fixture tests cover classifier attribution, matched-seed replay, bounded execution and explicit JVM option enforcement. The combined assessment is work/commander-success-replays/20261013-121730/ASSESSMENT.md.
