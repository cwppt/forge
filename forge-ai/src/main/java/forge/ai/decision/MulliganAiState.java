package forge.ai.decision;

import java.util.List;

public record MulliganAiState(
        String fingerprint,
        Integer turnNumber,
        AiPlayerView activeOrStartingPlayer,
        AiPlayerView self,
        Integer selfLife,
        List<MulliganCardView> openingHand,
        int startingHandSize,
        int cardsToReturn,
        boolean startingPlayer) {

    public MulliganAiState {
        openingHand = List.copyOf(openingHand);
    }
}
