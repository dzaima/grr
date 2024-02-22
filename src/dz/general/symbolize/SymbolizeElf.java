package dz.general.symbolize;

import dz.debugger.Location;
import dz.general.Binary;
import dz.general.file.ElfParser;
import dz.layouts.Layout;
import dz.utils.AddrMapper;
import dzaima.utils.*;

import java.nio.file.*;
import java.util.function.Consumer;

public class SymbolizeElf {
  public static final Vec<Path> debugDirectories = Vec.of(
    Path.of("/usr/lib/debug")
  );
  
  public static void symbolize(Layout l, Binary bin, boolean unrelocatedRes, Vec<Symbolize.IPEntry> as, Consumer<Vec<Symbolize.Resolved>> got) {
    if (bin.file==null) { got.accept(null); return; }
    ElfParser e;
    manual: {
      e = ElfParser.loadFile(Path.of(bin.file));
      if (e==null) break manual;
      
      long diff = 0;
      if (bin.relocate) {
        Vec<ElfParser.Program> exes = e.programs.filter(ElfParser.Program::executable);
        if (exes.sz!=1) break manual;
        ElfParser.Program exe = exes.get(0);
        diff = exe.vaddr-exe.off;
      }
      
      AddrMapper<Symbolize.Sym> map = new AddrMapper<>();
      
      Consumer<Vec<ElfParser.Symbol>> addSymbols = syms -> {
        if (syms==null) return;
        for (ElfParser.Symbol sym : syms) {
          if (sym.size==0) continue;
          long s = sym.addr;
          map.overrideRange(new Symbolize.Sym(s, s+sym.size, new Location(s, sym.name, bin.desc, bin.file, null)));
        }
      };
      
      addSymbols.accept(e.readSymbols());
      
      ElfParser.Note buildID = e.getNote("GNU", 3);
      if (buildID!=null) {
        String binRel = bin.file;
        if (binRel.startsWith("/")) binRel = binRel.substring(1);
        String bid = Tools.hexBytes(buildID.desc);
        Path found = null;
        debugPath: for (Path p : debugDirectories) {
          Path p0 = p.resolve(".build-id/"+bid.substring(0,2)+"/"+bid.substring(2)+".debug");
          Path p1 = Path.of(bin.file+".debug");
          Path p2 = p1.getParent().resolve(".debug").resolve(p1.getFileName());
          Path p3 = p.resolve(binRel+".debug");
          for (Path pn : new Path[]{p0, p1, p2, p3}) {
            if (Files.exists(pn)) { found = pn; break debugPath; }
          }
        }
        if (found!=null) {
          ElfParser debugE = ElfParser.loadFile(found);
          if (debugE!=null) {
            addSymbols.accept(debugE.readSymbols());
            debugE.closeFile();
          }
        }
      }
      
      if (map.isEmpty()) break manual;
      
      Vec<Symbolize.Resolved> r = new Vec<>();
      for (Symbolize.IPEntry c : as) {
        long addrFake = bin.relocate? c.m.toStatic(c.ip)+diff : c.ip;
        Symbolize.Sym sym = map.find(addrFake);
        if (sym==null) {
          r.add(Symbolize.Resolved.not());
        } else {
          long addrReal = unrelocatedRes? addrFake : c.ip;
          r.add(new Symbolize.Resolved(unrelocatedRes? sym.s : sym.s + (addrReal-addrFake), c.ip - (addrFake-sym.s), sym.loc.sym));
        }
      }
      l.runLater.add(() -> got.accept(r));
      e.closeFile();
      return;
    }
    if (e!=null) e.closeFile();
    got.accept(null);
  }
}
