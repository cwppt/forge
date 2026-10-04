package forge.view;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.lang3.time.StopWatch;

import forge.LobbyPlayer;
import forge.ai.AiProfileUtil;
import forge.ai.decision.AiDecisionMetrics;
import forge.deck.Deck;
import forge.deck.DeckGroup;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameLogEntryType;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.tournament.system.AbstractTournament;
import forge.gamemodes.tournament.system.TournamentBracket;
import forge.gamemodes.tournament.system.TournamentPairing;
import forge.gamemodes.tournament.system.TournamentPlayer;
import forge.gamemodes.tournament.system.TournamentRoundRobin;
import forge.gamemodes.tournament.system.TournamentSwiss;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.Lang;
import forge.util.MyRandom;
import forge.util.TextUtil;
import forge.util.WordUtil;
import forge.util.storage.IStorage;

public class SimulateMatch {
    public static void simulate(String[] args) {
        FModel.initialize(null, null);

        System.out.println("Simulation mode");
        if (args.length < 4) {
            argumentHelp();
            return;
        }

        final Map<String, List<String>> params = new HashMap<>();
        List<String> options = null;

        for (int i = 1; i < args.length; i++) {
            // "sim" is in the 0th slot
            final String a = args[i];

            if (a.charAt(0) == '-') {
                if (a.length() < 2) {
                    System.err.println("Error at argument " + a);
                    argumentHelp();
                    return;
                }

                options = new ArrayList<>();
                params.put(a.replaceFirst("^-+", ""), options);
            } else if (options != null) {
                options.add(a);
            } else {
                System.err.println("Illegal parameter usage");
                return;
            }
        }

        configureExternalAi(params);
        AiDecisionMetrics.reset();
        List<String> auditOutput = params.get("decision-audit-jsonl");
        if (auditOutput != null && !auditOutput.isEmpty()) {
            try {
                AiDecisionMetrics.configureDecisionAudit(Path.of(auditOutput.get(0)));
            } catch (IOException e) {
                System.err.println("Unable to open decision audit JSONL: " + e.getMessage());
                return;
            }
        }

        String deckDir = null;
        if (params.containsKey("D")) {
            deckDir = params.get("D").get(0);
        }

        int nGames = 1;
        if (params.containsKey("n")) {
            // Number of games should only be a single string
            nGames = Integer.parseInt(params.get("n").get(0));
        }

        int matchSize = 0;
        if (params.containsKey("m")) {
            // Match size ("best of X games")
            matchSize = Integer.parseInt(params.get("m").get(0));
        }

        boolean outputGamelog = !params.containsKey("q");

        Long seed = null;
        if (params.containsKey("s")) {
            seed = Long.parseLong(params.get("s").get(0));
            MyRandom.setRandom(new Random(seed));
        }

        GameType type = GameType.Constructed;
        if (params.containsKey("f")) {
            type = GameType.valueOf(WordUtil.capitalize(params.get("f").get(0)));
        }

        GameRules rules = new GameRules(type);
        rules.setAppliedVariants(EnumSet.of(type));

        if (matchSize != 0) {
            rules.setGamesPerMatch(matchSize);
        }

        if (params.containsKey("t")) {
            simulateTournament(params, rules, outputGamelog);
            System.out.flush();
            return;
        }

        List<RegisteredPlayer> pp = new ArrayList<>();
        StringBuilder sb = new StringBuilder();

        int i = 1;

        // Optional AI profile per player, in the same order as the decks. Lets a run pit one set of
        // AI settings against another, which is the only way to tell from the results whether an AI
        // change actually helped.
        List<String> aiProfiles = params.get("a");
        if (aiProfiles != null) {
            for (String profile : aiProfiles) {
                if (!AiProfileUtil.getProfilesDisplayList().contains(profile)) {
                    System.out.println(TextUtil.concatNoSpace("Unknown AI profile - ", profile,
                            ". Available profiles: ", String.join(", ", AiProfileUtil.getProfilesDisplayList())));
                    return;
                }
            }
        }

        if (params.containsKey("d")) {
            for (String deck : params.get("d")) {
                Deck d = deckFromCommandLineParameter(deck, type, deckDir);
                if (d == null) {
                    System.out.println(TextUtil.concatNoSpace("Could not load deck - ", deck, ", match cannot start"));
                    return;
                }
                if (i > 1) {
                    sb.append(" vs ");
                }
                String profile = aiProfiles != null && aiProfiles.size() >= i ? aiProfiles.get(i - 1) : "";
                String name = TextUtil.concatNoSpace("Ai(", String.valueOf(i), ")-", d.getName());
                sb.append(name);
                if (!profile.isEmpty()) {
                    sb.append(" [").append(profile).append("]");
                }

                RegisteredPlayer rp;

                if (type.equals(GameType.Commander)) {
                    rp = RegisteredPlayer.forCommander(d);
                } else {
                    rp = new RegisteredPlayer(d);
                }
                rp.setPlayer(GamePlayerUtil.createAiPlayer(name, i - 1, profile));
                pp.add(rp);
                i++;
            }
        }

        if (params.containsKey("c")) {
            rules.setSimTimeout(Integer.parseInt(params.get("c").get(0)));
        }

        sb.append(" - ").append(Lang.nounWithNumeral(nGames, "game")).append(" of ").append(type);
        if (seed != null) {
            sb.append(" seed ").append(seed);
        }

        System.out.println(sb);

        Match mc = new Match(rules, pp, "Test");
        List<GameSimulationResult> results = new ArrayList<>();

        if (matchSize != 0) {
            int iGame = 0;
            while (!mc.isMatchOver()) {
                // play games until the match ends
                results.add(simulateSingleMatch(mc, iGame, outputGamelog, seed, params));
                iGame++;
            }
        } else {
            for (int iGame = 0; iGame < nGames; iGame++) {
                results.add(simulateSingleMatch(mc, iGame, outputGamelog, seed, params));
            }
        }

        outputEvaluation(params, results);

        System.out.flush();
    }

    private static void argumentHelp() {
        System.out.println("Syntax: forge.exe sim -d <deck1[.dck]> ... <deckX[.dck]> -D [D] -n [N] -m [M] -t [T] -p [P] -f [F] -s [S] -a [A] -q");
        System.out.println("\tsim - stands for simulation mode");
        System.out.println("\tdeck1 (or deck2,...,X) - constructed deck name or filename (has to be quoted when contains multiple words)");
        System.out.println("\tdeck is treated as file if it ends with a dot followed by three numbers or letters");
        System.out.println("\tD - absolute directory to load decks from");
        System.out.println("\tN - number of games, defaults to 1 (Ignores match setting)");
        System.out.println("\tM - Play full match of X games, typically 1,3,5 games. (Optional, overrides N)");
        System.out.println("\tT - Type of tournament to run with all provided decks (Bracket, RoundRobin, Swiss)");
        System.out.println("\tP - Amount of players per match (used only with Tournaments, defaults to 2)");
        System.out.println("\tF - format of games, defaults to constructed");
        System.out.println("\tS - RNG seed for simulation");
        System.out.println("\tA - AI profile per player, in the same order as the decks (e.g. -a Default Experimental)");
        System.out.println("\tc - Clock flag. Set the maximum time in seconds before calling the match a draw, defaults to 120.");
        System.out.println("\tq - Quiet flag. Output just the game result, not the entire game log.");
        System.out.println("\t--external-ai-enabled - legacy alias: enable external mulligan decisions");
        System.out.println("\t--external-ai-mulligan-enabled - enable external mulligan decisions");
        System.out.println("\t--external-ai-mulligan-disabled - disable external mulligan decisions for this run");
        System.out.println("\t--external-ai-main-phase-enabled - enable prepared main-phase action selection");
        System.out.println("\t--external-ai-main-phase-max-actions <N> - bounded prepared action count (default 3)");
        System.out.println("\t--external-ai-main-phase-include-rejected - expose bounded safe heuristic-rejected actions");
        System.out.println("\t--external-ai-main-phase-max-rejected-actions <N> - rejected action bound (default 1)");
        System.out.println("\t--external-ai-stack-response-enabled - enable prepared stack-response selection");
        System.out.println("\t--external-ai-stack-response-max-actions <N> - bounded response count (default 3)");
        System.out.println("\t--external-ai-stack-response-randomize-action-order - shuffle presented responses (default false)");
        System.out.println("\t--external-ai-combat-attackers-enabled - enable prepared attacker-set selection (default false)");
        System.out.println("\t--external-ai-combat-attackers-max-options <N> - bounded attacker options (default 4, hard cap 8)");
        System.out.println("\t--external-ai-endpoint <URL> - OpenAI-compatible chat/completions endpoint");
        System.out.println("\t--external-ai-model <model> - external model name");
        System.out.println("\t--external-ai-timeout <seconds> - positive request timeout");
        System.out.println("\t--external-ai-response-format <AUTO|JSON_SCHEMA|JSON_OBJECT> - structured response mode");
        System.out.println("\t--metrics-csv <file> - write mulligan and main-phase AI telemetry as CSV");
        System.out.println("\t--decision-audit-jsonl <file> - write sanitized external-AI decision audits as JSONL");
        System.out.println("\t--game-results-jsonl <file> - write one machine-readable game result per line");
        System.out.println("\t--game-log-jsonl <file> - write chronological game-log events as JSONL");
    }

    public static GameSimulationResult simulateSingleMatch(final Match mc, int iGame, boolean outputGamelog) {
        return simulateSingleMatch(mc, iGame, outputGamelog, null, Collections.emptyMap());
    }

    private static GameSimulationResult simulateSingleMatch(
            final Match mc, int iGame, boolean outputGamelog, Long runSeed,
            Map<String, List<String>> params) {
        final StopWatch sw = new StopWatch();
        sw.start();

        final Game g1 = mc.createGame();
        AiDecisionMetrics.registerGame(g1.getId(), iGame, runSeed);
        g1.setNoGUIUser();
        // will run match in the same thread
        try {
            TimeLimitedCodeBlock.runWithTimeout(() -> {
                mc.startGame(g1);
                sw.stop();
            }, mc.getRules().getSimTimeout(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            System.out.println("Stopping slow match as draw");
        } catch (Exception | StackOverflowError e) {
            e.printStackTrace();
        } finally {
            if (sw.isStarted()) {
                sw.stop();
            }
            g1.setGameOver(GameEndReason.Draw);
        }

        List<GameLogEntry> log;
        if (outputGamelog) {
            log = g1.getGameLog().getLogEntries(null);
        } else {
            log = g1.getGameLog().getLogEntries(GameLogEntryType.MATCH_RESULTS);
        }
        Collections.reverse(log);
        for (GameLogEntry l : log) {
            System.out.println(l);
        }

        List<String> gameLogOutput = params.get("game-log-jsonl");
        if (gameLogOutput != null && !gameLogOutput.isEmpty()) {
            try {
                appendGameLogJsonl(Path.of(gameLogOutput.get(0)), g1.getId(), iGame, runSeed,
                        g1.getGameLog().getLogEntries(null));
            } catch (IOException e) {
                System.err.println("Unable to write game log JSONL: " + e.getMessage());
            }
        }

        // If both players life totals to 0 in a single turn, the game should end in a draw
        if (g1.getOutcome().isDraw()) {
            AiDecisionMetrics.completeGame(g1.getId(), null, true, sw.getTime(),
                    g1.getPhaseHandler().getTurn());
            System.out.printf("\nGame Result: Game %d ended in a Draw! Took %d ms.%n", 1 + iGame, sw.getTime());
            return new GameSimulationResult(g1.getId(), iGame, runSeed, sw.getTime(),
                    g1.getPhaseHandler().getTurn(), true, null);
        } else {
            String winner = g1.getOutcome().getWinningLobbyPlayer().getName();
            AiDecisionMetrics.completeGame(g1.getId(), winner, false, sw.getTime(),
                    g1.getPhaseHandler().getTurn());
            System.out.printf("\nGame Result: Game %d ended in %d ms. %s has won!\n%n",
                    1 + iGame, sw.getTime(), winner);
            return new GameSimulationResult(g1.getId(), iGame, runSeed, sw.getTime(),
                    g1.getPhaseHandler().getTurn(), false, winner);
        }
    }

    public record GameSimulationResult(
            int gameId,
            int gameIndex,
            Long runSeed,
            long durationMs,
            int finalTurnCount,
            boolean draw,
            String winner) {
    }

    private static void configureExternalAi(Map<String, List<String>> params) {
        if (params.containsKey("external-ai-enabled")) {
            FModel.getPreferences().setPref(FPref.AI_EXTERNAL_MULLIGAN_ENABLED, true);
        }
        if (params.containsKey("external-ai-mulligan-enabled")) {
            FModel.getPreferences().setPref(FPref.AI_EXTERNAL_MULLIGAN_ENABLED, true);
        }
        if (params.containsKey("external-ai-mulligan-disabled")) {
            FModel.getPreferences().setPref(FPref.AI_EXTERNAL_MULLIGAN_ENABLED, false);
        }
        if (params.containsKey("external-ai-main-phase-enabled")) {
            FModel.getPreferences().setPref(FPref.AI_EXTERNAL_MAIN_PHASE_ENABLED, true);
        }
        if (params.containsKey("external-ai-main-phase-include-rejected")) {
            FModel.getPreferences().setPref(FPref.AI_EXTERNAL_MAIN_PHASE_INCLUDE_REJECTED, true);
        }
        if (params.containsKey("external-ai-stack-response-enabled")) {
            FModel.getPreferences().setPref(FPref.AI_EXTERNAL_STACK_RESPONSE_ENABLED, true);
        }
        if (params.containsKey("external-ai-stack-response-randomize-action-order")) {
            FModel.getPreferences().setPref(FPref.AI_EXTERNAL_STACK_RESPONSE_RANDOMIZE_ACTION_ORDER, true);
        }
        if (params.containsKey("external-ai-combat-attackers-enabled")) {
            FModel.getPreferences().setPref(FPref.AI_EXTERNAL_COMBAT_ATTACKERS_ENABLED, true);
        }
        setPreference(params, "external-ai-combat-attackers-max-options", FPref.AI_EXTERNAL_COMBAT_ATTACKERS_MAX_OPTIONS);
        setPreference(params, "external-ai-endpoint", FPref.AI_EXTERNAL_MULLIGAN_ENDPOINT);
        setPreference(params, "external-ai-model", FPref.AI_EXTERNAL_MULLIGAN_MODEL);
        setPreference(params, "external-ai-timeout", FPref.AI_EXTERNAL_MULLIGAN_TIMEOUT_SECONDS);
        setPreference(params, "external-ai-response-format", FPref.AI_EXTERNAL_RESPONSE_FORMAT);
        setPreference(params, "external-ai-main-phase-max-actions", FPref.AI_EXTERNAL_MAIN_PHASE_MAX_ACTIONS);
        setPreference(params, "external-ai-main-phase-max-rejected-actions",
                FPref.AI_EXTERNAL_MAIN_PHASE_MAX_REJECTED_ACTIONS);
        setPreference(params, "external-ai-stack-response-max-actions",
                FPref.AI_EXTERNAL_STACK_RESPONSE_MAX_ACTIONS);
    }

    private static void setPreference(Map<String, List<String>> params, String option, FPref preference) {
        List<String> values = params.get(option);
        if (values != null && !values.isEmpty()) {
            FModel.getPreferences().setPref(preference, values.get(0));
        }
    }

    private static void outputEvaluation(Map<String, List<String>> params, List<GameSimulationResult> results) {
        System.out.println("AI evaluation: " + AiDecisionMetrics.summary(results.size()));
        long draws = results.stream().filter(GameSimulationResult::draw).count();
        double averageDuration = results.stream().mapToLong(GameSimulationResult::durationMs).average().orElse(0);
        Map<String, Long> wins = new TreeMap<>();
        results.stream().filter(r -> !r.draw()).forEach(r -> wins.merge(r.winner(), 1L, Long::sum));
        System.out.printf(Locale.ROOT, "gameResults games=%d draws=%d avgGameLengthMs=%.2f wins=%s%n",
                results.size(), draws, averageDuration, wins);
        List<String> output = params.get("metrics-csv");
        if (output != null && !output.isEmpty()) {
            try {
                AiDecisionMetrics.writeCsv(Path.of(output.get(0)));
                System.out.println("AI decision metrics CSV written to " + output.get(0));
            } catch (IOException e) {
                System.err.println("Unable to write AI decision metrics CSV: " + e.getMessage());
            }
        }

        List<String> gameResultsOutput = params.get("game-results-jsonl");
        if (gameResultsOutput != null && !gameResultsOutput.isEmpty()) {
            try {
                writeGameResultsJsonl(Path.of(gameResultsOutput.get(0)), results);
                System.out.println("Game results JSONL written to " + gameResultsOutput.get(0));
            } catch (IOException e) {
                System.err.println("Unable to write game results JSONL: " + e.getMessage());
            }
        }
    }

    private static void appendGameLogJsonl(Path path, int gameId, int gameIndex, Long runSeed,
            List<GameLogEntry> entries) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>(entries.size());
        for (int eventIndex = 0; eventIndex < entries.size(); eventIndex++) {
            GameLogEntry entry = entries.get(eventIndex);
            String sourceCard = entry.sourceCard() == null ? null : entry.sourceCard().getName();
            lines.add(String.format(Locale.ROOT,
                    "{\"gameId\":%d,\"gameIndex\":%d,\"runSeed\":%s,\"eventIndex\":%d,"
                            + "\"type\":\"%s\",\"message\":\"%s\",\"sourceCard\":%s}",
                    gameId, gameIndex, runSeed == null ? "null" : runSeed.toString(), eventIndex,
                    jsonEscape(entry.type().name()), jsonEscape(entry.message()),
                    sourceCard == null ? "null" : "\"" + jsonEscape(sourceCard) + "\""));
        }
        Files.write(path, lines, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static void writeGameResultsJsonl(Path path, List<GameSimulationResult> results) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>(results.size());
        for (GameSimulationResult result : results) {
            lines.add(String.format(Locale.ROOT,
                    "{\"gameId\":%d,\"gameIndex\":%d,\"runSeed\":%s,\"durationMs\":%d,"
                            + "\"finalTurnCount\":%d,\"draw\":%s,\"winner\":%s}",
                    result.gameId(), result.gameIndex(),
                    result.runSeed() == null ? "null" : result.runSeed().toString(),
                    result.durationMs(), result.finalTurnCount(), result.draw(),
                    result.winner() == null ? "null" : "\"" + jsonEscape(result.winner()) + "\""));
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static void simulateTournament(Map<String, List<String>> params, GameRules rules, boolean outputGamelog) {
        String tournament = params.get("t").get(0);
        AbstractTournament tourney = null;
        int matchPlayers = params.containsKey("p") ? Integer.parseInt(params.get("p").get(0)) : 2;

        DeckGroup deckGroup = new DeckGroup("SimulatedTournament");
        List<TournamentPlayer> players = new ArrayList<>();
        int numPlayers = 0;
        if (params.containsKey("d")) {
            for (String deck : params.get("d")) {
                Deck d = deckFromCommandLineParameter(deck, rules.getGameType(), null);
                if (d == null) {
                    System.out.println(TextUtil.concatNoSpace("Could not load deck - ", deck, ", match cannot start"));
                    return;
                }

                deckGroup.addAiDeck(d);
                players.add(new TournamentPlayer(GamePlayerUtil.createAiPlayer(d.getName(), 0), numPlayers));
                numPlayers++;
            }
        }

        if (params.containsKey("D")) {
            // Load decks from the specified directory
            String foldName = params.get("D").get(0);
            File folder = new File(foldName);
            if (!folder.isDirectory()) {
                System.out.println("Directory not found - " + foldName);
            } else {
                for (File deck : folder.listFiles((dir, name) -> name.endsWith(".dck"))) {
                    Deck d = DeckSerializer.fromFile(deck);
                    if (d == null) {
                        System.out.println(TextUtil.concatNoSpace("Could not load deck - ", deck.getName(), ", match cannot start"));
                        return;
                    }
                    deckGroup.addAiDeck(d);
                    players.add(new TournamentPlayer(GamePlayerUtil.createAiPlayer(d.getName(), 0), numPlayers));
                    numPlayers++;
                }
            }
        }

        if (numPlayers == 0) {
            System.out.println("No decks/Players found. Please try again.");
        }

        if ("bracket".equalsIgnoreCase(tournament)) {
            tourney = new TournamentBracket(players, matchPlayers);
        } else if ("roundrobin".equalsIgnoreCase(tournament)) {
            tourney = new TournamentRoundRobin(players, matchPlayers);
        } else if ("swiss".equalsIgnoreCase(tournament)) {
            tourney = new TournamentSwiss(players, matchPlayers);
        }
        if (tourney == null) {
            System.out.println("Failed to initialize tournament, bailing out");
            return;
        }

        tourney.initializeTournament();

        String lastWinner = "";
        int curRound = 0;
        System.out.println(TextUtil.concatNoSpace("Starting a ", tournament, " tournament with ",
                String.valueOf(numPlayers), " players over ",
                String.valueOf(tourney.getTotalRounds()), " rounds"));
        while (!tourney.isTournamentOver()) {
            if (tourney.getActiveRound() != curRound) {
                if (curRound != 0) {
                    System.out.println(TextUtil.concatNoSpace("End Round - ", String.valueOf(curRound)));
                }
                curRound = tourney.getActiveRound();
                System.out.println();
                System.out.println(TextUtil.concatNoSpace("Round ", String.valueOf(curRound), " Pairings:"));

                for (TournamentPairing pairing : tourney.getActivePairings()) {
                    System.out.println(pairing.outputHeader());
                }
                System.out.println();
            }

            TournamentPairing pairing = tourney.getNextPairing();
            List<RegisteredPlayer> regPlayers = AbstractTournament.registerTournamentPlayers(pairing, deckGroup);

            StringBuilder sb = new StringBuilder();
            sb.append("Round ").append(tourney.getActiveRound()).append(" - ");
            sb.append(pairing.outputHeader());
            System.out.println(sb.toString());

            if (!pairing.isBye()) {
                Match mc = new Match(rules, regPlayers, "TourneyMatch");

                int exceptions = 0;
                int iGame = 0;
                while (!mc.isMatchOver()) {
                    // play games until the match ends
                    try {
                        simulateSingleMatch(mc, iGame, outputGamelog);
                        iGame++;
                    } catch (Exception e) {
                        exceptions++;
                        System.out.println(e.toString());
                        if (exceptions > 5) {
                            System.out.println("Exceeded number of exceptions thrown. Abandoning match...");
                            break;
                        } else {
                            System.out.println("Game threw exception. Abandoning game and continuing...");
                        }
                    }

                }
                LobbyPlayer winner = mc.getWinner().getPlayer();
                for (TournamentPlayer tp : pairing.getPairedPlayers()) {
                    if (winner.equals(tp.getPlayer())) {
                        pairing.setWinner(tp);
                        lastWinner = winner.getName();
                        System.out.println(TextUtil.concatNoSpace("Match Winner - ", lastWinner, "!"));
                        System.out.println();
                        break;
                    }
                }
            }

            tourney.reportMatchCompletion(pairing);
        }
        tourney.outputTournamentResults();
    }

    public static Match simulateOffthreadGame(List<Deck> decks, GameType format, int games) {
        return null;
    }

    private static Deck deckFromCommandLineParameter(String deckname, GameType type, String deckDir) {
        int dotpos = deckname.lastIndexOf('.');
        if (dotpos > 0 && dotpos == deckname.length() - 4) {
            String baseDir = deckDir != null ? deckDir : (type.equals(GameType.Commander) ?
                    ForgeConstants.DECK_COMMANDER_DIR : ForgeConstants.DECK_CONSTRUCTED_DIR);

            if (!baseDir.endsWith(File.separator)) {
                baseDir += File.separator;
            }

            File f = new File(baseDir + deckname);
            if (!f.exists()) {
                System.out.println("No deck found in " + baseDir);
            }

            return DeckSerializer.fromFile(f);
        }

        IStorage<Deck> deckStore = null;

        // Add other game types here...
        if (type.equals(GameType.Commander)) {
            deckStore = FModel.getDecks().getCommander();
        } else {
            deckStore = FModel.getDecks().getConstructed();
        }

        return deckStore.get(deckname);
    }

}
