package dz.ui;

import dz.general.DisasFn;
import dz.general.arch.Arch;
import dz.layouts.DebuggerLayout;
import dz.stat.*;
import dzaima.ui.gui.*;
import dzaima.ui.gui.io.*;
import dzaima.ui.gui.select.Selection;
import dzaima.ui.node.Node;
import dzaima.ui.node.ctx.Ctx;
import dzaima.ui.node.prop.*;
import dzaima.ui.node.types.*;
import dzaima.ui.node.types.editable.code.langs.Lang;
import dzaima.ui.node.utils.ListUtils;
import dzaima.utils.*;

import java.util.Arrays;

public abstract class AsmListNode extends Node implements SelectableListManager.ListSelectableNode {
  public static final AsmConfig DEF_CONFIG = new AsmConfig(AddrDisp.KNOWN, AddrFmt.DEC_OFF, false, 8);
  private final SelectableListManager sl;
  private Arch arch;
  private Lang lang;
  private int rawColID;
  private Font f;
  private Lang.LangInst l;
  
  public AsmListNode.AsmEntry activeEntry;
  
  public AsmListNode(Ctx ctx, Arch arch) {
    super(ctx, Props.none());
    this.arch = arch;
    lang = gc.langs().fromName("assembly");
    sl = new SelectableListManager(this, false);
  }
  
  protected abstract void entryHoverS(AsmEntry e);
  protected abstract void entryHoverE(AsmEntry e);
  protected abstract void entryClicked(AsmEntry e);
  protected abstract void entryRangeSelected(int s, int e);
  protected abstract void entryMenu(AsmEntry e, PartialMenu m);
  
  float cW;
  int arrowCol, arrowDispCol;
  public void propsUpd() {
    super.propsUpd();
    f = Typeface.of(gc.getProp("grr.codeFamily").str()).sizeMode(gc.em, 0);
    l = lang.forFont(f);
    byte[] tmp = new byte[3];
    l.l.init.after(3, "# x".toCharArray(), tmp);
    rawColID = tmp[0];
    
    arrowCol = gc.getProp("grr.asm.arrowCol").col();
    arrowDispCol = gc.getProp("grr.asm.arrowDispCol").col();
    cW = f.widthf("X");
    updateProps();
  }
  public void setArch(Arch a) {
    arch = a;
    lang = a.getLang(gc);
    mProp();
  }
  
  
  public void mouseStart(int x, int y, Click c) {
    sl.mouseStart(x, y, c);
    super.mouseStart(x, y, c);
  }
  public void selectS(Selection s) { sl.selectS(s); }
  public void selectE(Selection s) { sl.selectE(s); }
  public void focusE() { sl.focusE(); }
  public String selType() { return "v"; }
  public SelectableListManager getSelMgr() { return sl; }
  public void onNewSelection(boolean has) {
    mRedraw();
    if (has) {
      SelectableEntry s = SelectableEntry.getSelected(this);
      if (s!=null) s.sel(false);
      XY r = sl.getRange();
      entryRangeSelected(r.x, r.y);
    }
  }
  
  
  
  public boolean keyF(Key key, int scancode, KeyAction a) {
    switch (gc.keymap(key, a, "grr.list")) {
      case "copy": {
        XY range = sl.getRange();
        StringBuilder r = new StringBuilder();
        for (int i = range.x; i < range.y; i++) r.append(getEnt(i).shownText()).append('\n');
        if (r.length()!=0) r.deleteCharAt(r.length()-1);
        if (r.length()!=0) ctx.win().copyString(r.toString());
        return true;
      }
      case "selectAll":
        if (ch.sz>0) sl.selectManualAll();
        return true;
    }
    return false;
  }
  
  public enum AddrDisp { NONE, KNOWN, ALL }
  public enum AddrFmt { ADDR, DEC_OFF, HEX_OFF }
  public AsmConfig cfg = AsmListNode.DEF_CONFIG;
  public AsmConfig asmConfig() { return stat!=null && stat.forceCfg()!=null? stat.forceCfg() : cfg; }
  
  public static final class AsmConfig {
    public final AddrDisp disp;
    public final AddrFmt fmt;
    public final boolean raw;
    public final int rawPad;
    public AsmConfig(AddrDisp disp, AddrFmt fmt, boolean raw, int rawPad) { this.disp=disp; this.fmt=fmt; this.raw=raw; this.rawPad=rawPad; }
    public AsmConfig with(AddrDisp disp) { return new AsmConfig(disp, fmt, raw, rawPad); }
    public AsmConfig with(AddrFmt fmt)   { return new AsmConfig(disp, fmt, raw, rawPad); }
    public AsmConfig withRaw(boolean raw){ return new AsmConfig(disp, fmt, raw, rawPad); }
    public AsmConfig withPad(int rawPad) { return new AsmConfig(disp, fmt, raw, rawPad); }
  }
  public void setAsmConfig(AsmConfig ncfg) {
    boolean newText = (ncfg.raw?ncfg.rawPad:-1) != (cfg.raw?cfg.rawPad:-1);
    cfg = ncfg;
    updateProps();
    if (newText) setFn(fn, stat);
  }
  
  int leftW;
  int addrLen;
  protected DisasFn fn;
  protected StatSymbol stat;
  protected DisasFn.ParsedIns selected;
  long arrowS, arrowE;
  public void updateProps() { setFn(fn, stat, false); mRedraw(); }
  public void setFn(DisasFn fn, StatSymbol stat) { setFn(fn, stat, true); }
  private void setFn(DisasFn fn, StatSymbol stat, boolean remake) {
    if (remake) clearCh();
    this.stat = stat;
    if (fn==null) return; // TODO shouldn't this.fn be written to before this?
    if (remake) {
      this.fn = fn;
      selected = null;
      arrowS = arrowE = -1;
      fn.readJumps(arch);
    }
    
    
    addrLen = Math.max(DebuggerLayout.hexLength(fn.e), 4);
    
    leftW = Tools.ceil(cW*3); // arrows
    
    if (asmConfig().disp!=AddrDisp.NONE) switch (asmConfig().fmt) { default: throw new RuntimeException();
      case ADDR:      leftW+= Tools.ceil(f.widthf(Tools.repeat('0', addrLen))); break;
      case DEC_OFF:   leftW+= Tools.ceil(f.widthf(Tools.repeat('0', Long.toString   (fn.e-fn.s).length())) + cW); break;
      case HEX_OFF:   leftW+= Tools.ceil(f.widthf(Tools.repeat('0', Long.toHexString(fn.e-fn.s).length())) + cW); break;
    }
    
    if (stat!=null) leftW+= stat.fmtWidth(cW);
    
    if (remake) for (DisasFn.ParsedIns c : fn.ins) add(new AsmEntry(c));
    else for (Node c : ch) if (c.ch.sz>0) c.mResize();
    
    ScrollNode s = ScrollNode.nearestScrollNode(this);
    if (s!=null) s.toXS(true);
  }
  
  public String hoveredReg;
  public void hoverReg(String s) {
    hoveredReg = s!=null? arch.baseReg(s) : null;
    for (Node c : ch) c.mRedraw();
  }
  
  public void drawCh(Graphics g, boolean full) {
    int nsy = Tools.constrain((g.clip==null? 0 : g.clip.sy)/f.hi,   0, ch.sz);
    int ney = Tools.constrain((g.clip==null? h : g.clip.ey)/f.hi+1, 0, ch.sz);
    XY range = sl.getRange();
    for (int i = nsy; i < ney; i++) {
      AsmEntry c = getEnt(i);
      c.inSelection = i>=range.x && i<range.y;
      c.draw(g, full);
    }
  }
  
  public AsmEntry getEnt(int idx) {
    return (AsmEntry) ch.get(idx);
  }
  public void focusEntry(int idx, ScrollNode.Mode mode) {
    // g.scrollTo(ch.get(idx), ScrollNode.Mode.NONE, mode, 0, 0);
    ScrollNode.scrollTo(this, ScrollNode.Mode.INSTANT, mode, 0, (int) (f.hi*(idx+.5f)));
  }
  
  public int minW() { return ListUtils.vMinW(ch); }
  public int minH(int w) { return ch.sz * f.hi; }
  
  protected void resized() {
    // problem:
    //   1. created lazy, minW returns tiny number
    //   2. scroll sees tiny minW, decides hVis=false
    //   3. scroll to bottom added  
    //   4. isz() → scrollMax() uses that
    //   5. on next tick, elements are loaded & minW increases 
    //   6. now hVis=true
    //   7. scroll now scrolls down a bit to fit in horizontal scroll bar 
    int y = 0;
    for (Node c : ch) {
      c.resize(w, f.hi, 0, y);
      y+= f.hi;
    }
  }
  
  public static final String[] arrows = {"?", "↑", "↓", "→", "←"};
  public class AsmEntry extends SelectableEntry {
    public final DisasFn.ParsedIns ins;
    private final String desc;
    
    public AsmEntry(DisasFn.ParsedIns c) {
      super(AsmListNode.this.ctx, Props.none());
      this.ins = c;
      desc = arch.prettyIns(c.desc, c.s).replace('\t', ' ');
    }
    
    public int minW() { return ch.sz==0? 0 : ch.get(0).maxW()+leftW; }
    public int minH(int w) { return f.hi; }
    public int maxH(int w) { return f.hi; }
    
    protected void resized() {
      if (ch.sz==1) ch.get(0).resize(w-leftW, h, leftW, 0);
    }
    
    public byte[] cols() {
      String pre = prefix();
      byte[] tmp = new byte[desc.length()];
      lang.init.after(desc.length(), desc.toCharArray(), tmp);
      byte[] res = new byte[pre.length()+tmp.length];
      System.arraycopy(tmp, 0, res, pre.length(), tmp.length);
      Arrays.fill(res, 0, pre.length(), (byte) rawColID);
      return res;
    }
    private boolean wantText;
    public void tickC() {
      if (ch.sz==0 && wantText) add(asmText(AsmListNode.this, shownText(), f, l, cols()));
    }
    
    public String prefix() {
      if (!cfg.raw) return "";
      int p = cfg.rawPad;
      char[] t = Tools.hexBytes(ins.opcode).toCharArray();
      int n = ins.opcode.length;
      char[] r = new char[Math.max(n,p)*3];
      for (int i = 0; i < n; i++) {
        r[i*3  ] = t[i*2];
        r[i*3+1] = t[i*2+1];
        r[i*3+2] = ' ';
      }
      Arrays.fill(r, n*3, r.length, ' ');
      return new String(r);
    }
    public String shownText() {
      return prefix()+desc;
    }
    
    private float cmul(int c0, float m0, int c1, float m1, int sh) {
      return ((c0>>sh) & 0xff)*m0 + ((c1>>sh) & 0xff)*m1;
    }
    public boolean inSelection;
    public int bgColCalc() {
      int base;
      if (inSelection) base = gc.getProp("grr.list.bgSelFocus").col();
      else if (activeEntry==this && !isFocused()) base = gc.getProp("grr.list.bgActive").col();
      else base = super.bgColCalc();
      
      StatInstr frac = getStat();
      if (frac==null) return base;
      int slow = gc.getProp("grr.asm.slowCol").col();
      float ba = (base>>>24)*(1/255f);
      float sa = (slow>>>24)*(1/255f) * frac.saturation();
      if ((base>>>24) != 0) sa*= 0.5f;
      float na = sa+ba - sa*ba;
      float bf = ba * (1/na);
      float sf = sa * (1/na);
      return Tools.argb255(
        na*255,
        cmul(base, bf, slow, sf, 16),
        cmul(base, bf, slow, sf, 8),
        cmul(base, bf, slow, sf, 0)
      );
    }
    public StatInstr getStat() {
      if (stat==null) return null;
      return stat.get(ins.s - fn.s);
    }
    public void drawC(Graphics g) {
      int y = f.ascI;
      
      // perf percent
      StatInstr p = getStat();
      if (p!=null) g.text(p.shortText(), f, cW*.2f, y, gc.getProp("grr.asm.countCol").col());
      
      // address
      String addr;
      switch (asmConfig().disp) { default: throw new RuntimeException();
        case NONE: addr = null; break;
        case KNOWN:
          if (ins.from==null && !ins.likelyNewBB) {
            addr = null;
            break;
          }
          // fallthrough
        case ALL:
          switch (asmConfig().fmt) { default: throw new RuntimeException();
            case ADDR: addr = DebuggerLayout.hexLong(ins.s, addrLen); break;
            case DEC_OFF:  addr = "+" + (ins.s-fn.s); break;
            case HEX_OFF:  addr = Long.toHexString(ins.s-fn.s); break;
          }
          break;
      }
      if (addr!=null) g.text(addr, f, leftW - f.widthf(addr) - cW*3, y, gc.getProp("grr.asm.addrCol").col());
      
      // pretty jump arrows
      boolean drawBgArrow = ins.jumpMode!=0;
      if (selected!=null && ins.s>=arrowS && ins.s<=arrowE) {
        String t = ins.s==arrowS? "┌─→" : ins.s==arrowE? "└─→" : "│";
        if (selected==ins) t = t.charAt(0)+"──";
        if (t.length()==3) drawBgArrow = false;
        g.text(t, f, leftW -cW*3, y, arrowDispCol);
      }
      if (drawBgArrow) g.text(arrows[ins.jumpMode], f, leftW -cW*2, y, arrowCol);
      
      // actual disassembled instruction
      if (ch.sz==0) {
        drawColoredText(g, leftW, y, shownText(), f, l, cols()); // TODO don't do this when children can be inserted live
        mTick();
        wantText = true;
      }
    }
    
    public void hoverS() { entryHoverS(this); }
    public void hoverE() { entryHoverE(this); }
    
    public void onClick(CT type) {
      if (type!=CT.QUIET) entryClicked(this);
      
      selected = ins;
      if (ins.target!=null) {
        arrowS = Math.min(ins.s, ins.target.s);
        arrowE = Math.max(ins.s, ins.target.s);
      } else arrowS = arrowE = 0;
      p.mRedraw();
    }
    
    public void mouseStart(int x, int y, Click c) { if (c.bR()) c.register(this, x, y); else super.mouseStart(x, y, c); }
    
    public void mouseDown(int x, int y, Click cl) {
      if (cl.bR()) {
        onlyHighlight();
        PartialMenu m = new PartialMenu(gc);
        entryMenu(this, m);
        
        Node c = this;
        while (true) {
          Node n = c.findCh(x, y);
          if (n==null) break;
          x-= n.dx;
          y-= n.dy;
          c = n;
          if (c instanceof AsmTextNode) {
            byte idx = ((AsmTextNode) c).idx;
            String cF = ((StringNode) c.ch.get(0)).s;
            if (idx==5) m.add(gc.getProp("grr.tabs.asm.menu.copyNum").gr(), s -> {
              switch (s) {
                case "copyHex": { Long v = parseNum(cF); ctx.win().copyString(v==null? cF : "0x"+Long.toHexString(v)); return true; }
                case "copyDec": { Long v = parseNum(cF); ctx.win().copyString(v==null? cF : Long.toString(v)); return true; }
              }
              return false;
            });
            break;
          }
        }
        
        m.open(ctx, cl);
        return;
      }
      super.mouseDown(x, y, cl);
    }
    
    public Long parseNum(String s) {
      try {
        if (s.startsWith("0x")) return Long.parseUnsignedLong(s.substring(2), 16);
        else return Long.parseUnsignedLong(s);
      } catch (NumberFormatException e) { return null; }
    }
    
    public boolean keyF(Key key, int scancode, KeyAction a) {
      if (entryKeyF(this, key, scancode, a)) return true;
      return super.keyF(key, scancode, a);
    }
  }
  
  protected abstract boolean entryKeyF(AsmEntry e, Key key, int scancode, KeyAction a);
  
  
  public static void drawColoredText(Graphics g, float x, int y, String s, Font f, Lang.LangInst l, byte[] cols) {
    if (cols.length==0) return;
    byte pc = cols[0];
    int pi=0, i=0;
    while (true) {
      if (i==cols.length || (cols[i]!=-1 && cols[i]!=pc)) {
        if (pc!=-1) {
          String ss = s.substring(pi, i);
          g.text(ss, f, x, y, l.style(pc).getColor());
          x+= f.widthf(ss);
        }
        if (i==cols.length) break;
        pc = cols[i];
        pi = i;
      }
      i++;
    }
  }
  
  public static class AsmTextNode extends TextNode {
    public final AsmListNode l;
    public byte idx;
    
    AsmTextNode(AsmListNode l, int col, byte idx, String s) {
      super(l.ctx, Props.of("color", new ColProp(col)));
      this.l = l;
      add(new StringNode(ctx, s));
      this.idx = idx;
    }
    
    public void bg(Graphics g, boolean full) {
      super.bg(g, full);
      if (idx==7 && l.hoveredReg!=null && l.arch.baseReg(get()).equals(l.hoveredReg)) {
        g.rect(sX, sY1, eX, sY2, gc.getProp("grr.asm.regHoverBg").col());
      }
    }
    public String get() {
      return ((StringNode) ch.get(0)).s;
    }
    
    public void hoverS() { if (idx==7) l.hoverReg(get()); }
    public void hoverE() { if (idx==7) l.hoverReg(null); }
  }
  public static Node asmText(AsmListNode al, String s, Font f, Lang.LangInst l, byte[] cols) {
    TextNode n = new STextNode(al.ctx, Props.of("family", new StrProp(f.tf.name)));
    if (cols.length==0) return n;
    byte pc = cols[0];
    int pi=0, i=0;
    while (true) {
      if (i==cols.length || (cols[i]!=-1 && cols[i]!=pc)) {
        if (pc!=-1) n.add(new AsmTextNode(al, l.style(pc).getColor(), pc, s.substring(pi, i)));
        if (i==cols.length) break;
        pc = cols[i];
        pi = i;
      }
      i++;
    }
    return n;
  }
}