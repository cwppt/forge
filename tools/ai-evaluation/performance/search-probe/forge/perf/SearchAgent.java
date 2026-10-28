package forge.perf;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import java.lang.instrument.Instrumentation;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Observe actual frozen classes at load time; never replace a method's result. */
public final class SearchAgent {
    public static void premain(String ignored,Instrumentation instrumentation) {
        SearchProbe.initialize();
        new AgentBuilder.Default().disableClassFormatChanges()
            .with(new AgentBuilder.Listener.Adapter() {
                @Override public void onTransformation(TypeDescription t,ClassLoader l,JavaModule m,boolean loaded,DynamicType type){SearchProbe.transformed(t.getName());}
                @Override public void onError(String name,ClassLoader l,JavaModule m,boolean loaded,Throwable error){SearchProbe.error(name+": "+error);}
            })
            .type(named("forge.ai.AiBlockController").or(named("forge.ai.ComputerUtilCombat")).or(named("forge.ai.ComputerUtilCard"))
                .or(named("forge.ai.simulation.GameCopier")).or(named("forge.game.combat.CombatUtil")).or(named("forge.game.Game"))
                .or(named("forge.game.combat.Combat")).or(named("forge.game.card.CardCopyService")).or(named("forge.ai.AiCache")).or(named("forge.perf.ProbeFixture")))
            .transform((builder,type,loader,module,domain)-> {
                net.bytebuddy.matcher.ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription> selected=type.getName().equals("forge.ai.AiBlockController")?
                    nameStartsWith("assign").or(nameStartsWith("make")).or(nameStartsWith("getPossibleBlockers")).or(nameStartsWith("getSafeBlockers")).or(nameStartsWith("getKillingBlockers")).or(named("sortPotentialAttackers")):
                    type.getName().equals("forge.ai.ComputerUtilCombat")?
                        nameStartsWith("predict").or(nameStartsWith("canDestroy")).or(named("canGainKeyword")).or(nameStartsWith("lifeIn")).or(named("getEnoughDamageToKill")):
                    type.getName().equals("forge.ai.ComputerUtilCard")?named("evaluateCreature"):
                    type.getName().equals("forge.ai.simulation.GameCopier")?named("makeCopy").or(named("copyGameState")):
                    type.getName().equals("forge.game.combat.CombatUtil")?nameStartsWith("canBlock").or(named("mustBlockAnAttacker")):
                    type.getName().equals("forge.game.Game")?named("copyLastState"):
                    type.getName().equals("forge.game.card.CardCopyService")?nameStartsWith("getLKI").or(named("copyCard")):
                    type.getName().equals("forge.ai.AiCache")?named("getCached").or(named("clear")):
                    type.getName().equals("forge.perf.ProbeFixture")?named("predict").or(named("getPossibleBlockers")).or(named("copyGameState")):none();
                return builder.visit(Advice.to(Value.class).on(isMethod().and(selected).and(not(returns(void.class)))))
                    .visit(Advice.to(VoidCall.class).on(isMethod().and(selected).and(returns(void.class))))
                    .visit(Advice.to(Epoch.class).on(isMethod().and(type.getName().equals("forge.game.Game")?named("fireEvent"):
                        type.getName().equals("forge.game.combat.Combat")?nameStartsWith("add").or(nameStartsWith("remove")).or(nameStartsWith("clear")):none())))
                    .visit(Advice.to(Traversal.class).on(isMethod().and(type.getName().equals("forge.game.Game")?named("getCardsIn"):none())))
                    .visit(Advice.to(Constructed.class).on(isConstructor().and(type.getName().equals("forge.game.Game") || type.getName().equals("forge.ai.simulation.GameCopier") || type.getName().equals("forge.game.card.CardCopyService")?any():none())));
            }).installOn(instrumentation);
    }
    public static class Value {
        @Advice.OnMethodEnter public static SearchProbe.Frame enter(@Advice.Origin("#t.#m#d") String method,@Advice.This(optional=true) Object receiver,@Advice.AllArguments Object[] args){return SearchProbe.enter(method,receiver,args);}
        @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(@Advice.Enter SearchProbe.Frame frame,@Advice.Return(typing=Assigner.Typing.DYNAMIC) Object result,@Advice.Thrown Throwable error){if(frame!=null)SearchProbe.exit(frame,result,error);}
    }
    public static class VoidCall {
        @Advice.OnMethodEnter public static SearchProbe.Frame enter(@Advice.Origin("#t.#m#d") String method,@Advice.This(optional=true) Object receiver,@Advice.AllArguments Object[] args){return SearchProbe.enter(method,receiver,args);}
        @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(@Advice.Enter SearchProbe.Frame frame,@Advice.Thrown Throwable error){if(frame!=null)SearchProbe.exit(frame,null,error);}
    }
    public static class Epoch {@Advice.OnMethodEnter public static void enter(){SearchProbe.mutation();}}
    public static class Traversal {@Advice.OnMethodEnter public static void enter(@Advice.Origin("#t.#m#d") String method){SearchProbe.traversal(method);}}
    public static class Constructed {@Advice.OnMethodExit public static void exit(@Advice.Origin("#t.#m#d") String method){SearchProbe.traversal(method);}}
}
