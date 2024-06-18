package dz.tabs;

import dz.debugger.*;
import dz.gdb.*;
import dz.layouts.*;
import dzaima.ui.node.Node;
import dzaima.ui.node.prop.Prop;
import dzaima.ui.node.types.*;
import dzaima.ui.node.types.editable.EditNode;
import dzaima.ui.node.types.tabs.*;
import dzaima.utils.*;

import java.util.*;

public class TimelineManagerTab extends GrrTab<GdbLayout> implements SerializableTab {
  public final Node node;
  
  public boolean fg_merge, fg_trim, fg_freeze, fg_local;
  public int perRequest;
  public boolean hoverPreview;
  public long tidToSample;
  public enum SampleMode {
    TICKS("ticks",10), STEPI("stepi",50), CONT("continue",10);
    final int perReq;
    final String name;
    SampleMode(String s, int p) { name=s; perReq=p; }
  }
  public SampleMode mode = SampleMode.TICKS;
  
  public RecordedState stateToRestore;
  public boolean sampling;
  public long sampledTid = -1;
  public final Vec<RecordedState> samples = new Vec<>();
  public final HashSet<RecordedState> sampleCSet = new HashSet<>();
  private final Vec<Double> samplesTimesOrig = new Vec<>();
  private final HashSet<Long> sampledTicks = new HashSet<>();
  private final HashSet<RecordedState> lastBatchLeft = new HashSet<>();
  private RecordedState prevState;
  private Promise<Boolean> quickSeekSupported;
  
  public TimelineManagerTab(GdbLayout g) {
    super(g);
    assert g.d.isRR();
    node = ctx.make(ctx.gc.getProp("grr.tabs.timelineManager.ui").gr());
    ((CheckboxNode) node.ctx.id("preview")).setFn(b -> { hoverPreview = b; g.forTabs(TimelineTab.class, t -> t.node.mRedraw()); });
    ((CheckboxNode) node.ctx.id("merge"  )).setFn(b -> { fg_merge     = b; g.forTabs(TimelineTab.class, t -> t.node.mRedraw()); });
    // ((CheckboxNode) node.ctx.id("trim"   )).setFn(b -> { fg_trim      = b; g.forTabs(TimelineTab.class, t -> t.node.mRedraw()); });
    ((CheckboxNode) node.ctx.id("freeze" )).setFn(b -> { fg_freeze    = b; g.forTabs(TimelineTab.class, t -> t.node.mRedraw()); });
    ((CheckboxNode) node.ctx.id("local"  )).setFn(b -> { fg_local     = b; g.forTabs(TimelineTab.class, t -> t.node.mRedraw()); });
    
    ((BtnNode) node.ctx.id("mode")).setFn(b -> {
      if (sampling) return;
      switch (mode) {
        case TICKS: mode = SampleMode.STEPI; break;
        case STEPI: mode = SampleMode.CONT; break;
        case CONT:  mode = SampleMode.TICKS; break;
      }
      b.replace(0, new StringNode(b.ctx, mode.name));
    });
    
    if (g instanceof DebuggerLayout) {
      DebuggerLayout gd = (DebuggerLayout) g;
      Runnable clear = () -> {
        samples.clear();
        samplesTimesOrig.clear();
        sampledTicks.clear();
        sampleCSet.clear();
      };
      ((BtnNode) node.ctx.id("clear")).setFn(c -> clear.run());
      
      BtnNode run = (BtnNode) node.ctx.id("run");
      Runnable toggleRunning = () -> {
        gd.loadRRDump(r -> {});
        if (!sampling && !gd.readyForExec() || !gd.rrDump.isResolved()) return;
        
        boolean toSample = !sampling;
        if (toSample) {
          stateToRestore = gd.mainState;
          if (!stateToRestore.getLWCP(i -> {})) return;
          gd.atMainState = false;
          gd.ignoreStateUpdates = true;
          gd.mainState.requestable = false;
          
          if (sampledTid != tidToSample) clear.run();
          sampledTid = tidToSample;
          
          gd.disableInternalLog = true;
          prevState = stateToRestore;
          perRequest = mode.perReq;
          gd.addRawOut("(logging disabled during sampling)\n");
        } else {
          gd.d.p.consoleCmd("p 1").run(r -> { // ensure not running, intentionally no .ds()
            gd.disableInternalLog = false;
            gd.ignoreStateUpdates = false;
            gd.mainState = stateToRestore;
            Integer num = gd.mainState._lwcp.get();
            if (num==null) {
              gd.atMainState = true; // no lwcp support
            } else gd.d.curr.toCheckpoint(num, () -> {
              gd.atMainState = true;
              gd.mainState.requestable = true;
            });
          });
        }
        sampling = toSample;
        run.replace(0, new StringNode(ctx, ctx.gc.getProp(sampling? "grr.tabs.timelineManager.runBtnOn" : "grr.tabs.timelineManager.runBtnOff").str()));
      };
      run.setFn(b -> {
        if (mode == SampleMode.TICKS) {
          if (quickSeekSupported==null) quickSeekSupported = Promise.create(p -> {
            g.d.p.consoleCmd("help quick-seek-ticks").ds().run(r -> p.set(r.type.ok()));
          });
          quickSeekSupported.then(ok -> g.runLater.add(toggleRunning));
        } else {
          toggleRunning.run();
        }
        
      });
      EditNode resC = (EditNode) node.ctx.id("resolutionCoarse");
      resC.append("50");
      EditNode resF = (EditNode) node.ctx.id("resolutionFine");
      resF.append("300");
    }
  }
  
  private double resolution(boolean coarse) {
    EditNode n = (EditNode) node.ctx.id(coarse? "resolutionCoarse" : "resolutionFine");
    try { return Double.parseDouble(n.getAll()); }
    catch (NumberFormatException e) { return 1; }
  }
  
  public boolean samplesOpen() {
    return sampling || samples.sz>0;
  }
  
  public Vec<Pair<RecordedState, Double>> samplesInRange(long tid, double tS, double tE) {
    Vec<Pair<RecordedState, Double>> res = new Vec<>();
    TimeManager tm = ((DebuggerLayout) g).smgr.forThreadX(tid);
    if (tm==null) return res;
    for (RecordedState s : samples) {
      TimeManager.Spot spot = tm.tryFind(s);
      if (spot==null) continue;
      double t = spot.myTime();
      if (t>=tS && t<=tE) res.add(new Pair<>(s, t));
    }
    return res;
  }
  
  private static class Interval implements Comparable<Interval> {
    final double s,e;
    private Interval(double s, double e) { this.s=s; this.e=e; }
    public int compareTo(Interval o) { return Double.compare(s, o.s); }
  }
  
  private String prevInfo = "";
  private void setInfo(String newInfo) {
    if (!newInfo.equals(prevInfo)) {
      prevInfo = newInfo;
      node.ctx.id("framegraphInfo").replace(0, new StringNode(ctx, newInfo));
    }
  }
  public void tick() {
    String newInfo = "";
    if (samplesOpen()) {
      newInfo = samples.sz+" samples for tid "+sampledTid;
      // newInfo = samples.sz+" done, "+sampledTicks.size()+" total";
    }
    setInfo(newInfo);
    
    TimelineTab tl = g.findTab(TimelineTab.class);
    if (!sampling || tl==null) return;
    DebuggerLayout gd = (DebuggerLayout) g;
    
    RRDump.ThreadData evs = gd.rrDump.get().thread(sampledTid);
    if (evs==null) return;
    
    if (g.d.queueEmpty() || lastBatchLeft.size() < perRequest) {
      lastBatchLeft.clear();
      double tS = tl.node.v0;
      double tE = tl.node.v1;
      TimeManager tmgr = gd.smgr.forThread(gd.rrDump.get(), tidToSample);
      switch (mode) {
        case TICKS:
          int left = requestSamples(tl, tmgr, evs, perRequest, tS, tE, resolution(true));
          requestSamples(tl, tmgr, evs, left, tS, tE, resolution(false));
          break;
        case STEPI:
          for (int i = 0; i < perRequest; i++) {
            g.d.p.cmd("-exec-step-instruction").ds().run();
            RecordedState s = new RecordedState(gd.smgr, g.d.getRRExe());
            s.requestable = true;
            recordSample(tl, s);
            if (prevState!=null) tmgr.addAdjacent(prevState, s);
            s.requestable = false;
            prevState = s;
          }
          break;
        case CONT:
          for (int i = 0; i < perRequest; i++) {
            g.d.p.cmd("-exec-continue").ds().run();
            RecordedState s = new RecordedState(gd.smgr, g.d.getRRExe());
            s.requestable = true;
            recordSample(tl, s);
            s.requestable = false;
          }
          break;
      }
    }
  }
  
  // TODO sub-tick sampling on tick mode?
  private int requestSamples(TimelineTab tl, TimeManager tmgr, RRDump.ThreadData evs, int maxRQ, double tS, double tE, double resolution) { // returns remaining request count
    if (maxRQ==0 || resolution==0) return 0;
    
    double tMin = (tE-tS) / resolution;
    double tBound = tMin*0.999;
    
    Vec<Interval> remove = new Vec<>();
    for (Pair<RecordedState, Double> p : samplesInRange(sampledTid, tS, tE)) {
      remove.add(new Interval(p.b-tBound, p.b+tBound));
    }
    for (double t : samplesTimesOrig) { // in case the actually-sampled time is off the requested one, to not get in an infinite loop
      remove.add(new Interval(t-tBound, t+tBound));
    }
    
    boolean closed = true;
    int i0 = Math.max(evs.timeToIdx(tS) - 1, 0);
    int i1 = Math.min(evs.timeToIdx(tE) + 2, evs.evs.sz);
    LongVec obligatory = new LongVec();
    double tA = -1;
    for (int i = i0; i < i1; i++) {
      RRDump.Event e = evs.evs.get(i);
      double tB = Tools.constrain(e.time, tS, tE);
      if (closed) {
        if (tA!=-1 && tB-tA >= tMin*2) {
          obligatory.add(evs.evs.get(i-1).ticks-1);
          obligatory.add(e.ticks);
          obligatory.add(e.ticks+1);
        }
        remove.add(new Interval(tA, tB));
      }
      tA = tB;
      closed = e.mode==RRDump.EvMode.START;
    }
    
    remove.sort();
    obligatory.sort();
    
    int ri = 0;
    int oi = 0;
    double tC = tS;
    int rql = maxRQ;
    rqs: while (true) {
      while (ri < remove.sz) {
        Interval c = remove.get(ri);
        if (tC < c.s) break;
        tC = Math.max(tC, c.e);
        ri++;
      }
      if (tC >= tE) break;
      
      samplesTimesOrig.add(tC);
      long tick = tmgr.timeToTicksTrivial(tC).ticks;
      while (oi<obligatory.sz && obligatory.get(oi)<tick) {
        if (trySampleTick(tl, obligatory.get(oi++)) && 0 == --rql) break rqs;
      }
      if (trySampleTick(tl, tick) && 0 == --rql) break;
      tC+= tMin;
    }
    return rql;
  }
  
  private boolean trySampleTick(TimelineTab tl, long tick) {
    if (sampledTicks.add(tick)) {
      if (quickSeekSupported.get()) g.d.toTicksQuick(tick, null);
      else g.d.toTicks(tick, null);
      RecordedState s = new RecordedState(stateToRestore.smgr, g.d.curr);
      s.requestable = true;
      recordSample(tl, s);
      s.requestable = false;
      return true;
    }
    return false;
  }
  
  private void recordSample(TimelineTab tl, RecordedState s) {
    lastBatchLeft.add(s);
    Box<Integer> ctr = new Box<>(0);
    
    int NUMBER_OF_THINGS = 5;
    Runnable next = () -> {
      ctr.set(ctr.get()+1);
      if (ctr.get() == NUMBER_OF_THINGS) {
        ((DebuggerLayout) g).smgr.add(s, (time, spot) -> {
          RecordedState canonical = spot==null? null : spot.s;
          if (canonical==null || !sampleCSet.contains(canonical)) {
            lastBatchLeft.remove(s);
            sampleCSet.add(canonical==null? s : canonical);
            samples.add(s);
          }
          tl.node.mRedraw();
        });
      }
      assert ctr.get() <= NUMBER_OF_THINGS;
    };
    
    // NUMBER_OF_THINGS things:
    s.getLWCP(i -> next.run());
    s.currThreadWhen(r -> next.run());
    s.limitedStack(s.currThread, stk -> next.run());
    s.currThreadState(r -> next.run());
    s.threads(r -> next.run());
  }
  
  
  RecordedState _lastState;
  public void onNewState(RecordedState st) {
    _lastState = st;
    st.currThreadWhen(g -> {
      node.ctx.id("when").replace(0, new StringNode(ctx, g==null? "??" : g.event+""));
      node.ctx.id("ticks").replace(0, new StringNode(ctx, g==null? "??" : g.ticks+""));
    });
    if (samplesOpen() && st.requestable) recordSample(g.findTab(TimelineTab.class), st);
    onSelectedThread(st.currThread);
  }
  
  public void onSelectedThread(ProcThread t) {
    if (!(g instanceof DebuggerLayout)) return;
    ((DebuggerLayout) g).mainState.threadState(t, ts -> {
      if (ts!=null) {
        tidToSample = ts.tid;
        node.ctx.id("tid").replace(0, new StringNode(ctx, tidToSample+""));
      }
    });
  }
  
  public static Tab deserialize(GdbLayout g, HashMap<String, Prop> p) {
    return new TimelineManagerTab(g);
  }
  
  public Node show() { return node; }
  public String name() { return "timeline manager"; }
  public String serializeName() { return "timelineManager"; }
}
