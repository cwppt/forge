package forge.ai;
import org.testng.Assert;
import org.testng.annotations.Test;

public final class BenchmarkMetricsCompatibilityTest {
    public static final class Valid {static int calls;public static long[] snapshot(){calls++;return new long[]{3,4};}}
    public static final class Invalid {public static long[] snapshot(){return new long[]{3};}}
    public static final class Broken {public static long[] snapshot(){throw new IllegalStateException("fixture-provider-error");}}
    @Test public void disabledCountersDoNotInvokeProvider(){Valid.calls=0;Assert.assertNull(BenchmarkOptionalMetrics.read(Valid.class.getName(),false));Assert.assertEquals(Valid.calls,0);}
    @Test public void absentProviderIsUnavailableNotZero(){Assert.assertNull(BenchmarkOptionalMetrics.read("forge.ai.MissingBenchmarkCounterProvider",true));}
    @Test public void presentProviderIsRead(){Assert.assertEquals(BenchmarkOptionalMetrics.read(Valid.class.getName(),true),new long[]{3,4});}
    @Test(expectedExceptions=IllegalStateException.class) public void invalidCounterSchemaStillFails(){BenchmarkOptionalMetrics.read(Invalid.class.getName(),true);}
    @Test(expectedExceptions=IllegalStateException.class) public void brokenPresentProviderStillFails(){BenchmarkOptionalMetrics.read(Broken.class.getName(),true);}
    @Test public void actualEngineCapabilityIsReportedWithoutShadowClasses() throws Exception {
        String name="forge.game.combat.RequirementSnapshotMetrics";long[] counts=BenchmarkOptionalMetrics.read(name,true);
        boolean present;try{Class.forName(name);present=true;}catch(ClassNotFoundException absent){present=false;}
        Assert.assertEquals(counts!=null,present);if(counts!=null)Assert.assertEquals(counts.length,2);
    }
}
