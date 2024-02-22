package dz.stat.perf;

import dz.general.DisasFn;
import dz.stat.*;
import dz.ui.AsmListNode;
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
  
  public DisasFn disas() {
    if (sym.name==null && !stat.isEmpty()) {
      Vec<Long> es = Vec.ofCollection(stat.keySet());
      es.sort();
      DisasFn.ParsedIns[] is = new DisasFn.ParsedIns[es.sz];
      for (int i = 0; i < es.sz; i++) {
        long a0 = es.get(i);
        is[i] = new DisasFn.ParsedIns(a0, new byte[0], i==es.sz-1? "" : "# "+(es.get(i+1)-a0)+" bytes until next");
      }
      return new DisasFn(0, -1, name, false, is);
    }
    return null;
  }
  public static AsmListNode.AsmConfig cfg = new AsmListNode.AsmConfig(AsmListNode.AddrDisp.ALL, AsmListNode.AddrFmt.ADDR, false, 0);
  public AsmListNode.AsmConfig forceCfg() {
    if (sym.name==null && !stat.isEmpty()) return cfg;
    return null;
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
