package dz.debugger;

import dz.gdb.*;
import dzaima.utils.*;

import java.util.HashMap;

public class StateManager {
  public final Dbi d;
  public Vec<RecordedState> hist = new Vec<>();
  
  // rr-specific
  public HashMap<Long, TimeManager> threadTimes = new HashMap<>();
  public Promise<RRDump> dump = new Promise<>();
  
  public StateManager(Dbi d) {
    this.d = d;
  }
  
  public void setDump(RRDump d) { dump.set(d); }
  public boolean dumpReady() { return dump.isResolved(); }
  
  public void addExplicit(RecordedState s, OnResolved onResolved) {
    hist.add(s);
    add(s, onResolved);
  }
  public void add(RecordedState s, OnResolved onResolved) {
    if (d.isRR()) {
      TimeManager.prefetch(s);
      s.currThreadState(th -> {
        if (th==null) return;
        dump.then(d -> forThread(d, th.tid).addState(s, onResolved));
      });
    }
  }
  
  public TimeManager forThread(RRDump d, long tid) {
    return threadTimes.computeIfAbsent(tid, tid2 -> new TimeManager(d.thread(tid2)));
  }
  
  public TimeManager forThreadX(long tid) {
    return threadTimes.get(tid);
  }
  
  
  public interface OnResolved {
    void accept(double time, TimeManager.Spot spot);
  }
}
