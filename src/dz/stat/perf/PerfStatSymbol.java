package dz.stat.perf;

import dz.stat.*;
import dzaima.utils.*;

import java.util.HashMap;

public class PerfStatSymbol extends StatSymbol {
  private static final boolean DEBUG_NAMES = false;
  public final PerfStat s;
  public final HashMap<Long, StatInstr> stat;
  public double score;
  final long sum, max;
  private String name;
  
  public PerfStatSymbol(PerfStat s, StatGlobal.BasicSymbol sym, HashMap<Long, StatInstr> stat, long sum, long max) {
    super(sym);
    this.s = s;
    this.stat = stat;
    this.sum = sum;
    this.max = max;
    this.score = sum;
  }
  
  void finish() {
    if (sym.name != null && (!DEBUG_NAMES || stat.isEmpty())) {
      name = sym.name;
    } else if (stat.isEmpty()) {
      name = "(zero samples?)";
    } else {
      long maxAddr = 0;
      long minAddr = -1L;
      for (long c : stat.keySet()) {
        minAddr = Tools.ulongMin(c, minAddr);
        maxAddr = Tools.ulongMax(c, maxAddr);
      }
      if (DEBUG_NAMES) {
        name = (sym.name==null? "" : sym.name+" ")+"("+Tools.hexLong(minAddr)+"…"+Tools.hexLong(maxAddr)+" - "+(maxAddr-minAddr)+"B)";
      } else {
        name = "("+stat.size()+" addresses in a "+Math.abs(maxAddr-minAddr)+"-byte span)";
      }
    }
  }
  
  public void onAllSelected() {
    s.onSelection(Vec.ofExCollection(stat.values()));
  }
  public void onSelection(Vec<StatInstr> instrs) {
    s.onSelection(instrs);
  }
  
  public StatInstr get(long rel) { return stat.get(rel); }
  public double score() { return score; }
  public String name() { return name; }
}
