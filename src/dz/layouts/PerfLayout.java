package dz.layouts;

import dz.Main;
import dz.debugger.Location;
import dz.gdb.*;
import dz.general.*;
import dz.general.java.JavaPrintAssembly;
import dz.tabs.PerfListTab;
import dzaima.ui.gui.io.*;
import dzaima.ui.node.types.tabs.SerializableTab;
import dzaima.utils.*;
import dzaima.utils.options.Options;

import java.nio.file.*;
import java.util.HashSet;
import java.util.function.Consumer;

public class PerfLayout extends GdbLayout {
  public final boolean hasCore;
  
  public PerfLayout(Main m, Options o, String binPath) {
    super(m);
    String corePath = o.optOne("--core");
    hasCore = corePath!=null;
    assert o.has("--cachegrind") || (binPath!=null) == hasCore;
    
    node = m.ctx.make(gc.getProp("grr.uiPerf").gr());
    
    String gdbBin = o.optOne("--gdb-bin");
    this.d = new Dbi(hasCore? GdbProcess.makeGdb(gdbBin, GdbProcess.TTYMode.NONE, binPath, corePath) : GdbProcess.makeGdb(gdbBin, GdbProcess.TTYMode.NONE));
    if (hasCore) {
      Executable e = d.makeExe(Paths.get(binPath));
      d.toExe(e, b -> { if (!b) Log.error("perf load", "Failed to open binary"); });
    }
    dbiInit(o);
    
    String layoutPath = o.optOne("--layout");
    String layoutStr = layoutPath!=null? Tools.readFile(Path.of(layoutPath)) : Tools.readRes("perfLayout.dzcfg");
    treePlace().replace(0, SerializableTab.deserializeTree(node.ctx, layoutStr, getCtors()));
    
    forTabs(PerfListTab.class, t -> { if (t.w.sel) t.onSelected(); });
    
    loadSampleData(o, binPath, true);
    
    layoutInit(o);
    o.used();
  }
  
  
  public Executable currFile;
  private final HashSet<String> loadedMaps = new HashSet<>();
  public void getDisas(Binary bin, Location l, Consumer<DisasFn> res) {
    if (bin.file==null || (l.addr==null && l.sym==null)) { res.accept(null); return; }
    
    if (bin.file.startsWith(JavaPrintAssembly.ID_PRE) && javaMach!=null) {
      JavaPrintAssembly.JSym sym = javaMach.findByID(bin.file);
      
      Path jitElf = javaMach.elfPath();
      if (jitElf==null) { res.accept(null); return; }
      
      currFile = d.makeExe(jitElf);
      d.toExe(currFile, b -> {
        if (b) {
          sym.insFrom(currFile, r -> {
            injectCustomSource(new DisasFn(sym.addrS, sym.addrE, sym.name, r, false, null), r2 -> res.accept(r2));
          });
        }
      });
      return;
    }
    
    if (bin.virtSym) { res.accept(null); return; }
    
    String path = remap(bin.file);
    Path ff = Paths.get(path);
    if (path.endsWith(".map") && loadedMaps.add(path)) loadJITMap(-1, ff);
    if (!hasCore && (currFile==null || !currFile.p.equals(ff))) {
      currFile = d.makeExe(ff);
      d.toExe(currFile, b -> {
        if (!b) {
          Log.error("disas", "failed to open \""+currFile.p+"\" for disassembly");
          res.accept(null);
        } else {
          getDisasDirect(l, FnCache.NameMode.PREFIX, sourceInjector(res));
        }
      });
    } else {
      getDisasDirect(l, FnCache.NameMode.PREFIX, sourceInjector(res));
    }
  }
  
  public boolean key(Key key, int scancode, KeyAction a) {
    return false;
  }
}
