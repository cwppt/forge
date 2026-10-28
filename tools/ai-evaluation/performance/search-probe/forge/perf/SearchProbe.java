package forge.perf;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded observation. Argument/epoch proxies are explicitly NOT full game-state equivalence. */
public final class SearchProbe {
    private static final com.sun.management.ThreadMXBean MX=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    private static final Set<String> transformed=new ConcurrentSkipListSet<>();
    private static final List<String> errors=new CopyOnWriteArrayList<>();
    private static final int EVERY=Integer.getInteger("forge.search.sampleEvery",32),CAP=Integer.getInteger("forge.search.signatureCap",1024);
    private static final ThreadLocal<Long> decision=ThreadLocal.withInitial(()->0L);
    private static volatile Window active;
    private static BufferedWriter writer;
    public static void initialize() {
        try {if(EVERY<1 || CAP<1 || CAP>8192)throw new IOException("Invalid bounded probe configuration");
            writer=Files.newBufferedWriter(Path.of(System.getProperty("forge.search.output").replace("{pid}",String.valueOf(ProcessHandle.current().pid()))),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            Runtime.getRuntime().addShutdownHook(new Thread(()->{try{if(active!=null)error("Process exited inside observation window");write(Map.of("type","AGENT_END","transformedClasses",transformed,"errors",errors));writer.close();}catch(Exception error){error.printStackTrace();}},"search-probe-close"));
        }catch(IOException error){throw new UncheckedIOException(error);}
    }
    public static void transformed(String type){transformed.add(type);}
    public static void error(String error){if(errors.size()<16)errors.add(error);}
    private static synchronized void write(Object value) throws IOException {writer.write(new GsonBuilder().serializeNulls().create().toJson(value));writer.newLine();writer.flush();}
    public static synchronized void beginWindow(String fixture,String path,int repetition) {
        if(active!=null)throw new IllegalStateException("Overlapping search windows");active=new Window(fixture,path,repetition);decision.remove();
    }
    public static synchronized void endWindow() {
        Window w=active;if(w==null)throw new IllegalStateException("No observation window");active=null;
        if(w.open.get()!=0)throw new IllegalStateException("Instrumented worker still active at window end");
        var result=new LinkedHashMap<String,Object>();result.put("type","WINDOW");result.put("case",w.fixture);result.put("path",w.path);result.put("rep",w.rep);
        result.put("sampleEvery",EVERY);result.put("observedMutationEpochs",w.epoch.get());result.put("decisionRoots",w.decisions.get());
        result.put("exactDistinctCombatStates",null);result.put("provenEquivalentPredictionRepeats",null);
        result.put("equivalence","Unproven: signatures include method/arguments/reference identities, thread/decision and observed mutation epoch, not all hidden traits, stack, RNG, budgets or silent hypothetical mutations. Never use them as a cache key.");
        var methods=new TreeMap<String,Object>();w.stats.forEach((k,v)->methods.put(k,v.export()));result.put("methods",methods);
        result.put("signatureCoverageCapped",w.capped.get());result.put("errors",List.copyOf(errors));
        try{write(result);}catch(IOException error){throw new UncheckedIOException(error);}decision.remove();
    }
    public static void mutation(){Window w=active;if(w!=null)w.epoch.incrementAndGet();}
    public static void traversal(String name){Window w=active;if(w!=null)w.stats.computeIfAbsent(name,k->new Stats()).calls.incrementAndGet();}
    private static long cpu(){return MX.isCurrentThreadCpuTimeSupported()?MX.getCurrentThreadCpuTime():0;}
    private static long bytes(){return MX.isThreadAllocatedMemorySupported()?MX.getThreadAllocatedBytes(Thread.currentThread().getId()):0;}
    public static Frame enter(String name,Object receiver,Object[] args) {
        Window w=active;if(w==null)return null;
        Stats s=w.stats.computeIfAbsent(name,k->new Stats());long n=s.calls.getAndIncrement();boolean root=name.contains("AiBlockController.assign") && decision.get()==0;
        if(root)decision.set(w.decisions.incrementAndGet());
        boolean filter=name.contains(".getPossibleBlockers(");boolean sampled=n%EVERY==0;
        if(filter && args.length>2 && args[2] instanceof List<?> list)s.inputPairs.add(list.size());
        if(!sampled && !root && !filter)return null;
        Frame f=new Frame();f.window=w;f.stats=s;f.root=root;f.sampled=sampled;f.filter=filter;
        if(sampled){f.signature=w.signature(name,receiver,args,decision.get());f.cpu=cpu();f.bytes=bytes();f.start=System.nanoTime();}
        w.open.incrementAndGet();return f;
    }
    public static void exit(Frame f,Object result,Throwable error) {
        try {
            if(f.filter && result instanceof List<?> list)f.stats.outputPairs.add(list.size());
            if(error!=null)f.stats.exceptions.increment();
            if(f.sampled) {
                long elapsed=System.nanoTime()-f.start;f.stats.samples.increment();f.stats.wall.add(elapsed);f.stats.cpu.add(Math.max(0,cpu()-f.cpu));f.stats.bytes.add(Math.max(0,bytes()-f.bytes));
                int bucket=elapsed<=0?0:63-Long.numberOfLeadingZeros(elapsed);f.stats.latency[bucket].increment();
                String value=result==null?"null":result instanceof Number || result instanceof Boolean || result instanceof String?String.valueOf(result):result.getClass().getName();
                f.stats.result(f.window,f.signature,value);
            }
        }finally {if(f.root)decision.remove();f.window.open.decrementAndGet();}
    }
    public static final class Frame {Window window;Stats stats;boolean sampled,root,filter;long cpu,bytes,start;String signature;}
    private static final class Window {
        final String fixture,path;final int rep;final AtomicLong epoch=new AtomicLong(),decisions=new AtomicLong();final AtomicInteger open=new AtomicInteger(),signatureCount=new AtomicInteger();final AtomicBoolean capped=new AtomicBoolean();
        final ConcurrentHashMap<String,Stats> stats=new ConcurrentHashMap<>();final List<Object> refs=new ArrayList<>();
        Window(String f,String p,int r){fixture=f;path=p;rep=r;}
        synchronized String signature(String method,Object receiver,Object[] args,long d) {
            StringBuilder s=new StringBuilder(method).append("|thread=").append(Thread.currentThread().getId()).append("|decision=").append(d).append("|eventEpoch=").append(epoch.get());
            s.append("|receiver=").append(arg(receiver,0));
            for(Object a:args)s.append('|').append(arg(a,0));return s.toString();
        }
        String arg(Object a,int depth) {
            if(a==null)return "null";
            if(a instanceof Number || a instanceof Boolean || a instanceof Enum<?>)return a.getClass().getName()+":"+a;
            if(a instanceof String text){if(text.length()>256){capped.set(true);return "truncated-string";}return "string:"+text;}
            if(a instanceof List<?> list && depth<2){if(list.size()>64){capped.set(true);return "truncated-list-size:"+list.size();}StringBuilder s=new StringBuilder("list[");for(Object x:list)s.append(arg(x,depth+1)).append(';');return s.append(']').toString();}
            // Avoid hashCode()/identityHashCode(): observation must not assign identity hashes to game keys.
            for(int i=0;i<refs.size();i++)if(refs.get(i)==a)return a.getClass().getName()+":ref"+i;
            if(refs.size()>=2048){capped.set(true);return "untracked-reference:"+a.getClass().getName();}refs.add(a);return a.getClass().getName()+":ref"+(refs.size()-1);
        }
    }
    private static final class Stats {
        final AtomicLong calls=new AtomicLong();final LongAdder samples=new LongAdder(),wall=new LongAdder(),cpu=new LongAdder(),bytes=new LongAdder(),inputPairs=new LongAdder(),outputPairs=new LongAdder(),exceptions=new LongAdder(),repeated=new LongAdder(),changed=new LongAdder();
        final LongAdder[] latency=new LongAdder[64];final Map<String,String> signatures=new HashMap<>();final List<String> changedExamples=new ArrayList<>();
        Stats(){for(int i=0;i<latency.length;i++)latency[i]=new LongAdder();}
        synchronized void result(Window w,String key,String value){
            if(signatures.containsKey(key)){repeated.increment();if(!Objects.equals(signatures.get(key),value)){changed.increment();if(changedExamples.size()<4)changedExamples.add(key.substring(0,Math.min(400,key.length())));}}
            else if(w.signatureCount.incrementAndGet()<=CAP)signatures.put(key,value);else w.capped.set(true);
        }
        synchronized Object export(){var r=new LinkedHashMap<String,Object>();r.put("calls",calls.get());r.put("timedSamples",samples.sum());r.put("sampledInclusiveWallNs",wall.sum());r.put("sampledInclusiveCpuNs",cpu.sum());r.put("sampledInstrumentedThreadAllocationBytes",bytes.sum());r.put("filterInputPairs",inputPairs.sum());r.put("filterAcceptedPairs",outputPairs.sum());r.put("exceptions",exceptions.sum());r.put("observedArgumentEpochSignaturesLowerBound",signatures.size());r.put("sampledRepeatedArgumentEpochSignatures",repeated.sum());r.put("sampledChangedResultsWithinSignature",changed.sum());r.put("changedResultExamples",List.copyOf(changedExamples));r.put("latencyLog2NsHistogram",Arrays.stream(latency).mapToLong(LongAdder::sum).toArray());return r;}
    }
}
