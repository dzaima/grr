package dz.tabs;

import dz.debugger.*;
import dz.gdb.*;
import dz.general.arch.Arch;
import dz.layouts.Layout;
import dz.stat.*;
import dz.ui.*;
import dzaima.ui.gui.PartialMenu;
import dzaima.ui.gui.io.Click;
import dzaima.ui.node.Node;
import dzaima.ui.node.types.tabs.*;

public abstract class GrrTab<T extends Layout> extends Tab {
  public final T g;
  
  public GrrTab(T g) {
    super(g.node.ctx);
    this.g = g;
  }
  
  public void onRightClick(Click cl) {
    PartialMenu m = new PartialMenu(g.gc);
    WindowSplitNode.onTabRightClick(m, this);
    addMenuBarOptions(m);
    m.open(ctx, cl);
  }
  
  public void focusSelectedEntry(Node node) {
    for (Node c : node.ch) {
      if (c instanceof SelectableEntry && ((SelectableEntry) c).sel) {
        c.focusMe();
        break;
      }
    }
  }
  
  public void switchTo() {
    super.switchTo();
    onSelected();
  }
  
  public /*open*/ void onSelected() { }
  
  public /*open*/ void tick() { }
  
  // DebuggerLayout
  public /*open*/ void onNewArch() { }
  public /*open*/ void onAsmConfig(AsmListNode.AsmConfig config) { }
  public /*open*/ void onStatusChange() { }
  public /*open*/ void onSelectedThread(ProcThread t) { }
  public /*open*/ void onSelectedLocation(Location l, boolean justFunction, boolean nonTop) { }
  public /*open*/ void onGdbPrint(String s, GdbProcess.OutWhere b) { }
  public /*open*/ void onModifiedBreakpoints() { }
  public /*open*/ void onRegHover(Arch.RegInfo reg, boolean enable) { }
  
  public /*open*/ void onStopped(StopReason s) { }
  
  // PerfLayout
  public /*open*/ void onSelectedStatSymbol(Location l, StatSymbol stat) { }
  public /*open*/ void onAddedStatSource(StatGlobal<?> d) { }
  public /*open*/ void onSelectedStatSource() { }
  public /*open*/ void onStatRefresh() { }

  public /*open*/ void onNewState(RecordedState st) { }
}
