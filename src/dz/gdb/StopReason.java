package dz.gdb;

import dz.gdb.GdbFormat.GVal;
import dz.gdb.GdbProcess.Result;
import dzaima.utils.Vec;

import java.util.HashMap;

public class StopReason {
  public final Vec<Breakpoint> breakpoints;
  public final Vec<Watchpoint> watchpoints;
  
  public StopReason(Vec<Breakpoint> b, Vec<Watchpoint> w) {
    this.breakpoints = b;
    this.watchpoints = w;
  }
  
  public static StopReason of(Result o) {
    Vec<Breakpoint> breakpoints = new Vec<>();
    Vec<Watchpoint> watchpoints = new Vec<>();
    
    String[] ks = o.es.map(c -> c.k).toArray(new String[0]);
    GVal[] vs = o.es.map(c -> c.v).toArray(new GVal[0]);
    // GDB/MI for some FUCKING STUPID REASON decided that the way to do multiple reasons for stopping is
    // to just spew the properties of all in a single list, and the way to decide which is for which is by.. the order?
    // so you get like reason="breakpoint-hit",bkptno="1",[...],reason="access-watchpoint-trigger",value={old="0",new="1"}
    // additionally, "frame", "thread-id", and "stopped-threads" are properties for all reasons, not just the last one.
    // additionally, in some cases the `reason=` will be in the middle of its respective fields, e.g.
    // *stopped,hw-awpt={number="2",...},reason="access-watchpoint-trigger",value={new="22 '\\026'"},hw-awpt={number="5",...},reason="access-watchpoint-trigger",value={new="22 '\\026'"},frame=...
    Vec<HashMap<String, GVal>> reasons = new Vec<>();
    HashMap<String, GVal> currReason = new HashMap<>();
    boolean firstReason = true;
    for (int i = 0; i < ks.length; i++) {
      if (ks[i].equals("reason")) {
        if (firstReason) firstReason = false;
        else {
          reasons.add(currReason);
          currReason = new HashMap<>();
        }
      }
      currReason.put(ks[i], vs[i]);
    }
    reasons.add(currReason);
    
    for (HashMap<String, GVal> r : reasons) {
      if (!r.containsKey("reason")) continue;
      String reason = r.get("reason").str();
      switch (reason) {
        case "breakpoint-hit": {
          if (r.containsKey("bkptno")) {
            breakpoints.add(new Breakpoint(Integer.parseInt(r.get("bkptno").str())));
          }
          break;
        }
        case "access-watchpoint-trigger":
        case "read-watchpoint-trigger":
        case "watchpoint-trigger": {
          GVal values = r.get("value");
          
          String prev=null, curr=null;
          if (values instanceof GdbFormat.GKVList) {
            prev = values.optStr("old");
            curr = values.optStr("new");
            if (curr==null) curr = values.optStr("value");
          }
          if (curr==null) curr = "(unknown)";
          
          String watchKey = null;
          if (r.containsKey("wpt")) watchKey = "wpt";
          else if (r.containsKey("hw-awpt")) watchKey = "hw-awpt";
          else if (r.containsKey("hw-rwpt")) watchKey = "hw-rwpt";
          
          int no = -1;
          if (watchKey!=null) no = r.get(watchKey).optInt("number", -1);
          watchpoints.add(new Watchpoint(no, prev, curr));
          break;
        }
      }
    }
    
    return new StopReason(breakpoints, watchpoints);
  }
  
  public static class Breakpoint {
    public final int no;
    private Breakpoint(int no) { this.no = no; }
  }
  
  public static class Watchpoint {
    public final int no;
    public final String prev, curr; // if prev==null, read; curr is always non-null
    private Watchpoint(int no, String prev, String curr) { this.no = no; this.prev = prev; this.curr = curr; }
  }
}
