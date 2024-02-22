package dz.general.arch;

import dz.gdb.GdbFormat;
import dz.gdb.GdbFormat.GVal;
import dzaima.ui.gui.config.GConfig;
import dzaima.ui.node.types.editable.code.langs.Lang;
import dzaima.utils.*;

import java.util.HashMap;

public abstract class Arch {
  public static final String UNK_REG = "unknown";
  
  public Lang getLang(GConfig gc) {
    return gc.langs().fromName("generic assembly");
  }
  
  public static abstract class Register {
    public abstract String base();
    public abstract String[] all();
  }
  public static class BasicReg extends Arch.Register {
    public final String[] names;
    public BasicReg(String name) { names = new String[]{name}; }
    public String base() { return names[0]; }
    public String[] all() { return names; }
  }
  public static final Vec<Register> regs = new Vec<>();
  
  public abstract String baseReg(String s);
  public abstract Vec<RegInfo> groupRegs(Vec<Pair<Integer, String>> names);
  
  
  public enum InstrMode {
    COND, // cond branches+addr
    NORETURN, // trap; unconditional jump+addr
    RETURN, // return from this function
    CALL // call function+addr
  }
  public static class InstrInfo {
    public final InstrMode mode;
    public final long jumpAddr; // -1 on none
    public InstrInfo(long jumpAddr, InstrMode mode) { this.jumpAddr=jumpAddr; this.mode=mode; }
    public InstrInfo(Long jumpAddr, InstrMode mode) { this(jumpAddr==null? -1 : jumpAddr, mode); }
  }
  public abstract InstrInfo instrInfo(String desc, long addr);
  public abstract String prettyIns(String desc, long addr);
  
  public static class RegRes {
    public final String raw;
    public final byte[] bytes;
    public final int length;
    public RegRes(String raw, byte[] bytes, int length) {
      this.raw = raw;
      this.length = length;
      this.bytes = bytes;
    }
  }
  public abstract static class RegInfo implements Comparable<RegInfo> {
    public final String name;
    public final String group;
    public final int num;
    protected RegInfo(int num, String name, String group) {
      this.num = num;
      this.name = name;
      this.group = group;
    }
    
    public abstract void addNeeded(IntVec r);
    public abstract RegRes get(HashMap<Integer, GVal> values);
    public boolean knownDefined() { return false; }
    public RegRes getDefined(HashMap<Integer, GVal> values) { throw new IllegalStateException(); }
    public int namePad() { return 4; }
    
    public String toString() { return name+"("+num+","+group+")"; }
  }
  public static class Registers {
    public final Vec<RegInfo> regs;
    public final HashMap<String, Vec<RegInfo>> groupMap;
    public Registers(Arch a, Vec<Pair<Integer, String>> raw) {
      regs = a.groupRegs(raw);
      groupMap = new HashMap<>();
      for (RegInfo c : regs) groupMap.computeIfAbsent(c.group, n->new Vec<>()).add(c);
      for (Vec<RegInfo> v : groupMap.values()) v.sort();
    }
  }
  
  
  public abstract Vec<String> defaultEnabledGroups();
  
  public static String simplifyReg(GVal rawObj, boolean reverse) {
    String raw = rawObj.optStr("value");
    if (raw==null) return "";
    if (raw.startsWith("0x")) {
      String r = raw.substring(2);
      if (reverse) {
        StringBuilder b = new StringBuilder();
        int l = r.length();
        for (int i = 0; i < l; i+= 2) b.append(r, l-i-2, l-i);
        return b.toString();
      }
      return r;
    }
    if (!raw.startsWith("{v")) return raw;
    
    int i = raw.indexOf("_int8 = {")+9;
    StringBuilder b = new StringBuilder();
    while (raw.charAt(i)!='}') {
      if (raw.charAt(i)==',') {
        i+= 2;
        continue;
      }
      if (raw.charAt(i)!='0') {
        System.err.println("failed to parse vector at "+i+": "+raw);
        return raw;
      }
      String cByte = raw.substring(i+2, i+4);
      i+= 4;
      if (raw.startsWith(" <repeats ", i)) {
        i+= 10;
        int n = 0;
        while (GdbFormat.dig(raw.charAt(i))) n = n*10 + raw.charAt(i++)-'0';
        b.append(cByte.repeat(n));
        i+= 7;
      } else {
        b.append(cByte);
      }
    }
    return b.toString();
  }
  
  public static int groupLen(int l) {
    if (l<=8) return 8;
    return l<=16? 4 : l<=32? 8 : 16;
  }
  public static String groupReg(String hex) {
    int l = hex.length();
    int gl = groupLen(l);
    if (l<=gl) return hex;
    return groupReg(hex, gl);
  }
  public static String groupReg(String hex, int gr) {
    if (hex.length()%gr != 0) return hex;
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < hex.length(); i+= gr) {
      if (i!=0) b.append(" ");
      b.append(hex, i, i+gr);
    }
    return b.toString();
  }
  
  public static byte[] parseHex(String s) {
    if (s.length()%2 != 0) return null;
    
    byte[] p = new byte[s.length()/2];
    for (int i = 0; i < s.length(); i+= 2) {
      int p1 = GdbFormat.readHex(s.charAt(i));
      int p2 = GdbFormat.readHex(s.charAt(i+1));
      if (p1==-1 || p2==-1) return null;
      p[i>>1] = (byte) (p1<<4 | p2);
    }
    return p;
  }
  
  
  protected int tows(String d, int s) {
    while (s<d.length() && d.charAt(s)!=' ' && d.charAt(s)!='\t') s++;
    return s;
  }
  protected Long readAddr(String d, int s) {
    if (s>=d.length()) return null;
    int e = tows(d, s);
    if (s==e) return null;
    String a = d.substring(s, e);
    try { return GdbFormat.parseHex(a); }
    catch (NumberFormatException t) { return null; }
  }
}
