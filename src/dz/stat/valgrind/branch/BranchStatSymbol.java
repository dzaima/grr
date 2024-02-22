package dz.stat.valgrind.branch;

import dz.stat.*;
import dzaima.utils.Vec;

import java.util.HashMap;

public class BranchStatSymbol extends StatSymbol {
  public final BranchStat d;
  public HashMap<Long, BranchStatInstr> m = new HashMap<>();
  protected double score;
  protected float dispSumMul;
  public BranchStatSymbol(BranchStat d, StatGlobal.BasicSymbol sym) {
    super(sym);
    this.d = d;
  }
  
  long configUpdated() {
    long sc = 0, div = 0;
    for (BranchStatInstr c : m.values()) {
      long v = d.disp==BranchStat.InsDisp.N_TOT? c.tot() : c.miss();
      div+= v;
      sc+= v;
    }
    score = sc;
    dispSumMul = div==0? 0 : 1f / div;
    return div;
  }
  
  public void onAllSelected() {
    d.onSelection(Vec.ofExCollection(m.values()));
  }
  public void onSelection(Vec<StatInstr> instrs) {
    d.onSelection(instrs);
  }
  
  public StatInstr get(long rel) { return m.get(rel); }
  public double score() { return score; }
  public String name() { return sym.name; }
}
