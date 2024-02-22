package dz.ui;

import dzaima.ui.gui.Graphics;
import dzaima.ui.gui.config.GConfig;
import dzaima.ui.gui.io.*;
import dzaima.ui.node.Node;
import dzaima.ui.node.ctx.Ctx;
import dzaima.ui.node.prop.Props;
import dzaima.ui.node.types.ScrollNode;
import dzaima.utils.*;

public abstract class SelectableEntry extends Node {
  public boolean sel;
  
  public SelectableEntry(Ctx ctx, Props props) {
    super(ctx, props);
  }
  
  public SelectableEntry(Node n) {
    super(n.ctx, Props.none());
    add(n);
  }
  
  
  @SuppressWarnings("unchecked")
  public static <T extends SelectableEntry> T getSelected(Node list) {
    for (Node ch : list.ch) if (((SelectableEntry) ch).sel) return (T) ch;
    return null;
  }
  
  
  public int minW() { return ch.get(0).maxW(); }
  public int minH(int w) { return ch.get(0).minH(w); }
  public int maxH(int w) { return minH(w); }
  
  protected void resized() {
    if (ch.sz==1) ch.get(0).resize(w, h, 0, 0);
  }
  
  
  public enum CT {
    QUIET, // selected without interaction, i.e. default position
    MOVE, // moved to by arrow keys 
    CLICK // actual click / enter
  };
  public abstract void onClick(CT type);
  
  public void mouseStart(int x, int y, Click c) {
    if (c.bL()) OnClick.on(this, x, y, c, ()-> select(CT.CLICK));
    super.mouseStart(x, y, c);
  }
  
  public static class OnClick implements Click.RequestImpl {
    public final Node n;
    public final Runnable a;
    OnClick(Node n, Runnable a) { this.n = n; this.a = a; }
    public void mouseDown(int x, int y, Click c) { }
    public void mouseTick(int x, int y, Click c) { c.onClickEnd(); }
    public void mouseUp(int x, int y, Click c) { a.run(); }
    public GConfig gc() { return n.gc; }
    public XY relPos(Node nullArgument) { return n.relPos(nullArgument); }
    public static void on(Node n, int x, int y, Click c, Runnable a) {
      c.register(new OnClick(n, a), x, y);
    }
  }
  
  public void onlyHighlight() {
    for (Node n : p.ch) if (((SelectableEntry) n).sel) ((SelectableEntry) n).sel(false);
    sel(true);
  }
  public void select(CT type) {
    onlyHighlight();
    onClick(type);
    if (visible) {
      if (type!=CT.QUIET) focusMe();
      else ScrollNode.scrollTo(this, ScrollNode.Mode.NONE, ScrollNode.Mode.INSTANT);
    }
  }
  
  public boolean keyF(Key key, int scancode, KeyAction a) {
    int i = p.ch.indexOf(this);
    switch (gc.keymap(key, a, "grr.list")) {
      case "click": select(CT.CLICK); return true;
      case "down": if (i!=p.ch.sz-1) { ((SelectableEntry) p.ch.get(i+1)).select(CT.MOVE); return true; } break;
      case "up":   if (i!=0        ) { ((SelectableEntry) p.ch.get(i-1)).select(CT.MOVE); return true; } break;
      case "first": ((SelectableEntry) p.ch.get(0)).select(CT.MOVE); return true;
      case "last":  ((SelectableEntry) p.ch.peek()).select(CT.MOVE); return true;
      case "selectAll":
        if (!(p instanceof SelectableListManager.ListSelectableNode)) return false;
        ((SelectableListManager.ListSelectableNode) p).getSelMgr().selectManualAll();
        return true;
    }
    return false;
  }
  
  public int bgColCalc() {
    return sel? gc.getProp(isFocused()? "grr.list.bgSelFocus" : "grr.list.bgSel").col() : 0;
  }
  public void bg(Graphics g, boolean full) {
    int bgCol = bgColCalc();
    if (Tools.st(bgCol)) pbg(g, full);
    if (Tools.vs(bgCol)) g.rect(0, 0, w, h, bgCol);
  }
  
  public void sel(boolean b) {
    sel = b;
    mRedraw();
  }
}
