package dz.general.arch;

import dzaima.ui.gui.config.GConfig;
import dzaima.ui.node.types.editable.code.langs.*;

import java.util.HashMap;

public class RISCV extends GenericArch {
  public static final RISCV inst = new RISCV();
  public static HashMap<String, String> baseReg = new HashMap<>();
  static {
    baseReg.put("zero", "x0");
    baseReg.put("ra", "x1");
    baseReg.put("sp", "x2");
    baseReg.put("gp", "x3");
    baseReg.put("tp", "x4");
    baseReg.put("t0", "x5");
    baseReg.put("t1", "x6");
    baseReg.put("t2", "x7");
    baseReg.put("s0", "x8");
    baseReg.put("fp", "x8");
    baseReg.put("s1", "x9");
    baseReg.put("a0", "x10");
    baseReg.put("a1", "x11");
    baseReg.put("a2", "x12");
    baseReg.put("a3", "x13");
    baseReg.put("a4", "x14");
    baseReg.put("a5", "x15");
    baseReg.put("a6", "x16");
    baseReg.put("a7", "x17");
    baseReg.put("s2", "x18");
    baseReg.put("s3", "x19");
    baseReg.put("s4", "x20");
    baseReg.put("s5", "x21");
    baseReg.put("s6", "x22");
    baseReg.put("s7", "x23");
    baseReg.put("s8", "x24");
    baseReg.put("s9", "x25");
    baseReg.put("s10", "x26");
    baseReg.put("s11", "x27");
    baseReg.put("t3", "x28");
    baseReg.put("t4", "x29");
    baseReg.put("t5", "x30");
    baseReg.put("t6", "x31");
    
    baseReg.put("fs0", "f8");
    baseReg.put("fs1", "f9");
    for (int i = 2; i <= 11; i++) baseReg.put("fs"+i, "f"+(i+16));
    for (int i = 0; i < 8; i++) {
      baseReg.put("ft"+i, "f"+i);
      baseReg.put("fa"+i, "f"+(i+10));
    }
    for (int i = 8; i <= 11; i++) baseReg.put("ft"+i, "f"+(i+20));
    baseReg.put("v0.t", "v0");
  }
  
  public Lang getLang(GConfig gc) {
    return AsmLang.RISCV;
  }
  
  public InstrInfo instrInfo(String d, long addr) {
    int s = tows(d, 0);
    String b = d.substring(0, s);
    if (d.equals("jalr")) return new InstrInfo(-1, InstrMode.CALL);
    if (d.equals("jal")) return new InstrInfo(readAddrRV(d, s+1), InstrMode.CALL);
    if (b.equals("ret")) return new InstrInfo(-1, InstrMode.RETURN);
    if (b.equals("ebreak") || b.equals("unimp")) return new InstrInfo(-1, InstrMode.NORETURN);
    if (b.equals("j")) return new InstrInfo(readAddrRV(d, s+1), InstrMode.NORETURN);
    if (b.equals("jr")) return new InstrInfo(-1, InstrMode.NORETURN);
    if (b.equals("beq")||b.equals("bne")||b.equals("blt")||b.equals("bge")||b.equals("bltu")||b.equals("bgeu")||b.equals("beqz")||b.equals("bnez")) {
      return new InstrInfo(readAddrRV(d, s+1), InstrMode.COND);
    }
    return null;
  }
  
  protected Long readAddrRV(String d, int s) {
    while (s<d.length() && !d.startsWith("0x", s)) s++;
    return readAddr(d, s);
  }
  
  public String baseReg(String s) {
    String b = baseReg.get(s);
    return b==null? s : b;
  }
}
