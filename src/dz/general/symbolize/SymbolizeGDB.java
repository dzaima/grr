package dz.general.symbolize;

import dz.debugger.Location;
import dz.gdb.*;
import dz.general.Binary;
import dz.general.file.ElfParser;
import dz.general.symbolize.Symbolize.Resolved;
import dz.utils.*;
import dzaima.utils.*;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

public class SymbolizeGDB {
  public static void symbolize(Dbi db, Binary bin, Vec<Symbolize.IPEntry> as, Consumer<Vec<Resolved>> got) {
    if (bin.file==null) { Symbolize.noSymbols(as, got); return; }
    Path p = Path.of(bin.file);
    Executable exe = db.makeExe(p);
    db.toExe(exe, b -> {
      if (!b) {
        Log.info("symb", "couldn't open gdb to "+exe.p+" for symbolization");
        Symbolize.noSymbols(as, got);
        return;
      }
      
      long toVirt = 0;
      try {
        ElfParser elf = ElfParser.loadFile(p);
        if (elf!=null) {
          Vec<ElfParser.Program> vs = elf.programs.filter(ElfParser.Program::executable);
          if (vs.sz==1) toVirt = vs.peek().vaddr-vs.peek().off;
          elf.closeFile();
        }
      } catch (Throwable ex) {
        Log.error("symb", "failed to get virtual offset; assuming 0");
        Log.stacktrace("symb", ex);
      }
      run(exe, toVirt, as, got);
    });
  }
  
  private static void run(Executable exe, long toVirt, Vec<Symbolize.IPEntry> as, Consumer<Vec<Resolved>> got) {
    long sns = Symbolize.DEBUG_SYMBOLIZE? System.nanoTime() : 0;
    Box<Integer> oG = new Box<>(0);
    Vec<Resolved> res = new Vec<>();
    AddrMapper<Symbolize.Sym> syms = new AddrMapper<>();
    final int BATCH = 50;
    long[] bufA = new long[BATCH];
    Symbolize.IPEntry[] bufE = new Symbolize.IPEntry[BATCH];
    
    RepeatRunnable.run(next -> {
      int o = oG.get();
      if (o == as.sz) {
        got.accept(res);
        if (Symbolize.DEBUG_SYMBOLIZE) Log.info("symb", "Finished symbolizing in "+(System.nanoTime()-sns)/1e6+"ms");
        return;
      }
      
      int bi = 0;
      while (o < as.sz && bi<BATCH) {
        Symbolize.IPEntry c = as.get(o++);
        Symbolize.Sym p = syms.find(c.ip);
        if (p!=null) {
          res.add(p.loc==null? Resolved.not() : new Resolved(p.loc.addr, p.s, p.loc.sym));
        } else {
          bufA[bi] = c.m.toStatic(c.ip) + toVirt;
          bufE[bi] = c;
          bi++;
        }
      }
      if (Symbolize.DEBUG_SYMBOLIZE) Log.info("symb", "Required "+bi+" queries from "+(o-oG.get())+" samples starting at "+oG.get());
      oG.set(o);
      
      exe.addrsToSymbol(Arrays.copyOf(bufA, bi), r -> {
        for (int i = 0; i < r.length; i++) {
          Location l = r[i];
          
          Symbolize.IPEntry old = bufE[i];
          long s0 = old.ip;
          long e = s0+1;
          long s = old.m.toReal(l.addr-toVirt);
          assert s<=s0;
          if (l.sym==null) {
            if (Symbolize.DEBUG_SYMBOLIZE) Log.info("symb", "Unknown @ "+s0);
            if (!syms.has(s0)) syms.addRange(new Symbolize.Sym(s0, e, null));
          } else {
            Symbolize.Sym prev = syms.find(s);
            Symbolize.Sym prevOrig = prev;
            keep_prev: if (prev!=null) {
              if (prev.s!=s || !Objects.equals(prev.loc.sym, l.sym)) Log.warn("perf symbolize", "Mismatched symbol data");
              else if (prev.e >= e) break keep_prev;
              syms.remove(prev);
              prev = null;
            }
            if (Symbolize.DEBUG_SYMBOLIZE) Log.info("symb", "Adding "+l.sym+" to ["+s+"; "+e+")" + (prevOrig==null? " - new" : prev==null? " - replacing" : " - prev was better"));
            if (prev==null) syms.addRange(new Symbolize.Sym(s, e, l));
          }
          res.add(new Resolved(l.addr, s, l.sym));
        }
        next.run();
      });
    });
  }
}
