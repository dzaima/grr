package dz.debugger;

import dzaima.utils.Nullable;

import java.util.Objects;

public class Location {
  public static final Location IDK = new Location(null, null, null, null, null);
  @Nullable public final Long addr;
  @Nullable public final String sym;
  @Nullable public final String fullFile; // if `line!=null`, fullFile is a proper source file path; else, either a source file or binary if any
  @Nullable public final String shortFile; // pretty description of source (not necessarily a file path; definitely not if fullFile==null)
  @Nullable public final Integer line;
  
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
  
  public Location decrementedIf(boolean afterCall) {
    return afterCall && addr!=null? new Location(addr-1, sym, shortFile, fullFile, line) : this;
  }
  
  public boolean equals(Object o) {
    if (!(o instanceof Location)) return false;
    Location l = (Location) o;
    return Objects.equals(addr, l.addr) && Objects.equals(sym, l.sym)
      && Objects.equals(shortFile, l.shortFile) && Objects.equals(fullFile, l.fullFile) && Objects.equals(line, l.line);
  }
}
