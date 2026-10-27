# Explicit Commander experiment configuration

New batches require -ExperimentSpec and explicit configuration matching that independent JSON file. Existing serial/v2/v3 requests retain their saved runner and frozen inputs. Archived PowerShell launchers refuse new experiments. No existing batch is relabelled or migrated.

The public launcher previews exact variant labels, internal deck names, filenames, SHA256 hashes, AI profile, worker count, timeout and valid-game targets before task registration. Wrong candidate set, deck hash, profile, concurrency, timeout or targets fail before batch preparation or launch. Worker verification repeats the frozen specification check. The specification itself and the actual candidate list are saved and hashed; later edits block resume. Only specified candidates are scheduled; targets, seed ranges and finalisation are dynamic for the actual candidate count.

The prepared specification tools/ai-evaluation/experiments/R3-vs-R4-A.json requests exactly R3 and R4-A, BoundedAdvisory, five workers, 300 seconds, 100 valid games each (200 total), with unchanged opponents and fixed seats. R4-A has exactly R3's card list minus one Reforge the Soul plus one Rakdos Charm. Its metadata name is distinct; no other card or commander change is allowed. Source R3 remains unchanged. Both decks have 37 lands.

From C:\Users\Chris\Workspace\forge-commander-harness:

```powershell
# Read-only configuration preview. Does not prepare or launch an experiment.
.\tools\ai-evaluation\Start-CommanderBatch.ps1 -Seed 4500 -GamesPerVariant 100 -CandidateSet R3-R4-A -ExperimentSpec .\tools\ai-evaluation\experiments\R3-vs-R4-A.json -MaxParallelGames 5 -AiProfile BoundedAdvisory -TimeoutSeconds 300 -PreviewOnly

# Correct future launch; deliberately NOT executed during development.
.\tools\ai-evaluation\Start-CommanderBatch.ps1 -Seed 4500 -GamesPerVariant 100 -CandidateSet R3-R4-A -ExperimentSpec .\tools\ai-evaluation\experiments\R3-vs-R4-A.json -MaxParallelGames 5 -AiProfile BoundedAdvisory -TimeoutSeconds 300 -PublishOnCompletion

.\tools\ai-evaluation\Get-CommanderBatchStatus.ps1 -Seed 4500 -Detailed
```

For resume, repeat the launch arguments and add -Resume. Never apply this specification to seed3500: that batch actually ran R2/R3/R3-38L at16 workers and is published with its actual configuration.

The v4 worker inherits the reviewed v3 identity fix: a cached Windows process snapshot checks executable name, command line, creation identity and batch-owned JVM descendants. Missing/exited or demonstrably reused PIDs do not block; unknown relevant identities or genuine surviving Forge processes block safely. Old FILETIME identities remain supported. No unrelated process is terminated. Separate finalize-only mode revalidates/publishes completed games without running simulations.

Verification:68 v3 regressions pass, including inaccessible identities, reuse, genuine orphan, missing process and finalize-only no-rerun. V4 has24 passing tests (18 classifier cases and6 contract/coordinator cases), including exact delta, dynamic two-variant targets/seeds, all configuration mismatches, snapshot integrity and resume checks. Public PowerShell5.1 preview passed; wrong candidate/concurrency previews were rejected and seed4500 remains absent. No R4-A game was launched.
