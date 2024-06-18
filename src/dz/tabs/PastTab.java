package dz.tabs;

import dz.debugger.*;
import dz.gdb.ProcThread;
import dz.layouts.DebuggerLayout;
import dz.ui.SelectableEntry;
import dz.utils.LocationUtils;
import dzaima.ui.node.Node;
import dzaima.ui.node.types.BtnNode;
import dzaima.ui.node.types.tabs.SerializableTab;
import dzaima.utils.*;

import java.util.HashMap;

public class PastTab extends GrrTab<DebuggerLayout> implements SerializableTab {
  public final Node node, list;
  public PastTab(DebuggerLayout g) {
    super(g);
    node = ctx.make(ctx.gc.getProp("grr.tabs.history.ui").gr());
    list = node.ctx.id("list");
    ((BtnNode) node.ctx.id("goto")).setFn(b -> {
      Entry e = SelectableEntry.getSelected(list);
      if (e!=null && e.lwcp!=-1 && g.d.curr==e.st.exe) g.d.curr.gotoLWCP(e.lwcp, null);
    });
  }
  
  public Node show() { return node; }
  public void onSelected() { focusSelectedEntry(list); }
  
  public String name() { return "history"; }
  public String serializeName() { return "history"; }
  
  public void onNewState(RecordedState s) {
    list.add(new Entry(this, s));
  }
  
  private ProcThread.StackFrame tryMap(RecordedState st, HashMap<ProcThread, Promise<Pair<Vec<ProcThread.StackFrame>, Boolean>>> m) {
    if (m!=null && m.containsKey(st.currThread)) {
      Promise<Pair<Vec<ProcThread.StackFrame>, Boolean>> p = m.get(st.currThread);
      if (p.isResolved()) if (p.get()!=null && p.get().a.sz>0) return p.get().a.get(0);
    }
    return null;
  }
  public Node dispNode(RecordedState st) {
    ProcThread.StackFrame f = tryMap(st, st._limitedStack);
    if (f==null) f = tryMap(st, st._fullStack);
    
    String time = Time.localNearTimeStr(st.visitTime);
    if (st._elapsedTime!=null && st._elapsedTime.isResolved()) time = time+" "+DebuggerLayout.fmtNanos(st._elapsedTime.get());
    
    if (f!=null) return LocationUtils.node(ctx, time, f);
    else return LocationUtils.node("grr.frame.all", ctx, time, Location.IDK, null);
  }
  public static class Entry extends SelectableEntry {
    public final PastTab tab;
    public final RecordedState st;
    int lwcp = -1;
    public Entry(PastTab t, RecordedState st0) {
      super(t.dispNode(st0));
      this.tab = t;
      this.st = st0;
      st.getLWCP(n -> {
        if (n!=null) lwcp=n;
      });
      st.onUpdate.add(() -> replace(0, tab.dispNode(st)));
    }
    
    public void toThis() {
      if (lwcp!=-1) tab.g.d.curr.gotoLWCP(lwcp, null);
    }
    
    public void onSelect(CT type) {
      if (type.click2) toThis();
    }
  }
}