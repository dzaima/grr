package dz.ui;

import dzaima.ui.gui.Graphics;
import dzaima.ui.node.Node;
import dzaima.ui.node.ctx.Ctx;
import dzaima.ui.node.prop.Props;
import dzaima.ui.node.types.ScrollNode;
import dzaima.utils.*;

public abstract class LazyList extends Node {
  public LazyList(Ctx ctx, Props props) {
    super(ctx, props);
  }
  
  public void propsUpd() {
    mResize();
    mTick();
  }
  
  public /*open*/ int overread() { return 2; }
  public abstract int lineHeight();
  public abstract int count();
  public abstract Node calcAt(int i);
  public /*open*/ boolean canRemove(int idx, Node val) { return true; }
  
  
  public final void elementsChanged() {
    clearCh();
    currOs = new int[0];
    mResize();
    mTick();
  }
  
  public void focusEntry(int n, ScrollNode.Mode mode) {
    ScrollNode.scrollTo(this, ScrollNode.Mode.NONE, mode, 0, (int) ((n + 0.5)*lineHeight()));
  }
  
  
  
  
  
  int[] currOs = new int[0];
  int sy=-1, ey=0;
  public void drawC(Graphics g) {
    int lh = lineHeight();
    int c = count();
    int nsy = (g.clip==null? 0 : g.clip.sy)/lh;
    int ney = (g.clip==null? h : g.clip.ey)/lh;
    int overread = overread();
    nsy = Math.max(nsy-overread, 0);
    ney = Math.min(ney+overread, c);
    if (nsy!=sy || ney!=ey) {
      mTick();
      sy = nsy;
      ey = ney;
    }
  }
  public void tickC() {
    if (sy==-1) return;
    int c = count();
    Vec<Node> prevNs = new Vec<>(ch);
    int[] prevOs = currOs;
    IntVec newOs = new IntVec();
    clearCh();
    
    int prevI = 0;
    for (int i = sy; i < ey; i++) {
      int pl = newOs.sz;
      prevI = updPrevI(prevI, prevOs, prevNs, newOs, i);
      if (pl==newOs.sz && i<c) { newOs.add(i); Node n=calcAt(i); add(n); properPos(n, i); System.out.println("adding new on "+i); }
    }
    updPrevI(prevI, prevOs, prevNs, newOs, count());
    currOs = newOs.get();
  }
  
  private int updPrevI(int prevI, int[] prevOs, Vec<Node> prevCs, IntVec newOs, int keep) {
    while (prevI!=prevOs.length) {
      int po = prevOs[prevI];
      if (po>keep) break;
      Node pn = prevCs.get(prevI);
      if (po==keep || !canRemove(po, pn)) { newOs.add(po); add(pn); System.out.println("kept previous for "+po); }
      prevI++;
    }
    return prevI;
  }
  
  public final Node load(int i) {
    int s=0, e=currOs.length;
    while (e-s > 1) {
      int m = (s+e)/2;
      if (i>currOs[m]) e = m;
      else s = m;
    }
    
    if (s<currOs.length && currOs[s]==i) return ch.get(s);
    Node r = calcAt(i);
    insert(s, r);
    properPos(r, i);
    int[] newOs = new int[currOs.length+1];
    System.arraycopy(currOs, 0, newOs, 0, s);
    newOs[s] = i;
    System.arraycopy(currOs, s, newOs, s+1, currOs.length-s);
    currOs = newOs;
    return r;
  }
  
  final void properPos(Node n, int i) {
    n.dx=0; n.w=w;
    n.h = lineHeight();
    n.dy = n.h*i;
  }
  
  public int minH(int w) { return lineHeight()*count(); }
  
  protected void resized() {
    int lh = lineHeight();
    int i = 0;
    for (Node c : ch) c.resize(w, lh, 0, lh*currOs[i++]);
  }
}
