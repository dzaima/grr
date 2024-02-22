package dz.ui;

import dzaima.ui.gui.Graphics;
import dzaima.ui.node.ctx.Ctx;
import dzaima.ui.node.prop.Props;
import dzaima.ui.node.types.editable.code.CodeAreaNode;

public class SourceAreaNode extends CodeAreaNode {
  public SourceAreaNode(Ctx ctx, Props props) {
    super(ctx, props);
  }
  
  int hlMain = -1;
  public void setMainHighlight(int ln) {
    this.hlMain = ln;
    mRedraw();
  }
  int hlHover = -1;
  public void setHoverHighlight(int ln) {
    this.hlHover = ln;
    mRedraw();
  }
  
  public void preLineDraw(Graphics g, int ln, int x, int y, int w, int lh, int th) {
    if (ln==hlMain || ln==hlHover) {
      g.rectWH(x, y, w, lh, gc.getProp(ln==hlMain? "grr.tabs.source.highlightSelected" : "grr.tabs.source.highlightHovered").col());
    }
  }
}
