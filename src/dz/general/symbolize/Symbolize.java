package dz.general.symbolize;

import dz.debugger.Location;
import dz.stat.StatGlobal;
import dz.utils.AddrMapper;
import dzaima.utils.*;

import java.util.function.Consumer;

public class Symbolize {
  public static final boolean DEBUG_SYMBOLIZE = false;
  
  public static class IPEntry {
    public final StatGlobal.Mapping m;
    public final long ip;
    public IPEntry(StatGlobal.Mapping m, long ip) { this.m=m; this.ip=ip; }
  }
  public static class Resolved {
    public final long sym_static, sym_dyn;
    public final @Nullable String sym;
    public Resolved(long sym_static, long symDyn, @Nullable String sym) {
      this.sym_static = sym_static;
      sym_dyn = symDyn;
      this.sym = sym;
    }
    
    private static final Resolved NONE = new Resolved(0, 0, null);
    public static Resolved not() { return NONE; }
  }
  
  public static class Sym implements AddrMapper.Range {
    public final long s, e;
    public final Location loc;
    public Sym(long s, long e, Location loc) { this.s=s; this.e=e; this.loc = loc; }
    public long s() { return s; }
    public long e() { return e; }
    
    public String toString() { return "["+s+";"+e+"):"+loc; }
  }
  
  public static void noSymbols(Vec<IPEntry> as, Consumer<Vec<Resolved>> got) {
    got.accept(as.map(c -> Resolved.not()));
  }
}
