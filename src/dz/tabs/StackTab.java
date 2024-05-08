package dz.tabs;

import dz.debugger.Location;
import dz.gdb.ProcThread;
import dz.gdb.ProcThread.*;
import dz.layouts.DebuggerLayout;
import dz.ui.SelectableEntry;
import dz.ui.SelectableEntry.CT;
import dz.utils.*;
import dzaima.ui.node.Node;
import dzaima.ui.node.types.tabs.SerializableTab;
import dzaima.utils.Vec;

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
      if (f.l.sym==null && f.l.addr!=null) {
        Location l2 = g.cachedJITLocation(f.l);
        if (l2!=null) f = new StackFrame(f.level, l2, f.afterCall);
      }
      list.add(new StackFrameNode(LocationUtils.node(ctx, DebuggerLayout.pad("#"+f.level, ' ', 2), f), this, f));
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
    
    public void onSelect(CT type) {
      if (tab.g.d.status().paused()) tab.g.selectFrame(f);
    }
  }
}
