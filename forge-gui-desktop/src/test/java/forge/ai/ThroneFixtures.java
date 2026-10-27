package forge.ai;
import forge.game.Game;
import forge.game.card.*;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.game.ability.AbilityKey;
import org.testng.Assert;
import org.testng.annotations.Test;
public class ThroneFixtures extends AITest {
 @Test public void goldspanSpellTargetTreasureDoubled(){var g=setup();var p=g.getPlayers().get(1);var dragon=addCard("Goldspan Dragon",p);refresh(g);enter(g,p,"Roaming Throne");var shock=addCardToZone("Shock",p,ZoneType.Hand);var a=shock.getFirstSpellAbility();a.setActivatingPlayer(p);a.getTargets().add(dragon);g.getStack().add(a);drain(g);Assert.assertEquals(countCardsWithName(g,"Treasure Token"),2);}
 @Test public void nativeChoiceWithExactFrozenChallenger() throws Exception {var g=initAndCreateGame();var p=g.getPlayers().get(1);for(var o:g.getPlayers())o.setLife(40,null);for(String line:java.nio.file.Files.readAllLines(java.nio.file.Path.of(System.getProperty("fixture.deck")))){if(!line.matches("[0-9]+ .*"))continue;var parts=line.split(" ",2);for(int i=0;i<Integer.parseInt(parts[0]);i++)if(!parts[1].equals("Roaming Throne"))addCardToZone(parts[1],p,ZoneType.Library);}refresh(g);var t=enter(g,p,"Roaming Throne");Assert.assertEquals(t.getChosenType(),"Dragon");}
 Game setup(){var g=initAndCreateGame();for(var p:g.getPlayers()){p.setLife(1000,null);for(int i=0;i<25;i++)addCardToZone("Shivan Dragon",p,ZoneType.Library);}return g;}
 void refresh(Game g){g.getAction().checkStateEffects(true);g.getTriggerHandler().resetActiveTriggers();}
 void drain(Game g){for(int i=0;i<100;i++){g.getAction().checkStateEffects(true);g.getStack().addAllTriggeredAbilitiesToStack();if(g.getStack().isEmpty())return;g.getStack().resolveStack();}Assert.fail("Unbounded trigger loop");}
 Card enter(Game g,Player p,String name){var c=addCardToZone(name,p,ZoneType.Hand);var r=g.getAction().moveToPlay(c,null,AbilityKey.newMap());drain(g);return r;}
 long tokens(Player p){return p.getCreaturesInPlay().stream().filter(Card::isToken).count();}
 @Test public void nativeChoiceOwnEntryAndLaterLathlissEntries(){var g=setup();var p=g.getPlayers().get(1);addCard("Lathliss, Dragon Queen",p);refresh(g);var t=enter(g,p,"Roaming Throne");Assert.assertEquals(t.getChosenType(),"Dragon");Assert.assertEquals(tokens(p),2L,"Throne entry must produce two Lathliss tokens");enter(g,p,"Shivan Dragon");Assert.assertEquals(tokens(p),4L,"Later Dragon must produce exactly two tokens");}
 @Test public void ganaxTreasureDoubled(){var g=setup();var p=g.getPlayers().get(1);addCard("Ganax, Astral Hunter",p);refresh(g);enter(g,p,"Roaming Throne");Assert.assertEquals(countCardsWithName(g,"Treasure Token"),2);enter(g,p,"Shivan Dragon");Assert.assertEquals(countCardsWithName(g,"Treasure Token"),4);}
 @Test public void scourgeEntryDamageDoubled(){var g=setup();var p=g.getPlayers().get(1);var o=g.getPlayers().get(0);addCard("Scourge of Valkas",p);refresh(g);enter(g,p,"Roaming Throne");Assert.assertEquals(o.getLife(),996,"Two triggers with two Dragons");}
 @Test public void goldspanAttackTreasureDoubled(){var g=setup();var p=g.getPlayers().get(1);var o=g.getPlayers().get(0);var dragon=addCard("Goldspan Dragon",p);refresh(g);enter(g,p,"Roaming Throne");dragon.setSickness(false);var combat=new forge.game.combat.Combat(p);g.getPhaseHandler().setCombat(combat);combat.addAttacker(dragon,o);forge.game.combat.CombatUtil.checkDeclaredAttacker(g,dragon,combat,true);drain(g);Assert.assertEquals(countCardsWithName(g,"Treasure Token"),2);}
 @Test public void noncreatureEnginesNotDoubled(){var g=setup();var p=g.getPlayers().get(1);var o=g.getPlayers().get(0);addCard("Dragon Tempest",p);var door=addCard("Door of Destinies",p);door.setChosenType("Dragon");refresh(g);enter(g,p,"Roaming Throne");Assert.assertEquals(o.getLife(),999,"One Dragon Tempest damage trigger on Throne entry");enter(g,p,"Shivan Dragon");Assert.assertEquals(o.getLife(),997,"One Tempest trigger counting two Dragons");addCards("Mountain",8,p);refresh(g);var c=addCardToZone("Thunderbreak Regent",p,ZoneType.Hand);var a=c.getFirstSpellAbility();a.setActivatingPlayer(p);p.getController().playChosenSpellAbility(a);drain(g);Assert.assertEquals(door.getCounters(CounterEnumType.CHARGE),1);}
}

