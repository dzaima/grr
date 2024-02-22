package dz.debugger;

import dz.debugger.StateManager.OnResolved;
import dz.gdb.RRDump;
import dz.utils.Promise;
import dzaima.utils.*;

import java.util.*;
import java.util.function.Consumer;

public class TimeManager {
  public boolean noMergeAdj;
  public final RRDump.ThreadData evs;
  public final SortedMap<TickRef, Group> groups = new TreeMap<>();
  public final NavigableMap<Spot, SpotInterval> adjIntervals = new TreeMap<>(); // key is start
  
  public TimeManager(RRDump.ThreadData evs) {
    this.evs = evs;
  }
  
  public TickRef timeToTicksTrivial(double time) {
    int i = evs.timeToIdx(time);
    RRDump.Event e0 = evs.evs.get(i);
    RRDump.Event e1 = i+1>=evs.evs.sz? e0 : evs.evs.get(i+1);
    if (e0.ticks+1 >= e1.ticks) return new TickRef(e0.event, e0.ticks);
    long ticks = e0.ticks + (long) ((e1.ticks-e0.ticks+1) * (time-e0.time)/(e1.time-e0.time));
    return new TickRef(e0.event, ticks);
  }
  
  public TickRef timeToTicks(double time) {
    return timeToTicksTrivial(time);
  }
  
  public Group getGroup(TickRef t, boolean create) {
    Pair<RRDump.Event, RRDump.Event> p = evs.around(t);
    if (p.a != p.b) t = new TickRef(p.a.event, t.ticks); // discard a when which is on a SYSCALLBUF_FLUSH
    Group g = groups.get(t);
    if (g==null) {
      if (!create) return null;
      groups.put(t, g = new Group(this, t));
    }
    return g;
  }
  public static void prefetch(RecordedState s) {
    s.currThreadWhen(r->{});
    s.getLWCP(r->{});
  }
  public void addState(RecordedState s, OnResolved onResolved) {
    Promise<TickRef> t = Promise.create(p -> s.currThreadWhen(p::set));
    Promise<Integer> c = Promise.create(p -> s.getLWCP(p::set));
    Promise.run2(t, c, (tr, cr) -> { if (tr!=null) getGroup(tr, true).add(s, cr, onResolved); });
  }
  
  public Spot tryFind(RecordedState s) {
    if (!s._ticks.isResolved() || s._ticks.get()==null) return null;
    Group g = getGroup(s._ticks.get(), false);
    if (g==null) return null;
    return g.find(s);
  }
  public Promise<Spot> find(RecordedState s) {
    return Promise.create(g -> addState(s, (t, r) -> g.set(r)));
  }
  
  
  public static class Spot implements Comparable<Spot> { // a single instruction-precise point in time
    public final int lwcp; // or -1 if not recorded
    public final Group g;
    public final RecordedState s; // canonical state
    public final Vec<RecordedState> alts = new Vec<>();
    public final Promise<Pair<Double, Spot>> promise = new Promise<>();
    
    public Spot(RecordedState s, int lwcp, Group g) {
      this.s = s;
      this.lwcp = lwcp;
      this.g = g;
    }
    
    public double myTimeTrivial() {
      double gs = g.ts;
      double ge = g.te;
      if (gs == ge) return gs;
      double avg = (gs+ge) / 2;
      int n = g.instrs.sz;
      if (n==1) return avg;
      int i = g.instrs.indexOf(this);
      double dev = ge - gs;
      double pos = i/(n-1.0) - 0.5; // pos∊[-0.5;0.5]
      double range0 = 0.95 - 5.0/(n+2);
      double range1 = (n+2)*0.045;
      double range = n<8? range1 : range0; // must be ∊[0;1] for n∊[2;∞)
      return avg + pos*dev*range;
    }
    private double cachedTime = -1;
    public double myTime() {
      if (cachedTime != -1) return cachedTime;
      if (g.tm.noMergeAdj) return cachedTime = myTimeTrivial();
      Map.Entry<Spot, SpotInterval> it = g.tm.adjIntervals.floorEntry(this); // greatest with it.s <= this
      if (it!=null) {
        SpotInterval itv = it.getValue();
        if (compareTo(itv.e)<=0) { // it.s <= this && this <= it.e
          double ts = itv.s.myTimeTrivial();
          double te = itv.e.myTimeTrivial();
          int i = itv.before(this);
          return cachedTime = ts + (te-ts) * i / (itv.spots.size()-1);
        }
      }
      return cachedTime = myTimeTrivial();
    }
    public void invalidateCachedTime() {
      cachedTime = -1;
    }
    
    public boolean isAt(RecordedState st) {
      return s==st || alts.linearFind(c->c==st)!=null;
    }
    
    public int compareTo(Spot o) {
      int r = g.compareTo(o.g);
      if (r != 0) return r;
      return Integer.compare(g.instrs.indexOf(this), g.instrs.indexOf(o));
    }
    
    public String toString() { return "(spot ev="+g.event+" t="+g.ticks+" "+g.instrs.indexOf(this)+"/"+g.instrs.sz+")"; }
  }
  public class Group extends TickRef { // single unique event/tick pair, likely multiple instructions
    public final TimeManager tm;
    public final Vec<Spot> instrs = new Vec<>(); // canonical spots, ordered in ascending time order
    public final Vec<Spot> pending = new Vec<>(); // may be discarded, may be moved to instrs
    public final double ts, te;
    
    public Group(TimeManager tm, TickRef r) {
      super(r.event, r.ticks);
      this.tm = tm;
      Pair<RRDump.Event, RRDump.Event> ab = evs.around(this);
      // System.out.println(ab.a + " <= "+this+" <= "+ab.b);
      // a.when ≤ when < b.when;  a.ticks ≤ ticks ≤ b.ticks  (maybe except at the edges of the entire recording)
      long dt = ab.b.ticks - ab.a.ticks;
      if (dt==0) {
        ts = ab.a.time;
        te = ab.b.time;
      } else {
        double m = (ab.b.time-ab.a.time) / (dt+1); // time allocated to one tick
        ts = ab.a.time + (ticks-ab.a.ticks)*m;
        te = ts + m;
      }
    }
    
    boolean noCompareCheckpoints;
    public void add(RecordedState st, Integer lwcp, OnResolved onResolved) {
      Consumer<Pair<Double, Spot>> then = p -> onResolved.accept(p.a, p.b);
      
      Vec<Spot> ss = new Vec<>();
      ss.addAll(instrs);
      ss.addAll(pending);
      for (Spot c : ss) if (c.s == st) {
        c.promise.then(then);
        return;
      }
      ss.filterInplace(c -> c.lwcp!=-1);
      
      Spot tmp = new Spot(st, lwcp==null? -1 : lwcp, this);
      tmp.promise.then(then);
      
      Consumer<IntVec> got = iv -> {
        if (pending.sz>0) pending.remove(tmp);
        HashMap<Spot, Integer> cmps = new HashMap<>();
        for (int i = 0; i < ss.sz; i++) cmps.put(ss.get(i), iv.get(i));
        Spot found = null;
        for (int i = 0; i < instrs.sz; i++) {
          Spot c = instrs.get(i);
          Integer cc = cmps.get(c);
          if (cc==null) cc = 1;
          if (cc==0) {
            c.alts.add(st);
            found = c;
            break;
          }
          if (cc==-1) {
            instrs.insert(i, tmp);
            found = tmp;
            break;
          }
        }
        if (found==null) instrs.add(found = tmp);
        for (Spot c : instrs) c.invalidateCachedTime();
        tmp.promise.set(new Pair<>(found.myTime(), found));
        if (!st._spot.isResolved()) st._spot.set(found);
      };
      if (ss.sz==0) {
        got.accept(new IntVec());
      } else {
        pending.add(tmp);
        Consumer<String> onCmp = (s) -> {
          IntVec v = new IntVec();
          ok: if (s!=null) {
            String[] ps0 = s.split(" is ");
            if (ps0.length!=2) break ok;
            String[] ps = ps0[1].split(", ");
            if (ps.length!=ss.sz) break ok;
            for (String p : ps) v.add(p.startsWith("after")? 1 : p.startsWith("before")? -1 : 0);
            got.accept(v);
            return;
          }
          if (!noCompareCheckpoints) {
            noCompareCheckpoints = true;
            Log.info("No compare-checkpoint");
          }
          for (int i = 0; i < ss.sz; i++) v.add(1);
          got.accept(v);
        };
        if (noCompareCheckpoints || lwcp==null) {
          onCmp.accept(null);
        } else {
          StringBuilder cmd = new StringBuilder("compare-checkpoint ");
          cmd.append(lwcp);
          for (Spot c : ss) cmd.append(' ').append(c.lwcp);
          
          st.exe.d.p.consoleCmd(cmd.toString()).ds().runWithPrefix("~\"Checkpoint ", (r, s) -> onCmp.accept(s));
        }
      }
    }
    
    public Spot find(RecordedState s) {
      return instrs.linearFind(c -> c.isAt(s));
    }
  }
  
  
  public static class TickRef implements Comparable<TickRef> {
    public long event, ticks;
    public TickRef(long event, long ticks) {
      this.event = event;
      this.ticks = ticks;
    }
    public int compareTo(TickRef o) {
      int r = Long.compare(event, o.event);
      if (r!=0) return r;
      return Long.compare(ticks, o.ticks);
    }
    public boolean equals(Object o0) {
      if (!(o0 instanceof TickRef)) return false;
      TickRef o = (TickRef) o0;
      return event==o.event && ticks==o.ticks;
    }
    
    public String toString() {
      return "(w="+event+" t="+ticks+")";
    }
  }
  
  
  public void addAdjacent(RecordedState s, RecordedState e) {
    Promise.run2(find(s), find(e), this::addAdjacent);
  }
  public void addAdjacent(Spot s, Spot e) { // assumes there's only one instruction
    assert s.g.tm==this && e.g.tm==this;
    if (s.g.event != e.g.event) return; // don't want to count cross-event spots as adjacent, that messes things up
    int cmp = s.compareTo(e);
    if (cmp==0) return;
    if (cmp>0) { Spot t=s;s=e;e=t; }
    Spot rs = s;
    Spot re = e;
    Vec<Spot> sSet = null;
    Vec<Spot> eSet = null;
    Map.Entry<Spot, SpotInterval> prev = adjIntervals.floorEntry(s); // greatest with val.s <= s
    if (prev!=null) {
      if (prev.getValue().e.compareTo(e) >= 0) return; // val.s<=s && val.e>=e → completely overlaps
      if (prev.getValue().e.compareTo(s) >= 0) { // else, val.e<s - no overlap
        rs = prev.getKey();
        sSet = prev.getValue().spots;
        adjIntervals.remove(prev.getKey());
      }
    }
    
    Map.Entry<Spot, SpotInterval> next = adjIntervals.higherEntry(s); // least with val.s > s
    if (next!=null) {
      // no chance for complete overlap - prev already handles that
      if (next.getKey().compareTo(e) <= 0) { // else, val.s>e - no overlap
        re = next.getValue().e;
        eSet = next.getValue().spots;
        adjIntervals.remove(next.getKey());
      }
    }
    
    Vec<Spot> rSet;
    if (sSet!=null && eSet!=null) {
      boolean sBigger = sSet.size() > eSet.size();
      if (sBigger) { rSet = sSet; rSet.addAll(eSet); }
      else         { rSet = eSet; rSet.addAll(sSet); }
    } else {
      rSet = sSet!=null? sSet : eSet;
      if (rSet==null) rSet = new Vec<>();
      if (sSet==null) rSet.add(rs);
      if (eSet==null) rSet.add(re);
    }
    for (Spot c : rSet) c.invalidateCachedTime(); // TODO this is very O(n^2)
    adjIntervals.put(rs, new SpotInterval(rs, re, rSet));
  }
  
  public static class SpotInterval {
    public final Spot s, e;
    public final long ts, te;
    public final Vec<Spot> spots;
    private boolean spotsSorted = false;
    public SpotInterval(Spot s, Spot e, Vec<Spot> spots) {
      this.spots = spots;
      this.s = s;
      this.e = e;
      this.ts = s.g.ticks;
      this.te = e.g.ticks;
    }
    public int before(Spot t) {
      if (!spotsSorted) {
        spots.sort();
        spotsSorted = true;
      }
      int s = 0;
      int e = spots.size();
      while (s+1 < e) {
        int m = (s+e)/2;
        if (spots.get(m).compareTo(t)<=0) s = m;
        else e = m;
      }
      return s;
    }
  }
}
