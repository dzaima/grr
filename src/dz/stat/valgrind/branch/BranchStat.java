package dz.stat.valgrind.branch;

import dz.Main;
import dz.stat.*;
import dz.stat.valgrind.CachegrindData;
import dzaima.ui.node.Node;
import dzaima.ui.node.ctx.Ctx;
import dzaima.ui.node.types.*;
import dzaima.ui.node.types.editable.EditNode;
import dzaima.utils.Vec;

import java.util.HashMap;

public class BranchStat extends StatGlobal<BranchStatSymbol> {
  public HashMap<BasicSymbol, BranchStatSymbol> map = new HashMap<>();
  public HashMap<StatGlobal.BasicSymbol, BranchStatSymbol> groupToBasic() { return map; }
  public String name() { return "branch"; }
  
  public InsDisp disp = InsDisp.P_INS;
  public boolean direct=true, indirect=true;
  
  public int minToHighlight = 100;
  public enum InsDisp {
    N_TOT("bWc"), N_MISS("bWm"), P_INS("bWpi"), P_FUNC("bWpf"), P_GLOBAL("bWpg");
    final String id; InsDisp(String id) { this.id=id; }
  }
  
  private Node node;
  public Node activate(Ctx ctx, Runnable onRefresh) {
    Runnable refresh = () -> {
      configUpdated();
      onRefresh.run();
    };
    node = ctx.make(ctx.gc.getProp("grr.tabs.config.uiBranch").gr());
    RadioNode dispRadio = (RadioNode) node.ctx.id("bWc");
    dispRadio.quietSetTo(disp.id);
    dispRadio.setFn(s -> {
      String id = s.getProp("id").val();
      disp = Vec.of(InsDisp.values()).linearFind(c -> c.id.equals(id));
      refresh.run();
    });
    
    CheckboxNode bDir = (CheckboxNode) node.ctx.id("bTD"); bDir.set(direct);
    CheckboxNode bInd = (CheckboxNode) node.ctx.id("bTI"); bInd.set(indirect);
    bDir.setFn(b -> {   direct = b; refresh.run(); });
    bInd.setFn(b -> { indirect = b; refresh.run(); });
    
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
  
  public void onSelection(Vec<StatInstr> is) {
    if (node==null) return;
    long sBc=0, sBcm=0, sBi=0, sBim=0;
    for (StatInstr c : is) {
      sBc += ((BranchStatInstr) c).vs[CachegrindData.Bc];
      sBcm+= ((BranchStatInstr) c).vs[CachegrindData.Bcm];
      sBi += ((BranchStatInstr) c).vs[CachegrindData.Bi];
      sBim+= ((BranchStatInstr) c).vs[CachegrindData.Bim];
    }
    node.ctx.id("sBc") .replace(0, new StringNode(node.ctx, Long.toUnsignedString(sBc)));
    node.ctx.id("sBcm").replace(0, new StringNode(node.ctx, Long.toUnsignedString(sBcm)));
    node.ctx.id("sBi") .replace(0, new StringNode(node.ctx, Long.toUnsignedString(sBi)));
    node.ctx.id("sBim").replace(0, new StringNode(node.ctx, Long.toUnsignedString(sBim)));
    node.ctx.id("fBc") .replace(0, new StringNode(node.ctx, sBc==0? "" : " ("+StatGlobal.fmtPercent(sBcm*1.0/sBc)+")"));
    node.ctx.id("fBi") .replace(0, new StringNode(node.ctx, sBi==0? "" : " ("+StatGlobal.fmtPercent(sBim*1.0/sBi)+")"));
  }
  
  public void configUpdated() {
    long globalSum = 0;
    double scoreSum = 0;
    for (BranchStatSymbol c : map.values()) {
      globalSum+= c.configUpdated();
      scoreSum+= c.score;
    }
    float globalMul = globalSum==0? 0 : 1f/globalSum;
    double scoreMul = scoreSum==0? 0 : 1/scoreSum;
    boolean divGlobal = disp == InsDisp.P_GLOBAL;
    for (BranchStatSymbol c : map.values()) {
      if (divGlobal) c.dispSumMul = globalMul;
      c.score*= scoreMul;
    }
  }
}
