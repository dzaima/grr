package dz.general;

import dz.general.arch.Arch;
import dz.utils.AddrMapper;
import dzaima.utils.Vec;

import java.util.HashMap;

public class DisasFn implements Comparable<DisasFn>, AddrMapper.Range {
  public final long s, e;
  public final String name;
  public final boolean mutable; // TODO this is wrong: "if true, ins may not be present, or should be re-read after any program execution"
  boolean jumpsRead;
  public final ParsedIns[] ins;
  
  public DisasFn(long s, long e, String name, boolean mutable, ParsedIns[] ins) {
    this.s = s;
    this.e = e;
    this.name = name;
    this.mutable = mutable;
    this.ins = ins;
  }
  
  public void readJumps(Arch a) {
    if (jumpsRead) return;
    jumpsRead = true;
    ParsedIns[] insns = ins;
    HashMap<Long, ParsedIns> map = new HashMap<>();
    for (ParsedIns c : insns) map.put(c.s, c);
    boolean nextNewBB = false;
    for (ParsedIns c : insns) {
      c.likelyNewBB = nextNewBB;
      nextNewBB = false;
      
      Arch.InstrInfo i = a.instrInfo(c.desc, c.s);
      if (i==null) continue;
      c.jumpMode = (byte) (i.mode==Arch.InstrMode.RETURN? 4 : 3);
      if (i.jumpAddr!=-1 && i.mode!=Arch.InstrMode.CALL) {
        ParsedIns target = map.get(i.jumpAddr);
        if (target != null) {
          c.target = target;
          if (target.from==null) target.from = new Vec<>();
          target.from.add(c);
          c.jumpMode = (byte) (i.jumpAddr<c.s? 1 : 2);
        }
      }
      
      nextNewBB = i.mode==Arch.InstrMode.NORETURN || i.mode==Arch.InstrMode.RETURN;
    }
  }
  
  public int compareTo(DisasFn o) { return Long.compare(e, o.e); }
  
  public int indexOf(long addr) {
    int s = 0;
    int e = ins.length;
    while (e - s > 1) {
      int m = (s + e)/2;
      if (ins[m].s > addr) e = m;
      else s = m;
    }
    return s;
  }
  
  public boolean contains(long addr) {
    return addr >= s && addr < e;
  }
  
  public long s() { return s; }
  public long e() { return e; }
  
  public static class ParsedIns {
    public final long s;
    public final byte[] opcode;
    public final String desc;
    public byte jumpMode; // 0-none; 1-up; 2-down; 3-call; 4-return
    public boolean likelyNewBB;
    public ParsedIns target;
    public Vec<ParsedIns> from;
    public SourceMap map;
    
    public ParsedIns(long s, byte[] opcode, String desc) { this.s = s; this.opcode = opcode; this.desc = desc; }
    public long e() { return s + opcode.length; }
    
    public boolean equals(Object o) { return o instanceof ParsedIns && s==((ParsedIns)o).s; }
    public int hashCode() { return (int) s; }
  }
  
  public static class SourceMap {
    public static final SourceMap NONE = new SourceMap(null, null, null, -1, -1);
    public final SourceMap next; // to build a stack of source mappings if such is supported by debug info
    public final String shortFile, fullFile; // either both are null, or both are set
    public final int line, col; // -1 if unsupported
    public SourceMap(SourceMap next, String shortFile, String fullFile, int line, int col) {
      this.next = next;
      this.shortFile = shortFile;
      this.fullFile = fullFile;
      this.line = line;
      this.col = col;
    }
    
    public static Vec<SourceMap> unroll(SourceMap p) { // deepest location first
      Vec<SourceMap> r = new Vec<>();
      while (p!=null) {
        r.add(p);
        p = p.next;
      }
      return r;
    }
  }
}
