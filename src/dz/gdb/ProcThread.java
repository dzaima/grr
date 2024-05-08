package dz.gdb;

import dz.debugger.Location;
import dzaima.utils.*;

import java.util.function.*;

public class ProcThread {
  public final Executable exe;
  protected final String[] arg;
  
  public ProcThread(Executable exe, String num) {
    this.exe = exe;
    assert num!=null;
    arg = new String[]{"--thread", num};
  }
  
  private ProcThread(Executable exe) {
    this.exe = exe;
    arg = null;
  }
  public static ProcThread makeCurrThread(Executable exe) { return new ProcThread(exe); }
  
  public void stacktrace(boolean args, int start, int end, Consumer<Vec<ProcThread.StackFrame>> got) { // end==-1 for all
    exe.d._stacktrace(this, args, start, end, got);
  }
  public void stackHeight(IntConsumer got) {
    exe.d._stackHeight(this, got);
  }
  
  
  public static class StackFrame {
    public final int level;
    @NotNull public final Location l;
    public final boolean afterCall;
    public Vec<Arg> args;
    
    public StackFrame(int level, Location l, boolean afterCall) {
      this.level = level;
      this.l = l;
      this.afterCall = afterCall;
    }
    
    public String toString() {
      StringBuilder b = new StringBuilder().append(level).append(": ").append(l);
      if (args!=null) {
        b.append(" (");
        boolean first = true;
        for (Arg a : args) {
          if (first) first = false;
          else b.append(", ");
          b.append(a);
        }
        b.append(')');
      }
      return b.toString();
    }
    
    public boolean equalLocation(StackFrame o) {
      return l.equals(o.l);
    }
  }
  public static class Arg {
    public final String type, name, val;
    public Arg(String type, String name, String val) {
      this.type = type;
      this.name = name;
      this.val = val;
    }
    
    public String toString() {
      return val==null? type+" "+name : type+" "+name+" = "+val;
    }
  }
  
  public String toString() {
    return "thread("+(arg==null? "current" : "gdbID="+arg[1])+")";
  }
}
