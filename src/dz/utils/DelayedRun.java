package dz.utils;

import dz.layouts.GdbLayout;

import java.time.Instant;

public class DelayedRun implements Comparable<DelayedRun> {
  private final GdbLayout g;
  public final int ms;
  private Runnable thing;
  public Instant when;
  private final long id;
  private static long global_id;
  
  public DelayedRun(GdbLayout g) {
    this(g, 300);
  }
  
  public DelayedRun(GdbLayout g, int ms) {
    this.g = g;
    this.ms = ms;
    id = global_id++;
  }
  
  public boolean isWaiting() {
    return when!=null;
  }
  
  public void set(Runnable r) {
    if (isWaiting()) cancel();
    thing = r;
    when = g.approxNow.plusMillis(ms);
    g.delayedRuns.add(this);
  }
  
  public void cancel() {
    if (!isWaiting()) return;
    g.delayedRuns.remove(this);
    when = null;
    thing = null;
  }
  
  public int compareTo(DelayedRun o) {
    assert when!=null && o.when!=null;
    int i = when.compareTo(o.when);
    if (i!=0) return i;
    return Long.compare(id, o.id);
  }
  
  public boolean maybeRun() {
    if (g.approxNow.isAfter(when)) {
      thing.run();
      cancel();
      return true;
    }
    return false;
  }
}
