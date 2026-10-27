package forge.ai.simulation;

import com.google.common.collect.*;
import forge.LobbyPlayer;
import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.CardType;
import forge.game.*;
import forge.game.ability.effects.DetachedCardEffect;
import forge.game.card.Card;
import forge.game.card.CardCloneStates;
import forge.game.card.CardCopyService;
import forge.game.card.CardFactory;
import forge.game.card.CounterType;
import forge.game.card.token.TokenInfo;
import forge.game.combat.Combat;
import forge.game.mana.Mana;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.TriggerType;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.Zone;
import forge.game.zone.PlayerZoneBattlefield;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

public class GameCopier {
    private static final ZoneType[] ZONES = new ZoneType[] {
        ZoneType.Battlefield,
        ZoneType.Hand,
        ZoneType.Graveyard,
        ZoneType.Library,
        ZoneType.Exile,
        ZoneType.Stack,
        ZoneType.Command,
    };

    private Game origGame;
    private BiMap<Player, Player> playerMap = HashBiMap.create();
    private BiMap<Card, Card> cardMap = HashBiMap.create();
    private CopiedGameObjectMap gameObjectMap;
    // LKI versions can share a card id with the live zone card.
    private final Map<Card, Card> referenceCards = new IdentityHashMap<>();
    private final Map<SpellAbility, SpellAbility> referenceAbilities = new IdentityHashMap<>();
    private GameSnapshot snapshot = null;
    private final boolean forceCopyStack;

    public GameCopier(Game origGame) {
        this(origGame, false);
    }

    public GameCopier(Game origGame, boolean forceCopyStack) {
        this.origGame = origGame;
        this.forceCopyStack = forceCopyStack;
        if (origGame.EXPERIMENTAL_RESTORE_SNAPSHOT) {
            this.snapshot = new GameSnapshot(origGame);
        }
    }

    public Game getOriginalGame() {
        return origGame;
    }

    public Game getCopiedGame() {
        return gameObjectMap.getGame();
    }

    public Game makeCopy() {
        return makeCopy(null, null);
    }
    public Game makeCopy(PhaseType advanceToPhase, Player aiPlayer) {
        if (origGame.EXPERIMENTAL_RESTORE_SNAPSHOT) {
            // How do we advance to phase when using restores?
            return snapshot.makeCopy();
        }

        List<RegisteredPlayer> origPlayers = origGame.getMatch().getPlayers();
        List<RegisteredPlayer> newPlayers = new ArrayList<>();
        for (RegisteredPlayer p : origPlayers) {
            newPlayers.add(clonePlayer(p));
        }

        GameRules currentRules = origGame.getRules();
        Match newMatch = new Match(currentRules, newPlayers, origGame.getView().getTitle());
        Game newGame = new Game(newPlayers, currentRules, newMatch);
        newGame.setNoGUIUser();
        newGame.dangerouslySetTimestamp(origGame.getTimestamp());

        for (Player origPlayer : origGame.getRegisteredPlayers()) {
            Player newPlayer = newGame.getPlayer(origPlayer.getId());
            newPlayer.setTeam(origPlayer.getTeam());
            newPlayer.setLife(origPlayer.getLife(), null);
            newPlayer.setLifeLostLastTurn(origPlayer.getLifeLostLastTurn());
            newPlayer.setLifeLostThisTurn(origPlayer.getLifeLostThisTurn());
            newPlayer.setLifeGainedThisTurn(origPlayer.getLifeGainedThisTurn());
            newPlayer.setCommitedCrimeThisTurn(origPlayer.getCommittedCrimeThisTurn());
            newPlayer.setLifeStartedThisTurnWith(origPlayer.getLifeStartedThisTurnWith());
            newPlayer.setDamageReceivedThisTurn(origPlayer.getDamageReceivedThisTurn());
            newPlayer.setLandsPlayedThisTurn(origPlayer.getLandsPlayedThisTurn());
            newPlayer.setCounters(HashMultiset.create(origPlayer.getCounters()));
            newPlayer.setSpeed(origPlayer.getSpeed());
            // Blessing state travels with the copied command-zone effect card
            // (wired via copyEffectCardsToSnapshot below); calling setBlessing
            // here would create a second effect card the zone copy duplicates.
            newPlayer.setDescended(origPlayer.getDescended());
            newPlayer.setLibrarySearched(origPlayer.getLibrarySearched());
            newPlayer.setSpellsCastLastTurn(origPlayer.getSpellsCastLastTurn());
            for (int j = 0; j < origPlayer.getSpellsCastThisTurn(); j++) {
                newPlayer.addSpellCastThisTurn();
            }
            newPlayer.setMaxHandSize(origPlayer.getMaxHandSize());
            newPlayer.setUnlimitedHandSize(origPlayer.isUnlimitedHandSize());
            newPlayer.setCrankCounter(origPlayer.getCrankCounter());
            // TODO creatureAttackedThisTurn
            for (Mana m : origPlayer.getManaPool()) {
                newPlayer.getManaPool().addManaNoEvent(m);
            }
            playerMap.put(origPlayer, newPlayer);
            if (!origGame.getPlayers().contains(origPlayer)) {
                // Keep historical owners available without reviving eliminated opponents.
                newPlayer.getStats().setOutcome(origPlayer.getOutcome());
                newGame.getPlayers().remove(newPlayer);
                newGame.getLostPlayers().add(newPlayer);
            }
        }

        PhaseHandler origPhaseHandler = origGame.getPhaseHandler();
        Player newPlayerTurn = playerMap.get(origPhaseHandler.getPlayerTurn());
        newGame.getPhaseHandler().devModeSet(origPhaseHandler.getPhase(), newPlayerTurn, origPhaseHandler.getTurn());
        newGame.getTriggerHandler().suppressMode(TriggerType.ChangesZone);
        for (Player p : newGame.getPlayers()) {
            ((PlayerZoneBattlefield) p.getZone(ZoneType.Battlefield)).setTriggers(false);
        }

        copyGameState(newGame, aiPlayer);

        for (Player origPlayer : origGame.getPlayers()) {
            Player newPlayer = playerMap.get(origPlayer);
            origPlayer.copyCommandersToSnapshot(newPlayer, c -> mapCommanderReference(newGame, c));
            origPlayer.copyEffectCardsToSnapshot(newPlayer, gameObjectMap::map);
            ((PlayerZoneBattlefield) newPlayer.getZone(ZoneType.Battlefield)).setTriggers(true);
        }
        newGame.getTriggerHandler().clearSuppression(TriggerType.ChangesZone);

        for (Card c : newGame.getCardsInGame()) {
            Card origCard = (Card) reverseFind(c);
            if (origCard.hasRemembered()) {
                for (Object o : origCard.getRemembered()) {
                    if (o instanceof GameObject) {
                        c.addRemembered(mapReference(o));
                    } else {
                        System.err.println(c + " Remembered: " + o + "/" + o.getClass());
                        c.addRemembered(o);
                    }
                }
            }
            for (SpellAbility sa : c.getSpellAbilities()) {
                Player activatingPlayer = sa.getActivatingPlayer();
                if (activatingPlayer != null && activatingPlayer.getGame() != newGame) {
                    sa.setActivatingPlayer(gameObjectMap.map(activatingPlayer));
                }
            }
        }

        // Undo effects first before calculating them below, to avoid them applying twice.
        for (StaticEffect effect : origGame.getStaticEffects().getEffects()) {
            effect.removeMapped(gameObjectMap);
        }

        if (origPhaseHandler.getCombat() != null) {
            newGame.getPhaseHandler().setCombat(new Combat(origPhaseHandler.getCombat(), gameObjectMap));
        }

        newGame.getAction().checkStateEffects(true); //ensure state based effects and triggers are updated
        newGame.getTriggerHandler().resetActiveTriggers();

        if (forceCopyStack || GameSimulator.COPY_STACK)
            copyStack(origGame, newGame, gameObjectMap, forceCopyStack);

        // TODO update thisTurnCast

        if (advanceToPhase != null) {
            newGame.getPhaseHandler().devAdvanceToPhase(advanceToPhase, () -> GameSimulator.resolveStack(newGame, aiPlayer.getWeakestOpponent()));
        }

        return newGame;
    }

    private Card mapCommanderReference(Game newGame, Card commander) {
        Card mapped = cardMap.get(commander);
        if (mapped != null) {
            return mapped;
        }
        // Damage/cast history can outlive the commander and its owner leaving
        // the game. Preserve the identity and statistics in a detached copy;
        // putting this card into a zone would resurrect a removed permanent.
        if (!commander.isCommander() || origGame.getCardState(commander, null) != null) {
            return gameObjectMap.map(commander);
        }
        mapped = createCardCopy(newGame, playerMap.get(commander.getOwner()), commander, null);
        mapped.setGameTimestamp(commander.getGameTimestamp());
        cardMap.put(commander, mapped);
        return mapped;
    }

    private Zone mapReferenceZone(Zone zone) {
        if (zone == null) return null;
        return zone.getPlayer() == null ? gameObjectMap.getGame().getStackZone()
                : playerMap.get(zone.getPlayer()).getZone(zone.getZoneType());
    }

    private Card mapReferenceCard(Card source) {
        if (source == null) return null;
        Card live = origGame.getCardState(source, null);
        if (!source.isLKI() && live != null && source.equalsWithGameTimestamp(live)) {
            return find(live);
        }
        Card result = referenceCards.get(source);
        if (result != null) return result;
        // Preserve last-known characteristics, counters and timestamps, but never
        // insert historical sources/tokens into a live zone. Cache before recursion.
        result = CardCopyService.getLKICopy(source);
        referenceCards.put(source, result);
        result.setOwner(playerMap.get(source.getOwner()));
        result.dangerouslySetGame(gameObjectMap.getGame());
        result.setController(playerMap.get(source.getController()), 0);
        result.setZone(mapReferenceZone(source.getZone()));
        result.setLastKnownZone(mapReferenceZone(source.getLastKnownZone()));
        if (source.hasChosenPlayer()) result.setChosenPlayer(playerMap.get(source.getChosenPlayer()));
        result.clearRemembered();
        for (Object remembered : source.getRemembered()) result.addRemembered(mapReference(remembered));
        result.clearImprintedCards();
        for (Card imprinted : source.getImprintedCards()) result.addImprintedCard(mapReferenceCard(imprinted));
        if (source.getEffectSource() != null) result.setEffectSource(mapReferenceCard(source.getEffectSource()));
        if (source.getCopiedPermanent() != null) result.setCopiedPermanent(mapReferenceCard(source.getCopiedPermanent()));
        return result;
    }

    private Object mapReference(Object value) {
        if (value instanceof Card) return mapReferenceCard((Card) value);
        if (value instanceof Player) return find((Player) value);
        if (value instanceof SpellAbility) return copyPreparedAbility((SpellAbility) value);
        if (value instanceof forge.game.card.CardCollectionView) {
            forge.game.card.CardCollection cards = new forge.game.card.CardCollection();
            for (Card card : (forge.game.card.CardCollectionView) value) cards.add(mapReferenceCard(card));
            return cards;
        }
        if (value instanceof Map<?, ?>) {
            Map<Object, Object> mapped = new LinkedHashMap<>();
            ((Map<?, ?>) value).forEach((key, item) -> mapped.put(mapReference(key), mapReference(item)));
            return mapped;
        }
        if (value instanceof Collection<?>) {
            Collection<Object> mapped = value instanceof Set<?> ? new LinkedHashSet<>() : new ArrayList<>();
            for (Object item : (Collection<?>) value) mapped.add(mapReference(item));
            return mapped;
        }
        return value;
    }

    private SpellAbility copyPreparedAbility(SpellAbility source) {
        SpellAbility cached = referenceAbilities.get(source);
        if (cached != null) return cached;
        Card host = mapReferenceCard(source.getHostCard());
        SpellAbility result;
        if (source instanceof WrappedAbility) {
            WrappedAbility wrapper = (WrappedAbility) source;
            SpellAbility inner = copyPreparedAbility(wrapper.getWrappedAbility());
            forge.game.trigger.Trigger trigger = wrapper.getTrigger().copy(host, true, true, inner);
            List<Object> remembered = trigger.getTriggerRemembered();
            for (int i = 0; i < remembered.size(); i++) remembered.set(i, mapReference(remembered.get(i)));
            result = new WrappedAbility(trigger, inner, find(wrapper.getDecider()));
        } else {
            result = source.copy(host, find(source.getActivatingPlayer()), true, true);
        }
        referenceAbilities.put(source, result);
        SpellAbility originalNode = source;
        SpellAbility copiedNode = result;
        while (originalNode != null && copiedNode != null) {
            referenceAbilities.put(originalNode, copiedNode);
            copiedNode.setActivatingPlayer(find(originalNode.getActivatingPlayer()));
            copiedNode.setTargetingPlayer(find(originalNode.getTargetingPlayer()));
            if (originalNode.usesTargeting()) {
                copiedNode.resetTargets();
                for (GameObject target : originalNode.getTargets()) {
                    copiedNode.getTargets().add((GameObject) mapReference(target));
                }
            }
            Map<forge.game.ability.AbilityKey, Object> objects = forge.game.ability.AbilityKey.newMap();
            originalNode.getTriggeringObjects().forEach((key, value) -> objects.put(key, mapReference(value)));
            copiedNode.setTriggeringObjects(objects);
            if (originalNode.getLastStateBattlefield() != null) {
                copiedNode.setLastStateBattlefield((forge.game.card.CardCollection) mapReference(originalNode.getLastStateBattlefield()));
            }
            if (originalNode.getLastStateGraveyard() != null) {
                copiedNode.setLastStateGraveyard((forge.game.card.CardCollection) mapReference(originalNode.getLastStateGraveyard()));
            }
            originalNode = originalNode.getSubAbility();
            copiedNode = copiedNode.getSubAbility();
        }
        return result;
    }

    private void copyStack(Game origGame, Game newGame, IEntityMap map,
            boolean includeNonSpellAbilities) {
        for (SpellAbilityStackInstance entry : origGame.getStack()) {
            SpellAbility ability = entry.getSpellAbility();
            if (ability.isSpell() || includeNonSpellAbilities) {
                newGame.getStack().addSnapshotEntry(copyPreparedAbility(ability), entry.getId());
            }
        }
        for (SpellAbility cast : origGame.getStack().getSpellsCastThisTurn()) {
            newGame.getStack().getSpellsCastThisTurn().add(copyPreparedAbility(cast));
        }
        for (SpellAbility activated : origGame.getStack().getAbilityActivatedThisTurn()) {
            newGame.getStack().getAbilityActivatedThisTurn().add(copyPreparedAbility(activated));
        }
        newGame.updateStackForView();
    }

    private RegisteredPlayer clonePlayer(RegisteredPlayer p) {
        RegisteredPlayer clone = new RegisteredPlayer(p.getDeck());
        LobbyPlayer lp = p.getPlayer();
        if (!(lp instanceof LobbyPlayerAi)) {
            // TODO should probably also override them if they're normal AI
            lp = new LobbyPlayerAi(p.getPlayer().getName(), Sets.newHashSet(AIOption.USE_FULL_SIMULATION));
        }
        clone.setPlayer(lp);
        return clone;
    }

    private void copyGameState(Game newGame, Player aiPlayer) {
        // Copied cards keep their original ids (id order is AI-visible via
        // Card.compareTo and id-keyed collections; renumbering makes forked
        // games deterministically diverge from the mainline). Sync the fresh-id
        // counters first so ids created during or after the copy cannot collide.
        newGame.dangerouslySyncCardIdCounters(origGame);
        newGame.EXPERIMENTAL_RESTORE_SNAPSHOT = origGame.EXPERIMENTAL_RESTORE_SNAPSHOT;
        newGame.AI_TIMEOUT = origGame.AI_TIMEOUT;
        newGame.setAge(origGame.getAge());

        // TODO countersAddedThisTurn

        if (origGame.getStartingPlayer() != null) {
            newGame.setStartingPlayer(playerMap.get(origGame.getStartingPlayer()));
        }
        if (origGame.getMonarch() != null) {
            newGame.setMonarch(playerMap.get(origGame.getMonarch()));
        }
        if (origGame.getMonarchBeginTurn() != null) {
            newGame.setMonarchBeginTurn(playerMap.get(origGame.getMonarchBeginTurn()));
        }
        if (origGame.getHasInitiative() != null) {
            newGame.setHasInitiative(playerMap.get(origGame.getHasInitiative()));
        }
        if (origGame.getDayTime() != null) {
            newGame.setDayTime(origGame.getDayTime());
        }

        for (ZoneType zone : ZONES) {
            for (Card card : origGame.getCardsIn(zone)) {
                addCard(newGame, zone, card, aiPlayer);
            }
            // TODO CardsAddedThisTurn is now messed up
        }
        gameObjectMap = new CopiedGameObjectMap(newGame);

        for (Card card : origGame.getCardsIn(ZoneType.Battlefield)) {
            Card otherCard = cardMap.get(card);
            otherCard.setGameTimestamp(card.getGameTimestamp());
            otherCard.setLayerTimestamp(card.getLayerTimestamp());
            otherCard.setSickness(card.hasSickness());
            otherCard.setState(card.getCurrentStateName(), false);
            if (card.isAttachedToEntity()) {
                GameEntity ge = gameObjectMap.map(card.getEntityAttachedTo());
                otherCard.setEntityAttachedTo(ge);
                ge.addAttachedCard(otherCard);
            }
            if (card.getCrewedByThisTurn() != null) {
                otherCard.setCrewedByThisTurn(card.getCrewedByThisTurn());
            }
            if (card.getCloneOrigin() != null) {
                otherCard.setCloneOrigin(cardMap.get(card.getCloneOrigin()));
            }
            if (card.getHaunting() != null) {
                otherCard.setHaunting(cardMap.get(card.getHaunting()));
            }
            if (card.getSaddledByThisTurn() != null) {
                otherCard.setSaddledByThisTurn(card.getSaddledByThisTurn());
            }
            if (card.getEffectSource() != null) {
                otherCard.setEffectSource(cardMap.get(card.getEffectSource()));
            }
            if (card.isPaired()) {
                otherCard.setPairedWith(cardMap.get(card.getPairedWith()));
            }
            if (card.getCopiedPermanent() != null) {
                // TODO would it be safe to simply reuse the prototype?
                otherCard.setCopiedPermanent(new CardCopyService(card.getCopiedPermanent()).copyCard(false));
            }
            // TODO: Verify that the above relationships are preserved bi-directionally or not.
        }
    }

    private static PaperCard hidden_info_card = new PaperCard(CardRules.fromScript(Lists.newArrayList("Name:hidden", "Types:Artifact", "Oracle:")), "", CardRarity.Common);
    private static final boolean PRUNE_HIDDEN_INFO = false;
    private static final boolean USE_FROM_PAPER_CARD = true;
    private Card createCardCopy(Game newGame, Player newOwner, Card c, Player aiPlayer) {
        if (c.isToken() && !c.isImmutable()) {
            Card result = new TokenInfo(c).makeOneToken(newOwner, c.getId());
            new CardCopyService(c).copyCopiableCharacteristics(result, null, null);
            return result;
        }
        if (USE_FROM_PAPER_CARD && !c.isImmutable() && c.getPaperCard() != null) {
            Card newCard;
            if (PRUNE_HIDDEN_INFO && aiPlayer != null && !c.getView().canBeShownTo(aiPlayer.getView())) {
                // TODO also check REVEALED_CARDS memory
                newCard = new Card(c.getId(), hidden_info_card, newGame);
                newCard.setOwner(newOwner);
            } else {
                newCard = CardFactory.getCard(c.getPaperCard(), newOwner, c.getId(), newGame);
            }
            newCard.setCommander(c.isCommander());
            return newCard;
        }

        // TODO: The above is very expensive and accounts for the vast majority of GameCopier execution time.
        // The issue is that it requires parsing the original card from scratch from the paper card. We should
        // improve the copier to accurately copy the card from its actual state, so that the paper card shouldn't
        // be needed. Once the below code accurately copies the card, remove the USE_FROM_PAPER_CARD code path.
        Card newCard;
        if (c instanceof DetachedCardEffect)
            newCard = new DetachedCardEffect((DetachedCardEffect) c, newGame, false);
        else
            newCard = new Card(c.getId(), c.getPaperCard(), newGame);
        newCard.setGamePieceType(c.getGamePieceType());
        newCard.setOwner(newOwner);
        newCard.setName(c.getName());
        newCard.setCommander(c.isCommander());
        newCard.setType(new CardType(c.getType()));
        for (StaticAbility stAb : c.getStaticAbilities()) {
            newCard.addStaticAbility(stAb.copy(newCard, true));
        }
        for (SpellAbility sa : c.getSpellAbilities()) {
            SpellAbility saCopy = sa.copy(newCard, true);
            if (saCopy != null) {
                newCard.addSpellAbility(saCopy);
            } else {
                System.err.println(sa.toString());
            }
        }

        return newCard;
    }

    private void addCard(Game newGame, ZoneType zone, Card c, Player aiPlayer) {
        final Player owner = playerMap.get(c.getOwner());
        final Card newCard = createCardCopy(newGame, owner, c, aiPlayer);
        cardMap.put(c, newCard);

        // TODO ExiledWith

        Player zoneOwner = owner;
        // everything the CreatureEvaluator checks must be set here
        if (zone == ZoneType.Battlefield) {
            // TODO: Controllers' list with timestamps should be copied.
            zoneOwner = playerMap.get(c.getController());
            newCard.setController(zoneOwner, 0);

            if (c.isBattle()) {
                newCard.setProtectingPlayer(playerMap.get(c.getProtectingPlayer()));
            }

            newCard.setCameUnderControlSinceLastUpkeep(c.cameUnderControlSinceLastUpkeep());

            newCard.setPTTable(c.getSetPTTable());
            newCard.setPTCharacterDefiningTable(c.getSetPTCharacterDefiningTable());

            newCard.setPTBoost(c.getPTBoostTable());
            // TODO copy by map
            newCard.setDamage(c.getDamage());
            newCard.setDamageReceivedThisTurn(c.getDamageReceivedThisTurn());

            newCard.copyFrom(c);
            newCard.updateKeywordsCache();

            if (c.isTapped()) {
                newCard.setTapped(true);
            }
            if (c.isFaceDown()) {
                newCard.turnFaceDown(true);
                if (c.isManifested()) {
                    newCard.setManifested(c.getManifestedSA());
                }
                if (c.isCloaked()) {
                    newCard.setCloaked(c.getCloakedSA());
                }
            }
            if (c.isMonstrous()) {
                newCard.setMonstrous(true);
            }
            if (c.isRenowned()) {
                newCard.setRenowned(true);
            }
            if (c.isSolved()) {
                newCard.setSolved(true);
            }
            if (c.isSaddled()) {
                newCard.setSaddled(true);
            }
            if (c.isSuspected()) {
                newCard.setSuspected(true);
            }
            if (c.isPlaneswalker()) {
                for (SpellAbility sa : c.getAllSpellAbilities()) {
                    int active = sa.getActivationsThisTurn();
                    if (sa.isPwAbility() && active > 0) {
                        SpellAbility newSa = findSAInCard(sa, newCard);
                        if (newSa != null) {
                            for (int i = 0; i < active; i++) {
                                newCard.addAbilityActivated(newSa);
                            }
                        }
                    }
                }
            }

            newCard.setFlipped(c.isFlipped());
            for (Map.Entry<Long, CardCloneStates> e : c.getCloneStates().entrySet()) {
                newCard.addCloneState(e.getValue().copy(newCard, true), e.getKey());
            }

            Multiset<CounterType> counters = c.getCounters();
            if (!counters.isEmpty()) {
                newCard.setCounters(HashMultiset.create(counters));
            }
            if (c.hasChosenPlayer()) {
                newCard.setChosenPlayer(playerMap.get(c.getChosenPlayer()));
            }
            if (c.hasChosenType()) {
                newCard.setChosenType(c.getChosenType());
            }
            if (c.hasChosenType2()) {
                newCard.setChosenType2(c.getChosenType2());
            }
            if (c.hasChosenColor()) {
                newCard.setChosenColors(Lists.newArrayList(c.getChosenColors()));
            }
            if (c.hasNamedCard()) {
                newCard.setNamedCards(Lists.newArrayList(c.getNamedCards()));
            }

            newCard.setSprocket(c.getSprocket());

            newCard.setSVars(c.getSVars());
            newCard.copyChangedSVarsFrom(c);
        }

        if (zone == ZoneType.Exile && c.isFaceDown()) {
            // Face-down exile state (foretell, "exile face down" effects) must
            // survive the copy: cards rebuilt from their paper card default to
            // face up, leaking hidden information into the copied game.
            newCard.turnFaceDownNoUpdate();
            if (c.isForetold()) {
                newCard.setForetold(true);
                if (c.isForetoldCostByEffect()) {
                    newCard.setForetoldCostByEffect(true);
                }
            }
        }

        if (zone == ZoneType.Stack) {
            newGame.getStackZone().add(newCard);
        } else {
            zoneOwner.getZone(zone).add(newCard);
        }
    }

    private static SpellAbility findSAInCard(SpellAbility sa, Card c) {
        String saDesc = sa.getDescription();
        for (SpellAbility cardSa : c.getAllSpellAbilities()) {
            if (saDesc.equals(cardSa.getDescription())) {
                return cardSa;
            }
        }
        return null;
    }

    /** Maps a card-owned ability into the copied game without exposing the copier's internal maps. */
    public SpellAbility findSpellAbility(SpellAbility sa) {
        if (sa == null || sa.getHostCard() == null) {
            return null;
        }
        return findSAInCard(sa, find(sa.getHostCard()));
    }

    private class CopiedGameObjectMap implements IEntityMap {
        private final Game copiedGame;

        public CopiedGameObjectMap(Game copiedGame) {
            this.copiedGame = copiedGame;
        }

        @Override
        public Game getGame() {
            return copiedGame;
        }

        @Override
        public GameObject map(GameObject o) {
            return find(o);
        }
    }

    public <T extends GameObject> T find(T o) {
        if (origGame.EXPERIMENTAL_RESTORE_SNAPSHOT) {
            return (T) snapshot.find(o);
        }

        T result = null;
        if (o instanceof Card) {
            result = (T) cardMap.get(o);
            if (result != null) {
                return result;
            } else {
                System.out.println("Couldn't map " + o + "/" + System.identityHashCode(o));
            }
        } else if (o instanceof Player) {
            result = (T) playerMap.get(o);
            if (result != null)
                return result;
        }
        if (o != null)
            throw new RuntimeException("Couldn't map " + o + "/" + System.identityHashCode(o));
        return result;
    }
    public GameObject reverseFind(GameObject o) {
        if (origGame.EXPERIMENTAL_RESTORE_SNAPSHOT) {
            return snapshot.reverseFind(o);
        }

        GameObject result = cardMap.inverse().get(o);
        if (result != null)
            return result;
        // TODO: Have only one GameObject map?
        return playerMap.inverse().get(o);
    }
}
