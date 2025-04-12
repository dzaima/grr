package dz.tabs;

import dz.gdb.GdbFormat.GVal;
import dz.general.arch.Arch;
import dz.layouts.DebuggerLayout;
import dz.ui.SelectableEntry;
import dz.utils.Utils;
import dzaima.ui.eval.PNodeGroup;
import dzaima.ui.gui.io.*;
import dzaima.ui.node.Node;
import dzaima.ui.node.prop.Props;
import dzaima.ui.node.types.*;
import dzaima.ui.node.types.tabs.SerializableTab;
import dzaima.utils.*;

import java.util.HashMap;

public class RegisterTab extends GrrTab<DebuggerLayout> implements SerializableTab {
  public final Node node, list;
  
  public RegisterTab(DebuggerLayout g) {
    super(g);
    node = ctx.make(ctx.gc.getProp("grr.tabs.registers.ui").gr());
    list = node.ctx.id("list");
  }
  
  public Node show() {
    onStatusChange();
    return node;
  }
  public void onSelected() { focusSelectedEntry(list); }
  
  Arch.Registers registers;
  Vec<String> selectedGroups;
  Vec<Arch.RegInfo> shown;
  
  public IntVec shownRegs() {
    IntVec r = new IntVec();
    for (Arch.RegInfo c : shown) c.addNeeded(r);
    return r;
  }
  
  public void onNewArch() {
    g.requestArchRegs(r -> {
      if (r==null) {
        registers = null;
        selectedGroups = null;
      } else {
        registers = r;
        selectedGroups = g.arch.defaultEnabledGroups();
      }
      updateRegs();
    });
  }
  public void onStatusChange() {
    if (registers==null) {
      onNewArch();
      return;
    }
    if (g.d.status().paused() && open) updateRegs();
  }
  
  private void updateRegs() {
    if (registers==null) {
      list.clearCh();
      return;
    }
    shown = new Vec<>();
    for (String c : selectedGroups) {
      Vec<Arch.RegInfo> arr = registers.groupMap.get(c);
      if (arr!=null) shown.addAll(arr);
    }
    
    IntVec regs = shownRegs();
    String[] vs = new String[regs.sz];
    for (int i = 0; i < vs.length; i++) vs[i] = String.valueOf(regs.get(i));
    g.d.p.cmd("-data-list-register-values", "--skip-unavailable", "r", vs).ds().run(r -> {
      list.clearCh();
      PNodeGroup reg = ctx.gc.getProp("grr.tabs.registers.reg").gr();
      HashMap<Integer, GVal> valMap = new HashMap<>();
      if (r.type.ok()) {
        GVal arr = r.get("register-values");
        for (GVal o : arr.vs()) valMap.put(Integer.parseInt(o.getStr("number")), o);
      }
      Props colD = Props.of("color", ctx.gc.getCfgProp("grr.colors.valDefined"));
      Props colH = Props.of("color", ctx.gc.getCfgProp("grr.colors.valHalfDefined"));
      Props colU = Props.of("color", ctx.gc.getCfgProp("grr.colors.valUndefined"));
      
      for (Arch.RegInfo c : shown) {
        String name = Utils.padL(c.name, ' ', c.namePad());
        Node v = ctx.make(reg);
        
        v.ctx.id("name").add(new StringNode(ctx, name));
        Arch.RegRes rB = c.get(valMap);
        Arch.RegRes rD = c.knownDefined()? c.getDefined(valMap) : null;
        String raw = rB.raw;
        if (rD==null || rB.bytes==null || rD.bytes==null || rB.length!=rD.length) {
          v.ctx.id("value").add(new StringNode(ctx, raw));
        } else {
          TextNode t = new TextNode(ctx, Props.none());
          int gl = Arch.groupLen(rB.length/4);
          for (int i = 0; i < rB.length/4; i++) {
            int sh = 4 - (i&1)*4;
            int vB = (rB.bytes[i>>1]>>sh) & 0xf;
            int vD = (rD.bytes[i>>1]>>sh) & 0xf;
            TextNode colored = new TextNode(ctx, vD == 0? colD : vD == 15? colU : colH);
            colored.add(new StringNode(ctx, (i!=0 && i%gl==0? " " : "")+Integer.toHexString(vB)));
            t.add(colored);
          }
          v.ctx.id("value").add(t);
        }
        list.add(new RegisterNode(v, this, c, raw));
      }
    });
  }
  
  public String name() { return "registers"; }
  public String serializeName() { return "registers"; }
  
  public static class RegisterNode extends SelectableEntry {
    public final RegisterTab tab;
    public final Arch.RegInfo reg;
    public final String toCopy;
    
    public RegisterNode(Node n, RegisterTab tab, Arch.RegInfo reg, String toCopy) {
      super(n);
      this.tab = tab;
      this.reg = reg;
      this.toCopy = toCopy;
    }
    
    public void onSelect(CT type) { }
    
    public boolean keyF(Key key, int scancode, KeyAction a) {
      switch (gc.keymap(key, a, "grr.entry")) {
        default: return false;
        case "copy":
          ctx.win().copyString(toCopy);
          return true;
      }
    }
    
    public void hoverS() { for (GrrTab<?> c : tab.g.tabs) c.onRegHover(reg, true); }
    public void hoverE() { for (GrrTab<?> c : tab.g.tabs) c.onRegHover(reg, false); }
  }
}
