package dz.tabs;

import dz.gdb.GdbFormat;
import dz.gdb.GdbProcess.OutWhere;
import dz.layouts.GdbLayout;
import dz.ui.ReplFieldNode;
import dzaima.ui.node.Node;
import dzaima.ui.node.prop.Prop;
import dzaima.ui.node.types.ScrollNode;
import dzaima.ui.node.types.editable.code.CodeAreaNode;
import dzaima.ui.node.types.tabs.*;
import dzaima.utils.Vec;

import java.util.HashMap;

public class GdbTab extends GrrTab<GdbLayout> implements SerializableTab {
  public final Node node;
  public final CodeAreaNode code;
  public final ReplFieldNode input;
  public enum Mode { DEBUGGER, RAW, PROCESS }
  public final Mode mode;
  
  public GdbTab(GdbLayout g, Mode mode) {
    super(g);
    this.mode = mode;
    node = ctx.make(ctx.gc.getProp("grr.tabs.gdb.ui").gr());
    code = (CodeAreaNode) node.ctx.id("code");
    code.setLang(g.gc.langs().fromName("C"));
    input = (ReplFieldNode) node.ctx.id("input");
    input.onRun(s -> {
      switch (mode) {
        case RAW: g.runCommand(true, s, ()->{}); break;
        case DEBUGGER: append("  "+s+"\n"); g.runCommand(false, s, ()->{}); break;
        case PROCESS:
          String full = s+"\n";
          append(full);
          g.d.p.sendProcessStdin(full);
          break;
      }
    });
  }
  
  public static long parseNum(String s) {
    if (s.startsWith("0x")) return GdbFormat.parseHex(s);
    else return Long.parseLong(s);
  }
  
  private final Vec<String> toAppend = new Vec<>();
  public void append(String s) {
    toAppend.add(s);
    if (open) processAppend();
  }
  private void processAppend() {
    if (toAppend.sz <= 0) return;
    StringBuilder b = new StringBuilder();
    for (String c : toAppend) b.append(c.replace("\t", "    "));
    code.append(b.toString());
    ((ScrollNode) code.p).toYE(true);
    toAppend.clear();
  }
  
  public Node show() { processAppend(); return node; }
  public void onSelected() { input.focusMe(); }
  
  public String name() {
    switch (mode) { default: throw new IllegalStateException();
      case RAW: return "raw gdb";
      case DEBUGGER: return "gdb";
      case PROCESS: return "process";
    }
  }
  
  public String serializeName() { return "log"; }
  public String serialize() { return "mode="+mode.toString().toLowerCase(); }
  public static Tab deserialize(GdbLayout g, HashMap<String, Prop> p) {
    return new GdbTab(g, Mode.valueOf(p.get("mode").val().toUpperCase()));
  }
  
  public void onGdbPrint(String s, OutWhere b) {
    switch (mode) {
      case PROCESS:
        if (b==OutWhere.INF_STDERR || b==OutWhere.INF_STDOUT) append(s);
        break;
      case DEBUGGER:
        append(s);
        break;
    }
  }
}
