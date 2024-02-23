package dz.tabs;

import dz.debugger.RecordedState;
import dz.layouts.DebuggerLayout;
import dz.ui.SelectableEntry;
import dz.utils.LocationUtils;
import dzaima.ui.gui.io.*;
import dzaima.ui.node.Node;
import dzaima.ui.node.types.BtnNode;
import dzaima.ui.node.types.tabs.SerializableTab;

public class CheckpointTab extends GrrTab<DebuggerLayout> implements SerializableTab {
  public final Node node, list;
  
  public CheckpointTab(DebuggerLayout g) {
    super(g);
    node = ctx.make(ctx.gc.getProp("grr.tabs.checkpoints.ui").gr());
    list = node.ctx.id("list");
    
    ((BtnNode) node.ctx.id("add")).setFn(btn -> makeCheckpoint());
    ((BtnNode) node.ctx.id("go")).setFn(btn -> {
      CheckpointNode c = SelectableEntry.getSelected(list);
      if (c!=null && g.atPausedMainState()) g.d.curr.toCheckpoint(c.num, null);
    });
    ((BtnNode) node.ctx.id("del")).setFn(btn -> {
      CheckpointNode c = SelectableEntry.getSelected(list);
      if (c!=null) remove(c);
    });
  }
  
  public void remove(CheckpointNode n) {
    list.remove(n);
    g.d.curr.rmCheckpoint(n.num, g::checkpointsUpdated);
  }
  
  public void makeCheckpoint() {
    if (!g.atPausedMainState()) return;
    g.d.curr.addCheckpoint(n -> {
      if (n==null) return;
      g.mainState.currThreadState(t -> t.obj.stacktrace(true, 0, 1, bt -> {
        Node line = LocationUtils.node(ctx, DebuggerLayout.pad("#"+n, ' ', 2), bt.sz==0? DebuggerLayout.BAD_STACKFRAME : bt.get(0));
        list.add(new CheckpointNode(line, this, n, g.mainState));
        g.checkpointsUpdated();
      }));
    });
  }
  
  public Node show() { return node; }
  public void onSelected() { focusSelectedEntry(list); }
  public String name() { return "checkpoints"; }
  public String serializeName() { return "checkpoints"; }
  
  public static class CheckpointNode extends SelectableEntry {
    public final CheckpointTab tab;
    public final int num;
    public final RecordedState st;
    
    public CheckpointNode(Node n, CheckpointTab tab, int num, RecordedState st) {
      super(n);
      this.tab = tab;
      this.num = num;
      this.st = st;
    }
    
    public void onClick(CT type) { }
    
    public void mouseUp(int x, int y, Click c) {
      super.mouseUp(x, y, c);
      if (gc.isDoubleclick(c) && tab.g.atPausedMainState()) tab.g.d.curr.toCheckpoint(num, null);
    }
    
    public boolean keyF(Key key, int scancode, KeyAction a) {
      switch (gc.keymap(key, a, "grr.entry")) {
        case "delete":
          tab.remove(this);
          return true;
        case "click":
          select(CT.CLICK);
          return true;
      }
      return super.keyF(key, scancode, a);
    }
  }
}
