package dz.general.arch;

import dz.gdb.GdbFormat.GVal;
import dzaima.utils.*;

import java.util.HashMap;

public class GenericArch extends Arch {
  public static final GenericArch inst = new GenericArch();
  
  public String baseReg(String s) {
    return s;
  }
  
  public Vec<RegInfo> groupRegs(Vec<Pair<Integer, String>> names) {
    Vec<RegInfo> r = new Vec<>();
    for (Pair<Integer, String> c : names) {
      r.add(new RegInfo(c.a, c.b, "all") {
        public void addNeeded(IntVec r) { r.add(c.a); }
        public RegRes get(HashMap<Integer, GVal> values) {
          GVal rawObj = values.get(c.a);
          String v = rawObj==null? Arch.UNK_REG : simplifyReg(rawObj, false);
          byte[] bs = parseHex(v);
          if (bs!=null) v = groupReg(v);
          return new RegRes(v, bs, bs==null? -1 : bs.length*8);
        }
        public int compareTo(RegInfo o) { return Integer.compare(num, o.num); }
      });
    }
    return r;
  }
  
  private final Vec<String> defaultEnabledGroups = Vec.of("all");
  public Vec<String> defaultEnabledGroups() {
    return defaultEnabledGroups;
  }
  
  public InstrInfo instrInfo(String d, long addr) {
    return null;
  }
  
  public String prettyIns(String desc, long addr) {
    return expandCommas(desc);
  }
  public static String expandCommas(String desc) {
    return desc.replaceAll(",(?! )", ", ");
  }
}
