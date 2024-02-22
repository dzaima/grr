package dz.ui;

import dzaima.ui.gui.NodeWindow;
import dzaima.ui.gui.config.GConfig;
import dzaima.ui.gui.io.*;
import dzaima.ui.gui.select.*;
import dzaima.ui.node.Node;
import dzaima.utils.*;

public class SelectableListManager implements Selectable, Click.RequestImpl {
  public final Node node;
  private final Vec<Node> ch;
  private final boolean v;
  public Selection sel;
  private final boolean middle;
  
  public interface ListSelectableNode extends Selectable /* extends Node */ {
    void onNewSelection(boolean has);
    SelectableListManager getSelMgr();
  }
  
  public SelectableListManager(ListSelectableNode node, boolean middle) {
    this.node = (Node) node;
    this.middle = middle;
    this.ch = this.node.ch;
    this.v = node.selType().equals("v");
  }
  
  
  
  // things to directly wrap
  public void selectS(Selection s) {
    sel = s;
    if (middle) s.setSorted(idx(s.aS.ln)+s.aS.pos > idx(s.bS.ln)+s.bS.pos);
    else        s.setSorted(idx(s.aS.ln)          > idx(s.bS.ln));
    ((ListSelectableNode) node).onNewSelection(true);
  }
  public void selectE(Selection s) {
    sel = null;
    ((ListSelectableNode) node).onNewSelection(false);
  }
  public void focusE() {
    if (sel!=null) sel.invalidate();
  }
  public void mouseStart(int x, int y, Click c) {
    if (Key.none(c.mod0) && c.bL()) c.register(this, x, y);
  }
  
  
  
  // utils
  public void selectManual(Node s, int sPos, Node e, int ePos) {
    NodeWindow w = win();
    w.startFocusSelection(Position.make((Selectable) node, s, sPos));
    w.continueFocusSelection(Position.make((Selectable) node, e, ePos));
    w.focus(node);
  }
  public void selectManualAll() {
    selectManual(ch.get(0), 0, ch.peek(), 1);
  }
  public XY getRange() {
    if (sel==null) return XY.ZERO;
    int iS = idx(sel.sS.ln);
    int iE = idx(sel.eS.ln);
    if (iS==-1 || iE==-1) {
      sel.invalidate();
      return XY.ZERO;
    }
    if (middle) return new XY(iS+sel.sS.pos, iE+sel.eS.pos);
    else        return new XY(iS,            iE+1);
  }
  
  
  
  public String selType() { return v? "v" : "h"; }
  private int dirD(Node n) { return v? n.dy : n.dx; }
  private NodeWindow win() { return node.ctx.win(); }
  private int idx(Node s) {
    int nd = dirD(s);
    int i = ch.binarySearch(c -> dirD(c) >= nd);
    if (i<ch.sz && ch.get(i)==s) return i;
    return ch.indexOfEqual(s);
  }
  
  private Position pos0;
  public void mouseDown(int x, int y, Click c) { pos0 = Position.getPosition(node, x, y); }
  public void mouseTick(int x, int y, Click c) {
    NodeWindow w = win();
    if (pos0==null) {
      w.continueFocusSelection(Position.getPosition(node, x, y));
    } else if (!gc().isClick(c)) {
      w.startFocusSelection(pos0);
      pos0 = null;
    }
  }
  public void mouseUp(int x, int y, Click c) {
    if (pos0==null) return;
    pos0 = null;
    c.unregister();
  }
  
  public GConfig gc() { return node.gc; }
  public XY relPos(Node nullArgument) {return node.relPos(nullArgument); }
}
