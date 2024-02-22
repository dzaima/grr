package dz.stat.valgrind.cache;

import dz.Main;
import dz.stat.*;
import dz.stat.valgrind.CachegrindData;
import dzaima.ui.node.Node;
import dzaima.ui.node.ctx.Ctx;
import dzaima.ui.node.types.*;
import dzaima.ui.node.types.editable.EditNode;
import dzaima.utils.Vec;

import java.util.HashMap;

public class CacheStat extends StatGlobal<CacheStatSymbol> {
  public HashMap<BasicSymbol, CacheStatSymbol> map = new HashMap<>();
  public HashMap<StatGlobal.BasicSymbol, CacheStatSymbol> groupToBasic() { return map; }
  public String name() { return "cache"; }
  
  public InsDisp disp = InsDisp.L1M;
  public Cache cache = Cache.D_RW;
  public int minToHighlight = 100;
  
  public enum InsDisp {
    ACCESS("la"), L1M("l1"), LLM("ll");
    final String id; InsDisp(String id) { this.id=id; }
  }
  public enum Cache {
    I_R("cI", CachegrindData.Ir),
    D_R("cDR", CachegrindData.Dr),
    D_W("cDW", CachegrindData.Dw),
    D_RW("cDRW", CachegrindData.Drw);
    final String id;
    final int acc;
    Cache(String id, int acc) { this.id=id; this.acc = acc; }
  }
  
  private Node node;
  public Node activate(Ctx ctx, Runnable onRefresh) {
    Runnable refresh = () -> {
      configUpdated();
      onRefresh.run();
    };
    node = ctx.make(ctx.gc.getProp("grr.tabs.config.uiCache").gr());
    
    RadioNode cacheRadio = (RadioNode) node.ctx.id("cI");
    cacheRadio.quietSetTo(cache.id);
    cacheRadio.setFn(s -> {
      String id = s.getProp("id").val();
      cache = Vec.of(Cache.values()).linearFind(c -> c.id.equals(id));
      refresh.run();
    });
    
    
    RadioNode dispRadio = (RadioNode) node.ctx.id("la");
    dispRadio.quietSetTo(disp.id);
    dispRadio.setFn(s -> {
      String id = s.getProp("id").val();
      disp = Vec.of(InsDisp.values()).linearFind(c -> c.id.equals(id));
      refresh.run();
    });
    
    
    EditNode hMin = (EditNode) node.ctx.id("highlightMin");
    hMin.append(String.valueOf(minToHighlight));
    hMin.setFn((a,mod) -> {
      if (!a.done) return false;
      minToHighlight = Main.parseInt(hMin, minToHighlight);
      refresh.run();
      return true;
    });
    return node;
  }
  
  private Vec<StatInstr> lastSelection;
  public void onSelection(Vec<StatInstr> is) {
    if (node==null) return;
    lastSelection = is;
    long sa=0, sl1=0, sll=0;
    for (StatInstr c : is) {
      sa+=  ((CacheStatInstr) c).acc();
      sl1+= ((CacheStatInstr) c).l1m();
      sll+= ((CacheStatInstr) c).llm();
    }
    node.ctx.id("sa" ).replace(0, new StringNode(node.ctx, Long.toUnsignedString(sa)));
    node.ctx.id("s1m").replace(0, new StringNode(node.ctx, Long.toUnsignedString(sl1)));
    node.ctx.id("slm").replace(0, new StringNode(node.ctx, Long.toUnsignedString(sll)));
    node.ctx.id("f1m") .replace(0, new StringNode(node.ctx, sa==0? "" : " ("+StatGlobal.fmtPercent(sl1*1.0/sa)+")"));
    node.ctx.id("flm") .replace(0, new StringNode(node.ctx, sa==0? "" : " ("+StatGlobal.fmtPercent(sll*1.0/sa)+")"));
  }
  
  
  public void configUpdated() {
    double scoreSum = 0;
    for (CacheStatSymbol c : map.values()) {
      c.configUpdated();
      scoreSum+= c.score;
    }
    double scoreMul = scoreSum==0? 0 : 1/scoreSum;
    for (CacheStatSymbol c : map.values()) c.score*= scoreMul;
    
    if (lastSelection!=null) onSelection(lastSelection);
  }
}
