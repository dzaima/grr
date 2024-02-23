package dz.stat.java;

import dz.general.DisasFn;
import dz.general.file.*;
import dzaima.utils.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.stream.Stream;

public class JavaPrintAssembly {
  public final Vec<JSym> syms;
  private final HashMap<String, JSym> idMap = new HashMap<>();
  private final HashMap<String, JSym> nameMap = new HashMap<>();
  JavaPrintAssembly(Vec<JSym> syms) {
    this.syms = syms;
    for (JSym c : syms) idMap.put(c.id, c);
    for (JSym c : syms) nameMap.put(c.name, c);
  }
  
  public JSym findByID(String id) {
    return idMap.get(id);
  }
  public JSym findByName(String id) {
    return nameMap.get(id);
  }
  
  public static final String ID_PRE = "//java-dyn";
  private static int idCtr = 0;
  
  public static final class JSym {
    public final byte[] code;
    public final long addrS;
    public final String name;
    public final String id;
    public final Vec<Pair<Long, DisasFn.SourceMap>> maps;
    public final long addrE;
    
    public JSym(byte[] code, long addrS, String name, Vec<Pair<Long, DisasFn.SourceMap>> maps) {
      this.code = code;
      this.addrS = addrS;
      this.name = name;
      this.maps = maps;
      addrE = addrS+code.length;
      id = ID_PRE + idCtr++;
    }
    
    public long size() { return addrE-addrS; }
    
    public DisasFn.SourceMap findSource(long addr) {
      int i = maps.binarySearch(c -> c.a >= addr);
      return i<maps.sz? maps.get(i).b : null;
    }
  }
  
  public void writeElf(Path p) {
    ElfWriter w = new ElfWriter(64, Elf.ET_DYN, Elf.A_AMD64);
    
    ElfWriter.ElfSyms tab = w.symtab();
    int[] i = new int[1];
    for (JSym c : syms) {
      ElfWriter.Data d = new ElfWriter.Data() {
        public void writeTo(Elf.Writer w) { w.w(c.code); }
      };
      w.addProg(Elf.PT_LOAD, Elf.PF_R|Elf.PF_X, c.addrS, d);
      int sec = w.addSec(new ElfWriter.Section() {
        protected int type() { return Elf.SHT_PROGBITS; }
        public String name() { return ".text."+(i[0]++); }
        protected long addr() { return c.addrS; }
        public ElfWriter.Data getData() { return d; }
        protected int flags() { return Elf.SHF_ALLOC | Elf.SHF_EXECINSTR; }
      });
      
      tab.addSymbol(c.addrS, c.size(), Elf.STT_FUNC|Elf.STB_LOCAL, c.name, sec);
    }
    
    
    try { Files.write(p, w.finish()); }
    catch (IOException e) { Log.stacktrace("JSym::writeElf", e); }
  }
  
  public static JavaPrintAssembly load(Path p) {
    try (Stream<String> ls = Files.lines(p)) {
      Vec<JSym> syms = new Vec<>();
      int mode = 0; // 0:none; 1:before-MachCode; 2:MachCode
      String comp = "", name = "??";
      HashMap<String, Integer> collisionMap = new HashMap<>();
      ByteVec bs = new ByteVec();
      Vec<Pair<Long, DisasFn.SourceMap>> symMaps = new Vec<>();
      Vec<DisasFn.SourceMap> cmap = null;
      long addr0 = -1;
      long caddr = -1;
      for (String l : (Iterable<String>) ls::iterator) {
        if (mode!=2) {
          if (l.equals("----------------------------------- Assembly -----------------------------------") || l.startsWith("Compiled method (n/a)")) {
            name = "??";
            comp = "NA";
            mode = 1;
            caddr=addr0=-1;
            bs = new ByteVec();
          } else if (l.equals("[MachCode]")) mode = 2;
          else if (l.startsWith("Compiled method (")) {
            int i = l.indexOf(')', 17);
            if (i!=-1) comp = l.substring(17, i);
          }
          continue;
        }
        
        String comment = null;
        if (l.startsWith("  0x")) {
          l = l.substring(4);
          int i = l.indexOf(':');
          long addr = Long.parseUnsignedLong(l.substring(0,i), 16);
          if (addr0==-1) addr0 = caddr = addr;
          assert caddr == addr : "expected to be at "+addr+", was at "+caddr;
          
          int j = l.indexOf(';', i);
          if (j==-1) j = l.length();
          else comment = l.substring(j+1);
          l = l.substring(i+2, j);
          
          StringBuilder hex = new StringBuilder();
          for (int k = 0; k < l.length(); k++) {
            char c = l.charAt(k);
            if (c>='0' & c<='9' || c>='a' & c<='f') hex.append(c);
          }
          int n = hex.length()/2;
          for (int k = 0; k < n; k++) {
            bs.add((byte) (phex(hex.charAt(k*2))<<4 | phex(hex.charAt(k*2+1))));
          }
          if (n!=0 && cmap!=null) {
            DisasFn.SourceMap sm = null;
            for (int k = cmap.sz-1; k >= 0; k--) {
              DisasFn.SourceMap c = cmap.get(k);
              sm = new DisasFn.SourceMap(sm, c.shortFile, c.fullFile, c.fnName, c.line, c.col);
            }
            symMaps.add(new Pair<>(caddr, sm));
            cmap = null;
          }
          caddr+= n;
        } else if (l.startsWith("                      ;")) {
          comment = l.substring(23);
        } else if (l.startsWith("  # {method}")) {
          String[] ps = Tools.split(l, '\'');
          name = JavaMangling.demangleArguments(ps[3], JavaMangling.demanglePath(ps[5])+"::"+ps[1]);
        } else if (l.equals("[/MachCode]")) {
          mode = 0;
          String spec = comp.toUpperCase();
          String key = name + " ^ " + spec;
          if (collisionMap.containsKey(key)) {
            collisionMap.put(key, collisionMap.get(key)+1);
            spec+= "#"+collisionMap.get(key);
          } else collisionMap.put(key, 1);
          syms.add(new JSym(bs.get(), addr0, "["+spec+"] "+name, symMaps));
          symMaps = new Vec<>();
        }
        
        parseComment: if (comment!=null) {
          int i0 = comment.indexOf(" - ");
          if (i0==-1) break parseComment;
          i0+= 3;
          if (comment.startsWith("(reexecute) ", i0)) i0+= 12;
          int i1 = comment.indexOf('@', i0);
          if (i1==-1) break parseComment;
          String n = comment.substring(i0, i1);
          int i2 = comment.indexOf("(line ", i1);
          int ln = -1;
          if (i2!=-1) {
            int i3 = comment.indexOf(')', i2);
            if (i3==-1) break parseComment;
            ln = Integer.parseInt(comment.substring(i2 + 6, i3));
          }
          
          int i4 = n.indexOf("::");
          String path = n.substring(0,i4).replace('.', '/');
          int i5 = path.indexOf('$');
          if (i5!=-1) path = path.substring(0, i5);
          path+= ".java";
          
          if (cmap==null) cmap = new Vec<>();
          cmap.add(new DisasFn.SourceMap(null, path, "/java-src/"+path, n.substring(i4+2), ln, -1));
        }
      }
      return new JavaPrintAssembly(syms);
    } catch (Throwable e) {
      Log.stacktrace("jvm-out parser", e);
      return null;
    }
  }
  private static int phex(char c) { return c<='9'? c-'0' : c-'a'+10; }
}
