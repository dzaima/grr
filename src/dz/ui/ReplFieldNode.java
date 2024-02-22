package dz.ui;

import dzaima.ui.gui.io.*;
import dzaima.ui.node.ctx.Ctx;
import dzaima.ui.node.prop.Props;
import dzaima.ui.node.types.editable.TextFieldNode;
import dzaima.utils.Vec;

import java.util.function.Consumer;

public class ReplFieldNode extends TextFieldNode {
  public ReplFieldNode(Ctx ctx, Props props) {
    super(ctx, props);
  }
  
  public final Vec<String> history = new Vec<>();
  int historyPos = 0;
  
  Consumer<String> onRun;
  public void onRun(Consumer<String> f) {
    onRun = f;
  }
  public boolean action(EditAction a, int mod) {
    if (!a.enter) return false;
    
    String s = getAll();
    if (s.isEmpty()) {
      if (history.sz>0) s = history.peek();
      else return true;
    } else if (history.sz==0 || !history.peek().equals(s)) {
      history.add(s);
    }
    historyPos = history.sz;
    removeAll();
    um.clear();
    onRun.accept(s);
    return true;
  }
  
  public void toHistory(int pos) {
    if (history.sz==0) return;
    if (pos<0) pos = 0;
    if (pos>history.sz) pos = history.sz;
    removeAll();
    if (pos != history.sz) append(history.get(pos));
    historyPos = pos;
  }
  
  public int action(Key key, KeyAction a) {
    switch (gc.keymap(key, a, "grr.repl")) {
      case "prev": {
        toHistory(historyPos-1);
        return 1;
      }
      case "next": {
        toHistory(historyPos+1);
        return 1;
      }
      default: return super.action(key, a);
    }
  }
}
