package dz.stat.valgrind.cache;

import dz.stat.*;
import dz.stat.valgrind.CachegrindData;

public class CacheStatInstr extends StatInstr {
  public final CacheStatSymbol sym;
  public final long[] vs;
  private static final int L1O = CachegrindData.D1mr-CachegrindData.Dr;
  private static final int LLO = CachegrindData.DLmr-CachegrindData.Dr;
  
  public CacheStatInstr(CacheStatSymbol sym, long[] vs) {
    this.sym = sym;
    this.vs = vs;
  }
  
  public String shortText() {
    if (sym.d.disp==CacheStat.InsDisp.ACCESS) {
      long acc = acc();
      if (acc==0) return "";
      return Long.toUnsignedString(acc);
    } else {
      float f = frac();
      if (f==-1) return "";
      return StatGlobal.fmtPercentPadded(f);
    }
  }
  
  public long acc() { return vs[sym.d.cache.acc]; }
  public long l1m() { return vs[sym.d.cache.acc + L1O]; }
  public long llm() { return vs[sym.d.cache.acc + LLO]; }
  
  public float frac() {
    long acc = acc();
    if (acc==0) return -1;
    switch (sym.d.disp) { default: throw new IllegalStateException();
      case ACCESS: return acc * sym.dispSumMul;
      case L1M: return l1m()*1f/acc;
      case LLM: return llm()*1f/acc;
    }
  }
  
  public float saturation() {
    float r = frac();
    if (r<=0 || acc() < sym.d.minToHighlight) return 0;
    if (sym.d.disp!=CacheStat.InsDisp.ACCESS) r*= sym.dispSumMul;
    return r;
  }
}
