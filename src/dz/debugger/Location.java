package dz.debugger;

import java.util.Objects;

public class Location {
  public static final Location IDK = new Location(null, null, null, null, null);
  public final Long addr;
  public final String sym;
  public final String shortFile, fullFile; // TODO: describe/verify when this is a source file and when a binary (or separate the two)
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
