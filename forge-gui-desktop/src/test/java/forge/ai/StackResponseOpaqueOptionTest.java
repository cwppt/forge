package forge.ai;

import forge.ai.decision.LegalActionView;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class StackResponseOpaqueOptionTest {
    private LegalActionView view(String name, int internalPosition) {
        return new LegalActionView("INTERNAL_" + name, "SPELL", name, "Instant", "Counter",
                "Counter target spell", "{U}{U}", List.of("Visible target"), List.of(), null,
                internalPosition, null);
    }

    @Test
    public void opaqueIdsAreStableDistinctAndIgnoreInternalPositions() {
        List<LegalActionView> actions = List.of(view("Counterspell", 0), view("Essence Scatter", 1));
        List<String> ids = StackResponseOptionIds.generate("decision-material", actions);
        Assert.assertEquals(ids, StackResponseOptionIds.generate("decision-material", actions));
        Assert.assertEquals(new HashSet<>(ids).size(), actions.size());
        ids.forEach(id -> Assert.assertTrue(id.matches("OPT_[0-9A-F]{24}")));
        Assert.assertEquals(ids, StackResponseOptionIds.generate("decision-material",
                List.of(view("Counterspell", 42), view("Essence Scatter", 17))));
        Assert.assertNotEquals(ids, StackResponseOptionIds.generate("another-decision", actions));
        Assert.assertNotEquals(ids.get(0), StackResponseOptionIds.generate("decision-material",
                List.of(view("Mana Leak", 0))).get(0));
    }

    @Test
    public void duplicateMaterialsAndTruncationCollisionsAreResolvedDeterministically() {
        List<LegalActionView> identical = List.of(view("Counterspell", 0), view("Counterspell", 1), view("Counterspell", 2));
        List<String> ids = StackResponseOptionIds.generate("decision", identical);
        Assert.assertEquals(new HashSet<>(ids).size(), 3);
        Assert.assertEquals(ids, StackResponseOptionIds.generate("decision", identical));
        Assert.assertEquals(ids.get(0).length(), 28);
        Assert.assertEquals(ids.get(1).length(), 28);
        Assert.assertEquals(ids.get(2).length(), 28);
        List<LegalActionView> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(view("Distinct response " + i, i));
        // Forty different materials guarantee collisions among sixteen one-hex-digit prefixes.
        List<String> shortIds = StackResponseOptionIds.generate("decision", many, 1);
        Assert.assertEquals(new HashSet<>(shortIds).size(), 40);
        Assert.assertTrue(shortIds.stream().anyMatch(id -> id.length() == 28));
        Assert.assertEquals(shortIds, StackResponseOptionIds.generate("decision", many, 1));
    }

    @Test
    public void opaqueMappingRetainsExactOriginalActionsAndRejectsOrdinalProviderIds() {
        PreparedAiAction first = new PreparedAiAction("ACTION_0", null, null, null,
                PreparedAiAction.ActionCategory.SPELL, 0, "First", true);
        PreparedAiAction second = new PreparedAiAction("ACTION_1", null, null, null,
                PreparedAiAction.ActionCategory.SPELL, 1, "Second", true);
        List<PreparedAiAction> originals = List.of(first, second);
        List<String> ids = StackResponseOptionIds.generate("decision", List.of(view("Counterspell", 0), view("Essence Scatter", 1)));
        var mapping = StackResponseActionPresentation.create(originals, true, 1000, 0, 1, 0, "state").withOptionIds(ids);
        for (int i = 0; i < ids.size(); i++) {
            PreparedAiAction original = originals.get(mapping.originalPositions().get(i));
            Assert.assertSame(mapping.resolve(ids.get(i)), original);
            Assert.assertEquals(mapping.presentedId(original.actionId()), ids.get(i));
        }
        Assert.assertNull(mapping.resolve("ACTION_0"));
        Assert.assertNull(mapping.resolve("PASS"));
        Assert.assertEquals(mapping.presentedId("PASS"), "PASS");
        Assert.assertEquals(mapping.presentedId("FORGE_LEGACY_RESPONSE"), "FORGE_LEGACY_RESPONSE");
    }
}
