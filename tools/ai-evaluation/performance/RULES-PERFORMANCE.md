# Phase 7: interpreted rules dispatcher

The existing Phase 6 JFR is the investigation input; no additional game has been
run for discovery. `CardProperty.cardHasProperty` appears in 1,212 of 3,283 CPU
samples (1,051 exclusive leaves), always interpreted. The frozen class has a
14,082-byte method body. Most sampled call chains are validity/restriction
filters. Line samples span the ordered dispatcher; this does not establish
individual property frequencies or exact invocation counts.

The method handles dynamic controller/LKI, characteristics, phasing, zone,
combat/history and other restrictions, ultimately delegating to
`CardStateProperty`. Its callers participate in static effects, legal-action,
trigger and replacement processing. Caching its Boolean result across calls is
not justified. Exact comparison/parsing counts and exclusive allocation totals
are unavailable from the existing sampled evidence.

The installed JDK reports `DontCompileHugeMethods = true`. The isolated candidate
changes only the JVM flag `-XX:-DontCompileHugeMethods`, leaving every engine JAR
entry identical. This permits compilation of **all** huge methods, not just the
hotspot; attribution must acknowledge that scope. Compiler XML is collected only
in separate diagnostic microbenchmark JVMs, never in primary timing runs. The
candidate is default off and is not validated or recommended merely because the
hotspot was interpreted.

Predeclared gates:

* 38 Java methods per arm (32 existing plus six dynamic-property tests), existing
  timeout/failure-path/result regressions, eight strict comparator tests and full
  64-fixture legal/assignment/damage/trigger/state parity.
* Targeted method CPU median at least 20% lower, improvement in all three JVM
  blocks, no >5% allocation regression. Direct original-functional comparison is
  measured for this workload only; fixed properties are not claimed to match a
  real-game frequency distribution.
* No >5% combat median/p95, current-thread CPU or allocation regression across
  four existing combat workloads. Report the old 15% blocker-primary target
  separately; do not redefine historical acceptance.
* Three new matched native games initially (one per frozen Lathliss variant;
  seeds 573300–573302). At least 10% total active elapsed reduction, every pair
  improves, no material game-p95/heap/GC regression and exact structured/final
  outcome/native-role parity. An unexplained difference stops subsequent work.
* Only after those gates pass, extend to nine matched pairs (through 573308).
  At least eight of nine improve, every variant paired median improves and the
  10% active-elapsed gate remains satisfied. This is preliminary evidence, not
  statistical proof. No automatic merge, adoption or Phase 8.

Active elapsed includes admission, isolated runtime, JVM startup, game,
independent validation and analysis, excluding publication. Exact game allocation
is unavailable; micro allocation/GC and whole-game turn-boundary heap/GC are
reported with their limitations. Failed attempts stop the run, remain separate
and are not replaced. A zero process exit cannot override failure classification
or independent validation. The original 500 ms / 2,000-work AI settings remain
unchanged. Faster computation can affect wall-clock exhaustion, which is one
reason native parity is mandatory.

Commands (from the isolated checkout):

```powershell
python -B tools/ai-evaluation/performance/rules_run.py <output> --prepare
python -B tools/ai-evaluation/performance/rules_run.py <output> --initial
python -B tools/ai-evaluation/performance/rules_run.py <output> --freeze
python -B tools/ai-evaluation/performance/rules_run.py <output> --verify-only
```

Then launch the same command without an option as a hidden detached process.
Both preparation/testing and the supervisor use the existing global worker lock.
The finite supervisor also has an exclusive local lock and a one-shot start
marker, preserves the 7 GiB available-memory admission and 3 GiB free-space
reserve, and publishes independently. An interrupted supervisor requires manual
checkpoint/child review; the one-shot marker prevents blindly replaying stages.

Initial storage blocker: preparation stopped at admission before copying engines
or launching native tests. C: had about 2 GiB free, below the required 3 GiB.
The original harness and other workspaces were not moved or cleaned. No source
engine overlay was built. Offline Python analysis, bytecode-parser and acceptance
tests can run; native correctness and performance remain pending until space is
available. That blocker was subsequently resolved by moving the inactive
`chainer-foundation-native50-20261026` archive intact to
`D:\ForgeData\archived-harness-work`, retaining its original path as a junction.
All 4,715 regular-file hashes and 548 link targets were verified. Both arms then
passed 38 focused Java methods and initial structured combat parity. The test
fixture's `CardType.parse` call was corrected to supply the required `incomplete`
argument; the original compiler log remains preserved.

`--publish-blocked` publishes an honest partial state without
claiming a running supervisor or frozen native-execution request. Publication
ownership records are preserved so the eventual supervisor can update the same
report after validated preparation.

Historical phases remain separate links in performance history. No old candidate
flag is enabled. Cumulative whole-game benefit is unknown until directly
measured; unrelated historical percentages are never added.
