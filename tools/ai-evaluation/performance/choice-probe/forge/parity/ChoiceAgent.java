package forge.parity;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import forge.game.spellability.SpellAbility;
import forge.ai.AiAbilityDecision;
import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.Random;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Diagnostic load-time observation only; never replaces return values or RNGs. */
public final class ChoiceAgent {
    public static void premain(String ignored,Instrumentation inst) {
        ChoiceProbe.initialize();
        new AgentBuilder.Default().disableClassFormatChanges().with(new AgentBuilder.Listener.Adapter(){
            @Override public void onTransformation(TypeDescription t,ClassLoader l,JavaModule m,boolean loaded,DynamicType type){ChoiceProbe.event("TRANSFORM",t.getName());}
            @Override public void onError(String n,ClassLoader l,JavaModule m,boolean loaded,Throwable x){ChoiceProbe.event("ERROR",n+": "+x);}
        }).type(named("forge.ai.ability.CharmAi").or(named("forge.ai.PlayerControllerAi")).or(named("forge.ai.SpellAbilityAi")).or(named("forge.util.MyRandom")).or(named("forge.ai.AiEvaluationDiagnostics")))
        .transform((builder,type,loader,module,domain)-> {
            switch(type.getName()) {
                case "forge.ai.ability.CharmAi":return builder.visit(Advice.to(Charm.class).on(named("checkApiLogic"))).visit(Advice.to(Options.class).on(named("chooseOptionsAi")));
                case "forge.ai.PlayerControllerAi":return builder.visit(Advice.to(Controller.class).on(named("chooseModeForAbility")));
                case "forge.ai.SpellAbilityAi":return builder.visit(Advice.to(Check.class).on(named("canPlayWithSubs")));
                case "forge.ai.AiEvaluationDiagnostics":return builder.visit(Advice.to(BudgetBegin.class).on(named("begin"))).visit(Advice.to(BudgetClose.class).on(named("close")));
                default:return builder.visit(Advice.to(Rng.class).on(named("getRandom")));
            }
        }).installOn(inst);
    }
    public static class Charm {
        @Advice.OnMethodEnter public static long enter(@Advice.Argument(1) SpellAbility sa){return ChoiceProbe.begin("CHARM",sa);}
        @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(@Advice.Enter long id,@Advice.Argument(1) SpellAbility sa,@Advice.Thrown Throwable x){ChoiceProbe.end(id,sa,x);}
    }
    public static class Controller {
        @Advice.OnMethodEnter public static long enter(@Advice.Argument(0) SpellAbility sa){return ChoiceProbe.begin("CONTROLLER",sa);}
        @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(@Advice.Enter long id,@Advice.Argument(0) SpellAbility sa,@Advice.Thrown Throwable x){ChoiceProbe.end(id,sa,x);}
    }
    public static class Options {
        @Advice.OnMethodEnter public static void enter(@Advice.Argument(1) List<?> choices){ChoiceProbe.order(choices);}
    }
    public static class Check {
        @Advice.OnMethodExit public static void exit(@Advice.Argument(1) SpellAbility sa,@Advice.Return AiAbilityDecision result){ChoiceProbe.check(sa,result.willingToPlay());}
    }
    public static class Rng {
        @Advice.OnMethodExit public static void exit(@Advice.Return Random rng){ChoiceProbe.rng(rng);}
    }
    public static class BudgetBegin {
        @Advice.OnMethodExit public static void exit(@Advice.Return Object diagnostics){ChoiceProbe.budgetBegin(diagnostics);}
    }
    public static class BudgetClose {
        @Advice.OnMethodEnter public static void enter(){ChoiceProbe.budgetEnd();}
        @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(){ChoiceProbe.budgetReset();}
    }
}
