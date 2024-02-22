package dz.general.file;

import dzaima.utils.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.function.LongConsumer;

@SuppressWarnings({"PointlessBitwiseExpression", "unused"})
public class Elf {
  public static final int ET_NONE = 0; // unknown
  public static final int ET_REL  = 1; // relocatable file
  public static final int ET_EXEC = 2; // executable file
  public static final int ET_DYN  = 3; // shared object
  public static final int ET_CORE = 4; // core file
  
  public static final int A_X86 = 3;
  public static final int A_AMD64 = 62;
  public static final int A_ARM32 = 40;
  public static final int A_ARM64 = 183;
  public static final int A_RISCV = 243;
  
  public static final int PT_NULL    = 0; // unused
  public static final int PT_LOAD    = 1; // loadable segment
  public static final int PT_DYNAMIC = 2; // dynamic linking information
  public static final int PT_INTERP  = 3; // interpreter information
  public static final int PT_NOTE    = 4; // auxiliary information
  public static final int PT_SHLIB   = 5; // reserved
  public static final int PT_PHDR    = 6; // segment containing program header table
  public static final int PT_TLS     = 7; // thread-local storage template
  
  public static final int PF_X = 1<<0; // executable
  public static final int PF_W = 1<<1; // writable
  public static final int PF_R = 1<<2; // readable
  
  public static final int SHT_NULL = 0;
  public static final int SHT_PROGBITS = 1;
  public static final int SHT_SYMTAB = 2;
  public static final int SHT_STRTAB = 3;
  public static final int SHT_NOTE = 7;
  public static final int SHT_DYNSYM = 11;
  
  
  public static final int SHF_WRITE            = 1<<0;  // writable
  public static final int SHF_ALLOC            = 1<<1;  // occupies memory during execution
  public static final int SHF_EXECINSTR        = 1<<2;  // executable
  public static final int SHF_MERGE            = 1<<4;  // might be merged
  public static final int SHF_STRINGS          = 1<<5;  // contains null-terminated strings
  public static final int SHF_INFO_LINK        = 1<<6;  // 'sh_info' contains SHT index
  public static final int SHF_LINK_ORDER       = 1<<7;  // preserve order after combining
  public static final int SHF_OS_NONCONFORMING = 1<<8;  // non-standard OS specific handling required
  public static final int SHF_GROUP            = 1<<9;  // section is member of a group
  public static final int SHF_TLS              = 1<<10; // section hold thread-local data
  
  public static final int STT_NOTYPE   = 0;
  public static final int STT_OBJECT   = 1;
  public static final int STT_FUNC     = 2;
  public static final int STT_SECTION  = 3;
  public static final int STT_FILE     = 4;
  public static final int STT_COMMON   = 5;
  public static final int STT_TLS      = 6;
  
  
  public static final class Strtab {
    public final String[] unames;
    public final int[] idxs;
    public final int[] offs;
    public final int[] uoffs;
    public Strtab(String[] unames, int[] idxs, int[] offs, int[] uoffs) {
      this.unames = unames;
      this.idxs = idxs;
      this.offs = offs;
      this.uoffs = uoffs;
    }
    
    public byte[] data() {
      ByteVec r = new ByteVec();
      for (String c : unames) {
        r.addAll(c.getBytes(StandardCharsets.UTF_8));
        r.add((byte) 0);
      }
      return r.get();
    }
  }
  public static Strtab strtab(Vec<String> strs) { // assumes strs are all byte-strings
    Vec<String> unames = new Vec<>();
    int[] idxs = new int[strs.sz];
    int[] offs = new int[strs.sz];
    IntVec uoffs = new IntVec();
    int coff = 0;
    HashMap<String,int[]> map = new HashMap<>();
    for (int i = 0; i < strs.sz; i++) {
      String c = strs.get(i);
      int idx;
      if (map.containsKey(c)) {
        int[] l = map.get(c);
        idx = l[0];
        offs[i] = l[1];
      } else {
        idx = map.size();
        unames.add(c);
        uoffs.add(coff);
        offs[i] = coff;
        map.put(c, new int[]{idx, coff});
        coff+= c.length()+1;
      }
      idxs[i] = idx;
    }
    return new Strtab(unames.toArray(new String[0]), idxs, offs, uoffs.get());
  }
  
  public static class Writer {
    public final int bits, bytes;
    public Writer(int bits) {
      this.bits = bits;
      this.bytes = bits/8;
    }
    public byte[] get() { return v.get(); }
    
    // little-endian writes
    ByteVec v = new ByteVec();
    public void wN(long x, int n) { for (int i = 0; i < n; i++) v.add((byte) (x>>(i*8))); }
    public void w(byte[] a) { v.addAll(a); }
    public void w1(int x) { v.add((byte) x); }
    public void w2(int x) { wN(x, 2); }
    public void w4(int x) { wN(x, 4); }
    public void w8(long x) { wN(x, 8); }
    public void wS(long x) { wN(x, bytes); }
    public void wSb(long x, int bits) { wN(x, bits/8); }
    public void wv(int... a) {
      byte[] b = new byte[a.length];
      for (int i = 0; i < a.length; i++) b[i] = (byte) a[i];
      w(b);
    }
    
    public void wSLEB128(long x) {
      while (true) {
        byte c = (byte) (x & 0x7F);
        boolean last = (x>>7) == (x>>6);
        w1(c | (last? 0 : 0x80));
        if (last) return;
        x>>= 7;
      }
    }
    public void wULEB128(long x) {
      while (true) {
        byte c = (byte) (x & 0x7F);
        x>>>= 7;
        boolean last = x==0;
        w1(c | (last? 0 : 0x80));
        if (last) return;
      }
    }
    public int off() { return v.sz; }
    public LongConsumer prepS() { int p = v.sz; wN(0, bytes); return (x) -> updS(p, x); }
    public void updN(int p, long x, int n) { for (int i = 0; i < n; i++) v.arr[p+i] = (byte) (x>>(i*8)); }
    public void updS(int p, long x) { updN(p, x, bytes); }
    
    Runnable dwarfSz() {
      if (bits==64) w4(-1);
      LongConsumer i = prepS();
      int sz0 = v.sz;
      return () -> i.accept(v.sz-sz0);
    }
  }
}
