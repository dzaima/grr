package dz.debugger;

import dzaima.utils.*;

import java.util.Objects;

public class Location {
  public static final Location IDK = new Location(null, null, null, null, null);
  @Nullable public final Long addr;
  @Nullable public final String sym;
  @Nullable public final String file; // if `line!=null`, file is a proper source file path; else, either a source file or binary if any
  @Nullable public final String sourceInfo; // pretty description of source (perhaps path-ish, but definitely not if file==null)
  @Nullable public final Integer line; // line in file, 1-indexed
  
  public Location(Long addr, String sym, String file, Integer line, String sourceInfo) {
    this.addr = addr;
    this.sym = sym;
    this.sourceInfo = sourceInfo;
    this.file = file;
    this.line = line;
    if ("??".equals(sym)) Log.stacktraceHere("Location??");
  }
  
  
  public String toString() {
    StringBuilder b = new StringBuilder("<loc");
    if (addr!=null) b.append(" 0x").append(Long.toHexString(addr));
    if (sym!=null) b.append(" @").append(sym);
    if (sourceInfo!=null) b.append(" i=").append(sourceInfo);
    if (file!=null) b.append(" f=").append(file);
    if (line!=null) b.append(" L").append(line);
    return b.append('>').toString();
  }
  
  public Location decrementedIf(boolean afterCall) {
    return afterCall && addr!=null? new Location(addr-1, sym, file, line, sourceInfo) : this;
  }
  
  public boolean equals(Object o) {
    if (!(o instanceof Location)) return false;
    Location l = (Location) o;
    return Objects.equals(addr, l.addr) && Objects.equals(sym, l.sym)
      && Objects.equals(sourceInfo, l.sourceInfo) && Objects.equals(file, l.file) && Objects.equals(line, l.line);
  }
}
