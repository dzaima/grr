package dz.utils;

import dz.debugger.Location;
import dz.gdb.*;
import dz.layouts.DebuggerLayout;
import dzaima.ui.eval.PNodeGroup;
import dzaima.ui.gui.config.GConfig;
import dzaima.ui.node.Node;
import dzaima.ui.node.ctx.Ctx;
import dzaima.ui.node.types.StringNode;
import dzaima.utils.Vec;

import java.nio.file.Paths;
import java.util.function.Function;

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
  
  
  
  public static Location readFrom(LocMode m, GdbFormat.GVal... vs) {
    boolean m2 = m==LocMode.M2;
    boolean m3 = m==LocMode.M3;
    return new Location(
      readFld(m2||m3?  "address"      :"addr", vs, GdbFormat.GVal::addr),
      readFld(m3?"name":m2?"func-name":"func", vs, c -> c.str().equals("??")? null : c.str()),
      readFld(m3?          "filename" :"file", vs, GdbFormat.GVal::str),
      readFld("fullname",                      vs, GdbFormat.GVal::str),
      readFld("line",                          vs, GdbFormat.GVal::asInt)
    );
  }
  
  static <T> T readFld(String k, GdbFormat.GVal[] vs, Function<GdbFormat.GVal, T> f) {
    for (GdbFormat.GVal c : vs) {
      GdbFormat.GVal r = c.get(k);
      if (r!=null) return f.apply(r);
    }
    return null;
  }
  
  public enum LocMode { M1, M2, M3 }
}
