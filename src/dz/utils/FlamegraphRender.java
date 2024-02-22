package dz.utils;

import dz.debugger.*;
import dz.gdb.*;
import dz.layouts.DebuggerLayout;
import dz.tabs.*;
import dzaima.ui.gui.*;
import dzaima.ui.gui.config.GConfig;
import dzaima.utils.*;
import io.github.humbleui.skija.Paint;

import java.util.*;

public class FlamegraphRender {
  final TimelineManagerTab tm;
  final Location high, fail, self;
  private static final ProcThread.StackFrame unkFrame = new ProcThread.StackFrame(0, new Location(0L, "??", null, null, null), false);
  final int b; // border size
  final GConfig gc;
  final TimelineTab.TimelineNode n;
  double fracToWholeError = 0.2;
  double fracToMergeOver = 0.5;
  int preMax = 5;
  
  public FlamegraphRender(TimelineTab.TimelineNode n, TimelineManagerTab tm) {
    this.n = n;
    this.tm = tm;
    this.gc = n.gc;
    b = 1;
    
    high = new Location(null, "(stack too high)", null, null, null);
    fail = new Location(null, "(failed to get stacktrace)", null, null, null);
    self = new Location(null, "(self)", null, null, null);
  }
  
  boolean isBad(String s) {
    return high.sym.equals(s) || fail.sym.equals(s);
  }
  boolean isBad(Location l) {
    return l==high || l==fail;
  }
  
  final Vec<FlameStack> ch0 = new Vec<>();
  final Vec<FlameStack> stack = new Vec<>();
  FlameStack pop(double time) {
    FlameStack t = stack.pop();
    t.tE = time;
    return t;
  }
  void push(FlameStack n) {
    (stack.sz==0? ch0 : stack.peek().ch).add(n);
    stack.add(n);
  }
  void assertTopFail(double time, Location what) {
    Location top = stack.sz==0? null : stack.peek().l;
    if (top==what) return;
    if (isBad(top)) pop(time);
    push(new FlameStack(T_BAD, what, time));
  }
  
  private boolean eqLoc(Location a, Location b) {
    return a.sym.equals(b.sym);
  }
  
  public FlamegraphRender calc(long tid, double g0, double g1, double v0, double v1) {
    boolean merge = tm.fg_merge;
    // boolean trim = tm.fg_trim;
    boolean local = tm.fg_local;
    // TODO option to exclude event time from merged time
    double s0 = local? Math.max(v0,g0) : g0; 
    double s1 = local? Math.min(v1,g1) : g1;
    double visTime = v1 - v0; 
    
    ch0.clear();
    stack.clear();
    
    Vec<Pair<RecordedState, Double>> samples = tm.samplesInRange(tid, s0, s1);
    samples.sort(Comparator.comparing(c -> c.b));
    Vec<RRDump.Event> evs = ((DebuggerLayout) tm.g).rrDump.get().thread(tid).evs;
    int evi = 0;
    
    double t0 = -1;
    for (Pair<RecordedState, Double> s : samples) {
      double t1 = s.b;
      
      while (evi<evs.sz && evs.get(evi).time < t1) {
        RRDump.Event e0 = evs.get(evi++);
        if (evi>=evs.sz) continue;
        if (e0.mode!=RRDump.EvMode.START) continue;
        
        RRDump.Event e1 = evs.get(evi);
        if (!e1.functionsAsEnd()) continue;
        if (!e0.evName.equals(e1.evName)) continue;
        evi++;
        
        String name = e0.evName;
        if (name.startsWith("SYSCALL: ")) name = "(syscall: "+name.substring(9)+")";
        else name = "(event: "+name+")";
        push(new FlameStack(0, new Location(0L, name, null, null, null), e0.time));
        pop(t0 = e1.time);
      }
      
      if (t0==-1) {
        if (local) t1 = s0;
        t0 = t1;
      }
      double time = (t0+t1)/2;
      t0 = t1;
      
      Pair<Vec<ProcThread.StackFrame>, Boolean> sRes = s.a._limitedStack.get(s.a.currThread).get();
      if (sRes==null) { assertTopFail(time,fail); continue; }
      Vec<ProcThread.StackFrame> fs = sRes.a;
      if (sRes.b) { assertTopFail(time,high); continue; }
      if (fs.sz==0 || fs.peek().l.addr==0) { assertTopFail(time,fail); continue; } // TODO stack on top with heuristics? idk
      
      ProcThread.StackFrame[] rev = new ProcThread.StackFrame[fs.sz];
      int ri = 0;
      for (int i = 0; i < rev.length; i++) {
        ProcThread.StackFrame f = fs.get(rev.length-i-1);
        if (f.l.sym==null) {
          if (ri>0 && rev[ri-1]!=unkFrame) rev[ri++] = unkFrame;
        } else {
          rev[ri++] = f;
        }
      }
      if (ri==0) { assertTopFail(time,fail); continue; } // stack that is all entries without symbols, but bottom wasn't NULL
      if (rev.length!=ri) rev = Arrays.copyOf(rev, ri);
      
      int c = 0;
      for (int end = Math.min(stack.sz,rev.length); c < end; c++) if (!eqLoc(stack.get(c).l, rev[c].l)) break;
      
      for (int i = stack.sz-1; i >= c; i--) {
        FlameStack top = pop(time);
        if (isBad(top.l) && top.d() > visTime*fracToWholeError) {
          FlameStack top2 = (stack.sz == 0? ch0 : stack.peek().ch).pop();
          assert top == top2;
          double tS = top.tS;
          while (stack.sz!=0) pop(tS);
          push(top);
          pop(time);
          c = 0;
          break;
        }
      }
      
      for (int i = c; i < rev.length; i++) {
        push(new FlameStack(T_REG, rev[i].l, time));
      }
    }
    for (int i = stack.sz; i > 0; i--) pop(local? s1 : t0);
    
    if (merge) {
      double sep = visTime*fracToMergeOver;
      for (FlameStack f : ch0) merge(f, sep);
    }
    
    return this;
  }
  
  private static class MChs {
    final String sym;
    final Vec<FlameStack> ch;
    final double d;
    private MChs(String sym, Vec<FlameStack> ch, double d) { this.sym = sym; this.ch=ch; this.d=d; }
  }
  void mergeEntries(Vec<FlameStack> ch, double tS0, double tE0) {
    if (ch.sz==0) return;
    
    HashMap<String, Vec<FlameStack>> m = new LinkedHashMap<>();
    for (FlameStack f : ch) m.computeIfAbsent(f.l.sym, s -> new Vec<>()).add(f);
    
    Box<Double> tS = new Box<>(tS0);
    Vec<MChs> es = Vec.ofCollection(m.entrySet()).map(e -> {
      double d = 0;
      Vec<FlameStack> mch = new Vec<>();
      for (FlameStack f : e.getValue()) {
        d+= f.d();
        mch.addAll(f.ch);
      }
      return new MChs(e.getKey(), mch, d);
    });
    // es.sort(Comparator.comparing(c -> -c.t));
    es.sort(Comparator.comparing(c -> c.sym)); // sorting by duration or first occurrence means weird things on scrolling loading samples in/out 
    ch.clear();
    
    es.forEach((e) -> {
      FlameStack r = ch.add(new FlameStack(isBad(e.sym)? T_BAD : T_MERGE, new Location(null, e.sym, null, null, null), tS.get()));
      r.ch.swap(e.ch);
      mergeEntries(r.ch, r.tS, r.tE);
      r.tE = r.tS+e.d;
      tS.set(r.tE);
    });
    
    double tE = tS.get();
    if (tE0-tE > (tE0-tS0)*0.01) {
      FlameStack self = ch.add(new FlameStack(T_SELF, this.self, tE));
      self.tE = tE0;
      ch.add(self);
    }
  }
  
  int pe;
  void procEnd(Vec<FlameStack> r, Vec<FlameStack> ch, int gs, int ge, int te) {
    ge = Math.min(ge, te);
    gs = Math.min(gs, ge);
    r.addAll(r.sz, ch, pe, gs);
    if (gs != ge) {
      Vec<FlameStack> m = Vec.ofReuse(ch.get(gs, ge, FlameStack[].class));
      mergeEntries(m, m.get(0).tS, m.peek().tE);
      r.addAll(m);
    }
    r.addAll(r.sz, ch, ge, te);
    pe = te;
  }
  void merge(FlameStack f, double sep) {
    Vec<FlameStack> ch = f.ch;
    if (ch.sz>=1) {
      Vec<FlameStack> r = new Vec<>();
      pe = 0;
      
      int gsC = Integer.MAX_VALUE;
      int geC = 0;
      HashMap<String, Integer> curr = new HashMap<>();
      for (int i = 0; i < ch.sz; i++) {
        FlameStack c = ch.get(i);
        if (c.d() > sep) {
          procEnd(r, ch, gsC, geC, i);
          gsC = geC = Integer.MAX_VALUE;
          curr.clear();
        } else {
          Integer i0 = curr.putIfAbsent(c.l.sym, i);
          if (i0!=null) {
            gsC = Math.min(gsC, i0);
            geC = i+1;
          }
        }
      }
      procEnd(r, ch, gsC, geC, ch.sz);
      
      ch.swap(r);
    }
    for (FlameStack c : ch) if (c.type==T_REG) merge(c, sep);
  }
  
  Graphics g;
  int gw, y0, eh, mx, my, tsz;
  Font fnt;
  public void draw(Graphics g, int y0, int y, int w, int mx, int my) {
    this.g = g;
    this.gw = w;
    this.y0 = y0;
    this.mx = mx;
    this.my = my;
    border.setColor(borderCol = 0xff222222);
    border.setStroke(true);
    border.setStrokeWidth(b);
    tyBgs[0].setColor(0x3f40ff40); hvBgs[0].setColor(0x4C40ff40);
    tyBgs[1].setColor(0x3f4080ff); hvBgs[1].setColor(0x574080ff);
    tyBgs[2].setColor(0x0f4080ff); hvBgs[2].setColor(0x1f4080ff);
    tyBgs[3].setColor(0x3fff4040); hvBgs[3].setColor(0x57ff4040);
    eh = gc.getProp("grr.tabs.timeline.flamegraphEntH").len();
    tsz = gc.getProp("grr.tabs.timeline.flamegraphFontSize").len();
    fnt = Typeface.of(gc.getProp("grr.codeFamily").str()).sizeMode(tsz, 0);
    for (FlameStack f : ch0) recDrawFlamegraph(y0-y, f, new Vec<>());
    this.g = null;
  }
  
  @SuppressWarnings("resource")
  Paint[] tyBgs = new Paint[]{new Paint(),new Paint(),new Paint(),new Paint()};
  @SuppressWarnings("resource")
  Paint[] hvBgs = new Paint[]{new Paint(),new Paint(),new Paint(),new Paint()};
  Paint border = new Paint();
  int borderCol;
  int[] tyCols = new int[]{0xffD2D2D2, 0xffD2D2D2, 0x3fD2D2D2, 0x7fD2D2D2};
  @SuppressWarnings("StringConcatenationInLoop")
  private void recDrawFlamegraph(int yS, FlameStack f, Vec<String> pre) {
    float x0f = n.tToXf(f.tS);
    float x1f = n.tToXf(f.tE);
    float dx = x1f - x0f;
    if (dx < Math.max(1, gc.em*0.1f)) return;
    if (x1f<0 || x0f>gw) return;
    int x0 = (int) x0f;
    int x1 = (int) x1f;
    
    String sym = f.l.sym;
    int yE = yS + eh;
    boolean bad = isBad(f.l);
    if (yE > y0 || bad || (pre!=null && pre.sz>=preMax)) {
      int y1 = y0+eh;
      boolean pin = pre!=null && yE<=y1;
      int dys = pin? y0 : yS;
      int dye = pin? y1 : yE;
      boolean clipUp = !pin && yS <= y1;
      
      if (clipUp) {
        g.push();
        g.clipE(x0, y1, x1, yE);
      }
      
      boolean hover = mx>=x0 && mx<x1 && my>=(clipUp?y1:dys) && my<dye;
      g.rect(x0, dys, x1-b, dye-b, (hover? hvBgs : tyBgs)[f.type]);
      
      if (dx > gc.em) {
        g.push();
        g.clipE(x0, dys, x1-b, dye-b);
        String text;
        if (pin && !bad && pre.sz>0) {
          pre.add(sym);
          text = "";
          for (int i = pre.sz==1?0:1; i < pre.sz; i++) {
            if (!text.isEmpty()) text+= "→";
            text+= pre.get(i);
          }
        } else {
          text = sym;
        }
        float tw = pin? fnt.widthf(text) : 0;
        float tx = Math.max(x0, 0);
        if (tw>dx) tx = Math.min(x1, gw)-tw;
        g.text(text, fnt, tx, dye-fnt.dscI - (eh-fnt.hi)/2f, tyCols[f.type]);
        g.pop();
      }
      
      if (clipUp) g.pop();
      
      if (pin) pre = null;
    }
    if (pre!=null) pre.add(sym);
    for (FlameStack ch : f.ch) recDrawFlamegraph(yE, ch, pre);
    if (pre!=null) pre.pop();
  }
  
  
  private static final int T_REG = 0;
  private static final int T_MERGE = 1;
  private static final int T_SELF = 2;
  private static final int T_BAD = 3;
  private static class FlameStack {
    final int type; // 0: regular; 1: merged function; 2: self-time; 3: error
    final Location l;
    final double tS;
    final Vec<FlameStack> ch = new Vec<>();
    double tE;
    
    private FlameStack(int type, Location l, double tS) {
      this.l = l;
      this.tS = tS;
      this.type = type;
    }
    double d() { return tE-tS; }
  }
}