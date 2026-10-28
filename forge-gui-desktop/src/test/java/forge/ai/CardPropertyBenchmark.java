package forge.ai;

import com.google.gson.Gson;
import com.sun.management.ThreadMXBean;
import forge.game.card.CardProperty;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import org.testng.annotations.Test;

/** Fixed local inputs; no property-result reuse. Not a replacement for native games. */
public final class CardPropertyBenchmark extends AITest {
    private static volatile long sink;
    @Test public void immutableInputDispatchWorkload() {
        var g=initAndCreateGame();var p=g.getPlayers().get(0);var c=addCard("Runeclaw Bear",p);var source=addCard("Hill Giant",p);
        String[] properties={"YouCtrl","Other","Green","Creature","tapped","token","nonLand","powerGE2"};
        var bean=(ThreadMXBean)ManagementFactory.getThreadMXBean();
        if(bean.isThreadAllocatedMemorySupported())bean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().getId();
        for(int rep=-2;rep<5;rep++) {
            long gc=ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(x->Math.max(0,x.getCollectionTime())).sum();
            long allocation=bean.getThreadAllocatedBytes(thread),cpu=bean.getCurrentThreadCpuTime(),start=System.nanoTime(),checksum=0;
            for(int i=0;i<200000;i++)for(String property:properties)if(CardProperty.cardHasProperty(c,property,p,source,null))checksum++;
            double wall=(System.nanoTime()-start)/1e6,cpuMs=(bean.getCurrentThreadCpuTime()-cpu)/1e6;
            long bytes=bean.getThreadAllocatedBytes(thread)-allocation;sink=checksum;
            var row=new LinkedHashMap<String,Object>();row.put("rep",rep);row.put("warmup",rep<0);row.put("calls",1600000);row.put("checksum",checksum);
            row.put("wallMs",wall);row.put("cpuMs",cpuMs);row.put("allocationBytes",bytes);
            row.put("gcMs",ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(x->Math.max(0,x.getCollectionTime())).sum()-gc);
            System.out.println("RULES_BENCH "+new Gson().toJson(row));
        }
    }
}
