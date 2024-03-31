package dz.tabs;

import dz.debugger.Location;
import dz.general.DisasFn;
import dz.general.DisasFn.SourceMap;
import dz.general.arch.Arch;
import dz.layouts.*;
import dz.stat.*;
import dz.ui.*;
import dz.utils.Promise;
import dzaima.ui.gui.*;
import dzaima.ui.gui.io.*;
import dzaima.ui.node.Node;
import dzaima.ui.node.ctx.Ctx;
import dzaima.ui.node.prop.Props;
import dzaima.ui.node.types.*;
import dzaima.ui.node.types.tabs.SerializableTab;
import dzaima.utils.*;

import java.util.*;
import java.util.function.Consumer;

public class AsmTab extends GrrTab<GdbLayout> implements SerializableTab {
  public final Node node;
  public final AsmTabListNode asmList;
  public final ScrollNode scroll;
  public final AsmOverlayNode overlay;
  
  public AsmTab(GdbLayout g) {
    super(g);
    node = ctx.make(ctx.gc.getProp("grr.tabs.asm.ui").gr());
    asmList = new AsmTabListNode(g);
    scroll = (ScrollNode) node.ctx.id("placeScroll");
    scroll.add(asmList);
    overlay = (AsmOverlayNode) node.ctx.id("overlay");
    overlay.t = this;
    // ((ScrollNode) node).ignoreFocus(true);
  }
  
  public Node show() { return node; }
  public void onSelected() { focusSelectedEntry(asmList); }
  
  private String functionName;
  public String name() {
    return functionName==null? "assembly" : "assembly - "+functionName;
  }
  
  public String serializeName() { return "asm"; }
  
  public void setFnName(String s) {
    if (Objects.equals(s, functionName)) return;
    functionName = s;
    nameUpdated();
  }
  
  public void onSelectedFunction(Location l, boolean justFunction, boolean afterCall) {
    onSelectedFrame(l==null? null : l.decrementedIf(afterCall), justFunction, g.getPerfSymbol(l), false);
  }
  public void onSelectedStatSymbol(Location l, StatSymbol stat) {
    onSelectedFrame(l, true, stat, true);
  }
  
  public void onSelectedFrame(Location l, boolean justFunction, StatSymbol stat0, boolean immediateSource) {
    Long focusAddr = justFunction || l==null? null : l.addr;
    if (stat0!=null) {
      DisasFn ins = stat0.disas();
      if (ins!=null) {
        onSelectedDisasFn(ins, focusAddr, stat0, immediateSource);
        return;
      }
    }
    
    if (l!=null) g.getDisas(stat0==null? null : stat0.sym.bin, l, d -> onSelectedDisasFn(d, focusAddr, stat0, immediateSource));
  }
  
  public void onSelectedDisasFn(DisasFn fn, Long focusAddr, StatSymbol stat, boolean immediateSource) {
    if (fn==null || fn.ins==null || fn.ins.length==0) {
      fn = stat==null? null : stat.forceDisas();
      if (fn==null) {
        asmList.setFn(null, null);
        overlay.setFn(null, null);
        g.selectSourceMapStack(null, stat==null? null : stat.sym.bin.file);
        return;
      }
    }
    
    if (stat==null && fn.name!=null) stat = g.getPerfSymbol(new Location(null, fn.name, null, null, null)); // for rr+perf+perf JIT map
    
    int idx = focusAddr==null? -1 : fn.indexOf(focusAddr);
    setFn(fn, stat);
    asmList.focusEntry(idx==-1? 0 : idx, ScrollNode.Mode.INSTANT);
    if (immediateSource && asmList.ch.sz>0) {
      asmList.sourceToThis(asmList.getEnt(0));
    }
    if (idx!=-1) {
      AsmListNode.AsmEntry e = (AsmListNode.AsmEntry) asmList.ch.get(idx);
      e.select(SelectableEntry.CT.QUIET);
      asmList.activeEntry = e;
      if (stat!=null) stat.onOneSelected(e.getStat());
    }
  }
  
  private void setFn(DisasFn fn, StatSymbol stat) {
    if (stat!=null) stat.onAllSelected();
    asmList.setFn(fn, stat);
    overlay.setFn(fn, stat);
    if (fn==null) setFnName(null);
    else setFnName("??".equals(fn.name)? "0x"+DebuggerLayout.hexLong(fn.s) : fn.name);
  }
  
  public void onRegHover(Arch.RegInfo reg, boolean enable) {
    asmList.hoverReg(enable? reg.name : null);
  }
  
  public void onNewArch() {
    asmList.setArch(g.arch);
  }
  
  public void onStatRefresh() {
    asmList.mRedraw();
    overlay.statRefresh();
  }
  
  public void onAsmConfig(AsmListNode.AsmConfig config) {
    asmList.setAsmConfig(config);
  }
  
  public static class AsmOverlayNode extends WrapNode {
    AsmTab t;
    public AsmOverlayNode(Ctx ctx, Props props) {
      super(ctx, props);
    }
    
    public void over(Graphics g) {
      if (cols==null) return;
      int x1 = dx + w;
      int x0 = x1 - gc.getProp("scroll.barSize").len();
      int slow = gc.getProp("grr.asm.slowBarCol").col();
      if (!Tools.vs(slow)) return;
      float h1 = h*1f/cols.length;
      float y0=0, y1=0;
      int pal = 0;
      for (int i = 0; i < cols.length+1; i++) {
        int al = i==cols.length? -1 : (int) ((slow>>>24)*cols[i]) << 24;
        if (pal!=al) {
          if (pal!=0) g.rect(x0, y0, x1, y1, (slow&0xffffff) | pal);
          y0 = y1;
          pal = al;
        }
        y1 += h1;
      }
    }
    
    private float[] cols;
    private DisasFn lastFn;
    private StatSymbol lastStat;
    public void setFn(DisasFn fn, StatSymbol stat) {
      this.lastFn = fn;
      this.lastStat = stat;
      if (fn==null || stat==null) {
        cols = null;
        flags&= ~RD_AL;
        return;
      }
      flags|= RD_AL;
      int l = fn.ins.length;
      int g = Math.min(400, l);
      cols = new float[g];
      for (int i = 0; i < g; i++) cols[i] = 0f;
      for (int i = 0; i < l; i++) {
        DisasFn.ParsedIns ins = fn.ins[i];
        StatInstr n = stat.get(ins.s - fn.s);
        if (n!=null) cols[i*g/l]+= n.saturation();
      }
      float max=0, sum=0;
      for (int i = 0; i < g; i++) {
        max = Math.max(max, cols[i]);
        sum+= cols[i];
      }
      float frac = 1 / max; // make the brightest point max bright
      frac = Math.min(frac, 0.05f/(sum/g)); // but keep total average below 0.05
      for (int i = 0; i < g; i++) cols[i]*= frac;
    }
    
    public void statRefresh() {
      setFn(lastFn, lastStat);
    }
  }
  
  public static class AsmTabListNode extends AsmListNode {
    private final GdbLayout g;
    public AsmTabListNode(GdbLayout g) {
      super(g.node.ctx, g.arch);
      this.g = g;
    }
    
    public void setFn(DisasFn fn, StatSymbol stat) {
      locCache.clear();
      super.setFn(fn, stat);
    }
    
    protected boolean entryKeyF(AsmEntry e, Key key, int scancode, KeyAction a) {
      switch (gc.keymap(key, a, "grr.entry")) {
        case "copy":
          ctx.win().copyString(e.shownText());
          return true;
        case "click":
          if (e.ins.target!=null) for (Node ch : AsmTabListNode.this.ch) if (((AsmEntry) ch).ins == e.ins.target) ((AsmEntry) ch).select(SelectableEntry.CT.CLICK);
          return true;
      }
      switch (gc.keymap(key, a, "grr.tabs.asm")) {
        case "toggleAddressMode":
          switch (cfg.fmt) { default: throw new IllegalStateException();
            case ADDR:
              g.setAsmConfig(asmConfig().with(AddrFmt.DEC_OFF)); break;
            case DEC_OFF: case HEX_OFF:
              g.setAsmConfig(asmConfig().with(AddrFmt.ADDR)); break;
          }
          return true;
        case "toggleAddressDisplay":
          switch (cfg.disp) { default: throw new IllegalStateException();
            case NONE: g.setAsmConfig(asmConfig().with(AddrDisp.KNOWN)); break;
            case KNOWN: g.setAsmConfig(asmConfig().with(AddrDisp.ALL)); break;
            case ALL: g.setAsmConfig(asmConfig().with(AddrDisp.NONE)); break;
          }
          return true;
      }
      return false;
    }
    
    HashMap<DisasFn.ParsedIns, Promise<Void>> locCache = new HashMap<>();
    
    private void getSourceInfo(AsmEntry e0, Consumer<SourceMap> l) {
      if (e0.ins.map!=null) {
        l.accept(e0.ins.map);
        return;
      }
      
      locCache.computeIfAbsent(e0.ins, i -> Promise.create(r -> {
        if (g.d.curr==null) r.set(null);
        else g.d.curr.sourceInfo(i.s, i.e(), v -> {
          locCache.remove(i);
          if (v==null) e0.ins.map = SourceMap.NONE;
          else e0.ins.map = new SourceMap(null, v.shortFile, v.fullFile, fn==null? null : fn.name, v.line==null? -1 : v.line, -1);
          r.set(null);
        });
      })).then(z -> l.accept(e0.ins.map));
    }
    
    protected void entryHoverS(AsmEntry e) {
      if (!g.d.status().running()) getSourceInfo(e, p -> {
        g.hoverHighlightSource(SourceMap.unroll(p).map(c -> SourceMap.loc(e.ins.s, c)));
      });
    }
    
    protected void entryHoverE(AsmEntry e) {
      g.hoverHighlightSource(new Vec<>());
    }
    
    public void sourceToThis(AsmEntry e) {
      getSourceInfo(e, o -> g.selectSourceMapStack(o, stat==null? null : stat.sym.bin.file));
    }
    protected void entryClicked(AsmEntry e) {
      sourceToThis(e);
      if (stat!=null) stat.onOneSelected(e.getStat());
    }
    
    protected void entryRangeSelected(int s, int e) {
      Vec<StatInstr> instrs = new Vec<>();
      for (int i = s; i < e; i++) {
        StatInstr c = ((AsmEntry) ch.get(i)).getStat();
        if (c!=null) instrs.add(c);
      }
      if (stat!=null) stat.onSelection(instrs);
    }
    
    protected void entryMenu(AsmEntry e, PartialMenu m) {
      DisasFn.ParsedIns ins = e.ins;
      
      m.add(gc.getProp("grr.tabs.asm.menu.base").gr(), (s) -> {
        switch (s) {
          case "copyAddr": ctx.win().copyString(Tools.hexLong(ins.s)); return true;
        }
        return false;
      });
      if (g instanceof DebuggerLayout) {
        DebuggerLayout gd = (DebuggerLayout) g;
        m.add(gc.getProp("grr.tabs.asm.menu.run").gr(), (s) -> {
          switch (s) {
            case "break": gd.addBreakpoint(fn.jit, false, true, ins, null); return true;
            case "contF": if (gd.readyForExec()) { gd.addBreakpoint(fn.jit, true, true, ins, null); gd.d.curr.cont(false, null); } return true;
            case "contB": if (gd.readyForExec()) { gd.addBreakpoint(fn.jit, true, true, ins, null); gd.d.curr.cont(true,  null); } return true;
          }
          return false;
        });
        
        if (g.d.isRR()) m.add(gc.getProp("grr.tabs.asm.menu.rr").gr(), (s) -> false); // contB above
      }
    }
  }
}
