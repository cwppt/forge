# Fixed four-player Commander smoke decks

Deck order: Lathliss V2.4, Kaalia, Brudiclad, Breena. Each deck has 99 main cards and one commander and passed Forge Commander conformance validation on 4 October 2026. Use these files with Run-CommanderCohort.ps1 and an absolute -DeckDirectory pointing here.

The manifest pins SHA-256 hashes, sources, and adaptations. These fixtures validate simulation and telemetry, not matchup balance. Lathliss is the user-provided V2.4 list; Valakut Awakening uses its front-face database name. Kaalia and Breena use the repository's public Heavenly Inferno (2011) and Silverquill Statement (2021) lists. Brudiclad uses Exquisite Invention (2018), swapping command-zone placement with Saheeli while preserving all 100 cards. The Breena foil marker is normalized.

The detailed run report, reproducible launcher, and telemetry validator are saved in this task's outputs directory under the user's Documents/Codex folder.
