package dz.utils;

import dz.debugger.Location;
import dz.gdb.ProcThread;
import dz.layouts.DebuggerLayout;
import dzaima.ui.eval.PNodeGroup;
import dzaima.ui.gui.config.GConfig;
import dzaima.ui.node.Node;
import dzaima.ui.node.ctx.Ctx;
import dzaima.ui.node.types.StringNode;
import dzaima.utils.Vec;

import java.nio.file.Paths;

public class LocationUtils {
  public static Node node(Ctx ctx, String prefix, Location l, Vec<ProcThread.Arg> args) {
    return node("grr.frame.all", ctx, prefix, l, args);
  }
  
  public static Node node(Ctx ctx, String prefix, ProcThread.StackFrame f) {
    return node("grr.frame.all", ctx, prefix, f.l, f.args);
  }
  
  public static Node node(String kind, Ctx ctx, String prefix, Location l, Vec<ProcThread.Arg> args) {
    GConfig gc = ctx.gc;
    Node n = ctx.make(gc.getProp(kind).gr());
    
    Node cn = n.ctx.idNullable("prefix");
    if (cn!=null) cn.add(new StringNode(ctx, prefix==null || prefix.isEmpty()? "" : prefix+" "));
    
    cn = n.ctx.idNullable("addr");
    if (cn!=null) cn.add(new StringNode(ctx, l.addr==null? "0x??" : "0x"+ DebuggerLayout.hexLong(l.addr)));
    
    n.ctx.id("func").add(new StringNode(ctx, l.sym==null? "??" : l.sym));
    
    if (l.shortFile!=null) {
      Node file = ctx.make(gc.getProp("grr.stack.ifFile").gr());
      String filePath = l.shortFile;
      filePath = Paths.get(filePath).normalize().toString();
      file.ctx.id("file").add(new StringNode(ctx, filePath));
      if (l.line!=null && l.line!=-1) file.ctx.id("line").add(new StringNode(ctx, ":"+ l.line));
      n.ctx.id("ifFile").add(file);
    }
    if (args!=null) {
      Node argList = n.ctx.id("args");
      PNodeGroup sepP = gc.getProp("grr.stack.argSep").gr();
      boolean first = true;
      for (ProcThread.Arg a : args) {
        if (first) first = false;
        else argList.add(ctx.make(sepP));
        boolean value = a.val != null;
        boolean name = a.name != null;
        Node arg = ctx.make(gc.getProp(value? "grr.stack.argVal" : name? "grr.stack.argType" : "grr.stack.argOnlyType").gr());
        if (value||name) arg.ctx.id("name").add(new StringNode(ctx, a.name==null? "??" : a.name));
        if (value) arg.ctx.id("value").add(new StringNode(ctx, a.val));
        else       arg.ctx.id("type" ).add(new StringNode(ctx, a.type==null? "??" : a.type));
        argList.add(arg);
      }
    }
    return n;
  }
}
