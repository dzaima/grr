package dz.tabs;

import dz.debugger.Location;
import dz.gdb.*;
import dz.layouts.DebuggerLayout;
import dz.ui.*;
import dz.ui.SelectableEntry.CT;
import dzaima.ui.eval.PNodeGroup;
import dzaima.ui.gui.Popup;
import dzaima.ui.gui.io.*;
import dzaima.ui.node.Node;
import dzaima.ui.node.types.*;
import dzaima.ui.node.types.editable.EditNode;
import dzaima.ui.node.types.tabs.SerializableTab;
import dzaima.utils.Vec;

import java.util.HashMap;

public class BreakpointsTab extends GrrTab<DebuggerLayout> implements SerializableTab {
  public final Node node, list;
  
  public BreakpointsTab(DebuggerLayout g) {
    super(g);
    node = ctx.make(ctx.gc.getProp("grr.tabs.breakpoints.ui").gr());
    list = node.ctx.id("list");
  
    ((BtnNode) node.ctx.id("addBreak")).setFn(btn -> {
      Popup p = new SearchPopup(g) {
        public void accept(Location l, Vec<ProcThread.Arg> b) {
          g.d.addBreakpoint(
            ((CheckboxNode) node.ctx.id("hardware")).enabled,
            ((CheckboxNode) node.ctx.id("temporary")).enabled,
            ((CheckboxNode) node.ctx.id("enabled")).enabled,
            l.sym,
            null
          );
          close();
        }
        
        protected boolean key(Key key, KeyAction a) {
          switch (node.ctx.gc.keymap(key, a, "grr.tabs.breakpoints.addBreakMenu")) {
            case "close": close(); return true;
            case "accept": accept(); return true;
            case "toggleHardware":  ((CheckboxNode) node.ctx.id("hardware" )).toggle(); return true;
            case "toggleTemporary": ((CheckboxNode) node.ctx.id("temporary")).toggle(); return true;
            case "toggleEnabled":   ((CheckboxNode) node.ctx.id("enabled"  )).toggle(); return true;
          }
          return super.key(key, a);
        }
      };
      p.openVW(g.gc, g.node.ctx, g.gc.getProp("grr.tabs.breakpoints.addBreakMenu.ui").gr(), true);
    });
  
    ((BtnNode) node.ctx.id("watchAddr")).setFn(btn -> {
      Popup p = new Popup(g.m) {
        private EditNode size, addr;
        
        protected void unfocused() { close(); }
        protected void setup() {
          addr = (EditNode) node.ctx.id("addr");
          size = (EditNode) node.ctx.id("size");
          size.append("8");
          pw.focus(addr);
          addr.setFn((a,mod) -> { if (!a.enter) return false; accept(); return true; });
          size.setFn((a,mod) -> { if (!a.done) return false; pw.focus(addr); return true; });
        }
        
        void accept() {
          int sz;
          try { sz = Integer.parseInt(size.getAll()); }
          catch (NumberFormatException e) { System.err.println("Bad width"); return; }
          
          
          g.d.evalExpr(addr.getAll(), s -> {
            Long l = GdbFormat.numFromPrefix(s);
            if (l==null) { System.err.println("Failed to evaluate expression"); return; }
            
            boolean r = ((CheckboxNode) node.ctx.id("read")).enabled;
            boolean w = ((CheckboxNode) node.ctx.id("write")).enabled;
            String expr = "0x"+Long.toHexString(l);
            if (sz==1 || sz==2 || sz==4 || sz==8) expr = "((uint"+(sz*8)+"_t*)"+expr+")";
            g.d.curr.addWatchpoint("*"+expr, r, w, ()->{});
            close();
          });
        }
        
        protected boolean key(Key key, KeyAction a) {
          switch (node.ctx.gc.keymap(key, a, "grr.tabs.breakpoints.watchAddrMenu")) {
            case "close": close(); return true;
            case "accept": accept(); return true;
            case "toggleRead":  ((CheckboxNode) node.ctx.id("read" )).toggle(); return true;
            case "toggleWrite": ((CheckboxNode) node.ctx.id("write")).toggle(); return true;
            case "size1": size.removeAll(); size.append("1"); return true;
            case "size2": size.removeAll(); size.append("2"); return true;
            case "size4": size.removeAll(); size.append("4"); return true;
            case "size8": size.removeAll(); size.append("8"); return true;
          }
          return false;
        }
      };
      p.openVW(g.gc, g.node.ctx, g.gc.getProp("grr.tabs.breakpoints.watchAddrMenu.ui").gr(), true);
    });
  }
  
  public Node show() { return node; }
  public void onSelected() { focusSelectedEntry(list); }
  
  public String name() { return "breakpoints"; }
  public String serializeName() { return "breakpoints"; }
  
  public void onModifiedBreakpoints() {
    BreakpointNode sel = SelectableEntry.getSelected(list);
    boolean selFocused = sel!=null && sel.isFocused();
    int selID = sel==null? -1 : sel.b.number;
    list.clearCh();
    PNodeGroup i = ctx.gc.getProp("grr.tabs.breakpoints.item").gr();
    for (Breakpoint c : g.d.p.breakpoints) {
      Node n = ctx.make(i);
      n.ctx.id("desc").add(ctx.make(ctx.gc.getProp(c.enabled? "grr.tabs.breakpoints.itemEnabled" : "grr.tabs.breakpoints.itemDisabled").gr()));
      n.ctx.id("desc").add(new StringNode(ctx, c.desc));
      BreakpointNode nd = new BreakpointNode(this, n, c);
      list.add(nd);
      if (selID==c.number) nd.select(selFocused? CT.CLICK : CT.QUIET);
    }
  }
  
  public void onStopped(StopReason s) {
    int bestID = -1;
    if      (s.watchpoints.sz>0) bestID = s.watchpoints.get(0).no;
    else if (s.breakpoints.sz>0) bestID = s.breakpoints.get(0).no;
    HashMap<Integer, StopReason.Watchpoint> ws = new HashMap<>();
    for (StopReason.Watchpoint w : s.watchpoints) ws.put(w.no, w);
    for (Node c0 : list.ch) {
      BreakpointNode c = (BreakpointNode) c0;
      if (c.b.number == bestID) ((BreakpointNode) c0).select(CT.QUIET);
  
      c.update(ws.get(c.b.number));
    }
  }
  
  public static class BreakpointNode extends SelectableEntry {
    public final BreakpointsTab tab;
    public final Breakpoint b;
    
    public BreakpointNode(BreakpointsTab tab, Node n, Breakpoint b) {
      super(n);
      this.tab = tab;
      this.b = b;
    }
    
    public void onClick(CT type) { }
    
    public boolean keyF(Key key, int scancode, KeyAction a) {
      DebuggerLayout g = tab.g;
      switch (gc.keymap(key, a, "grr.entry")) {
        case "delete":
          g.d.deleteBreakpoint(b.number);
          return true;
        case "toggle":
          g.d.toggleBreakpoint(b.number, !b.enabled, ()->{});
          return true;
      }
      return super.keyF(key, scancode, a);
    }
    
    StopReason.Watchpoint pUpd;
    Dbi.ExecMode pMode;
    public void update(StopReason.Watchpoint r) {
      Node i = ch.get(0).ctx.id("info");
      i.clearCh();
      if (r!=null) {
        pUpd = r;
        pMode = tab.g.d.lastRunMode();
      }
      if (pUpd!=null) {
        boolean hasPrev = pUpd.prev != null;
        String t = !hasPrev? "Read" : pMode==Dbi.ExecMode.FORWARD? "WriteF" : pMode==Dbi.ExecMode.BACKWARD? "WriteB" : "WriteU";
        
        Node n = ctx.make(gc.getProp("grr.tabs.breakpoints.watch"+(r==null? "O" : "N")+t).gr());
        if (hasPrev) n.ctx.id("old").add(new StringNode(ctx, pUpd.prev));
        n.ctx.id("new").add(new StringNode(ctx, pUpd.curr));
        
        i.add(n);
      }
    }
  }
}
