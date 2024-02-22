package dz.stat;

import dz.general.Binary;
import dz.utils.AddrMapper;
import dzaima.ui.node.Node;
import dzaima.ui.node.ctx.Ctx;
import dzaima.utils.*;

import java.util.*;

public abstract class StatGlobal<T extends StatSymbol> {
  public abstract HashMap<BasicSymbol, T> groupToBasic();
  public abstract Node activate(Ctx ctx, Runnable onRefresh); // returns configuration node
  public abstract String name();
  public abstract void onSelection(Vec<StatInstr> is);
  
  public static String fmtPercent(double d) {
    if (d==1) return "100%";
    return String.format("%.2f%%", d*100d);
  }
  public static String fmtPercentPadded(double d) {
    if (d==1) return "  100%";
    else return String.format("%5.2f%%", d*100d);
  }
  
  public static class BasicSymbol { // generic symbol within binary
    public final Binary bin;
    public final @Nullable String name;
    public final long addr;
    public BasicSymbol(Binary bin, String name, long addr) {
      this.bin = bin;
      this.name = name;
      this.addr = addr;
    }
    
    public boolean equals(Object o) {
      if (!(o instanceof BasicSymbol)) return false;
      BasicSymbol t = (BasicSymbol) o;
      return addr==t.addr && bin.equals(t.bin) && Objects.equals(name, t.name);
    }
    
    public int hashCode() {
      int result = bin.hashCode();
      result = 31*result + (name!=null? name.hashCode() : 0);
      result = 31*result + Long.hashCode(addr);
      return result;
    }
  }
  public static class MappedSymbol { // specific encountered symbol
    public final Mapping map;
    public final @Nullable String name;
    public final long addr; // within unrelocated binary address space
    
    public MappedSymbol(Mapping map, String name, long addr) {
      this.map = map;
      this.name = name;
      this.addr = addr;
    }
    
    public final BasicSymbol asBasic() {
      return new BasicSymbol(map.bin, name, addr);
    }
    
    public boolean equals(Object o) {
      if (!(o instanceof MappedSymbol)) return false;
      MappedSymbol t = (MappedSymbol) o;
      return addr == t.addr && map.equals(t.map) && Objects.equals(name, t.name);
    }
    
    public int hashCode() {
      int result = map.hashCode();
      result = 31*result + (name != null? name.hashCode() : 0);
      result = 31*result + (int) (addr ^ (addr >>> 32));
      return result;
    }
  }
  
  public static class Thread { // specific ran thread; has identity?
    public final long pid, tid;
    public Thread(long pid, long tid) { this.pid=pid; this.tid=tid; }
  }
  
  public static class Mapping implements AddrMapper.Range { // specific mapping of a binary within a thread's address space
    public final Binary bin;
    public final long addrS, addrE, binS;
    
    public Mapping(Binary bin, long addrS, long addrE, long binS) {
      assert Tools.ulongLE(addrS, addrE);
      this.bin = bin;
      this.addrS = addrS;
      this.addrE = addrE;
      this.binS = binS;
    }
    
    public static Mapping fromPerfName(String name, long addrS, long addrE, long pgoff) { // TODO perhaps inline
      return new Mapping(new Binary(name.startsWith("[")||name.startsWith("//")? null : name, name, true), addrS, addrE, pgoff);
    }
    public final long toReal(long binSpace) {
      return binSpace - (binS-addrS);
    }
    public final long toStatic(long realAddr) {
      return realAddr - (addrS-binS);
    }
    public long s() { return addrS; }
    public long e() { return addrE; }
    public String toString() { return "["+addrS+";"+addrE+"):"+bin.desc+"["+binS+";"+(binS+addrE-addrS)+")"; }
  }
}