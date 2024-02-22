package dz.general.file;

import dz.general.file.Elf.*;
import dzaima.utils.Vec;

import java.util.LinkedHashSet;
import java.util.function.LongConsumer;

public class ElfWriter {
  final int type, arch, bits;
  final Vec<Program> progs = new Vec<>();
  final Vec<Section> sections = new Vec<>();
  final Vec<Runnable> onDone = new Vec<>();
  
  public ElfWriter(int bits, int type, int arch) {
    this.bits = bits;
    this.type = type;
    this.arch = arch;
    addSec(new Section() {
      protected int type() { return Elf.SHT_NULL; }
      public String name() { return ""; }
      protected long addr() { return 0; }
      public Data getData() { return null; }
    });
  }
  
  public void addProg(int type, int flags, long vaddr, Data data) {
    progs.add(new Program() {
      protected int type() { return type; }
      protected long vaddr() { return vaddr; }
      protected int flags() { return flags; }
      protected Data getData() { return data; }
    });
  }
  public void addSec(Section s) {
    sections.add(s);
  }
  
  public byte[] finish() {
    for (Runnable c : onDone) c.run();
    
    Vec<String> stab0 = new Vec<>();
    for (Section c : sections) stab0.add(c.name());
    stab0.add(".shstrtab");
    Strtab stab = Elf.strtab(stab0);
    Data d = new Data() {
      public void writeTo(Writer w) { w.w(stab.data()); }
    };
    sections.add(new Section() {
      protected int type() { return Elf.SHT_STRTAB; }
      public String name() { throw new IllegalStateException(); }
      protected long addr() { return 0; }
      public Data getData() { return d; }
    });
    
    
    
    Writer r = new Writer(bits);
    r.w(new byte[]{127, 69, 76, 70});
    
    r.w1(bits==64? 2 : 1);
    r.w1(1); // little-endian
    r.w1(1); // ELF version
    r.w1(0); // System V ABI
    r.w1(0); // further ABI version
    r.wN(0, 7); // pad
    r.w2(type);
    r.w2(arch);
    r.w4(1); // more version
    LongConsumer ep = r.prepS(); // entry point
    LongConsumer ph = r.prepS(); // program header table
    LongConsumer sh = r.prepS(); // section header table
    r.w4(0); // arch flags
    r.w2(bits==64? 64 : 52);   // header size
    r.w2(bits==64? 56 : 32);   // program header table entry size
    r.w2(progs.sz);   // program header table entry count
    r.w2(bits==64? 64 : 40);   // section header table entry size
    r.w2(sections.sz);         // section header table entry count
    r.w2(sections.sz-1);       // section header table entry with section names
    
    LinkedHashSet<Data> allData = new LinkedHashSet<>();
    for (Program c : progs) {
      Data data = c.getData();
      if (data!=null) allData.add(data);
    }
    for (Section c : sections) {
      Data data = c.getData();
      if (data!=null) allData.add(data);
    }
    for (Data c : allData) {
      int o0 = r.off();
      c.writeTo(r);
      int o1 = r.off();
      c._realOff = o0;
      c._realSize = o1-o0;
    }
    
    // program header
    ph.accept(r.off());
    for (int i = 0; i < progs.sz; i++) {
      Program c = progs.get(i);
      Data data = c.getData();
      
      r.w4(c.type());
      if (bits==64) r.w4(c.flags());
      r.wS(data==null? 0 : data.knownOff());
      r.wS(c.vaddr());
      r.wS(c.paddr());
      long sz = data==null? 0 : data.knownSize();
      r.wS(sz);
      r.wS(sz);
      if (bits==32) r.w4(c.flags());
      r.wS(0); // alignment
      
    }
    
    // section headers
    sh.accept(r.off());
    for (int i = 0; i < sections.sz; i++) {
      Section c = sections.get(i);
      r.w4(stab.offs[i]);
      r.w4(c.type());
      r.wS(c.flags());
      r.wS(c.addr());
      Data data = c.getData();
      r.wS(data==null? 0 : data.knownOff());
      r.wS(c.size(data==null? 0 : data.knownSize()));
      r.w4(0); // sh_link
      r.w4(0); // sh_info
      r.wS(0); // alignment
      r.wS(0); // sh_entsize
    }
    
    return r.get();
  }
  
  
  public abstract static class ElfSyms {
    Vec<ElfParser.Symbol> syms = new Vec<>();
    public void addSymbol(long s, long size, String name, int info, int sec) {
      syms.add(new ElfParser.Symbol(s, size, name, sec, info));
    }
    
    public abstract String symTabName();
    public abstract String strTabName();
    public Vec<Section> finish() {
      Strtab tab = Elf.strtab(syms.map(c -> c.name));
      
      Data strData = new Data() { public void writeTo(Writer w) { w.w(tab.data()); } };
      
      return Vec.of(
        new Section() {
          protected int type() { return Elf.SHT_SYMTAB; }
          public String name() { return symTabName(); }
          protected long addr() { return 0; }
          public Data getData() {
            return new Data() {
              public void writeTo(Writer w) {
                for (int i = 0; i < syms.sz; i++) {
                  ElfParser.Symbol s = syms.get(i);
                  if (w.bits==64) {
                    w.w4(tab.offs[i]);
                    w.w1(s.info);
                    w.w1(0); // other
                    w.w2(s.sec);
                    w.wS(s.addr);
                    w.wS(s.size);
                  } else {
                    throw new RuntimeException("todo 32-bit");
                  }
                }
              }
            };
          }
        },
        new Section() {
          protected int type() { return Elf.SHT_STRTAB; }
          public String name() { return strTabName(); }
          protected long addr() { return 0; }
          public Data getData() { return strData; }
        }
      );
    }
  }
  
  public abstract static class Data {
    private long _realSize = -1;
    private long _realOff = -1;
    public long knownSize() { // must have already been written to determine
      assert _realSize != -1;
      return _realSize;
    }
    public long knownOff() {
      assert _realSize != -1;
      return _realOff;
    }
    public abstract void writeTo(Writer w);
  }
  public abstract static class Section {
    public abstract String name();
    public abstract Data getData();
    protected abstract int type();
    protected abstract long addr();
    protected int flags() { return 0; }
    protected long size(long len) { return len; }
  }
  public abstract static class Program {
    protected abstract int type();
    protected abstract long vaddr();
    protected abstract int flags();
    protected abstract Data getData();
    protected long paddr() { return 0; }
  }
}
