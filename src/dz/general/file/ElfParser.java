package dz.general.file;

import dz.utils.ByteReader;
import dzaima.utils.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static dz.general.file.Elf.*;

public class ElfParser extends ByteReader {
  private ElfParser() { }
  public boolean bits64;
  public int type, arch;
  public long entryPoint, phoff, shoff;
  public final Vec<Section> sections = new Vec<>();
  public final Vec<Program> programs = new Vec<>();
  
  public Section findSection(String name) {
    for (Section c : sections) {
      if (name.equals(c.name)) return c;
    }
    return null;
  }
  
  public byte[] fileReadRange(long s, long len) {
    return readRange(s, Math.toIntExact(len)); 
  }
  public String fileReadString(long s) {
    setPos(s);
    return str();
  }
  
  public Note getNote(String name, int type) {
    for (Note c : getNotes()) {
      if (c.name.equals(name) && c.type==type) return c;
    }
    return null;
  }
  
  private long fusz() { return u32(); } // idk
  private Vec<Note> noteCache;
  public Vec<Note> getNotes() {
    if (noteCache==null) {
      noteCache = new Vec<>();
      for (Section c : sections) {
        if (c.type!=SHT_NOTE) continue;
        try {
          setPos(c.fileOff);
          long nameSz = fusz();
          long descSz = fusz();
          long type = fusz();
          byte[] nameB = nextBytes(nameSz);
          byte[] desc = nextBytes(descSz);
          
          int len = nameB.length;
          while (len>0 && nameB[len-1]==0) len--;
          String name = new String(nameB, 0, len, StandardCharsets.UTF_8);
          noteCache.add(new Note(c, name, desc, type));
        } catch (Throwable t) {
          Log.warn("elf load", "Couldn't load note of section "+c.name+": "+t);
          // Log.stacktrace("elf load", t);
        }
      }
    }
    return noteCache;
  }
  
  public Vec<Symbol> readSymbols() {
    Vec<Symbol> a = readSymbols(findSection(".strtab"), findSection(".symtab"), SHT_SYMTAB);
    Vec<Symbol> b = readSymbols(findSection(".dynstr"), findSection(".dynsym"), SHT_DYNSYM);
    if (a==null) return b;
    if (b==null) return a;
    a.addAll(b);
    return a;
  }
  private Vec<Symbol> readSymbols(Section strs, Section syms, int symType) {
    try {
      if (strs==null || syms==null) return null;
      if (strs.type!=SHT_STRTAB || syms.type!=symType) return null;
      Vec<Symbol> r = new Vec<>();
      setPos(syms.fileOff);
      int num = Math.toIntExact(syms.size/syms.entsize);
      for (int i = 0; i < num; i++) {
        int nameOff = u32();
        long value, size;
        int info, other;
        int shndx;
        if (bits64) {
          info = u8();
          other = u8();
          shndx = u16();
          value = u64();
          size = u64();
        } else {
          value = u32();
          size = u32();
          info = u8();
          other = u8();
          shndx = u16();
        }
        
        long p0 = getPos();
        setPos(strs.fileOff+nameOff);
        String nameStr = str();
        setPos(p0);
        r.add(new Symbol(value, size, info, nameStr, shndx));
      }
      return r;
    } catch (Throwable t) {
      Log.warn("elf read", "Failed to read symbol & string tables "+syms.name+" & "+strs.name+" in "+openFilePath());
      Log.stacktrace("elf read", t);
      return null;
    }
  }
  
  private boolean load() {
    if (size()<10) return false;
    if (u8()!=127) return false;
    if (u8()!='E') return false;
    if (u8()!='L') return false;
    if (u8()!='F') return false;
    int bitV = u8();
    if (bitV!=1 && bitV!=2) return false;
    bits64 = bitV==2;
    if (u8()!=1) return false; // endianness; want little
    if (u8()!=1) return false; // elf version
    u8(); // ABI
    u8(); // ABI
    skip(7); // padding
    type = u16();
    arch = u16();
    u32(); // more version
    entryPoint = usz();
    phoff = usz();
    shoff = usz();
    u32(); // arch flags
    u16(); // header size
    u16(); // program header table entry size
    int phnum = u16(); // program header table entry count
    u16(); // section header table entry size
    int shnum = u16(); // section header table entry count
    int shnms = u16(); // section header table entry with section names
    
    setPos(shoff);
    for (int i = 0; i < shnum; i++) {
      int nameOff = u32();
      int type = u32();
      long flags = usz();
      long addr = usz();
      long fileOff = usz();
      long size = usz();
      u32(); // link
      int info = u32(); // info
      usz(); // align
      long entsize = usz(); // entsize
      sections.add(new Section(nameOff, type, flags, addr, fileOff, size, info, entsize));
    }
    Section nameSec = sections.get(shnms);
    for (Section c : sections) {
      setPos(nameSec.fileOff+c.nameOff);
      c.name = str();
    }
    
    setPos(phoff);
    for (int i = 0; i < phnum; i++) {
      int flags = -1;
      int type = u32();
      if (bits64) flags = u32();
      long off = usz();
      long vaddr = usz();
      long paddr = usz();
      long filesz = usz();
      long memsz = usz();
      if (!bits64) flags = u32();
      long align = usz();
      programs.add(new Program(type, flags, off, vaddr, paddr, filesz, memsz, align));
    }
    return true;
  }
  private long usz() { return bits64? u64() : u32(); }
  
  public static ElfParser loadFile(Path p) {
    return loadFile(p, false);
  }
  public static ElfParser loadFile(Path p, boolean quietFail) {
    try {
      ElfParser r = new ElfParser();
      if (!r.loadFromFile(p)) return null;
      return r.load()? r : null;
    } catch (Throwable t) {
      if (!quietFail) Log.stacktrace("elf load", t);
      return null;
    }
  }
  
  public static class Section {
    final int nameOff;
    public final int type, info;
    public final long flags, addr, fileOff, size, entsize;
    public String name;
    
    public Section(int nameOff, int type, long flags, long addr, long fileOff, long size, int info, long entsize) {
      this.nameOff = nameOff;
      this.type = type;
      this.flags = flags;
      this.addr = addr;
      this.fileOff = fileOff;
      this.size = size;
      this.info = info;
      this.entsize = entsize;
    }
  }
  public static class Note {
    public final Section s;
    public final String name;
    public final byte[] desc;
    public final long type;
    public Note(Section s, String name, byte[] desc, long type) {
      this.s = s;
      this.name = name;
      this.desc = desc;
      this.type = type;
    }
  }
  public static class Program {
    public final int type, flags;
    public final long off, vaddr, paddr, filesz, memsz, align;
    public Program(int type, int flags, long off, long vaddr, long paddr, long filesz, long memsz, long align) {
      this.type = type;
      this.flags = flags;
      this.off = off;
      this.vaddr = vaddr;
      this.paddr = paddr;
      this.filesz = filesz;
      this.memsz = memsz;
      this.align = align;
    }
    public boolean executable() { return (flags&1)!=0; }
  }
  public static class Symbol {
    public final long addr;
    public final long size;
    public final String name;
    public final int sec, info;
    
    public Symbol(long addr, long size, int info, String name, int sec) {
      this.addr = addr;
      this.size = size;
      this.name = name;
      this.sec = sec;
      this.info = info;
    }
    
    public int st_type() { return info & 0xf; } // STT_*
    public int st_bind() { return info>>4; } // STB_*
  }
}
