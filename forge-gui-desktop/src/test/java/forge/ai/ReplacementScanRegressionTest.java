package forge.ai;

import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardTraitChanges;
import forge.game.card.CardState;
import forge.game.card.CounterEnumType;
import forge.card.CardStateName;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementHandler;
import forge.game.replacement.ReplacementLayer;
import forge.game.replacement.ReplacementType;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

public class ReplacementScanRegressionTest extends AITest {
    private ReplacementEffect prevention(Card host, String zone) {
        return ReplacementHandler.parseReplacement("Event$ DamageDone | ActiveZones$ " + zone
                + " | ValidTarget$ Player | Prevent$ True", host, false, null);
    }

    private List<ReplacementEffect> scan(Game game, Player target) {
        Map<AbilityKey, Object> params = AbilityKey.mapFromAffected(target);
        params.put(AbilityKey.DamageAmount, 3);
        params.put(AbilityKey.IsCombat, true);
        return game.getReplacementHandler().getReplacementList(
                ReplacementType.DamageDone, params, ReplacementLayer.Other);
    }

    @Test
    public void emptyCardsAreSkippedButNewPrintedEffectsInAnyZoneAreSeen() {
        Game game = initAndCreateGame();
        Player owner = game.getPlayers().get(1);
        Card host = addCardToZone("Mountain", owner, ZoneType.Library);
        Assert.assertFalse(host.getCurrentState().hasReplacementEffectSources());
        Assert.assertTrue(scan(game, owner).isEmpty());
        ReplacementEffect effect = prevention(host, "Library");
        host.addReplacementEffect(effect);
        Assert.assertTrue(host.getCurrentState().hasReplacementEffectSources());
        Assert.assertTrue(scan(game, owner).contains(effect));
    }

    @Test
    public void liveTraitAndKeywordChangesAreNotCachedAway() {
        Game game = initAndCreateGame();
        Player owner = game.getPlayers().get(1);
        Card host = addCard("Runeclaw Bear", owner);
        Assert.assertFalse(host.getCurrentState().hasReplacementEffectSources());
        ReplacementEffect effect = prevention(host, "Battlefield");
        host.getChangedCardTraitsByText().put(1L, 0L,
                new CardTraitChanges(null, null, List.of(effect), null, null));
        Assert.assertTrue(scan(game, owner).contains(effect));
        host.getChangedCardTraitsByText().clear();
        Assert.assertFalse(host.getCurrentState().hasReplacementEffectSources());
        host.getChangedCardTraits().put(2L, 0L,
                new CardTraitChanges(null, null, List.of(effect), null, null));
        Assert.assertTrue(scan(game, owner).contains(effect));
        host.getChangedCardTraits().clear();
        host.getCurrentState().getCachedKeywords().add("Flying");
        Assert.assertFalse(host.getCurrentState().hasReplacementEffectSources());
        host.getCurrentState().getCachedKeywords().removeAll(Keyword.FLYING);
        Assert.assertFalse(host.getCurrentState().hasReplacementEffectSources());
    }

    @Test
    public void counterAndSpecialTypeRulesKeepTheFullScan() {
        Game game = initAndCreateGame();
        Player owner = game.getPlayers().get(1);
        Card creature = addCard("Runeclaw Bear", owner);
        creature.setCounters(CounterEnumType.P1P1, 1);
        Assert.assertFalse(creature.getCurrentState().hasReplacementEffectSources());
        Assert.assertTrue(creature.getReplacementEffects().isEmpty());
        creature.setCounters(CounterEnumType.SHIELD, 1);
        Assert.assertTrue(creature.getCurrentState().hasReplacementEffectSources());
        Assert.assertTrue(creature.getReplacementEffects().stream().anyMatch(
                re -> re.getMode() == ReplacementType.DamageDone));
        creature.setCounters(CounterEnumType.SHIELD, 0);
        Assert.assertTrue(creature.getReplacementEffects().isEmpty());
        creature.setCounters(CounterEnumType.STUN, 1);
        Assert.assertTrue(creature.getCurrentState().hasReplacementEffectSources());
        Assert.assertTrue(creature.getReplacementEffects().stream().anyMatch(
                re -> re.getMode() == ReplacementType.Untap));
        creature.setCounters(CounterEnumType.STUN, 0);
        creature.setCounters(CounterEnumType.FINALITY, 1);
        Assert.assertTrue(creature.getCurrentState().hasReplacementEffectSources());
        Assert.assertTrue(creature.getReplacementEffects().stream().anyMatch(
                re -> re.getMode() == ReplacementType.Moved));
        for (String card : List.of("Chandra, Torch of Defiance", "The Eldest Reborn", "Invasion of Zendikar")) {
            Card special = addCard(card, owner);
            Assert.assertTrue(special.getCurrentState().hasReplacementEffectSources(), card);
            Assert.assertFalse(special.getReplacementEffects().isEmpty(), card);
        }
    }

    @Test
    public void damageScanDoesNotRebuildEmptyListsAndSeesNewKeywordEffects() {
        Game game = initAndCreateGame();
        Player owner = game.getPlayers().get(1);
        int[] rebuilds = {0};
        Card sentinel = new Card(game.nextCardId(), game) {
            @Override
            public FCollectionView<ReplacementEffect> getReplacementEffects() {
                rebuilds[0]++;
                return super.getReplacementEffects();
            }
        };
        sentinel.setName("Runeclaw Bear");
        sentinel.setOwner(owner);
        owner.getZone(ZoneType.Battlefield).add(sentinel);
        Assert.assertTrue(scan(game, owner).isEmpty());
        Assert.assertEquals(rebuilds[0], 0);
        sentinel.addIntrinsicKeyword("Flying");
        rebuilds[0] = 0;
        Assert.assertTrue(scan(game, owner).isEmpty());
        Assert.assertEquals(rebuilds[0], 0);
        sentinel.addIntrinsicKeyword("Absorb:1");
        rebuilds[0] = 0;
        scan(game, owner);
        Assert.assertEquals(rebuilds[0], 1);
        Assert.assertTrue(sentinel.getReplacementEffects().stream().anyMatch(
                re -> re.getMode() == ReplacementType.DamageDone));
    }

    @Test
    public void splitAdventureOmenAndChangedTypesCannotUseEmptyShortcut() {
        Game game = initAndCreateGame();
        Player owner = game.getPlayers().get(1);
        Card split = createCard("Wear // Tear", owner);
        Assert.assertTrue(split.getCurrentState().hasReplacementEffectSources());
        Card adventure = createCard("Bonecrusher Giant", owner);
        CardState adventureState = adventure.getState(CardStateName.Secondary);
        Assert.assertTrue(adventureState.hasReplacementEffectSources());
        Assert.assertFalse(adventureState.getReplacementEffects().isEmpty());
        Card dynamic = createCard("Mountain", owner);
        Assert.assertFalse(dynamic.getCurrentState().hasReplacementEffectSources());
        dynamic.getCurrentState().addType("Omen");
        Assert.assertTrue(dynamic.getCurrentState().hasReplacementEffectSources());
        Card saga = createCard("Mountain", owner);
        saga.getCurrentState().addType("Saga");
        saga.getCurrentState().updateTypes();
        Assert.assertTrue(saga.getCurrentState().hasReplacementEffectSources());
    }
}
