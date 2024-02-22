package dz.ui;

import dzaima.ui.gui.io.*;
import dzaima.ui.node.Node;
import dzaima.ui.node.ctx.Ctx;
import dzaima.ui.node.prop.Props;
import dzaima.ui.node.types.editable.TextFieldNode;
import dzaima.utils.Pair;

import java.util.function.*;

public class LiveTextFieldNode extends TextFieldNode {
  public LiveTextFieldNode(Ctx ctx, Props props) {
    super(ctx, props);
  }
  public Consumer<String> onUpdate;
  public Supplier<Pair<Node,Boolean>> associatedList; // boolean represents whether the first entry is pre-selected
  
  String prev;
  public void tickC() {
    super.tickC();
    String curr = getAll();
    if (!curr.equals(prev)) {
      prev = curr;
      if (onUpdate!=null) onUpdate.accept(curr);
    }
  }
  
  
  
  public int action(Key key, KeyAction a) {
    switch (gc.keymap(key, a, "grr.list")) {
      case "up": case "down":
        if (associatedList==null) break;
        Pair<Node,Boolean> list = associatedList.get();
        if (list==null || list.a==null) break;
        SelectableEntry e = (SelectableEntry) list.a.ch.get(0);
        e.select(SelectableEntry.CT.MOVE);
        if (list.b) e.keyF(key, 0, a);
        return 1;
    }
    return super.action(key, a);
  }
}
