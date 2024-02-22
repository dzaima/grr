package dz.stat.perf;

import dz.layouts.GdbLayout;
import dz.stat.*;
import dzaima.ui.node.Node;
import dzaima.ui.node.ctx.Ctx;
import dzaima.ui.node.types.*;
import dzaima.utils.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.function.*;

public class PerfStat extends StatGlobal<PerfStatSymbol> {
  public enum LoadMode { SCRIPT, GDB, MANUAL }
  public static void load(Path path, Function<String, String> remap, GdbLayout p, Consumer<PerfStat> got, LoadMode m, Vec<Mapping> initMappings, boolean unrelocate) {
    if (m == LoadMode.SCRIPT) {
      got.accept(PerfDataScript.run(path));
      return;
    }
    PerfDataManualParser.run(path, m, remap, p, initMappings, unrelocate, r -> {
      if (r!=null) got.accept(r);
      else got.accept(PerfDataScript.run(path));
    });
  }
  
  public InsDisp disp = InsDisp.P_FUNC;
  public enum InsDisp {
    COUNT("pn"), P_FUNC("pff"), P_GLOBAL("pfg");
    final String id; InsDisp(String id) { this.id=id; }
  }
  
  public final boolean properTimestamps;
  public final HashMap<Thread, ThreadData> threads = new HashMap<>();
  long totalSampleCount;
  public PerfStat(boolean timestamps) { properTimestamps = timestamps; }
  
  public ThreadData newThread(long pid, long tid) {
    Thread t = new Thread(pid, tid);
    ThreadData d = new ThreadData(t);
    ThreadData prev = threads.put(t, d);
    assert prev==null;
    return d;
  }
  
  public void finish() {
    for (ThreadData d : threads.values()) d.finish();
  }
  
  public static class SymbolData {
    public final HashMap<Long, Loc> addrs = new HashMap<>(); // key is address relative to symbol start 
  }
  public static class Loc implements Comparable<Loc> {
    public final long addr;
    public final LongVec times = new LongVec();
    
    public Loc(long addr) { this.addr = addr; }
    public int compareTo(Loc o) { return Long.compare(addr, o.addr); }
    public void finish() { times.sort(); }
  }
  
  public static class ThreadData { // perf data associated with a specific thread
    public final Thread thr;
    public final HashMap<MappedSymbol, SymbolData> perSymbol = new HashMap<>();
    public ThreadData(Thread thr) { this.thr = thr; }
    
    public void addTime(MappedSymbol s, long rel, long t) {
      SymbolData data = perSymbol.computeIfAbsent(s, s2 -> new SymbolData());
      data.addrs.computeIfAbsent(rel, Loc::new).times.add(t);
    }
    
    public void finish() {
      for (SymbolData l : perSymbol.values()) {
        for (Loc c : l.addrs.values()) c.finish();
      }
    }
  }
  
  public HashMap<BasicSymbol, PerfStatSymbol> groupToBasic() {
    totalSampleCount = 0;
    HashMap<BasicSymbol, HashMap<Long, Integer>> g0 = new HashMap<>();
    for (ThreadData thr : threads.values()) {
      thr.perSymbol.forEach((k, v2) -> {
        HashMap<Long, Integer> m = g0.computeIfAbsent(k.asBasic(), n -> new HashMap<>());
        
        long mv = -1;
        for (long c : Vec.ofCollection(v2.addrs.values()).map(c1 -> c1.addr)) mv = Tools.ulongMin(c, mv);
        
        for (Loc l : v2.addrs.values()) m.merge(l.addr, l.times.sz, Integer::sum);
      });
    }
    
    HashMap<BasicSymbol, PerfStatSymbol> r = new HashMap<>();
    g0.forEach((k, v) -> {
      HashMap<Long, StatInstr> m = new HashMap<>();
      long sum = 0;
      long max = 0;
      for (int n : v.values()) {
        sum+= n;
        max = Math.max(max, n);
      }
      totalSampleCount+= sum;
      
      // v.forEach((k2, n) -> System.out.println(Long.toHexString(k2)+"×"+n+": "+k.name+"+"+k2+" (static sym "+k.addr+")"));
      
      PerfStatSymbol sym = new PerfStatSymbol(this, k, m, sum, max);
      v.forEach((k2, n) -> m.put(k2, new PerfStatInstr(sym, n)));
      sym.finish();
      r.put(k, sym);
    });
    for (PerfStatSymbol c : r.values()) c.score/= totalSampleCount;
    return r;
  }
  
  public String name() {
    return "perf";
  }
  
  private Node node;
  public Node activate(Ctx ctx, Runnable onRefresh) {
    Runnable refresh = () -> {
      configUpdated();
      onRefresh.run();
    };
    node = ctx.make(ctx.gc.getProp("grr.tabs.config.uiPerf").gr());
    
    
    RadioNode dispRadio = (RadioNode) node.ctx.id("pn");
    dispRadio.quietSetTo(disp.id);
    dispRadio.setFn(s -> {
      String id = s.getProp("id").val();
      disp = Vec.of(InsDisp.values()).linearFind(c -> c.id.equals(id));
      refresh.run();
    });
    
    return node;
  }
  
  public void onSelection(Vec<StatInstr> is) {
    if (node==null) return;
    long sum = 0;
    for (StatInstr c : is) sum+= ((PerfStatInstr) c).n;
    
    node.ctx.id("sn").replace(0, new StringNode(node.ctx, Long.toUnsignedString(sum)));
    node.ctx.id("ff").replace(0, new StringNode(node.ctx, StatGlobal.fmtPercent(is.sz==0? 0 : sum*1.0 / ((PerfStatInstr) is.get(0)).sym.sum)));
    node.ctx.id("fg").replace(0, new StringNode(node.ctx, StatGlobal.fmtPercent(sum*1.0 / totalSampleCount)));
  }
  
  public void configUpdated() {
  }
}
