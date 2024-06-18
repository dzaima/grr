package dz.tabs;

import dz.debugger.Location;
import dz.general.DisasFn;
import dz.layouts.GdbLayout;
import dz.ui.SelectableEntry;
import dz.utils.LocationUtils;
import dzaima.ui.node.Node;
import dzaima.ui.node.types.tabs.SerializableTab;

import java.util.HashMap;

public class SourceMapStackTab extends GrrTab<GdbLayout> implements SerializableTab {
  public final Node node, list;
  public SourceMapStackTab(GdbLayout g) {
    super(g);
    node = ctx.make(ctx.gc.getProp("grr.tabs.srcStack.ui").gr());
    list = node.ctx.id("list");
  }
  
  public Node show() { return node; }
  
  public String name() { return "source stack"; }
  public String serializeName() { return "sourceStack"; }
  
  private final HashMap<DisasFn.SourceMap, SelectableEntry> m = new HashMap<>();
  public void onSelectedSourceMapStack(DisasFn.SourceMap map, String bin) {
    m.clear();
    list.clearCh();
    for (DisasFn.SourceMap c : DisasFn.SourceMap.unroll(map)) {
      Location l = DisasFn.SourceMap.loc(0, c);
      SrcEntryNode e = new SrcEntryNode(LocationUtils.node("grr.tabs.srcStack.line", ctx, null, l, null), this, c, bin);
      m.put(c, e);
      list.add(e);
    }
  }
  public void onSelectedSourceMap(DisasFn.SourceMap map, String bin) {
    SelectableEntry n = m.get(map);
    if (n!=null) n.select(SelectableEntry.CT.QUIET);
  }
  
  public static class SrcEntryNode extends SelectableEntry {
    public final DisasFn.SourceMap map;
    public final String bin;
    public final SourceMapStackTab tab;
    
    public SrcEntryNode(Node n, SourceMapStackTab tab, DisasFn.SourceMap map, String bin) {
      super(n);
      this.tab = tab;
      this.map = map;
      this.bin = bin;
    }
    
    public void onSelect(CT type) {
      if (type.sel1) tab.g.selectSourceMap(map, bin);
    }
  }
}
