package dz.tabs;

import dz.debugger.*;
import dz.gdb.Executable.ThreadState;
import dz.gdb.*;
import dz.layouts.DebuggerLayout;
import dz.utils.FlamegraphRender;
import dzaima.ui.gui.*;
import dzaima.ui.gui.io.Click;
import dzaima.ui.node.Node;
import dzaima.ui.node.prop.Props;
import dzaima.ui.node.types.tabs.SerializableTab;
import dzaima.utils.*;

import java.text.DecimalFormat;
import java.util.Objects;

public class TimelineTab extends GrrTab<DebuggerLayout> implements SerializableTab {
  public final TimelineNode node;
  public TimelineTab(DebuggerLayout g) {
    super(g);
    node = new TimelineNode(this);
  }
  
  protected boolean dumpRequested;
  public Node show() {
    if (!dumpRequested) {
      dumpRequested = true;
      g.loadRRDump(r -> {
        if (r==null) return;
        node.eventsGot(r);
        onNewState(g.mainState);
      });
    }
    return node;
  }
  
  double currTime = -1;
  public void currTime(double t) { currTime = t; node.mRedraw(); }
  
  long openAnim;
  private static final long ANIM_TOT = (int) 0.2e9;
  public void tick() {
    TimelineManagerTab tm = g.findTab(TimelineManagerTab.class);
    if (tm==null) return;
    boolean samples = tm.samplesOpen();
    long next = openAnim + ctx.gc.deltaNs*(samples? 1 : -1);
    next = Tools.constrain(next, 0, ANIM_TOT);
    if (next != openAnim) {
      openAnim = next;
      node.mResize();
    }
  }
  
  private RecordedState _lastState;
  public void onNewState(RecordedState st) {
    onNewState(st, st.currThread);
  }
  public void onNewState(RecordedState st, ProcThread sel) {
    _lastState = st;
    if (node.dump==null) return;
    st.threadState(sel, node::showThread);
  }
  
  public void onSelectedThread(ProcThread t) {
    onNewState(_lastState, t);
  }
  public void showThreadByID(long tid) {
    node.showThread(tid);
  }
  
  public String name() { return "timeline"; }
  public String serializeName() { return "timeline"; }
  
  public void focusNow() {
    node.v0 = currTime-0.001;
    node.v1 = currTime+0.001;
    node.posUpd();
  }
  
  public static class TimelineNode extends Node {
    public final TimelineTab t;
    
    public TimelineNode(TimelineTab t) {
      super(t.ctx, Props.none());
      this.t = t;
    }
    
    public int minH(int w) {
      return (int) (gc.em*(3+t.openAnim*8.0/ANIM_TOT));
    }
    
    double v0, v1, dv, dvi;
    
    public RRDump dump;
    public Vec<RRDump.Event> debugEvs;
    public RRDump.ThreadData e;
    public ThreadState tst;
    public long tid;
    double g0, g1;
    
    public void eventsGot(RRDump dump) {
      this.dump = dump;
      debugEvs = dump.rawAllEvents.filter(c->c.evType==RRDump.EvType.DEBUGBREAK && c.mode!=RRDump.EvMode.END);
      g0 = 0;
      g1 = dump.tEnd;
      v0 = g1*-0.02;
      v1 = g1* 1.02;
      posUpd();
    }
    public boolean showThread(ThreadState sel) {
      if (sel == null) {
        e = null;
        return false;
      }
      showThread(sel.tid);
      tst = sel;
      return true;
    }
    public void showThread(long tid) {
      this.tid = tid;
      this.tst = null;
      e = dump.thread(tid);
      mRedraw();
    }
    private boolean has() {
      return e!=null;
    }
    
    
    private void posUpd() {
      dv = v1-v0;
      dvi = 1/dv;
      mRedraw();
    }
    
    
    private RecordedState pst;
    private void previewState(RecordedState st) {
      if (t.g.d.status().running() || st==pst) return;
      pst = st;
      t.g.dst = st==null? t.g.mainState : st;
      for (GrrTab<?> c : t.g.tabs) {
        c.onNewState(t.g.dst);
        c.onStatusChange();
      }
    }
    
    boolean hovered;
    int pmx,pmy;
    public void hoverS() {
      hovered = true;
      pmx = Integer.MIN_VALUE;
    }
    public void hoverT(int mx, int my) {
      TimelineManagerTab tm = tm();
      if (tm==null) return;
      if (pmy==my && pmx==mx) return;
      pmx=mx; pmy=my;
      
      if (tm.hoverPreview) {
        double t0 = xToT(mx);
        double md = xToT(mx+gc.em)-t0;
        Vec<StateTimeRef> r0 = Vec.of(
          getNearest(checkpointTimes(), t0, md),
          getNearest(pastVisitTimes(), t0, md),
          getNearest(sampleTimes(), t0, md));
        r0.filterInplace(Objects::nonNull);
        StateTimeRef best = getNearest(r0, t0, md);
        previewState(best==null? null : best.s);
      }
      if (tm.samplesOpen()) mRedraw();
    }
    public void hoverE() {
      hovered = false;
      previewState(null);
      TimelineManagerTab tm = tm();
      if (tm!=null && tm.samplesOpen()) mRedraw();
    }
    
    private static abstract class TimeRef {
      public abstract boolean onThread(long tid);
      abstract double time(TimelineNode e); // if -1, cannot yet display
      abstract boolean canGo();
      public abstract void go(TimelineTab t);
    }
    
    private static class StateTimeRef extends TimeRef {
      public final RecordedState s;
      private StateTimeRef(RecordedState s) { this.s = s; }
      boolean canGo() { return s._lwcp!=null && s._lwcp.isResolved() && s._lwcp.get()!=null; }
      long ticks() { return s._ticks.get().ticks; }
      double time(TimelineNode e) {
        StateManager m = e.t.g.smgr;
        if (m!=null && m.dumpReady() && e.has()) {
          TimeManager.Spot p = s.trySpot();
          if (p==null) m.forThreadX(e.tid).tryFind(s);
          return p==null? -1 : p.myTime();
        }
        return -1;
      }
      public boolean onThread(long tid) {
        if (!s._threads.isResolved()) return false;
        ThreadState curr = ThreadState.curr(s._threads.get());
        return curr!=null && curr.tid==tid;
      }
      
      public void go(TimelineTab t) {
        if (canGo()) t.g.d.curr.gotoLWCP(s._lwcp.get(), null);
        else t.g.d.toTicks(ticks(), null);
      }
    }
    private static class CheckpointRef extends StateTimeRef {
      public final int num;
      private CheckpointRef(RecordedState s, int num) { super(s); this.num=num; }
      boolean canGo() { return true; }
      public void go(TimelineTab t) {
        t.g.d.curr.toCheckpoint(num, null);
      }
    }
    
    private static class EventTimeRef extends TimeRef {
      public final RRDump.Event ev;
      private EventTimeRef(RRDump.Event ev) { this.ev = ev; }
      boolean canGo() { return true; }
      double time(TimelineNode e) { return ev.time; }
      long when() { return ev.event; }
      public boolean onThread(long tid) { return ev.tid == tid; }
      public void go(TimelineTab t) { t.g.d.toEvent(when(), null); }
    }
    
    private <T extends TimeRef> Vec<T> filterCurrThread(Vec<T> ts) {
      ts.filterInplace(c -> c.onThread(tid));
      return ts;
    }
    private void filterReady(Vec<? extends TimeRef> ts) {
      ts.filterInplace(TimeRef::canGo);
    }
    
    
    
    private <T extends TimeRef> T getNearest(Vec<T> st, double time, double md) {
      filterReady(st);
      T s = null;
      for (T c : st) {
        if (c instanceof EventTimeRef && !((EventTimeRef) c).ev.eventOk()) continue;
        double cd = Math.abs(c.time(this)-time);
        if (cd>=md) continue;
        md = cd;
        s = c;
      }
      return s;
    }
    private void addNearest(PartialMenu m, Vec<? extends TimeRef> st, double time, double md, String msg) {
      TimeRef r = getNearest(st, time, md);
      if (r!=null) m.add(msg, () -> {
        if (t.g.atPausedMainState()) r.go(t);
      });
    }
    
    
    public void mouseStart(int x, int y, Click c) { if (c.bR()||c.bC()) c.register(this, x, y); }
    
    public void mouseDown(int x, int y, Click c) {
      if (c.bR()) {
        if (e==null) return;
        PartialMenu m = new PartialMenu(gc);
        double t0 = xToT(x);
        double md = xToT(x+gc.em)-t0;
        
        if (t.g.atPausedMainState() && t.g.d.isRR() && t.g.rrDump.isResolved()) {
          if (tst!=null) m.add("Go to approximate time", () -> {
            if (t.g.atPausedMainState()) t.g.d.getRRExe().selectThread(tst, (b) -> {
              if (b && t.g.atPausedMainState()) t.g.seekToTime(tid, t0, false, () -> {});
            });
          });
          
          addNearest(m, checkpointTimes(), t0, md, "Go to checkpoint");
          addNearest(m, pastVisitTimes(),  t0, md, "Go to past visit");
          addNearest(m, eventTimes(),      t0, md, "Go to event");
          addNearest(m, debugbreakTimes(), t0, md, "Go to break");
          addNearest(m, sampleTimes(),     t0, md, "Go to sample");
        }
        
        m.open(ctx, c);
      } else if (c.bC()) {
        tdx = tdy = 0;
      }
    }
    
    int tdx, tdy, tly;
    public void mouseTick(int x, int y, Click c) {
      if (c.bC()) {
        boolean dirDet = tdx >= gc.em*2 || tdy >= gc.em;
        if (!dirDet) {
          tdx+= Math.abs(c.dx);
          tdy+= Math.abs(c.dy);
        }
        double dt = c.dx*dv/w;
        v0-= dt;
        v1-= dt;
        if (dirDet && tdy > tdx*0.5) tly = Math.max(tly-c.dy, 0);
        posUpd();
      }
    }
    
    private static final int BIG = Integer.MAX_VALUE/8;
    public float tToXf(double t) { return (float) ((t-v0) * (dvi * w)); }
    private int tToX(double t) { float d = tToXf(t); return d>=BIG? BIG : d<=-BIG? -BIG : (int) d; }
    private double xToT(int x) { return x*dv/w+v0; }
    public boolean scroll(int x, int y, float dx, float dy) {
      double am = Math.pow(1.35, -dy/80);
      double c = xToT(x);
      if (dv < 2e-9 && am<1) return true;
      if (dv > 1e8 && am>1) return true;
      v0 = c + (v0-c)*am;
      v1 = c + (v1-c)*am;
      posUpd();
      return true;
    }
    
    FlamegraphRender fgr;
    public void drawC(Graphics g) {
      if (!has()) return;
      g.push();
      g.clip(0, 0, w, h);
      g.rect(0, 0, w, h, gc.getProp("grr.tabs.timeline.bg").col());
      int padBorders = 0x30000000;
      padding(g, 0.75f, gc.em*2, 0, false, 0, tToX(e.tStart), padBorders);
      padding(g, 0.75f, gc.em*2, 0, false, tToX(e.tEnd), w, padBorders);
      
      // event padding
      int evLines = 0x161616;
      int px = -1;
      for (int i = 0; i < e.evs.sz; i++) {
        RRDump.Event c = e.evs.get(i);
        int x = tToX(c.time);
        if (x == px) continue;
        if (i>0 && c.functionsAsEnd() && px+2<x && e.evs.get(i-1).mode == RRDump.EvMode.START) {
          int frac = (int) Tools.constrain((x-px)*100f/gc.em, 0, 255);
          int clc = evLines | frac << 24;
          padding(g, 0.4f, gc.em, px, true, px, x, clc);
        }
        px = x;
      }
      
      // top bar
      drawTimeBar(g);
      
      // current time vertical bar
      // TODO display rr-imagined ticks for non-current selected tid?
      int cx = tToX(t.currTime);
      g.rect(cx, 0, cx+1, h, 0xffA0A0A0);
      
      // draw the various sorts of checkpoints
      points(checkpointTimes(), g, 0, 0xFF50A050);
      points(pastVisitTimes(), g, 1, 0xFF804000);
      points(eventTimes(), g, 2, 0xA0111111);
      points(debugbreakTimes(), g, 3, 0xFFf02000);
      int y0 = points(sampleTimes(), g, 4, 0x7F804000);
      
      TimelineManagerTab tm = tm();
      if (tm!=null && tm.samples.sz!=0) {
        g.push();
        g.clipE(0, y0, w, h);
        FlamegraphRender fgrC = tm.fg_freeze && fgr!=null? fgr : new FlamegraphRender(this, tm).calc(tid, g0, g1, v0, v1);
        fgr = tm.fg_freeze? fgrC : null;
        XY rel = relPos(null);
        NodeWindow win = ctx.win();
        fgrC.draw(g, y0, tly, w, hovered? win.mx-rel.x : Integer.MIN_VALUE, win.my-rel.y);
        g.pop();
      }
      
      g.pop();
    }
    
    private TimelineManagerTab tm() {
      return t.g.findTab(TimelineManagerTab.class);
    }
    
    private Vec<TimeRef> eventTimes() {
      Vec<TimeRef> ts = e.evs.map(EventTimeRef::new);
      return filterCurrThread(ts);
    }
    private Vec<TimeRef> debugbreakTimes() {
      Vec<TimeRef> ts = debugEvs.map(EventTimeRef::new);
      return filterCurrThread(ts);
    }
    private Vec<StateTimeRef> checkpointTimes() {
      Vec<StateTimeRef> ts = new Vec<>();
      t.g.forTabs(CheckpointTab.class, t -> {
        for (Node c : t.list.ch) {
          CheckpointTab.CheckpointNode n = (CheckpointTab.CheckpointNode) c;
          ts.add(new CheckpointRef(n.st, n.num));
        }
      });
      return filterCurrThread(ts);
    }
    private Vec<StateTimeRef> pastVisitTimes() {
      Vec<StateTimeRef> ts = new Vec<>();
      for (RecordedState s : t.g.smgr.hist) ts.add(new StateTimeRef(s));
      return filterCurrThread(ts);
    }
    private Vec<StateTimeRef> sampleTimes() {
      Vec<StateTimeRef> ts = new Vec<>();
      TimelineManagerTab tm = tm();
      if (tm!=null) for (RecordedState s : tm.samples) ts.add(new StateTimeRef(s));
      return filterCurrThread(ts);
    }
    
    private void drawTimeBar(Graphics g) {
      // seg1: big segments; seg0: small segments
      
      int targetPx = gc.em*10; // target number of pixels per large divider
      int exp;
      double seg1T;
      
      boolean orig = true;
      do {
        seg1T = dv * targetPx / w; // seconds in one targetPx
        exp = (int) Math.floor(Math.log10(seg1T));
        
        if (!orig || exp >= -4) break;
        targetPx = targetPx*3/2; // if the time strings get quite long, allocate more space for each
        orig = false;
      } while (true);
      
      double seg1 = Math.pow(10, exp); // round down to the nearest power of 10
      
      int seg0n;
      double off = seg1T/seg1;
      if (off>5)      { seg0n=5; seg1*= 5; }
      else if (off>2) { seg0n=4; seg1*= 2; }
      else            { seg0n=5; }
      double seg0 = seg1/seg0n;
      
      DecimalFormat f = new DecimalFormat();
      f.setMinimumFractionDigits(Math.max(-exp, 0));
      int i = 0;
      double t0 = Math.floor(v0/seg1)*seg1;
      if (t0<=0) t0 = 0;
      double t1 = Math.min(v1, g1);
      
      double inc0 = -1;
      boolean incEnable = seg1<0.001;
      
      for (double t = t0; t < t1; t+= seg0) {
        int x = tToX(t);
        g.line(x, 0, x, gc.em/2, 0xff444444);
        if (i-- == 0) {
          g.line(x, 0, x, gc.em, 0xff555555);
          String s;
          if (!incEnable || inc0==-1) {
            s = f.format(t);
            if (s.equals("-0")) s = "0";
            if (incEnable) {
              if (x>0) {
                inc0 = t;
                f.setMinimumFractionDigits(0);
                f.setMaximumFractionDigits(2);
              } else {
                s = "";
              }
            }
          } else {
            double dt = t-inc0;
            if      (seg1>1e-5) s = "+"+f.format(dt*1e3)+"ms";
            else if (seg1>1e-7) s = "+"+f.format(dt*1e6)+"us";
            else                s = "+"+f.format(dt*1e9)+"ns";
          }
          g.text(s, gc.defFont, x+gc.em*0.1f, gc.em, 0xffAAAAAA);
          i = seg0n-1;
        }
      }
    }
    
    private int points(Vec<? extends TimeRef> ts, Graphics g, int l, int col) {
      int ln = 4;
      int blobH = Tools.ceil(gc.em*0.4);
      float blobW = gc.em*0.115f;
      int y0 = gc.em + blobH;
      float eh2 = Math.min((h - y0)/(float) ln, gc.em*0.7f);
      int y = (int) (y0 + eh2*l);
      
      Vec<Double> times = ts.map(c -> c.time(this));
      times.filterInplace(c -> c!=-1);
      times.sort();
      float px = -1;
      for (double t : times) {
        float x = tToXf(t);
        if (x <= px+0.5) continue;
        if (x>=0 && x<=w) g.rrect(x-blobW, y, x+blobW, y+blobH, gc.em*0.1f, col);
        px = x;
      }
      return y+blobH;
    }
    
    private void padding(Graphics g, float skew, int spacing, int xR, boolean whole, int x0, int x1, int col) {
      if (x0>w || x1<0) return;
      int visDelta = x1-x0;
      x0 = Math.max(x0, 0);
      x1 = Math.min(x1, w);
      if (x0 >= x1) return;
      if (whole) spacing = Math.min(spacing, Math.max(spacing/3, visDelta*2/3));
      
      g.push();
      g.clipE(x0, 0, x1, h);
      
      int tdx = (int) (h*skew);
      int lx0 = x0 - Math.floorMod(x0-xR, spacing);
      int lx1 = x1+tdx;
      for (int x = lx0; x < lx1; x+= spacing) {
        g.line(x, 0, x-tdx, h, col);
      }
      g.pop();
      g.rect(x0, 0, x0+1, h, col);
      g.rect(x1, 0, x1+1, h, col);
    }
  }
}
