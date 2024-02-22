package dz.debugger;

import dz.gdb.GdbFormat.GVal;

import java.util.Objects;
import java.util.function.Function;

public class Location {
  public final Long addr;
  public final String sym;
  public final String shortFile, fullFile;
  public final Integer line;
  
  public Location(Long addr, String sym, String shortFile, String fullFile, Integer line) {
    this.addr = addr;
    this.sym = sym;
    this.shortFile = shortFile;
    this.fullFile = fullFile;
    this.line = line;
  }
  
  
  public String toString() {
    StringBuilder b = new StringBuilder("<loc");
    if (addr!=null) b.append(" 0x").append(Long.toHexString(addr));
    if (sym!=null) b.append(" @").append(sym);
    if (shortFile!=null) b.append(" sf=").append(shortFile);
    if (fullFile !=null) b.append(" ff=").append(fullFile);
    if (line!=null) b.append(" L").append(line);
    return b.append('>').toString();
  }
  
  public Location decrementedIf(boolean nonTop) {
    return nonTop && addr!=null? new Location(addr-1, sym, shortFile, fullFile, line) : this;
  }
  
  public enum LocMode { M1, M2, M3 }
  public static Location readFrom(LocMode m, GVal... vs) {
    boolean m2 = m==LocMode.M2;
    boolean m3 = m==LocMode.M3;
    return new Location(
      readFld(m2||m3?  "address"      :"addr", vs, GVal::addr),
      readFld(m3?"name":m2?"func-name":"func", vs, c -> c.str().equals("??")? null : c.str()),
      readFld(m3?          "filename" :"file", vs, GVal::str),
      readFld("fullname",                      vs, GVal::str),
      readFld("line",                          vs, GVal::asInt)
    );
  }
  static <T> T readFld(String k, GVal[] vs, Function<GVal, T> f) {
    for (GVal c : vs) {
      GVal r = c.get(k);
      if (r!=null) return f.apply(r);
    }
    return null;
  }
  
  public boolean equals(Object o) {
    if (!(o instanceof Location)) return false;
    Location l = (Location) o;
    return Objects.equals(addr, l.addr) && Objects.equals(sym, l.sym)
      && Objects.equals(shortFile, l.shortFile) && Objects.equals(fullFile, l.fullFile) && Objects.equals(line, l.line);
  }
}
