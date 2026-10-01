package forge.ai.decision;

import java.util.List;

public record MainPhasePlayerState(
        AiPlayerView identity,
        int life,
        boolean self,
        String manaPool,
        List<MainPhaseCardView> hand,
        List<MainPhaseCardView> battlefield,
        List<MainPhaseCardView> graveyard,
        List<MainPhaseCardView> exile,
        List<MainPhaseCardView> command) {

    public MainPhasePlayerState {
        hand = List.copyOf(hand);
        battlefield = List.copyOf(battlefield);
        graveyard = List.copyOf(graveyard);
        exile = List.copyOf(exile);
        command = List.copyOf(command);
    }
}
