package dz.tabs;

import dz.gdb.*;
import dz.layouts.DebuggerLayout;
import dz.ui.LiveTextFieldNode;
import dz.utils.*;
import dzaima.ui.gui.*;
import dzaima.ui.node.Node;
import dzaima.ui.node.prop.Props;
import dzaima.ui.node.types.tabs.SerializableTab;
import dzaima.utils.*;

import java.util.function.Function;

public class MemoryTab extends GrrTab<DebuggerLayout> implements SerializableTab {
  public final boolean stack;
  public final Node node;
  public final MemViewer viewer;
  
  public MemoryTab(DebuggerLayout g, boolean stack) {
    super(g);
    this.stack = stack;
    node = ctx.make(ctx.gc.getProp("grr.tabs.memory.ui").gr());
    viewer = new MemViewer();
    node.ctx.id("place").add(viewer);
    LiveTextFieldNode f = (LiveTextFieldNode) node.ctx.id("name");
    f.setFn((a,mod) -> {
      if (!a.done) return false;
      String s = f.getAll();
      g.d.evalExpr("(void*)("+s+")", false, r -> {
        Long l = GdbFormat.numFromPrefix(r);
        if (l!=null) addr = Tools.ulongMin(l, Long.MAX_VALUE-1000);
        f.removeAll();
        viewer.mRedraw();
        f.append(Tools.hexLong(addr));
      });
      return true;
    });
  }
  
  public long addr = 0;
  public Node show() { return node; }
  
  public String name() { return stack? "stack memory" : "memory"; }
  public String serializeName() { return "memory"; }
  
  public class MemViewer extends Node {
    Font f;
    public MemViewer() {
      super(MemoryTab.this.ctx, Props.none());
    }
    public DebuggerLayout g() { return g; }
    
    public void propsUpd() {
      super.propsUpd();
      f = Typeface.of(gc.getProp("grr.codeFamily").str()).sizeMode(gc.em, 0);
    }
    
    int width = 16;
    public boolean scroll(int x, int y, float dx, float dy) {
      addr+= (dy<0? width : -width)*3L;
      addr = Tools.ulongMax(addr, 0);
      mRedraw();
      return true;
    }
    
    OverlapMapper<MemEnt> outdated = new OverlapMapper<>();
    OverlapMapper<MemEnt> map = new OverlapMapper<>();
    private final DelayedRun dr = new DelayedRun(g);
    private boolean colorOutdated;
    public void drawC(Graphics g) {
      addr = addr/width * width;
      int len = (h/f.hi + 1)*width;
      if (len==0) return;
      int lzHex = DebuggerLayout.hexLength(addr+len);
      lzHex = Math.max(lzHex, 6);
      
      g.push();
      g.clip(0, 0, w, h);
      
      Vec<OverlapMapper.Portion<MemEnt>> found = load(map, addr, addr+len, g().d.status().paused());
      boolean any = found.linearFind(c -> c.base.loaded())!=null;
      OverlapMapper<MemEnt> cm;
      if (any) {
        cm = map;
        colorOutdated = false;
      } else {
        cm = outdated;
        load(cm, addr, addr+len, false);
      }
      int col = !any && colorOutdated? 0x8fD2D2D2 : 0xffD2D2D2;
      
      MemEnt e = cm.findBase(addr);
      int y = f.ascI;
      for (int i = 0; i < len; i+= width) {
        // -1:idc 0:white 2:def 3:half 4:undef
        ColText hex = new ColText();
        ColText txt = new ColText();
        for (int j = 0; j < width; j++) {
          if (j!=0 && j%2==0) { hex.add(" ", -1); }
          long caddr = addr+i+j;
          if (!e.contains(caddr)) e = cm.findBase(caddr);
          
          if (!e.loaded()) { hex.add("??", 0); txt.add(" ", -1); }
          else if (!e.accessible(caddr)) { hex.add("--", 4);  txt.add(" ", -1); }
          else {
            int b = e.get(caddr);
            int d = e.def(caddr);
            boolean h = e.hasDef(caddr);
            for (int s = 0; s < 8; s+= 4) {
              hex.add(Integer.toUnsignedString((b>>s)&0xf, 16), unkCol(h, (d>>s)&0xf, 0xf));
            }
            txt.add(Character.toString(b>=' ' & b<='~'? (char)b : '.'), unkCol(h, d, 0xff));
          }
        }
        ColText all = new ColText();
        String addrS = DebuggerLayout.hexLong(i+addr);
        int[] cols = new int[]{col, 0,
          gc.getProp("grr.colors.valDefined").col(),
          gc.getProp("grr.colors.valHalfDefined").col(),
          gc.getProp("grr.colors.valUndefined").col(),
        };
        addrS = addrS.substring(16-lzHex);
        all.add(addrS+" ", 0);
        all.add(hex);
        all.add("  ", 0);
        all.add(txt);
        float x = 0;
        for (Pair<String, Integer> p : all.color(n -> cols[n])) {
          g.text(p.a, f, x, y, p.b);
          x+= f.widthf(p.a);
        }
        y+= f.hi;
      }
      g.pop();
    }
    
    static final int PAD_LOAD = 512;
    public Vec<OverlapMapper.Portion<MemEnt>> load(OverlapMapper<MemEnt> map, long s, long e, boolean request) {
      OverlapMapper<SimpleEnt> left = new OverlapMapper<>();
      left.addFullRange(new SimpleEnt(s, e));
      Vec<OverlapMapper.Portion<MemEnt>> found = map.findRanges(s, e);
      for (OverlapMapper.Portion<MemEnt> c : found) left.removeRange(c.s, c.e);
      Vec<OverlapMapper.Portion<SimpleEnt>> all = left.all();
      if (all.sz>0) {
        long rs = all.get(0).s;
        long re = all.peek().e;
        if (rs==s) rs = rs<=               PAD_LOAD? 0              : rs-PAD_LOAD;
        if (re==e) re = re>=Long.MAX_VALUE-PAD_LOAD? Long.MAX_VALUE : re+PAD_LOAD;
        MemEnt ne = new MemEnt(rs, re);
        if (request) ne.request(viewer);
        map.addFullRange(ne);
      }
      if (request) for (OverlapMapper.Portion<MemEnt> c : found) if (!c.base.requested) c.base.request(viewer);
      return found;
    }
    private void statusChanged() {
      if (map.isEmpty() || map.all().linearFind(c->c.base.loaded())==null) return;
      outdated = map;
      map = new OverlapMapper<>();
      dr.set(() -> { colorOutdated=true; mRedraw(); });
    }
  }
  
  private static int unkCol(boolean h, int b, int m) {
    if (!h) return 0;
    if (b==0) return 4;
    if (b==m) return 2;
    return 3;
  }
  private static class ColText {
    final StringBuilder b = new StringBuilder();
    final ByteVec c = new ByteVec();
    void add(String s, int col) {
      b.append(s);
      c.insertFill(c.sz, s.length(), (byte) col);
    }
    void add(ColText t) {
      b.append(t.b);
      c.addAll(t.c.get());
    }
    public <T> Vec<Pair<String, T>> color(Function<Byte, T> l) {
      byte[] cols = c.get();
      Vec<Pair<String,T>> r = new Vec<>();
      if (cols.length==0) return r;
      byte pc = cols[0];
      int pi=0, i=0;
      while (true) {
        if (i==cols.length || (cols[i]!=-1 && cols[i]!=pc)) {
          if (pc!=-1) r.add(new Pair<>(b.substring(pi, i), l.apply(pc)));
          if (i==cols.length) break;
          pc = cols[i];
          pi = i;
        }
        i++;
      }
      return r;
    }
  }
  
  public void onStatusChange() {
    viewer.statusChanged();
  }
  
  public static class MemEnt extends SimpleEnt {
    public boolean requested;
    public byte[] data;
    public short[] meta; // bit 15: accessible; bit 14: has definedness info; bits 0-7: defined
    public MemEnt(long s, long e) { super(s, e); }
    
    public boolean contains(long a) {
      return a>=s && a<e;
    }
    
    public boolean loaded() { return data!=null; }
    public short meta(long addr) { return meta[(int) (addr-s)]; }
    public boolean accessible(long addr) { return (meta(addr)&0x8000) != 0; }
    public boolean hasDef(long addr) { return (meta(addr)&0x4000) != 0; }
    public int get(long addr) { return data[(int) (addr-s)] & 0xff; }
    public int def(long addr) { return meta(addr)&0xff; }
    public void request(MemViewer v) {
      assert !requested;
      requested = true;
      v.g().d.curr.readMemory(s, e, (nM, nA) -> {
        v.dr.cancel();
        data = nM;
        meta = new short[nA.length];
        for (int i = 0; i < nA.length; i++) meta[i] = (short)(nA[i]? 0x80ff : 0);
        v.mRedraw();
        v.g().isValgrind(vg -> { if (vg) requestVG(v); });
      });
    }
    
    private void requestVG(MemViewer v) {
      v.g().d.p.consoleCmd("monitor xb 0x"+Long.toHexString(s)+" "+(e-s)).ds().runWithIntermediate(r -> {
        StringBuilder t = new StringBuilder();
        for (String c : r.intermediate.map(c -> GdbParser.parse(c.substring(1)).str())) t.append(c);
        ByteVec defVals = new ByteVec();
        parse: for (String ln : Tools.split(t.toString(), '\n')) {
          if (ln.isEmpty() || !Character.isWhitespace(ln.charAt(0))) continue;
          for (String b : ln.split("\\s+")) {
            if (b.isEmpty()) continue;
            if (b.equals("__")) {
              defVals.add((byte) 0);
            } else {
              try { defVals.add((byte) GdbFormat.parseHex(b)); }
              catch (Throwable ignored) { defVals=null; break parse; }
            }
          }
        }
        if (defVals==null || defVals.sz!=e-s) {
          Log.warn("grr valgrind", "bad monitor xb result");
        } else {
          for (int i = 0; i < meta.length; i++) {
            meta[i] = (short) ((meta[i]&0x8000) | 0x4000 | ((~defVals.get(i))&0xff));
          }
          v.mRedraw();
        }
      }, c -> c.startsWith("@"));
    }
  }
  public static class SimpleEnt implements AddrMapper.Range {
    public final long s, e;
    public SimpleEnt(long s, long e) { this.s = s; this.e = e; }
    
    public long s() { return s; }
    public long e() { return e; }
    
    public String toString() {
      return "["+s+";"+e+")";
    }
  }
}
