package dz.stat.perf;

import dz.stat.*;
import dzaima.utils.Tools;

public class PerfStatInstr extends StatInstr {
  public final PerfStatSymbol sym;
  public final int n;
  
  public PerfStatInstr(PerfStatSymbol sym, int n) {
    this.sym = sym;
    this.n = n;
  }
  
  public float frac() {
    switch (sym.s.disp) { default: throw new IllegalStateException();
      case COUNT:
      case P_FUNC: return n*1f/sym.sum;
      case P_GLOBAL: return n*1f/sym.s.totalSampleCount;
    }
  }
  public String shortText() {
    if (sym.s.disp==PerfStat.InsDisp.COUNT) return Long.toUnsignedString(n);
    return StatGlobal.fmtPercentPadded(frac());
  }
  public float saturation() {
    if (sym.s.disp==PerfStat.InsDisp.P_GLOBAL) return Tools.constrain(n*1f / sym.s.totalSampleCount * 4, 0, 1); 
    return n*1f/sym.max;
  }
}
