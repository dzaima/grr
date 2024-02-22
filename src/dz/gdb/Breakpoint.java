package dz.gdb;

import dz.gdb.GdbFormat.GVal;

public class Breakpoint {
  public final int number;
  public boolean enabled;
  public String type;
  public String desc;
  
  public Breakpoint(GVal o) {
    number = o.getInt("number");
    update(o);
  }
  
  public void update(GVal o) {
    enabled = o.getStr("enabled").equals("y");
    type = o.getStr("type");
    String desc;
    switch (type) {
      case "breakpoint":
        String orig = o.optStr("original-location");
        if (orig==null || !orig.startsWith("*")) {
          if (o.has("func")) {
            desc = "function "+o.getStr("func");
            break;
          }
          if (orig!=null) {
            desc = "function "+orig;
            break;
          }
        }
        // fallthrough
      case "hw breakpoint":
        String func = o.optStr("func");
        desc = "instruction pointer at "+o.optStr("addr")+(func==null? "" : " in "+func)+(type.equals("hw breakpoint")? " (hw)" : "");
        break;
      
      case "hw watchpoint": desc = "write watch for "+o.getStr("what").substring(1); break;
      case "acc watchpoint": desc = "r/w watch for "+o.getStr("what").substring(1); break;
      case "read watchpoint": desc = "read watch for "+o.getStr("what").substring(1); break;
      
      default:
        desc = type;
    }
    if (o.getStr("disp").equals("del")) desc = "(temp) "+desc;
    this.desc = desc;
  }
}
