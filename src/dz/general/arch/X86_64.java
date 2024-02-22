package dz.general.arch;

import dz.gdb.GdbFormat.GVal;
import dzaima.ui.gui.config.GConfig;
import dzaima.ui.node.types.editable.code.langs.*;
import dzaima.utils.*;

import java.util.*;
import java.util.regex.*;

public class X86_64 extends Arch {
  public static final X86_64 inst = new X86_64();
  
  public static class GPR extends Arch.Register {
    public final String w64, w32, w16, w8, w8H;
    public final String[] alts;
    
    GPR(String w64, String w32, String w16, String w8, String w8H) {
      Vec<String> alts = new Vec<>();
      this.w64 = w64; alts.add(w64);
      this.w32 = w32; alts.add(w32);
      this.w16 = w16; alts.add(w16);
      this.w8  = w8;  if (w8 !=null) alts.add(w8);
      this.w8H = w8H; if (w8H!=null) alts.add(w8H);
      this.alts = alts.toArray(new String[0]);
    }
    public String base() { return w64; }
    public String[] all() { return alts; }
  }
  
  public static class XMM extends Arch.Register {
    public final int idx;
    public final String[] alts;
    public XMM(int idx) {
      this.idx = idx;
      if (idx<8) alts = new String[]{"zmm"+idx, "ymm"+idx, "xmm"+idx, "mm"+idx};
      else       alts = new String[]{"zmm"+idx, "ymm"+idx, "xmm"+idx};
    }
    
    public String base() { return alts[0]; }
    public String[] all() { return alts; }
  }
  
  public static class X87ST extends BasicReg { public X87ST(String name) { super(name); } }
  public static class X87Flag extends BasicReg { public X87Flag(String name) { super(name); } }
  public static class Seg extends BasicReg { public Seg(String name) { super(name); } }
  public static class USeg extends BasicReg { public USeg(String name) { super(name); } }
  public static class Flag extends BasicReg { public Flag(String name) { super(name); } }
  public static class Mask extends BasicReg { public Mask(String name) { super(name); } }
  
  public static final GPR RIP;
  public static final Vec<GPR> GPRs = new Vec<>();
  public static final Vec<XMM> XMMs = new Vec<>();
  public static final Vec<Flag> FLAGS = new Vec<>();
  public static final Vec<X87ST> X87_STs = new Vec<>();
  public static final Vec<X87Flag> X87_EXTs = new Vec<>();
  public static final Vec<Mask> MASKS = new Vec<>();
  public static final Vec<USeg> USEFUL_SEGS = new Vec<>();
  public static final Vec<Seg> USELESS_SEGS = new Vec<>();
  public static final GPR ORIG_RAX;
  
  public static final HashMap<String, String> toBase = new HashMap<>();
  public static final HashMap<String, Register> regMap = new HashMap<>();
  static {
    for (char c : new char[]{'a', 'b', 'c', 'd'})      GPRs.add(new GPR("r"+c+"x", "e"+c+"x", c+"x", c+"l", c+"h"));
    for (String c : new String[]{"si","di","sp","bp"}) GPRs.add(new GPR("r"+c,     "e"+c,     c,     c+"l", null));
    for (int i = 8; i < 16; i++) { String r = "r"+i;   GPRs.add(new GPR(    r,         r+"d", r+"w", r+"b", null)); }
    RIP = new GPR("rip", "eip", "ip", null, null);
    ORIG_RAX = new GPR("orig_rax", "orig_eax", "orig_ax", null, null); // weird gdb thing
    
    for (int i = 0; i < 32; i++) XMMs.add(new XMM(i));
    for (int i = 0; i < 8; i++) X87_STs.add(new X87ST("st"+i));
    for (int i = 0; i < 8; i++) MASKS.add(new Mask("k"+i));
    
    for (String s : new String[]{"fctrl", "fstat", "ftag", "fiseg", "fioff", "foseg", "fooff", "fop"}) X87_EXTs.add(new X87Flag(s));
    USEFUL_SEGS.add(new USeg("fs_base"));
    for (String s : new String[]{"cs", "ss", "ds", "es", "fs", "gs", "gs_base"}) USELESS_SEGS.add(new Seg(s));
    FLAGS.add(new Flag("eflags"));
    FLAGS.add(new Flag("mxcsr"));
    
    regs.add(RIP);
    regs.addAll(GPRs);
    regs.addAll(XMMs);
    regs.addAll(X87_STs);
    regs.addAll(X87_EXTs);
    regs.addAll(USELESS_SEGS);
    regs.addAll(USEFUL_SEGS);
    regs.addAll(FLAGS);
    regs.add(ORIG_RAX);
    for (Arch.Register r : regs) {
      String base = r.base();
      for (String a : r.all()) toBase.put(a, base);
      regMap.put(r.base(), r);
    }
    for (int i = 0; i < XMMs.sz; i++) toBase.put("ymm"+i+"h", XMMs.get(i).base()); // more funky gdb registers
    for (int i = 8; i < 16; i++) toBase.put("r"+i+"l", GPRs.get(i).base()); // it's r8b not r8l gdb ಠ_ಠ
  }
  
  public Lang getLang(GConfig gc) {
    return AsmLang.X86;
  }
  
  public String baseReg0(String s) {
    return toBase.get(s.toLowerCase());
  }
  public String baseReg(String s) {
    if (s.startsWith("%")) s = s.substring(1);
    String r = baseReg0(s);
    return r!=null? r : s;
  }
  
  private final Vec<String> defaultEnabledGroups = Vec.of("gpr", "flag", "thread segment", "xmm");
  public Vec<String> defaultEnabledGroups() {
    return defaultEnabledGroups;
  }
  
  String best(String[] wanted, Vec<String> available) {
    for (String c : wanted) if (available.indexOfEqual(c)!=-1) return c;
    System.err.println("None of "+Arrays.toString(wanted)+" are available in "+available);
    return available.get(0);
  }
  public Vec<RegInfo> groupRegs(Vec<Pair<Integer, String>> names) {
    HashMap<String, Vec<String>> subs = new HashMap<>();
    HashSet<String> unknown = new HashSet<>();
    HashMap<String, Integer> idxs = new HashMap<>();
    for (Pair<Integer, String> c : names) {
      String name = c.b;
      idxs.put(name, c.a);
      String k = baseReg0(name);
      if (k != null) subs.computeIfAbsent(k, s -> new Vec<>()).add(name);
      else unknown.add(name);
    }
    Vec<RegInfo> res = new Vec<>();
    subs.forEach((k, v) -> {
      Register r = regMap.get(k);
      String group = r instanceof GPR? "gpr"
        : r instanceof XMM? "xmm"
        : r instanceof Flag? "flag"
        : r instanceof X87ST? "x87 stack"
        : r instanceof X87Flag? "x87 flag"
        : r instanceof Seg? "segment"
        : r instanceof USeg? "thread segment"
        : "unknown";
      String main = best(r.all(), v);
      Integer mainIdx = idxs.get(main);
      if (mainIdx==null) {
        System.err.println("didn't find register index for "+main);
        return;
      }
      
      Integer shIdx1_0 = idxs.get(main+"s1");
      Integer shIdx2_0 = null;
      if (shIdx1_0==null && r instanceof XMM && main.startsWith("ymm")) {
        shIdx1_0 = idxs.get("ymm"+((XMM) r).idx+"hs1");
        shIdx2_0 = idxs.get("xmm"+((XMM) r).idx+"s1");
      }
      Integer shIdx1 = shIdx1_0;
      Integer shIdx2 = shIdx2_0;
      
      res.add(new RegInfo(mainIdx, main, group) {
        public void addNeeded(IntVec r) {
          r.add(mainIdx);
          if (shIdx1!=null) r.add(shIdx1);
          if (shIdx2!=null) r.add(shIdx2);
        }
        
        RegRes get(HashMap<Integer, GVal> values, Integer idx) {
          GVal rawObj = values.get(idx);
          String v = rawObj==null? Arch.UNK_REG : simplifyReg(rawObj, r instanceof XMM);
          byte[] bs = parseHex(v);
          if (bs!=null) v = groupReg(v);
          return new RegRes(v, bs, bs==null? -1 : bs.length*8);
        }
        public RegRes get(HashMap<Integer, GVal> values) {
          return get(values, mainIdx);
        }
        
        public boolean knownDefined() {
          return shIdx1!=null;
        }
        
        public RegRes getDefined(HashMap<Integer, GVal> values) {
          if (shIdx1==null) return null;
          RegRes r1 = get(values, shIdx1);
          if (shIdx2==null) return r1;
          RegRes r2 = get(values, shIdx2);
          if (r1.bytes==null || r2.bytes==null) return null;
          int l1 = r1.bytes.length;
          int l2 = r2.bytes.length;
          byte[] nb = new byte[l1+l2];
          System.arraycopy(r1.bytes, 0, nb, 0, l1);
          System.arraycopy(r2.bytes, 0, nb, l1, l2);
          return new RegRes(r1.raw+" + "+r2.raw, nb, nb.length*8);
        }
        
        public int compareTo(RegInfo o) {
          return Integer.compare(num, o.num);
        }
        
        public int namePad() {
          return r instanceof XMM? 5 : r instanceof Flag? 6 : 4;
        }
      });
    });
    
    // StringBuilder b = new StringBuilder();
    // for (String c : unknown) {
    //   if (!(c.endsWith("s1") || c.endsWith("s2"))) {
    //     if (b.length()>0) b.append(", ");
    //     b.append(c);
    //   }
    // }
    // if (b.length()>0) Log.info("x86-64: unknown registers: "+b);
    return res;
  }
  
  public static final Set<String> addrInsns = Set.of("call", "callq", "jmp", "jo", "jno", "jb", "jae", "je", "jne", "jbe", "ja", "js", "jns", "jp", "jnp", "jl", "jge", "jle", "jg");
  public InstrInfo instrInfo(String d, long instrAddr) {
    if (d.startsWith("ds ")) d = d.substring(3);
    else if (d.startsWith("notrack ")) d = d.substring(8);
    
    int s = tows(d, 0);
    String b = d.substring(0, s);
    if (b.equals("ud1") || b.equals("ud2")) return new InstrInfo(-1, InstrMode.NORETURN);
    if (b.equals("ret")) return new InstrInfo(-1, InstrMode.RETURN);
    
    if (addrInsns.contains(b)) {
      InstrMode type = b.startsWith("call")? InstrMode.CALL : b.equals("jmp")? InstrMode.NORETURN : InstrMode.COND;
      has: {
        while (d.charAt(s)==' ') if (++s==d.length()) break has;
        if (!d.startsWith("0x",s)) break has;
        Long addr = readAddr(d, s);
        if (addr==null) break has;
        return new InstrInfo(addr, type);
      }
      if (type!=InstrMode.COND) return new InstrInfo(-1, type);
    }
    return null;
  }
  
  public static final Pattern decodedAddr = Pattern.compile("0x([0-9a-fA-F]+)( <([^>+]+)(\\+[0-9]+)?>)?$");
  public static final Pattern sizeMarker = Pattern.compile("(BYTE|WORD|DWORD|QWORD|TWORD|MMWORD|XMMWORD|YMMWORD|ZMMWORD) PTR \\[");
  public String prettyIns(String desc, long addr) {
    desc = GenericArch.expandCommas(desc);
    Matcher m = decodedAddr.matcher(desc);
    if (m.find()) {
      if (m.group(4)!=null) {
        InstrInfo ii = instrInfo(desc, addr);
        if (ii!=null && (ii.mode==InstrMode.COND || ii.mode==InstrMode.NORETURN)) {
          return desc.substring(0, m.start()) + m.group(4);
        }
      }
      long a2 = Long.parseUnsignedLong(m.group(1), 16);
      if ((m = sizeMarker.matcher(desc)).find()) {
        int sz;
        switch (m.group(1)) { default: sz=0; break;
          case    "BYTE": sz=1; break;
          case    "WORD": sz=2; break;
          case   "DWORD": sz=4; break;
          case   "QWORD":
          case  "MMWORD": sz=8; break;
          case "XMMWORD": sz=16; break;
          case "YMMWORD": sz=32; break;
          case "ZMMWORD": sz=64; break;
          case   "TWORD": sz=10; break;
        }
      }
    }
    return desc;
  }
}