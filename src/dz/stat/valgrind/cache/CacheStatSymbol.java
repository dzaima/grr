package dz.stat.valgrind.cache;

import dz.stat.*;
import dzaima.utils.Vec;

import java.util.HashMap;

public class CacheStatSymbol extends StatSymbol {
  public final CacheStat d;
  public HashMap<Long, CacheStatInstr> m = new HashMap<>();
  protected double score;
  protected float dispSumMul;
  public CacheStatSymbol(CacheStat d, StatGlobal.BasicSymbol sym) {
    super(sym);
    this.d = d;
  }
  
  void configUpdated() {
    long sc = 0;
    double tot = 0;
    for (CacheStatInstr c : m.values()) {
      long acc = c.acc();
      switch (d.disp) {
        case ACCESS: sc+= acc; tot+= acc; break;
        case L1M: { long m = c.l1m(); sc+= m; double f=m*1.0/acc; if (f>tot) tot=f; break; }
        case LLM: { long m = c.llm(); sc+= m; double f=m*1.0/acc; if (f>tot) tot=f; break; }
      }
    }
    score = sc;
    dispSumMul = (float) (tot == 0? 0 : 1/tot);
    if (d.disp!=CacheStat.InsDisp.ACCESS) dispSumMul = Math.min(dispSumMul, 4); // brighten up all miss percentages if none in the function are large, but cap the brightening to 4x
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
