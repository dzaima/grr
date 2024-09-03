package dz.debugger;

import dz.gdb.*;
import dz.gdb.Executable.ThreadState;
import dzaima.utils.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.function.*;

@SuppressWarnings("UnusedReturnValue")
public class RecordedState {
  public boolean requestable;
  public final Executable exe;
  public final StateManager smgr;
  public final Instant visitTime;
  
  // TODO extract functions that are explicitly specified to fill the promises
  public Promise<Long> _elapsedTime;
  public Promise<TimeManager.TickRef> _ticks;
  public final HashMap<ProcThread, Promise<Pair<Vec<ProcThread.StackFrame>, Boolean>>> _limitedStack = new HashMap<>();
  public final HashMap<ProcThread, Promise<Pair<Vec<ProcThread.StackFrame>, Boolean>>> _fullStack = new HashMap<>();
  public Promise<Integer> _lwcp;
  public final ProcThread currThread;
  public Promise<Vec<ThreadState>> _threads;
  public Promise<ThreadState> _currThreadState;
  public final Promise<TimeManager.Spot> _spot = new Promise<>(); // set by TimeManager
  public final Vec<Runnable> onUpdate = new Vec<>();
  
  public RecordedState(StateManager smgr, Executable exe) {
    this.exe = exe;
    this.smgr = smgr;
    this.visitTime = Instant.now();
    currThread = ProcThread.makeCurrThread(exe);
  }
  
  private void updated() {
    for (Runnable r : onUpdate) r.run();
  }
  
  
  // All following methods return whether they're requestable currently.
  // If is requestable, `got` may be called immediately, or at some later point (but before any later state may become active)
  // If not, `got` will never be called.
  
  public boolean threads(Consumer<Vec<ThreadState>> got) {
    if (!requestable && _threads==null) return false;
    if (_threads==null) {
      _currThreadState = new Promise<>();
      _threads = new Promise<>();
      exe.listThreads(currThread, l -> {
        _threads.set(l);
        _currThreadState.set(ThreadState.curr(l));
        updated();
      });
    }
    _threads.then(got);
    return true;
  }
  public boolean currThreadState(Consumer<ThreadState> got) {
    if (!threads(l -> {})) return false;
    _currThreadState.then(got);
    return true;
  }
  public boolean threadState(ProcThread thr, Consumer<ThreadState> got) {
    return threads(c -> {
      got.accept(c==null? null : c.linearFind(s -> s.obj == thr));
    });
  }
  
  private boolean spotRequested = false;
  public void requestSpot() {
    if (spotRequested) return;
    spotRequested = true;
    smgr.dump.then(d -> currThreadState(s -> { // TODO if this fails, _spot.then will leak, like, a lot
      if (s!=null) smgr.forThread(d, s.tid).find(this);
    }));
  }
  public boolean spot(Consumer<TimeManager.Spot> got) {
    requestSpot();
    _spot.then(got);
    return false;
  }
  public TimeManager.Spot trySpot() {
    if (_spot.isResolved()) return _spot.get();
    requestSpot();
    return null;
  }
  
  private <T extends Number> T tryParse(String s, Function<String, T> f, T def) {
    try { return f.apply(s); }
    catch (NumberFormatException e) { Log.stacktrace("currThreadWhen", e); return def; }
  }
  public boolean currThreadWhen(Consumer<TimeManager.TickRef> got) {
    if (!requestable && _ticks==null) return false;
    if (_ticks==null) {
      Promise<Long> t = getCmdValue("when-ticks", (p,s) -> tryParse(s, Long::parseLong, -1L), "Current tick: ");
      Promise<Long> e = getCmdValue("when",      (p,s) -> {
        Long r = tryParse(s, Long::parseLong, -1L);
        return r==null? null : p.equals("Completed event: ")? r : r-1;
      }, "Current event: ", "Completed event: ");
      _ticks = Promise.merge2(t, e, (tr, er) -> tr==null||er==null? null : new TimeManager.TickRef(er, tr));
    }
    _ticks.then(got);
    return true;
  }
  public boolean nativeElapsedTime(Consumer<Long> got) {
    if (!requestable && _elapsedTime==null) return false;
    
    if (_elapsedTime==null) _elapsedTime = getCmdValue("elapsed-time", (p,s) -> {
      try { return (long) (Double.parseDouble(s)*1e9); } // TODO use tryParse here too
      catch (NumberFormatException e) { Log.stacktrace("elapsed-time", e); return -1L; }
    }, "Elapsed Time (s): ");
    _elapsedTime.then(got);
    return true;
  }
  private <T> Promise<T> getCmdValue(String cmd, BiFunction<String, String, T> got, String... prefixes) {
    Vec<String> pres = Vec.of(prefixes).map(c->"~\""+c);
    return Promise.create(p -> exe.d.p.consoleCmd(cmd).ds().runWithIntermediate(r -> {
      if (r.intermediate.sz>0) {
        String all = r.intermediate.get(0);
        String pre = pres.linearFind(all::startsWith);
        p.set(got.apply(pre.substring(2), all.substring(pre.length(), all.length()-3)));
      } else {
        p.set(null);
      }
      updated();
    }, r -> pres.linearFind(r::startsWith)!=null));
  }
  
  
  public static int MAX_STACK = 200;
  public boolean limitedStack(ProcThread t, Consumer<Pair<Vec<ProcThread.StackFrame>, Boolean>> got) { // null, or a pair of stack and whether it was (possibly) limited
    return stackImpl(t, true, got);
  }
  public boolean fullStack(ProcThread t, Consumer<Vec<ProcThread.StackFrame>> got) { // null, or full stack
    return stackImpl(t, false, x -> got.accept(x.a));
  }
  
  
  public boolean getLWCP(Consumer<Integer> got) {
    if (!requestable && _lwcp==null) return false;
    if (_lwcp==null) _lwcp = Promise.create(p -> exe.addLWCP(val -> { p.set(val); updated(); }));
    _lwcp.then(got);
    return true;
  }
  
  private boolean stackImpl(ProcThread t0, boolean limited, Consumer<Pair<Vec<ProcThread.StackFrame>, Boolean>> got) {
    HashMap<ProcThread, Promise<Pair<Vec<ProcThread.StackFrame>, Boolean>>> m = limited? _limitedStack : _fullStack;
    if (!requestable && !m.containsKey(t0)) return false;
    
    m.computeIfAbsent(t0, t1 -> Promise.create(p -> {
      t1.stacktrace(true, 0, limited? MAX_STACK : -1, bt -> {
        p.set(bt==null? null : new Pair<>(bt, bt.sz>=MAX_STACK));
        updated();
      });
    })).then(got);
    return true;
  }
}
