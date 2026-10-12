package forge.ai;

import com.google.gson.*;
import forge.ai.decision.*;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class BlockerTacticalProjectionTest extends AITest {
    record Fixture(Game game, Player defender, Player enemy, Combat combat, Card attacker, Card blocker) { }
    private Fixture fixture(String attackerName, String blockerName, int life) {
        Game game = initAndCreateGame();
        Player defender = game.getPlayers().get(1), enemy = game.getPlayers().get(0);
        defender.setLife(life, null); fillLibrary(defender, 8); fillLibrary(enemy, 8);
        Card attacker = addCard(attackerName, enemy), blocker = addCard(blockerName, defender);
        attacker.setSickness(false); blocker.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, enemy);
        Combat combat = new Combat(enemy); game.getPhaseHandler().setCombat(combat);
        combat.addAttacker(attacker, defender);
        return new Fixture(game, defender, enemy, combat, attacker, blocker);
    }
    private CombatBlockOptionView.TacticalConsequences block(Fixture f) {
        return BlockerTacticalProjection.project(f.defender(), f.combat(),
                List.of(new ExternalCombatBlockers.Assignment(f.blocker(), f.attacker())));
    }
    @Test public void safeLifeChumpExplainsLossWithoutChangingDeclaration() {
        Fixture f = fixture("Hill Giant", "Runeclaw Bear", 20);
        var p=block(f);
        Assert.assertNotNull(p); Assert.assertEquals(p.projectedCreatureLossesSelf(), 1);
        Assert.assertEquals(p.projectedCreatureLossesOpponent(), 0);
        Assert.assertEquals(p.projectedDamagePrevented(), 3); Assert.assertEquals(p.projectedLifeAfterCombat(), 20);
        Assert.assertTrue(p.unfavorableTrade()); Assert.assertTrue(p.chumpBlock()); Assert.assertTrue(p.unnecessaryLosingBlock());
        Assert.assertFalse(p.preventsLethal()); Assert.assertFalse(p.nearLethalProtection());
        Assert.assertTrue(f.combat().getBlockers(f.attacker()).isEmpty());
        Assert.assertEquals(f.defender().getLife(), 20); Assert.assertTrue(f.blocker().isInPlay());
    }
    @Test public void lethalPreventionIsMaterialEvenWhenBlockerDies() {
        Fixture f=fixture("Hill Giant", "Runeclaw Bear", 3); var p=block(f);
        Assert.assertTrue(p.preventsLethal()); Assert.assertFalse(p.unnecessaryLosingBlock());
        Assert.assertFalse(p.unfavorableTrade());
        Assert.assertEquals(p.projectedLifeAfterCombat(), 3);
        var pass=BlockerTacticalProjection.project(f.defender(), f.combat(), List.of());
        Assert.assertEquals(pass.projectedLifeAfterCombat(), 0);
        Assert.assertFalse(pass.preservesBoard());
    }
    @Test public void nearLethalUsesIncomingDamageRelativeToRemainingLife() {
        Fixture f=fixture("Hill Giant", "Runeclaw Bear", 5); var p=block(f);
        Assert.assertTrue(p.nearLethalProtection()); Assert.assertFalse(p.preventsLethal());
        Assert.assertFalse(p.unnecessaryLosingBlock());
        Assert.assertFalse(p.unfavorableTrade());
    }
    @Test public void profitableTradeIsNotUnnecessary() {
        Fixture f=fixture("Colossal Dreadmaw", "Runeclaw Bear", 20);
        f.blocker().setBasePower(6); var p=block(f);
        Assert.assertEquals(p.projectedCreatureLossesSelf(), 1); Assert.assertEquals(p.projectedCreatureLossesOpponent(), 1);
        Assert.assertFalse(p.unfavorableTrade()); Assert.assertFalse(p.unnecessaryLosingBlock());
    }
    @Test public void profitableMultiBlockRemainsVisibleWithoutPairwiseGuess() {
        Fixture f=fixture("Hill Giant", "Runeclaw Bear", 20); Card second=addCard("Grizzly Bears", f.defender());
        f.combat().addBlocker(f.attacker(), f.blocker()); f.combat().addBlocker(f.attacker(), second);
        var prepared=ExternalCombatBlockers.prepare(f.defender(), f.combat(), "multi", 8);
        Assert.assertNotNull(prepared); Assert.assertEquals(prepared.options().get(0).view().blockerCount(), 2);
        Assert.assertNull(prepared.options().get(0).view().tacticalConsequences());
        Assert.assertTrue(prepared.context().options().stream().anyMatch(o -> o.optionId().equals("NO_BLOCK")));
    }
    @DataProvider(name="engineCases") public Object[][] engineCases() {
        return new Object[][] {
            {3,3,2,2,"","",3,1,0}, {6,6,4,4,"Trample","",4,1,0},
            {6,6,4,4,"Trample & Deathtouch","",1,1,0},
            {3,3,3,2,"","First strike",3,0,1},
            {3,3,2,2,"","First strike",3,1,0},
            {6,6,1,1,"","Deathtouch",6,1,1},
            {6,6,1,1,"Trample","First strike & Deathtouch",6,0,1},
            {3,3,2,2,"First strike","Deathtouch",3,1,0},
            {2,2,4,4,"","",2,0,1}
        };
    }
    @Test(dataProvider="engineCases") public void projectionMatchesActualForgeDamageAndDeaths(
            int ap,int at,int bp,int bt,String ak,String bk,int prevented,int ownDeaths,int enemyDeaths) {
        Fixture f=fixture("Hill Giant", "Runeclaw Bear", 20);
        f.attacker().setBasePower(ap); f.attacker().setBaseToughness(at);
        f.blocker().setBasePower(bp); f.blocker().setBaseToughness(bt);
        for (String k:ak.split(" & ")) if(!k.isEmpty()) f.attacker().addIntrinsicKeyword(k);
        for (String k:bk.split(" & ")) if(!k.isEmpty()) f.blocker().addIntrinsicKeyword(k);
        var p=block(f); Assert.assertNotNull(p);
        Assert.assertEquals(p.projectedDamagePrevented(), prevented);
        Assert.assertEquals(p.projectedCreatureLossesSelf(), ownDeaths);
        Assert.assertEquals(p.projectedCreatureLossesOpponent(), enemyDeaths);
        f.combat().addBlocker(f.attacker(),f.blocker()); f.combat().setBlocked(f.attacker(), true);
        f.combat().orderBlockersForDamageAssignment(); f.combat().orderAttackersForDamageAssignment();
        for(boolean first:List.of(true,false)) {
            f.game().getPhaseHandler().devModeSet(first?PhaseType.COMBAT_FIRST_STRIKE_DAMAGE:PhaseType.COMBAT_DAMAGE, f.enemy(),false,8);
            if(f.combat().assignCombatDamage(first)) f.combat().dealAssignedDamage();
            f.game().getAction().checkStateEffects(true); f.combat().removeAbsentCombatants();
        }
        Assert.assertEquals(f.defender().getLife(), p.projectedLifeAfterCombat());
        Assert.assertEquals(f.blocker().isInPlay(),ownDeaths==0); Assert.assertEquals(f.attacker().isInPlay(),enemyDeaths==0);
    }
    @Test public void deathTriggersAndUnsupportedKeywordsOmitUncertainProjection() {
        Assert.assertNull(block(fixture("Hill Giant","Mistmoon Griffin",20)));
        Fixture f=fixture("Hill Giant","Runeclaw Bear",20); f.attacker().addIntrinsicKeyword("Double strike");
        Assert.assertNull(block(f));
    }
    @Test public void defendingPlaneswalkerDoesNotBecomePlayerLifeProjection() {
        Fixture f=fixture("Hill Giant","Runeclaw Bear",20);
        Combat combat=new Combat(f.enemy());
        combat.addAttacker(f.attacker(),addCard("Jace Beleren",f.defender()));
        Assert.assertNull(BlockerTacticalProjection.project(f.defender(),combat,
                List.of(new ExternalCombatBlockers.Assignment(f.blocker(),f.attacker()))));
    }
    @Test public void annotationEntersFingerprintButNotOptionIdMaterial() {
        Fixture f=fixture("Hill Giant","Runeclaw Bear",20);
        var a=ExternalCombatBlockers.prepare(f.defender(),f.combat(),"first",8);
        var b=ExternalCombatBlockers.prepare(f.defender(),f.combat(),"second",8);
        Assert.assertEquals(a.context().stateFingerprint(),b.context().stateFingerprint());
        Assert.assertEquals(a.context().options(),b.context().options());
        var stripped=a.context().options().stream().map(o -> new CombatBlockOptionView(o.optionId(),o.assignments(),o.blockerCount(),o.blockedAttackerCount())).toList();
        Assert.assertEquals(stripped.stream().map(CombatBlockOptionView::optionId).toList(),a.context().options().stream().map(CombatBlockOptionView::optionId).toList());
        Gson canonical=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
        for(var option:a.context().options()) if(!option.assignments().isEmpty()) {
            String oldMaterial=canonical.toJson(a.context().state())+canonical.toJson(option.assignments());
            String oldId="OPT_"+java.util.HexFormat.of().withUpperCase().formatHex(
                    digest(oldMaterial)).substring(0,24);
            Assert.assertEquals(option.optionId(),oldId,"The pre-annotation ID algorithm is preserved");
        }
        Assert.assertNotEquals(ExternalCombatBlockers.fingerprint(a.context().state(),stripped),a.context().stateFingerprint());
    }
    private byte[] digest(String text) {
        try {return java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        catch(java.security.NoSuchAlgorithmException ex) {throw new AssertionError(ex);}
    }
    private Path root() {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null&&!Files.exists(root.resolve("tools/ai-evaluation/fixtures/stage14-2-blockers.json")))root=root.getParent();
        return Objects.requireNonNull(root);
    }
    @Test public void reviewedPublicStatesExportWithoutChangingIdsOrOrder() throws Exception {
        Path root=root(); Gson gson=new GsonBuilder().disableHtmlEscaping().create();
        String extra=System.getProperty("forge.stage14_2.input");
        Path input=extra==null?root.resolve("tools/ai-evaluation/fixtures/stage14-2-blockers.json"):Path.of(extra);
        JsonArray fixtures=JsonParser.parseString(Files.readString(input)).getAsJsonArray();
        JsonArray exports=new JsonArray(); int targets=0, annotatedTargets=0;
        for(var element:fixtures) {
            JsonObject entry=element.getAsJsonObject();
            CombatBlockersDecisionContext original=gson.fromJson(entry.get("context"),CombatBlockersDecisionContext.class);
            Game game=initAndCreateGame(); Map<String,Card> cards=new HashMap<>();
            for(int seat=0;seat<original.state().players().size();seat++) {
                var playerState=original.state().players().get(seat); Player player=game.getPlayers().get(seat);
                player.setLife(playerState.life(),null);
                for(var permanent:playerState.battlefield()) {
                    var view=permanent.card();
                    Card card=addCard(view.name().endsWith(" Token") ? "Grizzly Bears" : view.name(),player);
                    if(view.power()!=null) {card.setBasePower(view.power());card.setBaseToughness(view.toughness());}
                    if(view.markedDamage()!=null) card.setDamage(view.markedDamage());
                    card.setTapped(view.tapped()); card.setSickness(permanent.summoningSick());
                    // Current effective P/T and visible keywords are frozen; no triggers are executed.
                    cards.put(permanent.permanentId(),card);
                }
            }
            Player defender=game.getPlayers().get(entry.get("seat").getAsInt()), enemy=game.getPlayers().get(1-entry.get("seat").getAsInt());
            game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS,enemy);
            Combat combat=new Combat(enemy);game.getPhaseHandler().setCombat(combat);
            original.state().attackers().forEach(a -> combat.addAttacker(cards.get(a.permanent().permanentId()),defender));
            List<CombatBlockOptionView> options=new ArrayList<>();
            for(var option:original.options()) {
                var assignments=option.assignments().stream().map(a -> new ExternalCombatBlockers.Assignment(cards.get(a.blockerId()),cards.get(a.attackerId()))).toList();
                options.add(new CombatBlockOptionView(option.optionId(),option.assignments(),option.blockerCount(),option.blockedAttackerCount(),BlockerTacticalProjection.project(defender,combat,assignments)));
            }
            var revised=new CombatBlockersDecisionContext(original.decisionId(),ExternalCombatBlockers.fingerprint(original.state(),options),original.state(),options);
            Assert.assertEquals(revised.options().stream().map(CombatBlockOptionView::optionId).toList(),original.options().stream().map(CombatBlockOptionView::optionId).toList());
            if(entry.get("targeted").getAsBoolean()) {
                targets++;
                var selected=options.stream().filter(o -> o.optionId().equals(entry.get("originalChoice").getAsString())).findFirst().orElseThrow();
                if(selected.tacticalConsequences()!=null) {
                    annotatedTargets++;
                    Assert.assertTrue(selected.tacticalConsequences().unnecessaryLosingBlock(),entry.get("audit")+" T"+entry.get("turn"));
                }
            }
            JsonObject exported=entry.deepCopy();exported.add("revisedContext",gson.toJsonTree(revised));exports.add(exported);
        }
        if(extra==null) {
            Assert.assertEquals(targets,7);Assert.assertEquals(fixtures.size(),21);
            Assert.assertTrue(annotatedTargets>0,"Known conservative projections must be exercised; uncertain historical states may omit annotations");
        } else Assert.assertTrue(fixtures.size()>0);
        Path output=Path.of(System.getProperty("forge.stage14_2.output",root.resolve("work/stage14-2/frozen-projections.json").toString()));Files.createDirectories(output.getParent());
        Files.writeString(output,new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(exports));
    }
}
