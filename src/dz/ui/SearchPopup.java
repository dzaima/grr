package dz.ui;

import dz.debugger.Location;
import dz.gdb.ProcThread;
import dz.layouts.GdbLayout;
import dz.tabs.StackTab;
import dz.ui.SelectableEntry.CT;
import dzaima.ui.gui.Popup;
import dzaima.ui.node.Node;
import dzaima.utils.*;

import java.util.Comparator;


public abstract class SearchPopup extends Popup {
  public final GdbLayout g;
  private LiveTextFieldNode name;
  private Node list;
  
  public SearchPopup(GdbLayout g) {
    super(g.m);
    this.g = g;
  }
  
  protected void unfocused() { close(); }
  protected void setup() {
    name = (LiveTextFieldNode) node.ctx.id("name");
    list = node.ctx.id("list");
    pw.focus(name);
    name.onUpdate = s -> newValue();
    name.associatedList = () -> new Pair<>(list, true);
    name.setFn((a,mod) -> {
      if (!a.enter) return false;
      if (!waiting) accept(); return true;
    });
  }
  
  public void accept() {
    if (list.ch.sz>0) {
      Node focus = list.ctx.focusedNode();
      SelectableEntry e;
      if (list.ch.indexOf(focus)!=-1) e = (SelectableEntry) focus;
      else e = (SelectableEntry) list.ch.get(0);
      e.select(CT.CLICK);
    }
  }
  
  public abstract void accept(Location l, Vec<ProcThread.Arg> b);
  
  boolean waiting, newValue;
  void newValue() {
    if (waiting) { newValue = true; return; }
    waiting = true;
    String n = name.getAll();
    g.d.curr.symbolInfo(true, g.gc.getProp("grr.search.maxSymbols").i(), n, r -> {
      Vec<Pair<Location, Vec<ProcThread.Arg>>> s = new Vec<>();
      if (r!=null) s.addAll(r);
      s.sort(Comparator.comparing(x -> x.a.sym));
      s.sort(Comparator.comparing(x -> x.a.sym.length()));
      waiting = false;
      list.clearCh();
      for (Pair<Location, Vec<ProcThread.Arg>> c : s) {
        list.add(new SelectableEntry(StackTab.fnLine(node.ctx, "", c.a, c.b)) {
          public void onClick(CT type) {
            if (type==CT.CLICK) accept(c.a, c.b);
          }
          public int bgColCalc() {
            if (sel) return gc.getProp("grr.list.bgSelFocus").col();
            return super.bgColCalc();
          }
        });
      }
      if (newValue) { newValue(); newValue=false; }
      if (list.ch.sz>0) ((SelectableEntry) list.ch.get(0)).select(CT.QUIET);
    });
  }
}
