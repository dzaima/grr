package dz.tabs;

import dz.debugger.Location;
import dz.layouts.GdbLayout;
import dz.stat.StatSymbol;
import dz.ui.*;
import dzaima.ui.node.Node;
import dzaima.ui.node.prop.Props;
import dzaima.ui.node.types.*;
import dzaima.ui.node.types.tabs.SerializableTab;
import dzaima.utils.*;

import java.util.Comparator;

public class PerfListTab extends GrrTab<GdbLayout> implements SerializableTab {
  public Node node;
  public Node perfList;
  public LiveTextFieldNode filterNode;
  private String filter = "";
  
  public PerfListTab(GdbLayout g) {
    super(g);
    node = ctx.make(ctx.gc.getProp("grr.tabs.perfList.ui").gr());
    filterNode = (LiveTextFieldNode) node.ctx.id("name");
    filterNode.onUpdate = (filter) -> {
      this.filter = filter;
      onStatRefresh();
    };
    filterNode.associatedList = () -> new Pair<>(perfList, false);
  }
  
  Vec<StatSymbol> filteredSorted;
  public void onStatRefresh() {
    if (!g.statLoaded()) return;
    filteredSorted = new Vec<>();
    g.statSymbol.forEach((k, v) -> {
      if (v.name().contains(filter)) filteredSorted.add(v);
    });
    filteredSorted.sort(Comparator.comparing((StatSymbol c) -> -c.score()).thenComparing(c -> c.sym.name==null? "" : c.sym.name));
    if (filteredSorted.size()>10000) {
      perfList = new PerfList(this);
      ((ScrollNode) node).ignoreFocus(); // TODO don't
    } else {
      perfList = ctx.make(ctx.gc.getProp("grr.tabs.perfList.basicList").gr());
      for (StatSymbol s : filteredSorted) perfList.add(getEntry(s));
    }
    Node l = node.ctx.id("listPlace");
    l.clearCh();
    l.add(perfList);
  }
  
  public Node show() { return node; }
  public void onSelected() { g.runLater.add(() -> filterNode.focusMe()); }
  
  public String name() { return "perf report"; }
  public String serializeName() { return "perfsyms"; }
  
  private Node getEntry(StatSymbol stat) {
    Node n = ctx.make(g.gc.getProp("grr.tabs.perfList.entry").gr());
    n.ctx.id("amount").add(new StringNode(ctx, stat.fmtScore()));
    n.ctx.id("name").add(new StringNode(ctx, stat.name()));
    return new PerfListEntry(n, this, stat);
  }
  
  public static class PerfList extends LazyList {
    private final PerfListTab t;
    public PerfList(PerfListTab t) {
      super(t.node.ctx, Props.none());
      this.t = t;
    }
    public int lineHeight() { return t.g.gc.defFont.hi; }
    public int count() { return t.filteredSorted==null? 0 : t.filteredSorted.sz; }
    public Node calcAt(int i) { return t.getEntry(t.filteredSorted.get(i)); }
  }
  
  public static class PerfListEntry extends SelectableEntry {
    public final PerfListTab t;
    public final StatSymbol stat;
    
    public PerfListEntry(Node n, PerfListTab t, StatSymbol stat) {
      super(n);
      this.t = t;
      this.stat = stat;
    }
    
    public void onSelect(CT type) {
      if (!type.sel1) return;
      for (GrrTab<?> t : t.g.tabs) t.onSelectedStatSymbol(new Location(stat.sym.addr==-1? null : stat.sym.addr, stat.sym.name, null, null, null), stat);
    }
  }
}
