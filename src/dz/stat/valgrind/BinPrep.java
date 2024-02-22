package dz.stat.valgrind;

import dz.Main;
import dz.general.file.*;
import dzaima.utils.*;

import java.nio.file.*;

import static dz.general.file.Elf.STT_FUNC;

public class BinPrep {
  private static final String GRR_SECTION = ".grr_prep_cachegrind"; // for being able to determine input/output files already being prepared
  public static boolean writeAssemblyLineDwarf(Path inp, Path out) {
    ElfParser inpElf = ElfParser.loadFile(inp);
    if (inpElf==null) {
      System.err.println("Couldn't open input file");
      return false;
    }
    if (inpElf.findSection(GRR_SECTION)!=null) {
      System.err.println("Refusing to process an a --prep-cachegrind input that's an output of a previous --prep-cachegrind");
      return false;
    }
    questionOut: if (Files.exists(out)) {
      ElfParser outElf = ElfParser.loadFile(out, true);
      if (outElf!=null && outElf.findSection(GRR_SECTION)!=null) break questionOut;
      
      if (!Main.ask(inp.equals(out)? "Really write over the input? You'll still need the original unmodified binary to view afterwards!" : "Write over "+out+"?")) {
        System.err.println("Aborting.");
        return false;
      }
    }
    
    Dwarf.LineInfo lines = new Dwarf.LineInfo();
    Dwarf.DebugInfo info = new Dwarf.DebugInfo();
    // ElfWriter.ElfSyms syms = new ElfWriter.ElfSyms() {
    //   public String symTabName() { return ".symtab"; }
    //   public String strTabName() { return ".strtab"; }
    // };
    
    for (ElfParser.Symbol c : inpElf.readSymbols()) {
      // syms.addSymbol(c.addr, c.size, "grr_prep_"+c.addr+"_"+c.name, c.info, c.sec);
      if (c.st_type() != STT_FUNC) continue;
      info.addSym(c.addr, c.size, c.name);
      for (int i = 0; i < c.size; i++) {
        lines.line(c.addr+i, "/direct/byte/mapped/lines", i, 0);
      }
    }
    
    try {
      Vec<String> cmd = Vec.of(
        "objcopy",
        "--add-section", GRR_SECTION+"=/dev/null"
      );
      
      Vec<Dwarf.DwarfSection> ss = new Vec<>();
      ss.addAll(lines.finish());
      ss.addAll(info.finish());
      // ss.addAll(syms.finish().map(WrapperSection::new));
      
      Path[] ps = new Path[ss.sz];
      for (int i = 0; i < ps.length; i++) {
        Dwarf.DwarfSection s = ss.get(i);
        String n = s.name();
        Path p = Files.createTempFile("grrtmp_", n);
        ps[i] = p;
        Files.write(p, s.directData());
        
        if (s instanceof WrapperSection) {
          cmd.add("--update-section");
          cmd.add(n+"="+p);
        } else {
          cmd.add("--remove-section");
          cmd.add(n);
          
          cmd.add("--add-section");
          cmd.add(n+"="+p);
        }
      }
      
      cmd.add("--");
      cmd.add(inp.toString());
      cmd.add(out.toString());
      
      Process p = new ProcessBuilder(cmd.toArray(new String[0]))
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
        .redirectInput(ProcessBuilder.Redirect.INHERIT)
        .start();
      
      int exitCode = p.waitFor();
      
      for (Path path : ps) Files.delete(path);
      
      if (exitCode!=0) {
        System.err.println("objcopy returned with exit code "+p.exitValue());
        return false;
      }
      return true;
    } catch (Throwable e) {
      Log.stacktrace("dwarf rewriting", e);
      return false;
    }
  }
  
  private static class WrapperSection extends Dwarf.DwarfSection {
    private final ElfWriter.Section c;
    
    public WrapperSection(ElfWriter.Section c) { this.c = c; }
    
    protected byte[] computeData() {
      ElfWriter.Data d = c.getData();
      Elf.Writer w = new Elf.Writer(64);
      d.writeTo(w);
      return w.get();
    }
    
    public String name() {
      return c.name();
    }
  }
}
