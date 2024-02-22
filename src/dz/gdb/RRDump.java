package dz.gdb;

import dz.debugger.TimeManager;
import dz.layouts.DebuggerLayout;
import dz.utils.*;
import dzaima.utils.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.function.Consumer;
import java.util.regex.*;

public class RRDump {
  public final Vec<Event> rawAllEvents;
  public final Vec<Long> processes;
  public final HashMap<Long, ThreadData> perThread;
  public final long tidDefault;
  public final double origTimeStart;
  public final double tEnd; // event times are in [0; tEnd]
  private RRDump(Vec<Event> evs, HashMap<Long, Vec<Long>> cloneProc, HashMap<Long, Vec<Long>> cloneThread) {
    origTimeStart = evs.get(0).time;
    for (Event c : evs) {
      c.time-= origTimeStart;
    }
    rawAllEvents = evs;
    tidDefault = evs.get(0).tid;
    
    processes = new Vec<>();
    processes.add(tidDefault);
    for (Vec<Long> v : cloneProc.values()) processes.addAll(v);
    
    HashMap<Long, Long> whoThreadedMe = new HashMap<>();
    cloneThread.forEach((k, v) -> v.forEach(c -> whoThreadedMe.put(c, k)));
    HashMap<Long, Long> tidToGid = new HashMap<>();
    cloneThread.values().forEach(ts -> ts.forEach(t -> {
      long c = t;
      while (true) {
        Long n = whoThreadedMe.get(c);
        if (n==null) break;
        c = n;
      }
      tidToGid.put(t, c);
    }));
    processes.forEach(p -> tidToGid.put(p, p));
    
    tEnd = evs.peek().time;
    
    HashMap<Long, Vec<Event>> t = new HashMap<>();
    for (Event c : evs) t.computeIfAbsent(c.tid, l -> new Vec<>()).add(c);
    perThread = new HashMap<>();
    Vec<Long> empty = new Vec<>();
    t.forEach((k,v) -> perThread.put(k, new ThreadData(v, k, tidToGid.get(k), cloneProc.getOrDefault(k, empty), cloneThread.getOrDefault(k, empty))));
  }
  
  public ThreadData thread(long tid) {
    return perThread.get(tid);
  }
  public ThreadData thread(Executable.ThreadState thr) {
    return thread(thr.tid);
  }
  public long gid(long tid) {
    return thread(tid).gid;
  }
  
  public static class ThreadData {
    public final long tid, gid;
    public final Vec<Event> evs, evsRaw;
    public final Vec<Long> spawns, threads;
    public final double tStart;
    public final double tEnd;
    public ThreadData(Vec<Event> evsRaw, long tid, long gid, Vec<Long> spawns, Vec<Long> threads) {
      this.tid = tid;
      this.gid = gid;
      this.spawns = spawns;
      this.threads = threads;
      this.evsRaw = evsRaw;
      this.evs = evsRaw.filter(c -> c.evType!=EvType.SLOPPY);
      // long lastTick = 0;
      // for (Event c : evs) {
      //   if (c.ticks<lastTick) {
      //     if (c.ticks!=0) Log.warn("rr dump", "backwards ticks: event "+c.when+" ticks=="+c.ticks+", previous=="+lastTick);
      //     c.ticks = lastTick;
      //   }
      //   lastTick = c.ticks;
      // }
      tStart = evsRaw.get(0).time;
      tEnd = evsRaw.peek().time;
    }
    
    public Pair<Event,Event> around(TimeManager.TickRef r) { // gives two adjacent events a,b such that r∊[a;b); OOB r results in a==b
      int s=0, e=evs.sz;
      while (s+1<e) {
        int m = (s+e)/2;
        if (r.compareTo(evs.get(m))>=0) s=m;
        else e=m;
      }
      Event c = evs.get(s);
      if (r.compareTo(c)<0) return new Pair<>(c, c);
      return new Pair<>(c, s+1>=evs.sz? c : evs.get(s+1));
    }
    public int ticksToIdx(long ticks) {
      int s=0, e=evs.sz;
      while (s+1<e) {
        int m = (s+e)/2;
        if (ticks>=evs.get(m).ticks) s=m;
        else e=m;
      }
      return s;
    }
    public int timeToIdx(double time) {
      int s=0, e=evs.sz;
      while (s+1<e) {
        int m = (s+e)/2;
        if (time>=evs.get(m).time) s=m;
        else e=m;
      }
      return s;
    }
    
    public double ticksToTime(long ticks) {
      int i = ticksToIdx(ticks);
      Event e0 = evs.get(i);
      Event e1 = i+1>=evs.sz? e0 : evs.get(i+1);
      if (e0.ticks+1==e1.ticks) return e0.time;
      return e0.time + (e1.time-e0.time) * (ticks-e0.ticks)/(e1.ticks-e0.ticks-1);
    }
  }
  
  public enum EvMode { SINGLE, START, END }
  public enum EvType { UNKNOWN, SLEEP, DEBUGBREAK, SCHED, SLOPPY, BACK }
  public static class Event extends TimeManager.TickRef {
    public double time = -1;
    public EvMode mode = EvMode.SINGLE;
    public String evName = "??";
    public EvType evType = EvType.UNKNOWN;
    public long tid = -1;
    private boolean functionsAsEnd;
    public Event() { super(-1,-1); }
    public boolean functionsAsEnd() { return mode==EvMode.END || functionsAsEnd; }
    public boolean eventOk() { return evType!=EvType.SCHED; }
    
    public String toString() {
      return "ev["+event+"]{"+time+"s ticks:"+ticks+" tid:"+tid+" "+mode+" "+evType+" "+evName+"}";
    }
  }
  
  
  private static final Pattern clone_flags = Pattern.compile("tid=(\\d+) parent=(\\d+) clone_flags=0x([0-9a-fA-F]+)");
  public static void load(DebuggerLayout d, String rrBin, String name, Consumer<RRDump> got) {
    Vec<String> cmd = Vec.of(
      rrBin==null? "rr" : rrBin,
      "dump",
      "-e"
    );
    if (name!=null) { cmd.add("--"); cmd.add(name); }
    try {
      ProcessBuilder pb = new ProcessBuilder(cmd.toArray(new String[0]));
      Process p = pb.start();
      OutQueue stdout = new OutQueue(p.getInputStream(), ()->{});
      OutQueue stderr = new OutQueue(p.getErrorStream(), ()->{});
      
      HashMap<String, String> intern = new HashMap<>();
      Vec<Event> evs = new Vec<>();
      HashMap<Long,Vec<Long>> cloneProc = new HashMap<>();
      HashMap<Long,Vec<Long>> cloneThread = new HashMap<>();
      
      LineRequeue re = new LineRequeue(b -> {
        String ln = new String(b, StandardCharsets.UTF_8);
        if (ln.startsWith("  TraceTaskEvent::CLONE ")) {
          Matcher m = clone_flags.matcher(ln);
          if (m.find()) {
            long child = Long.parseUnsignedLong(m.group(1));
            long parent = Long.parseUnsignedLong(m.group(2));
            long flags = Long.parseUnsignedLong(m.group(3), 16);
            boolean makesThread = (flags & 0x10000) != 0;
            HashMap<Long,Vec<Long>> r = makesThread? cloneThread : cloneProc;
            r.computeIfAbsent(parent, c -> new Vec<>()).add(child);
          }
          return;
        }
        if (!ln.startsWith("  real_time:")) return;
        ln = ln.substring(2);
        Event ev = new Event();
        
        for (String c : ln.split(" ")) {
          if (tryPrefix(c, "real_time:",   l -> ev.time  = Double.parseDouble(l))) continue;
          if (tryPrefix(c, "global_time:", l -> ev.event = Long.parseLong(l))) continue;
          if (tryPrefix(c, "tid:",         l -> ev.tid   = Long.parseLong(l))) continue;
          if (tryPrefix(c, "ticks:",       l -> ev.ticks = Long.parseLong(l))) continue;
          if (c.contains("ENTERING_SYSCALL")) ev.mode = EvMode.START;
          else if (c.contains("EXITING_SYSCALL")) ev.mode = EvMode.END;
          else if (c.contains("SYSCALLBUF_RESET")) return; // TODO is this still needed with EvType.SLOPPY?
        }
        
        int nStart = ln.indexOf(", event:`");
        if (nStart!=-1) {
          nStart+= 9;
          int nEnd = ln.indexOf('\'', nStart);
          if (nEnd!=-1) {
            String n = ln.substring(nStart, nEnd);
            String ni = intern.putIfAbsent(n, n);
            if (ni != null) n = ni;
            ev.evName = n;
            switch (n) {
              case "SYSCALLBUF_ABORT_COMMIT": case "SYSCALLBUF_FLUSH": case "SYSCALLBUF_RESET":
              case "DESCHED": case "GROW_MAP":
                ev.evType = EvType.SLOPPY;
                break;
              case "SYSCALL: <unknown-syscall--1>":
                ev.evType = EvType.DEBUGBREAK;
                break;
              case "SCHED":
                ev.evType = EvType.SCHED;
                break;
              default:
                if (n.contains("nanosleep")) ev.evType = EvType.SLEEP;
                break;
            }
          }
        }
        
        evs.add(ev);
      });
      
      d.rrDumpCheck = (() -> {
        boolean isAlive = p.isAlive();
        
        while (stdout.has()) re.add(stdout.takeOne());
        
        if (isAlive) return;
        d.rrDumpCheck = null;
        stdout.stop();
        stderr.stop();
        if (p.exitValue()!=0) { Log.warn("rr dump", "Unexpected exit code"); got.accept(null); return; }
        
        got.accept(evs.sz==0? null : new RRDump(evs, cloneProc, cloneThread));
      });
    } catch (IOException e) {
      Log.stacktrace("rr dump loader", e);
      got.accept(null);
    }
  }
  private static boolean tryPrefix(String c, String pre, Consumer<String> r) {
    if (c.startsWith(pre)) {
      int e = c.indexOf(',');
      r.accept(c.substring(pre.length(), e==-1? c.length() : e));
      return true;
    }
    return false;
  }
}
