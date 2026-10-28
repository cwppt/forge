package forge.perf;
import java.util.*;
/** Agent self-test: count real calls, preserve returns/exceptions, cap signatures. */
public final class ProbeFixture {
    public static int predict(int value){if(value<0)throw new IllegalArgumentException("unchanged exception");return value*2;}
    public static List<Integer> getPossibleBlockers(Object a,Object b,List<Integer> input,boolean ignored){return input.subList(0,1);}
    public static void copyGameState(){ }
    public static void main(String[] args){
        SearchProbe.beginWindow("self-test","block",0);
        for(int i=0;i<128;i++)if(predict(7)!=14)throw new AssertionError("Return changed");
        try{predict(-1);throw new AssertionError("Exception swallowed");}catch(IllegalArgumentException expected){ }
        if(getPossibleBlockers(null,null,List.of(1,2,3),false).size()!=1)throw new AssertionError();copyGameState();
        SearchProbe.mutation();predict(7);SearchProbe.endWindow();System.out.println("SEARCH_PROBE_SELF_TEST_PASS");
    }
}
