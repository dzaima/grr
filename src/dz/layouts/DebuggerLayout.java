package dz.layouts;

import dz.Main;
import dz.debugger.*;
import dz.gdb.*;
import dz.general.*;
import dz.general.arch.Arch;
import dz.tabs.*;
import dz.ui.SearchPopup;
import dz.utils.*;
import dzaima.ui.gui.Popup;
import dzaima.ui.gui.io.*;
import dzaima.ui.node.Node;
import dzaima.ui.node.ctx.Ctx;
import dzaima.ui.node.types.*;
import dzaima.ui.node.types.tabs.SerializableTab;
import dzaima.utils.*;
import dzaima.utils.options.Options;

import java.nio.file.*;
import java.text.DecimalFormat;
import java.util.*;
import java.util.function.*;

public class DebuggerLayout extends GdbLayout {
  public static final ProcThread.StackFrame BAD_STACKFRAME = new ProcThread.StackFrame(-1, new Location(0L, null, null, null, null), true);
  
  public boolean atMainState = true; // false during e.g. flamegraph sampling
  public boolean ignoreStateUpdates = false; // false during e.g. flamegraph sampling
  public RecordedState mainState = new RecordedState(null, null); // state of the user-intended location; may not equal gdb state when e.g. flamegraph sampling
  public RecordedState dst; // displayed state; may not equal mainState when previewing on timeline
  public StateManager smgr;
  
  public int cp0 = -1;
  
  public Node toolbar;
  
  private final String rrBin, exeName;
  public Promise<RRDump> rrDump;
  public Runnable rrDumpCheck;
  
  public DebuggerLayout(Main m, Options o, boolean rr, Vec<String> bin) {
    super(m);
    node = m.ctx.make(gc.getProp("grr.uiDebugger").gr());
    
    GdbProcess.TTYMode tty;
    String ttyRaw = o.optOne("--tty");
    switch (ttyRaw==null? "new" : ttyRaw) {
      case "new": tty = GdbProcess.TTYMode.NEW; break;
      case "none": tty = GdbProcess.TTYMode.NONE; break;
      case "inherit": tty = GdbProcess.TTYMode.INHERIT; break;
      default: Main.fail("Invalid --tty value. Options: new, none, inherit"); throw new IllegalStateException();
    }
    
    String gdbBin = o.optOne("--gdb-bin");
    rrBin = o.optOne("--rr-bin");
    exeName = bin.sz==0? null : bin.get(0);
    if (rr) {
      Vec<String> extra = new Vec<>();
      extra.addAll(o.optArgs("--rr-arg"));
      
      String rn = o.optOne("--rr-process");
      if (rn!=null) { extra.add("-p"); extra.add(rn); }
      
      String fn = o.optOne("--rr-fork");
      if (fn!=null) { extra.add("-f"); extra.add(fn); }
      
      this.d = new Dbi(GdbProcess.makeRR(rrBin, gdbBin, tty, exeName, extra));
    } else {
      String core = o.optOne("--core");
      if (core!=null && exeName==null) Main.fail("'--core' requires a binary");
      this.d = new Dbi(core==null? GdbProcess.makeGdb(gdbBin, tty) : GdbProcess.makeGdb(gdbBin, tty, exeName, core));
      if (bin.sz>0) {
        Executable e = d.makeExe(Paths.get(Objects.requireNonNull(exeName)));
        boolean noRun = o.takeBool("--no-run");
        if (core!=null) {
          d.p.setStatus(Dbi.Status.PAUSED); // TODO don't require this mess
          d.assumeExe(e);
          d.notifyNewArchitecture();
        } else {
          d.toExe(e, b -> {
            if (!b) Log.error("debugger load", "Failed to open debugger to file \""+exeName+"\"");
          });
          if (!noRun) e.open(bin.get(1, bin.sz, String[].class), null);
        }
      }
    }
    dbiInit(o);
    
    d.p.onStatusChange.add(() -> {
      if (ignoreStateUpdates) return;
      if (d.status().hasexe() && d.curr==null) {
        // assume user has manually loaded executable
        d.assumeExe(d.makeExe(null));
      }
      stateChanged = true;
    });
    
    if (d.isRR()) {
      d.curr.addCheckpoint(n -> {
        if (n==null) System.err.println("Failed to set initial checkpoint");
        else cp0=n;
      });
    }
    
    loadSampleData(o, bin.sz==0? null : bin.get(0), false);
    
    node.ctx.id("toolbar").add(toolbar = node.ctx.make(gc.getProp(d.isRR()? "grr.rrToolbar" : "grr.gdbToolbar").gr()));
    
    Ctx tc = toolbar.ctx;
    if (!d.isRR()) { tc.id("timeDesc").clearCh(); tc.id("time").clearCh(); }
    
    BiConsumer<String, Consumer<BtnNode>> b = (name, fn) -> {
      Node n = tc.idNullable(name);
      if (n!=null) ((BtnNode) n).setFn(fn);
    };
    
    b.accept("toB", btn -> {
      toStart();
    });
    
    b.accept("runF", btn -> { if (readyForExec()) d.curr.cont(false, null); });
    b.accept("runB", btn -> { if (readyForExec()) d.curr.cont(true,  null); });
    
    b.accept("pause", btn -> { if (d.status().running()) d.curr.breakAll(null); });
    
    b.accept("iStepF", btn -> { if (readyForExec()) stepIns( 1); });
    b.accept("iStepB", btn -> { if (readyForExec()) stepIns(-1); });
    
    b.accept("lStepF", btn -> { if (readyForExec()) d.curr.stepLine( 1, null); });
    b.accept("lStepB", btn -> { if (readyForExec()) d.curr.stepLine(-1, null); });
    
    b.accept("fStepF", btn -> { if (readyForExec()) d.curr.finishFn(false, null); });
    b.accept("fStepB", btn -> { if (readyForExec()) d.curr.finishFn(true,  null); });
    
    
    b.accept("mkCp", btn -> {
      if (!d.status().paused()) return;
      CheckpointTab t = findTab(CheckpointTab.class);
      if (t==null) return;
      t.makeCheckpoint();
    });
    b.accept("toCp", btn -> {
      CheckpointTab t = findTab(CheckpointTab.class);
      if (t==null || t.list.ch.sz==0 || !readyForExec()) return;
      d.curr.toCheckpoint(((CheckpointTab.CheckpointNode) t.list.ch.peek()).num, null);
    });
    
    b.accept("toFn", btn -> {
      if (d.status().hasexe()) {
        Popup p = new SearchPopup(this) {
          public void accept(Location l, Vec<ProcThread.Arg> b1) {
            close();
            for (GrrTab<?> t : g.tabs) t.onSelectedFunction(l, true, false);
          }
        };
        p.openVW(gc, this.m.ctx, gc.getProp("grr.toFn.ui").gr(), true);
      }
    });
    
    
    if (!o.takeBool("--no-auto-map")) {
      if (!o.takeBool("--no-python")) {
        d.p.consoleCmd("python print(\"pid:\", gdb.selected_inferior().pid)").runWithPrefix("~\"pid: ", (r, s) -> {
          try { loadJITMap(Integer.parseInt(s.substring(0, s.length()-3))); }
          catch (NumberFormatException ignored) { }
        });
      }
    }
    d.p.consoleCmd("set print frame-info location").ds().run(); // disable printing the source line, which is problematically slow on large files, besides being unnecessary
    
    String layoutPath = o.optOne("--layout");
    String layoutStr = layoutPath!=null? Tools.readFile(Path.of(layoutPath)) : Tools.readRes(rr? "rrLayout.dzcfg" : "gdbLayout.dzcfg");
    treePlace().replace(0, SerializableTab.deserializeTree(node.ctx, layoutStr, getCtors()));
    
    forTabs(GdbTab.class, t -> { if (t.w.sel && t.mode==GdbTab.Mode.DEBUGGER) t.onSelected(); });
    
    smgr = new StateManager(d);
    if (d.isRR()) {
      loadRRDump(d -> {
        if (d==null) return;
        smgr.setDump(d);
        for (long c : d.perThread.keySet()) loadJITMap(c);
        for (long c : d.processes) loadJITMap(c);
      });
    }
    layoutInit(o);
    String disasSym = o.optOne("-d");
    if (disasSym!=null) {
      if (exeName==null) Main.fail("Binary not specified for '-d'");
      d.curr.symbolInfo(true, 200, disasSym, r -> {
        if (r.sz==0) System.err.println("Error: No symbol matching '"+disasSym+"' found");
        else {
          Vec<Pair<Location, Vec<ProcThread.Arg>>> r1 = r.filter(c -> c.a.sym.startsWith(disasSym));
          if (r1.sz==0) r1 = new Vec<>(r);
          r1.sort(Comparator.comparing(c -> c.a.sym.length()));
          Pair<Location, Vec<ProcThread.Arg>> r0 = r1.get(0);
          for (GrrTab<?> t : tabs) t.onSelectedFunction(r0.a, true, false);
        }
      });
    }
    o.used();
  }
  
  public void stepIns(int am) {
    assert atPausedMainState();
    RecordedState s0 = mainState;
    s0.requestSpot();
    d.curr.stepIns(am, b -> onNextMainState.add(s1 -> {
      if (b && Math.abs(am)==1) s0.spot(r0 -> s1.spot(r1 -> {
        if (r0.g.tm==r1.g.tm) r0.g.tm.addAdjacent(r0, r1);
      }));
    }));
  }
  
  public void loadRRDump(Consumer<RRDump> got) {
    if (!d.isRR()) throw new IllegalStateException("loadRRDump");
    if (d.curr==null) { Log.warn("rr dump", "Cannot load as no executable is loaded"); return; }
    if (rrDump==null) rrDump = Promise.create(p -> RRDump.load(this, rrBin, exeName, p::set));
    rrDump.then(got);
  }
  
  
  
  public boolean key(Key key, int scancode, KeyAction a) {
    String k = gc.keymap(key, a, "grr");
    switch (k) {
      case "toStart": toStart(); return true;
      case "break": if (d.status().running()) d.curr.breakAll(null); return true;
      case "focusTimeline":
        forTabs(TimelineTab.class, TimelineTab::focusNow);
        return true;
      default: {
        if (k.endsWith("F") || k.endsWith("B")) {
          boolean rev = k.endsWith("B");
          if (rev && !hasReverse()) return false;
          int am = rev? -1 : 1;
          switch (k.substring(0, k.length()-1)) {
            default: return false;
            case "cont":     if (readyForExec()) d.curr.cont(rev, null); return true;
            case "ins":      if (readyForExec()) stepIns(am); return true;
            case "insOver":  if (readyForExec()) d.curr.stepOverIns(am, null); return true;
            case "line":     if (readyForExec()) d.curr.stepLine(am, null); return true;
            case "lineOver": if (readyForExec()) d.curr.stepOverLine(am, null); return true;
            case "finish":   if (readyForExec()) { if (readyForExec()) d.curr.finishFn(rev, null); } return true;
          }
        }
        return false;
      }
    }
  }
  
  public boolean hasReverse() {
    return d.isRR();
  }
  public void isValgrind(Consumer<Boolean> then) {
    requestArchRegs(r -> {
      then.accept(r.regs.linearFind(Arch.RegInfo::knownDefined)!=null);
    });
  }
  
  public boolean atPausedMainState() {
    return atMainState && d.status().paused();
  }
  
  public boolean readyForExec() {
    return atPausedMainState() && d.queueEmpty();
  }
  
  public void addBreakpoint(boolean hw, boolean temp, boolean enabled, DisasFn.ParsedIns c, Runnable after) {
    d.addBreakpoint(hw, temp, enabled, "*0x"+ DebuggerLayout.hexLong(c.s), after);
  }
  
  public void toStart() {
    if (readyForExec()) {
      if (d.isRR()) {
        if (cp0!=-1) d.curr.toCheckpoint(cp0, null);
      } else {
        d.curr.open(null, null);
      }
    }
  }
  
  
  
  boolean stateChanged = true;
  public Vec<Consumer<RecordedState>> onNextMainState = new Vec<>();
  public void tick() {
    super.tick();
    if (rrDumpCheck!=null) rrDumpCheck.run();
    
    if (stateChanged) {
      stateChanged = false;
      mainState.requestable = false;
      if (d.status()==Dbi.Status.PAUSED) {
        mainState = new RecordedState(smgr, d.curr);
        dst = mainState;
        mainState.requestable = true;
        smgr.addExplicit(mainState, (t, c) -> setTime(t));
        for (GrrTab<?> c : tabs) c.onNewState(mainState);
        for (Consumer<RecordedState> c : onNextMainState) c.accept(mainState);
        onNextMainState.clear();
      }
      
      fGdb = fViewed = null;
      
      if (d.status().paused() && d.stopReason()!=null) for (GrrTab<?> t1 : tabs) t1.onStopped(d.stopReason());
      String msg;
      switch (d.status()) { default: msg = "??"; break;
        case NONE: msg = "no executable loaded"; break;
        case LOADED: msg = "not started"; break;
        case PAUSED: msg = "paused"; break;
        case RUNNING: msg = "running"; break;
        case KILLED: msg = "no session"; break;
      }
      toolbar.ctx.id("status").replace(0, new StringNode(node.ctx, msg));
      
      if (d.isRR()) dr.set(() -> setTime("unknown"));
      for (GrrTab<?> t : tabs) t.onStatusChange();
    }
  }
  final DelayedRun dr = new DelayedRun(this);
  
  
  
  public void getDisas(Binary bin, Location frame, Consumer<DisasFn> res) {
    getDisas(frame, FnCache.NameMode.PREFIX, sourceInjector(res));
  }
  
  public static String pad(String v, char s, int len) {
    if (v.length()>=len) return v;
    return Tools.repeat(s, len-v.length())+v;
  }
  public static String padL(String v, char s, int len) {
    if (v.length()>=len) return v;
    return v+Tools.repeat(s, len-v.length());
  }
  public static String hexLong(long l) { return hexLong(l, 16); }
  public static String hexLong(long l, int len) {
    return pad(Long.toUnsignedString(l, 16), '0', len);
  }
  public static int hexLength(long l) {
    return Math.max(16 - Long.numberOfLeadingZeros(l)/4, 2);
  }
  
  private void setTime(double d) {
    setTime(fmtSeconds(d));
    forTabs(TimelineTab.class, c -> c.currTime(d));
  }
  private void setTime(String s) {
    dr.cancel();
    toolbar.ctx.id("time").replace(0, new StringNode(node.ctx, s));
  }
  
  private static final DecimalFormat f = new DecimalFormat("0.#########");
  public static String fmtSeconds(double s) {
    return f.format(s);
  }
  public static String fmtNanos(long ns) {
    return fmtSeconds(ns/1e9);
  }
}