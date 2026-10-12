package forge.ai.decision;

import java.util.List;

/** Compact observer whitelist. No engine identities, hidden locations or generated prose. */
public record StrategicMemoryView(List<Fact> knownOpponentCards, List<Fact> interactionObserved,
        List<Fact> currentTurnFacts, List<Fact> recentStrategicEvents) {
    public StrategicMemoryView {
        knownOpponentCards=List.copyOf(knownOpponentCards); interactionObserved=List.copyOf(interactionObserved);
        currentTurnFacts=List.copyOf(currentTurnFacts); recentStrategicEvents=List.copyOf(recentStrategicEvents);
    }
    public record Fact(String category, String source, int turn, String cardName,
            String interaction, String knowledgeStatus, String location, String summary) { }
    public int size() { return knownOpponentCards.size()+interactionObserved.size()+currentTurnFacts.size()+recentStrategicEvents.size(); }
}
