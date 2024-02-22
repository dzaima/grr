package dz.general.arch;

import dzaima.ui.gui.config.GConfig;
import dzaima.ui.node.types.editable.code.langs.*;

import static dz.gdb.GdbFormat.dig;

public class AArch64 extends GenericArch {
  public static final AArch64 inst = new AArch64();
  
  public Lang getLang(GConfig gc) {
    return AsmLang.AARCH64;
  }
  
  public InstrInfo instrInfo(String d, long addr) {
    int s = tows(d, 0);
    String b = d.substring(0, s);
    if (b.equals("ret")) return new InstrInfo(-1, InstrMode.RETURN);
    if (b.equals("hlt") || b.equals("udf")) return new InstrInfo(-1, InstrMode.NORETURN);
    if (b.startsWith("b.")) return new InstrInfo(readAddr(d, s+1), InstrMode.COND);
    if (b.equals("bl") || b.equals("blr")) return new InstrInfo(readAddr(d, s+1), InstrMode.CALL);
    if (b.equals("b")) return new InstrInfo(readAddr(d, s+1), InstrMode.NORETURN);
    if (b.equals("br")) return new InstrInfo(-1, InstrMode.NORETURN);
    if (b.equals("cbz") || b.equals("cbnz")) {
      s = tows(d, s+1);
      return new InstrInfo(readAddr(d, s+1), InstrMode.COND);
    }
    if (b.equals("tbz") || b.equals("tbnz")) {
      s = tows(d, s+1);
      s = tows(d, s+1);
      return new InstrInfo(readAddr(d, s+1), InstrMode.COND);
    }
    return null;
  }
  
  public String baseReg(String s) {
    int i = s.indexOf('.');
    if (i!=-1) s = s.substring(0, i); // v0.4s / v1.s v1.s[1] → v0
    if (s.length()>=2 && dig(s.charAt(1))) {
      char c0 = s.charAt(0);
      if ("vqdshb".indexOf(c0)!=-1) return "v"+s.substring(1); // q0 → v0
      if (c0=='w') return "x"+s.substring(1);
    }
    return s;
  }
}
