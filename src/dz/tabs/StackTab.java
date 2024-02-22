package dz.tabs;

import dz.debugger.Location;
import dz.gdb.ProcThread;
import dz.gdb.ProcThread.*;
import dz.layouts.DebuggerLayout;
import dz.ui.SelectableEntry;
import dz.ui.SelectableEntry.CT;
import dz.utils.DelayedRun;
import dzaima.ui.eval.PNodeGroup;
import dzaima.ui.gui.config.GConfig;
import dzaima.ui.node.Node;
import dzaima.ui.node.ctx.Ctx;
import dzaima.ui.node.types.StringNode;
import dzaima.ui.node.types.tabs.SerializableTab;
import dzaima.utils.Vec;

import java.nio.file.Paths;

public class StackTab extends GrrTab<DebuggerLayout> implements SerializableTab {
  public final Node node, list;
  public StackTab(DebuggerLayout g) {
    super(g);
    node = ctx.make(ctx.gc.getProp("grr.tabs.stack.ui").gr());
    list = node.ctx.id("list");
  }
  
  public Node show() { return node; }
  public void onSelected() { focusSelectedEntry(list); }
  
  public String name() { return "stack"; }
  public String serializeName() { return "stack"; }
  
  public static Node fnLine(Ctx ctx, String prefix, Location l) {
    return frameLine(ctx, prefix, l, null);
  }
  
  public static Node fnLine(Ctx ctx, String prefix, Location l, Vec<Arg> args) {
    return frameLine(ctx, prefix, l, args);
  }
  
  public static Node frameLine(Ctx ctx, String prefix, StackFrame f) {
    return frameLine(ctx, prefix, f.l, f.args);
  }
  
  public static Node frameLine(Ctx ctx, String prefix, Location l, Vec<Arg> args) {
    GConfig gc = ctx.gc;
    Node n = ctx.make(gc.getProp("grr.stack.line").gr());
    n.ctx.id("prefix").add(new StringNode(ctx, prefix.isEmpty()? "" : prefix+" "));
    n.ctx.id("addr").add(new StringNode(ctx, l.addr==null? "0x??" : "0x"+DebuggerLayout.hexLong(l.addr)));
    n.ctx.id("func").add(new StringNode(ctx, l.sym==null? "??" : l.sym));
    if (l.shortFile!=null) {
      Node file = ctx.make(gc.getProp("grr.stack.ifFile").gr());
      String filePath = l.shortFile;
      filePath = Paths.get(filePath).normalize().toString();
      file.ctx.id("file").add(new StringNode(ctx, filePath));
      if (l.line!=null && l.line!=-1) file.ctx.id("line").add(new StringNode(ctx, ":"+ l.line));
      n.ctx.id("ifFile").add(file);
    }
    if (args!=null) {
      Node argList = n.ctx.id("args");
      PNodeGroup sepP = gc.getProp("grr.stack.argSep").gr();
      boolean first = true;
      for (Arg a : args) {
        if (first) first = false;
        else argList.add(ctx.make(sepP));
        boolean value = a.val != null;
        boolean name = a.name != null;
        Node arg = ctx.make(gc.getProp(value? "grr.stack.argVal" : name? "grr.stack.argType" : "grr.stack.argOnlyType").gr());
        if (value||name) arg.ctx.id("name").add(new StringNode(ctx, a.name==null? "??" : a.name));
        if (value) arg.ctx.id("value").add(new StringNode(ctx, a.val));
        else       arg.ctx.id("type" ).add(new StringNode(ctx, a.type==null? "??" : a.type));
        argList.add(arg);
      }
    }
    return n;
  }
  
  public static boolean showFullStack = false;
  
  final DelayedRun drClear = new DelayedRun(g);
  public void onSelectedThread(ProcThread t) {
    if (!g.d.status().paused()) return;
    Vec<StackFrame> prev = new Vec<>();
    int eqAm0 = -1;
    for (int i = 0; i < list.ch.sz; i++) {
      StackFrameNode sf = (StackFrameNode) list.ch.get(i);
      prev.add(sf.f);
      if (sf.sel && i!=0) eqAm0 = list.ch.sz-i;
    }
    int eqAm1 = eqAm0;
    drClear.set(list::clearCh);
    if (showFullStack) g.dst.fullStack(t, bt -> updateStack(eqAm1, prev, bt));
    else g.dst.limitedStack(t, r -> updateStack(eqAm1, prev, r==null? null : r.a));
  }
  private void updateStack(int eqAm0, Vec<StackFrame> prev, Vec<StackFrame> curr) {
    drClear.cancel();
    list.clearCh();
    if (curr!=null) for (StackFrame f : curr) {
      list.add(new StackFrameNode(frameLine(ctx, DebuggerLayout.pad("#"+f.level, ' ', 2), f), this, f));
    }
    if (curr==null || curr.sz==0) {
      g.selectFrame(null);
    } else {
      int eqAm = eqAm0;
      if (eqAm!=-1) {
        if (curr.sz < eqAm) eqAm = -1;
        else for (int i = 0; i < eqAm; i++) if (!curr.get(curr.sz-i-1).equalLocation(prev.get(prev.sz-i-1))) eqAm = -1;
      }
      ((StackFrameNode) list.ch.get(eqAm==-1? 0 : list.ch.sz-eqAm)).select(CT.QUIET);
    }
  }
  
  public void onStatusChange() {
    if (g.d.status().paused()) onSelectedThread(g.dst.currThread);
  }
  
  public static class StackFrameNode extends SelectableEntry {
    public final StackFrame f;
    public final StackTab tab;
    
    public StackFrameNode(Node n, StackTab tab, StackFrame f) {
      super(n);
      this.tab = tab;
      this.f = f;
    }
    
    public void onClick(CT type) {
      if (tab.g.d.status().paused()) tab.g.selectFrame(f);
    }
  }
}
