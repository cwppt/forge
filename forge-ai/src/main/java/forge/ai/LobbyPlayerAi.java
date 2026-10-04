package forge.ai;

import java.util.Set;

import forge.LobbyPlayer;
import forge.ai.decision.AiDecisionProvider;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import org.tinylog.Logger;

public class LobbyPlayerAi extends LobbyPlayer implements IGameEntitiesFactory {

    private String aiProfile = "";
    private boolean rotateProfileEachGame;
    private AIOption option;
    private final AiDecisionProvider decisionProvider;
    private final boolean externalMulliganEnabled;
    private final boolean externalMainPhaseEnabled;
    private final int externalMainPhaseMaxActions;
    private final boolean externalMainPhaseIncludeRejected;
    private final int externalMainPhaseMaxRejectedActions;
    private final boolean externalStackResponseEnabled;
    private final int externalStackResponseMaxActions;
    private final boolean externalStackResponseRandomizeActionOrder;
    private final boolean externalCombatAttackersEnabled;
    private final int externalCombatAttackersMaxOptions;

    public LobbyPlayerAi(String name, Set<AIOption> options) {
        this(name, options, null, false, false, 3);
    }

    public LobbyPlayerAi(String name, Set<AIOption> options, AiDecisionProvider decisionProvider) {
        this(name, options, decisionProvider, decisionProvider != null, false, 3);
    }

    public LobbyPlayerAi(String name, Set<AIOption> options, AiDecisionProvider decisionProvider,
            boolean externalMulliganEnabled, boolean externalMainPhaseEnabled, int externalMainPhaseMaxActions) {
        this(name, options, decisionProvider, externalMulliganEnabled, externalMainPhaseEnabled,
                externalMainPhaseMaxActions, false, 1);
    }

    public LobbyPlayerAi(String name, Set<AIOption> options, AiDecisionProvider decisionProvider,
            boolean externalMulliganEnabled, boolean externalMainPhaseEnabled, int externalMainPhaseMaxActions,
            boolean externalMainPhaseIncludeRejected, int externalMainPhaseMaxRejectedActions) {
        this(name, options, decisionProvider, externalMulliganEnabled, externalMainPhaseEnabled,
                externalMainPhaseMaxActions, externalMainPhaseIncludeRejected,
                externalMainPhaseMaxRejectedActions, false, 3);
    }

    public LobbyPlayerAi(String name, Set<AIOption> options, AiDecisionProvider decisionProvider,
            boolean externalMulliganEnabled, boolean externalMainPhaseEnabled, int externalMainPhaseMaxActions,
            boolean externalMainPhaseIncludeRejected, int externalMainPhaseMaxRejectedActions,
            boolean externalStackResponseEnabled, int externalStackResponseMaxActions) {
        this(name, options, decisionProvider, externalMulliganEnabled, externalMainPhaseEnabled,
                externalMainPhaseMaxActions, externalMainPhaseIncludeRejected, externalMainPhaseMaxRejectedActions,
                externalStackResponseEnabled, externalStackResponseMaxActions, false);
    }

    public LobbyPlayerAi(String name, Set<AIOption> options, AiDecisionProvider decisionProvider,
            boolean externalMulliganEnabled, boolean externalMainPhaseEnabled, int externalMainPhaseMaxActions,
            boolean externalMainPhaseIncludeRejected, int externalMainPhaseMaxRejectedActions,
            boolean externalStackResponseEnabled, int externalStackResponseMaxActions, boolean randomizeStackResponseOrder) {
        this(name, options, decisionProvider, externalMulliganEnabled, externalMainPhaseEnabled, externalMainPhaseMaxActions,
                externalMainPhaseIncludeRejected, externalMainPhaseMaxRejectedActions, externalStackResponseEnabled,
                externalStackResponseMaxActions, randomizeStackResponseOrder, false, 4);
    }

    public LobbyPlayerAi(String name, Set<AIOption> options, AiDecisionProvider decisionProvider,
            boolean externalMulliganEnabled, boolean externalMainPhaseEnabled, int externalMainPhaseMaxActions,
            boolean externalMainPhaseIncludeRejected, int externalMainPhaseMaxRejectedActions,
            boolean externalStackResponseEnabled, int externalStackResponseMaxActions, boolean randomizeStackResponseOrder,
            boolean externalCombatAttackersEnabled, int externalCombatAttackersMaxOptions) {
        super(name);
        this.decisionProvider = decisionProvider;
        this.externalMulliganEnabled = externalMulliganEnabled;
        this.externalMainPhaseEnabled = externalMainPhaseEnabled;
        this.externalMainPhaseMaxActions = externalMainPhaseMaxActions;
        this.externalMainPhaseIncludeRejected = externalMainPhaseIncludeRejected;
        this.externalMainPhaseMaxRejectedActions = externalMainPhaseMaxRejectedActions;
        this.externalStackResponseEnabled = externalStackResponseEnabled;
        this.externalStackResponseMaxActions = externalStackResponseMaxActions;
        this.externalStackResponseRandomizeActionOrder = randomizeStackResponseOrder;
        this.externalCombatAttackersEnabled = externalCombatAttackersEnabled;
        this.externalCombatAttackersMaxOptions = externalCombatAttackersMaxOptions;
        if (options != null && !options.isEmpty()) {
            option = options.iterator().next();
        }
    }

    public void setAiProfile(String profileName) {
        Logger.debug("[AI Preferences] " + name + " using profile " + profileName);
        aiProfile = profileName;
    }
    public String getAiProfile() {
        return aiProfile;
    }

    public void setRotateProfileEachGame(boolean rotateProfileEachGame) {
        this.rotateProfileEachGame = rotateProfileEachGame;
    }

    private PlayerControllerAi createControllerFor(Player ai) {
        PlayerControllerAi result = new PlayerControllerAi(ai.getGame(), ai, this, decisionProvider,
                externalMulliganEnabled, externalMainPhaseEnabled, externalMainPhaseMaxActions,
                externalMainPhaseIncludeRejected, externalMainPhaseMaxRejectedActions,
                externalStackResponseEnabled, externalStackResponseMaxActions, externalStackResponseRandomizeActionOrder,
                externalCombatAttackersEnabled, externalCombatAttackersMaxOptions);
        result.getAi().setUseSimulation(option);
        return result;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return createControllerFor(slave);
    }

    @Override
    public Player createIngamePlayer(Game game, final int id) {
        Player ai = new Player(getName(), game, id);
        ai.setFirstController(createControllerFor(ai));

        if (rotateProfileEachGame) {
            setAiProfile(AiProfileUtil.getRandomProfile());
        }
        return ai;
    }

    @Override
    public void hear(LobbyPlayer player, String message) { /* Local AI is deaf. */ }
}
