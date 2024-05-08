package dz.layouts;

import dz.Main;
import dz.debugger.Location;
import dz.gdb.*;
import dz.gdb.ProcThread.StackFrame;
import dz.general.*;
import dz.general.arch.*;
import dz.stat.*;
import dz.general.java.JavaPrintAssembly;
import dz.stat.perf.PerfStat;
import dz.stat.valgrind.CachegrindData;
import dz.tabs.*;
import dz.tabs.GdbTab.Mode;
import dz.ui.AsmListNode;
import dz.utils.Promise;
import dzaima.ui.node.Node;
import dzaima.ui.node.prop.Prop;
import dzaima.ui.node.types.tabs.*;
import dzaima.utils.*;
import dzaima.utils.options.*;

import java.nio.file.*;
import java.util.*;
import java.util.function.*;

public abstract class GdbLayout extends Layout {
  public Dbi d;
  public Arch arch = X86_64.inst;
  public boolean disableInternalLog = false;
  
  public GdbLayout(Main m) {
    super(m);
  }
  
  public HashMap<String, Function<HashMap<String, Prop>, Tab>> getCtors() {
    HashMap<String, Function<HashMap<String, Prop>, Tab>> ctors = new HashMap<>();
    BiConsumer<String, Function<HashMap<String, Prop>, Tab>> add = (s, f) -> {
      ctors.put(s, g -> tabs.add((GrrTab<?>) f.apply(g)));
    };
    add.accept("log", g -> GdbTab.deserialize(this, g));
    add.accept("asm", g -> new AsmTab(this));
    add.accept("source", g -> SourceTab.deserialize(this, g));
    add.accept("timelineManager", g -> TimelineManagerTab.deserialize(this, g));
    add.accept("perfsyms", g -> new PerfListTab(this));
    add.accept("config", g -> ConfigTab.deserialize(this, g));
    add.accept("sourceStack", g -> new SourceMapStackTab(this));
    if (this instanceof DebuggerLayout) {
      DebuggerLayout l = (DebuggerLayout) this;
      add.accept("breakpoints", g -> new BreakpointsTab(l));
      add.accept("checkpoints", g -> new CheckpointTab(l));
      add.accept("memory", g -> new MemoryTab(l, false));
      add.accept("history", g -> new PastTab(l));
      add.accept("registers", g -> new RegisterTab(l));
      add.accept("stack", g -> new StackTab(l));
      add.accept("threads", g -> new ThreadListTab(l));
      add.accept("timeline", g -> new TimelineTab(l));
    }
    return ctors;
  }
  
  public int maxDebugWidth = 2000;
  public Vec<Pair<String,String>> remap = new Vec<>();
  protected void dbiInit(Options o) {
    d.addOutputListener((s, b) -> {
      for (GrrTab<?> c : tabs) c.onGdbPrint(s, b);
    });
    d.addRawLogListener((s, b) -> {
      if (disableInternalLog) return;
      String s1 = s.length() > maxDebugWidth? s.substring(0, maxDebugWidth) : s;
      String m = (b?"  ":"")+s1+"\n";
      addRawOut(m);
    });
    d.addBreakpointUpdateListener(() -> {
      for (GrrTab<?> c : tabs) c.onModifiedBreakpoints();
    });
    d.addArchListener(a -> {
      if (a.startsWith("riscv:")) { setArch(RISCV.inst); return; }
      if (a.startsWith("i386")) { setArch(X86_64.inst); return; }
      switch (a) {
        default: setArch(GenericArch.inst); break;
        case "aarch64": setArch(AArch64.inst); break;
        case "i8086": setArch(X86_64.inst); break;
        case "auto": break;
      }
    });
    
    for (OptionItem c : o.optList("--move")) {
      int i = c.v.indexOf(':');
      if (i==-1) Main.fail("--move argument didn't contain ':'");
      String p = c.v.substring(0, i);
      String n = c.v.substring(i+1);
      remap.add(new Pair<>(p, n));
    }
  }
    
  protected void layoutInit(Options o) {
    Vec<OptionItem> lx = o.optList("-x");
    Vec<OptionItem> li = o.optList("--icmd");
    for (OptionItem c : OptionList.merge(lx, li)) {
      if (c.k.equals("-x")) d.p.consoleCmd(c.v).run();
      else runCommand(true, c.v);
    }
  }
  
  private Promise<Arch.Registers> currArchRegs;
  public void requestArchRegs(Consumer<Arch.Registers> then) {
    if (currArchRegs==null) currArchRegs = Promise.create(p -> d.listRegisters(r -> {
      if (r==null) {
        Log.warn("arch", "Failed to get register names");
        p.then(null);
        return;
      }
      p.set(new Arch.Registers(arch, r));
    }));
    currArchRegs.then(then);
  }
  public void setArch(Arch a) {
    arch = a;
    currArchRegs = null;
    for (GrrTab<?> c : tabs) c.onNewArch();
  }
  
  public void preEventTick() {
    d.tick();
  }
  public void tick() {
    super.tick();
    d.tick();
  }
  
  public abstract void getDisas(Binary bin, Location frame, Consumer<DisasFn> res);
  
  
  public final FnCache cache = new FnCache();
  public void getDisasDirect(Location l, FnCache.NameMode nameMode, Consumer<DisasFn> res) {
    cache.disas(-1, d, l, nameMode, res, d.isRR()); // TODO don't -1 if possible and get things working properly
  }
  public Location cachedJITLocation(Location l) {
    DisasFn d = cache.getJIT(-1, l);
    if (d==null) return null;
    return new Location(l.addr, d.name, null, null, "JIT");
  }
  public void injectCustomSource(DisasFn fn, Consumer<DisasFn> res) {
    if (fn==null) {
      res.accept(null);
      return;
    }
    if (javaMach!=null) {
      JavaPrintAssembly.JSym sym = javaMach.findByName(fn.name);
      if (sym!=null) {
        if (fn.ins == null) {
          sym.insFrom(d.curr, r -> {
            for (DisasFn.ParsedIns c : r) c.map = sym.findSource(c.s);
            res.accept(new DisasFn(fn.s, fn.e, fn.name, r, fn.jit, fn.forceCfg));
          });
          return;
        } else {
          for (DisasFn.ParsedIns c : fn.ins) c.map = sym.findSource(c.s);
        }
      }
    }
    res.accept(fn);
  }
  public Consumer<DisasFn> sourceInjector(Consumer<DisasFn> f) {
    return r -> injectCustomSource(r, f);
  }
  
  public void stopped() {
    d.exit();
    d.tick();
  }
  
  public void selectFrame(StackFrame f) {
    fViewed = f;
    fGdb = null; // to allow reselecting if it was changed in gdb
    for (GrrTab<?> t : tabs) {
      if (f==null) t.onSelectedFunction(null, false, false);
      else t.onSelectedFunction(f.l, false, f.afterCall);
    }
  }
  public void selectThread(ProcThread thr) {
    for (GrrTab<?> t : tabs) t.onSelectedThread(thr);
  }
  
  public void selectSourceMap(DisasFn.SourceMap map, String bin) {
    for (GrrTab<?> t : tabs) t.onSelectedSourceMap(map, bin);
  }
  public void selectSourceMapStack(DisasFn.SourceMap map, String bin) {
    for (GrrTab<?> t : tabs) t.onSelectedSourceMapStack(map, bin);
    selectSourceMap(map, bin);
  }
  public void hoverHighlightSource(Vec<Location> ls) {
    forTabs(SourceTab.class, t -> t.setHover(ls));
  }
  
  public void checkpointsUpdated() {
    forTabs(TimelineTab.class, c -> c.node.mRedraw());
  }
  
  private final HashMap<Long, Vec<DisasFn>> triedPids = new HashMap<>();
  public Vec<DisasFn> loadJITMap(long pid) {
    return triedPids.computeIfAbsent(pid, p -> loadJITMap(pid, Paths.get("/tmp/perf-"+p+".map")));
  }
  public Vec<DisasFn> loadJITMap(long pid, Path path) {
    if (!Files.exists(path)) return Vec.of();
    try {
      Vec<DisasFn> res = new Vec<>();
      for (String l : Files.readAllLines(path)) {
        int o1 = l.indexOf(' ');
        long start = GdbFormat.parseHex(l.substring(0, o1));
        int o2 = l.indexOf(' ', o1+1);
        long len = GdbFormat.parseHex(l.substring(o1+1, o2));
        String name = l.substring(o2+1);
        if (len!=0) res.add(cache.addJITRange(pid, start, start+len, "pid "+pid+": "+name));
      }
      return res;
    } catch (Throwable t) {
      Log.stacktrace("perf map read", t);
      System.err.println("Failed to read JIT map");
      return Vec.of();
    }
  }
  
  public String remap(String p) {
    if (p==null) return null;
    for (Pair<String, String> c : remap) {
      if (p.startsWith(c.a)) return c.b+p.substring(c.a.length());
    }
    return p;
  }
  
  
  
  protected StackFrame fViewed, fGdb;
  public void runCommand(boolean full, String s) {
    if (full && s.startsWith("grr ")) {
      icmdOut(s+"\n");
      try {
        s = s.substring(4);
        if (s.equals("exit")) {
          m.closeOnNext();
        }
        else if (s.startsWith("maxwidth ")) {
          maxDebugWidth = Integer.parseInt(s.substring(9));
        }
        else if (s.startsWith("stackend")) {
          StackTab.showFullStack^= true;
        }
        else if (s.equals("arch")) {
          icmdOut(arch.getClass().getSimpleName());
        }
        else if (s.startsWith("arch ")) {
          switch (s.substring(5)) {
            case "x86-64": setArch(X86_64.inst); break;
            case "aarch64": setArch(AArch64.inst); break;
            case "riscv": case "risc-v": setArch(RISCV.inst); break;
            case "generic": setArch(GenericArch.inst); break;
            default: throw new IllegalArgumentException("invalid architecture \""+s.substring(8)+"\"");
          }
        }
        else if (s.startsWith("file ")) {
          Executable e = d.makeExe(Paths.get(s.substring(5)));
          d.toExe(e, b -> { if (!b) icmdOut("Failed to open file"); });
        }
        else if (s.equals("assume-file")) {
          d.assumeExe(d.makeExe(null));
        }
        else if (s.equals("assume-break")) {
          if (d.curr == null) d.assumeExe(d.makeExe(null));
          d.p.setStatus(Dbi.Status.PAUSED);
        }
        else if (s.equals("layout")) {
          Node n = node.ctx.id("tree").ch.get(0);
          String b = SerializableTab.serializeTree(n);
          System.out.println(b);
        }
        else if (s.startsWith("start ")) {
          Executable e = d.makeExe(Paths.get(s.substring(6)));
          d.toExe(e, b -> {
            if (!b) icmdOut("Failed to open file");
            else e.open(new String[0], null);
          });
        }
        else icmdOut("unknown grr command\n");
      } catch (Throwable e) { icmdOut("error: "+e.getClass().getName()+": "+e.getMessage()+"\n"); }
    } else {
      if (fGdb != fViewed) {
        if (fViewed!=null) d.p.cmd("-stack-select-frame", fViewed.level).ds().run();
        fGdb = fViewed;
      }
      if (full) d.p.cmd(s).run();
      else d.p.cmd("-interpreter-exec", "console", s).run();
    }
  }
  
  private void icmdOut(String s) {
    addRawOut(s);
  }
  public void addRawOut(String s) {
    forTabs(GdbTab.class, t -> {
      if (t.mode==Mode.RAW) t.append(s);
    });
  }
  
  
  
  public StatGlobal<?> statGlobal;
  public HashMap<StatGlobal.BasicSymbol, ? extends StatSymbol> statSymbol;
  private HashMap<String, StatSymbol> statSymbolString; // for basic lookup by Location
  protected JavaPrintAssembly javaMach;
  
  protected void loadSampleData(Options o, String bin, boolean unrelocate) {
    if (o.has("--jvm-out")) {
      Path p = Path.of(o.reqOne("--jvm-out"));
      javaMach = JavaPrintAssembly.load(p);
      if (javaMach != null) {
        for (JavaPrintAssembly.JSym c : javaMach.syms) {
          cache.addJITRange(-1, c.addrS, c.addrE, c.name);
        }
      }
    }
    
    if (o.has("--perf")) {
      Path p = Path.of(o.reqOne("--perf"));
      if (Files.isDirectory(p)) p = p.resolve("perf.data");
      
      Vec<StatGlobal.Mapping> mappings = new Vec<>();
      
      PerfStat.LoadMode mode;
      String exe = o.optOne("--perf-extra-exe");
      if (exe!=null) {
        mappings.add(new StatGlobal.Mapping(new Binary(exe, exe, false), 0, Long.MAX_VALUE, 0));
      }
      if (javaMach!=null) {
        for (JavaPrintAssembly.JSym c : javaMach.syms) {
          mappings.add(new StatGlobal.Mapping(Binary.virtSym(c.id, c.name), c.addrS, c.addrE, 0));
        }
      }
      String m = o.optOne("--perf-symb");
      switch (m==null? "manual" : m) {
        default: throw Main.failR("Invalid --perf-symb mode. Supported: 'manual', 'gdb', 'script'");
        case "manual": mode = PerfStat.LoadMode.MANUAL; break;
        case "gdb": mode = PerfStat.LoadMode.GDB; break;
        case "script": mode = PerfStat.LoadMode.SCRIPT; break;
      }
      
      PerfStat.load(p, this::remap, this, this::addStatSource, mode, mappings, unrelocate);
    }
    
    if (o.has("--cachegrind")) {
      if (bin==null) throw Main.failR("Must provide binary for \"--cachegrind\"");
      Path inp = Paths.get(o.reqOne("--cachegrind"));
      CachegrindData.load(inp, Paths.get(bin), this, r -> {
        if (r==null) return;
        if (r.a!=null) addStatSource(r.a);
        if (r.b!=null) addStatSource(r.b);
      });
    }
  }
  
  public final Vec<StatGlobal<?>> allStatSources = new Vec<>();
  public void addStatSource(StatGlobal<?> d) {
    if (d!=null) {
      allStatSources.add(d);
      for (GrrTab<?> c : tabs) c.onAddedStatSource(d);
      if (allStatSources.sz==1) setCurrentStat(d);
    }
  }
  
  public void setCurrentStat(StatGlobal<?> newData) {
    if (newData==null) {
      Log.error("sample load", "Failed to load sample data");
      return;
    }
    statGlobal = newData;
    for (GrrTab<?> c : tabs) c.onSelectedStatSource();
    refreshCurrentStat();
  }
  public void refreshCurrentStat() {
    statSymbol = statGlobal.groupToBasic();
    statSymbolString = new HashMap<>();
    statSymbol.forEach((k, v) -> statSymbolString.put(k.name, v));
    
    for (GrrTab<?> c : tabs) c.onStatRefresh();
  }
  public boolean statLoaded() {
    return statGlobal!=null;
  }
  
  public StatSymbol getPerfSymbol(Location l) {
    if (!statLoaded()) return null;
    if (l.sym!=null) {
      StatSymbol r = statSymbolString.get(l.sym);
      if (r!=null) return r;
    }
    return null;
    // return filteredSymbols.findFirstFrom(l.addr);
  }
  
  
  public AsmListNode.AsmConfig asmConfig = AsmListNode.DEF_CONFIG;
  public void setAsmConfig(AsmListNode.AsmConfig cfg) {
    this.asmConfig = cfg;
    for (GrrTab<?> t : tabs) t.onAsmConfig(asmConfig);
  }
}
