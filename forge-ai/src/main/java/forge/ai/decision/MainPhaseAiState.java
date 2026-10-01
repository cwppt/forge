package forge.ai.decision;

import java.util.List;

public record MainPhaseAiState(
        String fingerprint,
        int turnNumber,
        String phase,
        AiPlayerView activePlayer,
        AiPlayerView self,
        List<MainPhasePlayerState> players,
        int stackSize) {

    public MainPhaseAiState {
        players = List.copyOf(players);
    }
}
