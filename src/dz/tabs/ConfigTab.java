package dz.tabs;

import dz.Main;
import dz.layouts.GdbLayout;
import dz.stat.StatGlobal;
import dz.ui.AsmListNode;
import dzaima.ui.node.Node;
import dzaima.ui.node.prop.Prop;
import dzaima.ui.node.types.*;
import dzaima.ui.node.types.editable.EditNode;
import dzaima.ui.node.types.tabs.*;
import dzaima.utils.Tools;

import java.util.HashMap;

public class ConfigTab extends GrrTab<GdbLayout> implements SerializableTab {
  private final Node node;
  private final RadioNode disp, fmt;
  private final CheckboxNode raw;
  private final EditNode rawPad;
  
  public ConfigTab(GdbLayout g) {
    super(g);
    node = ctx.make(ctx.gc.getProp("grr.tabs.config.ui").gr());
    
    disp = (RadioNode) node.ctx.id("aDisp");
    disp.setFn(s -> {
      switch (s.getProp("id").val()) { default: throw new IllegalStateException();
        case "aDisp": g.setAsmConfig(g.asmConfig.with(AsmListNode.AddrDisp.NONE)); break;
        case "aDispK": g.setAsmConfig(g.asmConfig.with(AsmListNode.AddrDisp.KNOWN)); break;
        case "aDispA": g.setAsmConfig(g.asmConfig.with(AsmListNode.AddrDisp.ALL)); break;
      }
    });
    
    fmt = (RadioNode) node.ctx.id("aFmt");
    fmt.setFn(s -> {
      switch (s.getProp("id").val()) { default: throw new IllegalStateException();
        case "aFmt": g.setAsmConfig(g.asmConfig.with(AsmListNode.AddrFmt.ADDR)); break;
        case "aFmtD": g.setAsmConfig(g.asmConfig.with(AsmListNode.AddrFmt.DEC_OFF)); break;
        case "aFmtH": g.setAsmConfig(g.asmConfig.with(AsmListNode.AddrFmt.HEX_OFF)); break;
      }
    });
    
    raw = (CheckboxNode) node.ctx.id("raw");
    raw.setFn(b -> g.setAsmConfig(g.asmConfig.withRaw(b)));
    
    CheckboxNode cacheJIT = (CheckboxNode) node.ctx.id("cacheJIT");
    cacheJIT.set(g.cache.shouldCacheJIT);
    cacheJIT.setFn(g.cache::setShouldCacheJIT);
    
    rawPad = (EditNode) node.ctx.id("rawPad");
    rawPad.setFn((a,mod) -> {
      if (!a.done) return false;
      int t = Main.parseInt(rawPad, g.asmConfig.rawPad);
      g.setAsmConfig(g.asmConfig.withPad(Tools.constrain(t, 1, 99)));
      return true;
    });
    
    modeList = ctx.make(g.gc.getProp("grr.tabs.config.uiModes").gr());
  }
  
  private final Node modeList;
  private RadioNode modeBase;
  private final HashMap<RadioNode, StatGlobal<?>> modeMap = new HashMap<>();
  private final HashMap<StatGlobal<?>, RadioNode> modeMapI = new HashMap<>();
  private int statSources = 0;
  public void onAddedStatSource(StatGlobal<?> d) {
    statSources++;
    Node n = ctx.make(g.gc.getProp("grr.tabs.config.modeLine").gr());
    
    Node textPlace = n.ch.get(0);
    assert ((StringNode) textPlace.ch.get(1)).s.equals("placeholder");
    textPlace.replace(1, new StringNode(ctx, d.name()));
    
    RadioNode r = (RadioNode) n.ctx.id("r");
    if (modeBase == null) {
      modeBase = r;
      modeBase.setFn(s -> g.setCurrentStat(modeMap.get(s)));
    } else {
      r.setBase(modeBase);
    }
    modeMap.put(r, d);
    modeMapI.put(d, r);
    
    modeList.ctx.id("more").add(n);
    if (statSources==2) node.ctx.id("modeListPlace").add(modeList);
  }
  
  public void onSelectedStatSource() {
    if (g.statGlobal==null) return;
    modeMapI.get(g.statGlobal).set();
    Node ms = node.ctx.id("modeSpecific");
    ms.clearCh();
    Node n = g.statGlobal.activate(node.ctx, g::refreshCurrentStat);
    if (n!=null) ms.add(n);
  }
  
  
  
  public static Tab deserialize(GdbLayout g, HashMap<String, Prop> p) {
    ConfigTab t = new ConfigTab(g);
    g.runLater.add(() -> {
      Prop d = p.get("d");
      Prop f = p.get("f");
      Prop r = p.get("r");
      Prop rp= p.get("rp");
      AsmListNode.AddrDisp cd = d==null? AsmListNode.AddrDisp.KNOWN : AsmListNode.AddrDisp.valueOf(d.val());
      AsmListNode.AddrFmt cf = f==null? AsmListNode.AddrFmt.DEC_OFF : AsmListNode.AddrFmt.valueOf(f.val());
      boolean cr = r!=null && r.b();
      int crp = rp==null? 8 : rp.i();
      g.setAsmConfig(new AsmListNode.AsmConfig(cd, cf, cr, crp));
    });
    return t;
  }
  
  public void onAsmConfig(AsmListNode.AsmConfig config) {
    switch (config.disp) {
      case NONE: disp.quietSetTo("aDisp"); break;
      case KNOWN: disp.quietSetTo("aDispK"); break;
      case ALL: disp.quietSetTo("aDispA"); break;
    }
    switch (config.fmt) {
      case ADDR: fmt.quietSetTo("aFmt"); break;
      case DEC_OFF: fmt.quietSetTo("aFmtD"); break;
      case HEX_OFF: fmt.quietSetTo("aFmtH"); break;
    }
    raw.set(config.raw);
    rawPad.removeAll();
    rawPad.append(String.valueOf(config.rawPad));
  }
  
  public Node show() { return node; }
  public String name() { return "config"; }
  public String serializeName() { return "config"; }
  public String serialize() {
    return "d="+g.asmConfig.disp+" f="+g.asmConfig.fmt;
  }
}
