package dz.stat.valgrind.branch;

import dz.stat.*;
import dz.stat.valgrind.CachegrindData;

public class BranchStatInstr extends StatInstr {
  public final BranchStatSymbol sym;
  public final long[] vs;
  
  public BranchStatInstr(BranchStatSymbol sym, long[] vs) {
    this.sym = sym;
    this.vs = vs;
  }
  
  public String shortText() {
    long tot = tot();
    if (tot==0) return "";
    if (sym.d.disp==BranchStat.InsDisp.N_TOT) return Long.toUnsignedString(tot);
    if (sym.d.disp==BranchStat.InsDisp.N_MISS) return Long.toUnsignedString(miss());
    
    float f = frac();
    if (f==-1) return "";
    return StatGlobal.fmtPercentPadded(f);
  }
  
  public long tot() {
    long r = 0;
    if (sym.d.direct)   r+= vs[CachegrindData.Bc];
    if (sym.d.indirect) r+= vs[CachegrindData.Bi];
    return r;
  }
  public long miss() {
    long r = 0;
    if (sym.d.direct)   r+= vs[CachegrindData.Bcm];
    if (sym.d.indirect) r+= vs[CachegrindData.Bim];
    return r;
  }
  
  public float frac() {
    long t = tot();
    if (t==0) return -1;
    
    switch (sym.d.disp) { default: throw new IllegalStateException();
      case P_INS: return miss()*1f/t;
      case N_TOT: return tot() * sym.dispSumMul;
      case N_MISS:
      case P_FUNC: case P_GLOBAL: return miss() * sym.dispSumMul;
    }
  }
  
  public float saturation() {
    float r = frac();
    if (r<=0 || tot() < sym.d.minToHighlight) return 0;
    return r*3;
  }
}
