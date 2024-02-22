package dz.stat;

import dz.general.DisasFn;
import dz.ui.AsmListNode;
import dzaima.utils.*;

public abstract class StatSymbol { // relates via BasicSymbol, but can come from a specific one if desired
  public final StatGlobal.BasicSymbol sym;
  public StatSymbol(StatGlobal.BasicSymbol sym) {
    this.sym = sym;
  }
  
  public abstract String name();
  public abstract StatInstr get(long rel); // input relative to function start
  public abstract double score();
  
  public /*open*/ String fmtScore() { return StatGlobal.fmtPercentPadded(score()); }
  public int fmtWidth(float cW) { return Tools.ceil(cW*7.2f); }
  
  public abstract void onSelection(Vec<StatInstr> instrs);
  public abstract void onAllSelected();
  
  public DisasFn disas() { return null; }
  public AsmListNode.AsmConfig forceCfg() { return null; }
}
