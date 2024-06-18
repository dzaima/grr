package dz.gdb;

import dz.debugger.Location;
import dz.gdb.ProcThread.*;
import dz.general.FnCache;
import dzaima.utils.*;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.*;

public class Executable {
  public final Dbi d;
  public final Path p;
  
  public Executable(Dbi d, Path p) {
    this.d = d;
    this.p = p;
  }
  
  public void open(String[] args, Runnable after) { assertThis();
    d._open(args, after);
  }
  
  
  
  public void cont(boolean rev, Runnable after) { assertPaused();
    d._continue(rev, after);
  }
  public void breakAll(Runnable after) { assertThis();
    d._breakAll(after);
  }
  public void stepLine(int n, Consumer<Boolean> after) { assertPaused();
    d._step(n, true, false, after);
  }
  public void stepOverLine(int n, Consumer<Boolean> after) { assertPaused();
    d._step(n, true, true, after);
  }
  public void stepIns(int n, Consumer<Boolean> after) { assertPaused();
    d._step(n, false, false, after);
  }
  public void stepOverIns(int n, Consumer<Boolean> after) { assertPaused();
    d._step(n, false, true, after);
  }
  public void finishFn(boolean rev, Runnable after) { assertPaused();
    d._finish(rev, after);
  }
  
  public void listThreads(ProcThread t0, Consumer<Vec<ThreadState>> got) { assertThis();
    d._listThreads(t0, got);
  }
  
  public void selectThread(ThreadState thr, Consumer<Boolean> after) { assertThis();
    d._selectThread(thr, after);
  }
  
  public void addLWCP(Consumer<Integer> got) { assertThis();
    d._addLWCP(got);
  }
  
  public void gotoLWCP(int lwcp, Consumer<Boolean> after) {
    d._gotoLWCP(lwcp, after);
  }
  
  public enum DisasMode { DISAS, OPS, SRC }
  public void disasSymbol(String sym, DisasMode mode, Consumer<Vec<Ins>> got) { assertLoaded();
    d._disas(0, mode, sym, null, got);
  }
  public void disasAroundAddr(long addr, DisasMode mode, Consumer<Vec<Ins>> got) { assertLoaded();
    d._disas(1, mode, addr, null, got);
  }
  public void disasSegment(long s, long e, DisasMode mode, Consumer<Vec<Ins>> got) { assertLoaded();
    d._disas(2, mode, s, e, got);
  }
  
  @FunctionalInterface
  public interface DisasRes {
    void got(String name, long s, long e, Vec<Ins> ins, Properness proper);
    default void got(Vec<Ins> ins, Properness proper) {
      if (ins==null || ins.sz == 0) {
        got(null, -1, -1, null, Properness.NONE);
      } else {
        Ins i0 = ins.get(0);
        got(i0.loc==null? null : i0.loc.sym, i0.addr, ins.peek().addrEnd(), ins, proper);
      }
    }
  }
  public enum Properness { NONE, DYN, FULL }
  public void disasLocation(Location l, boolean decrementLoc, DisasMode mode, FnCache.NameMode nameMode, int bytesOnUnknown, DisasRes got) { assertLoaded();
    int atIdx;
    String sym = l.sym==null? null : (atIdx = l.sym.indexOf('@'))!=-1? l.sym.substring(0, atIdx) : l.sym;
    
    Runnable fail = () -> got.got(null, Properness.NONE);
    Runnable tryRange = () -> {
      if (bytesOnUnknown<=0 || l.addr==null) fail.run();
      else disasSegment(l.addr, l.addr+bytesOnUnknown, mode, r -> {
        if (r!=null) got.got(r, Properness.DYN);
        else fail.run();
      });
    };
    Runnable trySymPrefix = () -> {
      if (sym==null || nameMode!=FnCache.NameMode.PREFIX) tryRange.run();
      else {
        d._setPrintDemangle("off", null); // otherwise the retrieved symbol is useless as a disas arg
        symbolInfo(true, 2, sym, r -> {
          if (r.sz==1) {
            disasSymbol(r.get(0).a.sym, mode, r2 -> {
              if (r2!=null) got.got(r2, Properness.FULL); 
              else tryRange.run();
            });
          } else tryRange.run();
        });
        d._setPrintDemangle("on", null); // should reset to a proper value, but getting it for this would introduce blocking
      }
    };
    Runnable trySym = () -> {
      if (sym==null || nameMode==FnCache.NameMode.NONE || nameMode==FnCache.NameMode.RANGE_ONLY) tryRange.run();
      else disasSymbol(sym, mode, r -> {
        if (r!=null) got.got(r, Properness.FULL);
        else trySymPrefix.run();
      });
    };
    Runnable tryAddr = () -> {
      if (l.addr==null || l.addr==0 || nameMode==FnCache.NameMode.RANGE_ONLY) trySym.run();
      else disasAroundAddr(decrementLoc? l.addr-1 : l.addr, mode, r -> {
        if (r!=null) got.got(r, Properness.FULL);
        else trySym.run();
      });
    };
    tryAddr.run();
  }
  public void sourceInfo(long s, long e, Consumer<Location> got) { assertLoaded();
    disasSegment(s, e, DisasMode.SRC, r -> {
      if (r==null || r.sz==0) got.accept(null);
      else got.accept(r.get(0).loc);
    });
  }
  
  public void addCheckpoint(Consumer<Integer> got) { assertPaused();
    d._addCheckpoint(false, got);
  }
  public void toCheckpoint(int num, Runnable after) { assertPaused();
    d._toCheckpoint(num, after);
  }
  public void rmCheckpoint(int num, Runnable after) { assertLoaded();
    d._rmCheckpoint(num, after);
  }
  
  public void addWatchpoint(String expr, boolean read, boolean write, Runnable after) { assertPaused(); // TODO: expr is leaky
    d._addWatchpoint(expr, read, write, after);
  }
  
  
  public void readMemory(long s, long e, BiConsumer<byte[], boolean[]> got) { assertLoaded();
    d._readMem(s, e, got);
  }
  
  // returned symbols will have at least a name
  public void symbolInfo(boolean nondebug, int max, String name, Consumer<Vec<Pair<Location, Vec<Arg>>>> got) { assertLoaded();
    d._symbolInfo(nondebug, max, name, got);
  }
  
  
  public void addrsToSymbol(long[] addrs, Consumer<Location[]> got) { assertLoaded();
    if (addrs.length==0) {
      got.accept(new Location[0]);
      return;
    }
    StringBuilder b = new StringBuilder("{");
    boolean first = true;
    for (long addr : addrs) {
      if (first) first = false;
      else b.append(',');
      b.append("(void*)0x").append(Long.toHexString(addr));
    }
    b.append("}");
    d.evalExpr(b.toString(), true, r -> {
      Location[] ls = new Location[addrs.length];
      
      if (r==null || !r.startsWith("{")) {
        Log.warn("grr", "Failed to get symbols");
        Arrays.fill(ls, Location.IDK);
        return;
      }
      
      // System.out.println(r);
      int ri = 1;
      for (int i = 0; i < ls.length; i++) {
        // System.out.println(i+": "+r.substring(ri));
        assert r.startsWith("0x", ri); ri+= 2;
        while (GdbFormat.readHex(r.charAt(ri)) != -1) ri++;
        
        int delta = 0;
        
        String sym = null;
        sym: if (r.startsWith(" <", ri) && !r.startsWith(" <repeats ", ri)) {
          ri+= 2;
          
          int oE = i==ls.length-1? r.length()-2 : (int)Math.min(r.indexOf(">, 0x", ri)&0xffffffffL, r.indexOf("> <", ri)&0xffffffffL);
          if (oE==-1) break sym;
          
          int oD = oE;
          while (r.charAt(oD-1)>='0' & r.charAt(oD-1)<='9') oD--;
          if (oD!=oE && r.charAt(oD-1)=='+') {
            delta = Integer.parseInt(r.substring(oD, oE));
            oD--;
          } else {
            oD = oE;
          }
          
          sym = r.substring(ri, oD);
          ri = oE+1;
        }
        // System.out.println(addrs[i]+": "+sym+" "+(addrs[i]-delta));
        Location l = new Location(addrs[i]-delta, sym, null, null, null);
        if (r.startsWith(" <repeats ", ri)) {
          int oS = ri+10;
          int oE = oS;
          int n = 0;
          while (r.charAt(oE)>='0' && r.charAt(oE)<='9') { n=n*10+(r.charAt(oE)-'0'); oE++; }
          ri = oE+7;
          for (int j = 0; j < n-1; j++) ls[i++] = l;
        }
        if (i != ls.length-1) {
          assert r.startsWith(", ", ri);
          ri+= 2;
        } else ri++;
        ls[i] = l;
      }
      assert ri==r.length();
      got.accept(ls);
    });
  }
  
  
  private void assertLoaded() {
    assertThis();
    assert d.status().hasexe();
  }
  private void assertPaused() {
    assertThis();
    assert d.status().paused();
  }
  private void assertThis() {
    assert d.curr==this : "Expected executable "+(p==null? "(null)" : p.toString())+" to be active while using it";
  }
  
  public static class ThreadState {
    public final StackFrame frame;
    public final String desc, gdbID, globalID;
    public final ProcThread obj;
    public final long tid;
    public final boolean current;
    public ThreadState(ProcThread obj, StackFrame frame, String desc, String gdbID, String globalID, long tid, boolean current) {
      this.frame = frame;
      this.desc = desc;
      this.gdbID = gdbID;
      this.globalID = globalID;
      this.obj = obj;
      this.tid = tid;
      this.current = current;
    }
    
    
    public String toString() { return desc+": "+obj+" @ "+frame; }
    
    public boolean eq(ThreadState s) {
      return globalID.equals(s.globalID) && tid==s.tid;
    }
    
    
    public static ThreadState curr(Vec<ThreadState> l) {
      return l.linearFind(c -> c.current);
    }
    public static ThreadState find(Vec<ThreadState> l, ProcThread thr) {
      return l.linearFind(c -> c.obj==thr);
    }
  }
  
  public static class Ins {
    public final long addr;
    public final int len;
    public final String repr;
    public final byte[] raw;
    public final Location loc;
    
    public Ins(long addr, int len, String repr, byte[] raw, Location loc) {
      this.addr = addr;
      this.len = len;
      this.repr = repr;
      this.raw = raw;
      this.loc = loc;
    }
    public String toString() {
      String s = "`"+repr+"`";
      return loc==null? s : s+"@"+loc;
    }
    
    public long addrEnd() {
      return addr + len;
    }
  }
}
