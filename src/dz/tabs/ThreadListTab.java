package dz.tabs;

import dz.gdb.Executable.ThreadState;
import dz.gdb.ProcThread;
import dz.layouts.DebuggerLayout;
import dz.ui.SelectableEntry;
import dz.ui.SelectableEntry.CT;
import dz.utils.*;
import dzaima.ui.gui.io.*;
import dzaima.ui.node.Node;
import dzaima.ui.node.types.tabs.SerializableTab;
import dzaima.utils.*;

import java.util.HashSet;

public class ThreadListTab extends GrrTab<DebuggerLayout> implements SerializableTab {
  public final Node node, list;
  public ThreadListTab(DebuggerLayout g) {
    super(g);
    node = ctx.make(ctx.gc.getProp("grr.tabs.stack.ui").gr());
    list = node.ctx.id("list");
    drClear = new DelayedRun(g);
  }
  
  public Node show() { return node; }
  public void onSelected() { focusSelectedEntry(list); }
  
  public String name() { return "threads"; }
  public String serializeName() { return "threads"; }
  
  ProcThread activeEntry;
  final DelayedRun drClear;
  long shownListCtr;
  public void onStatusChange() {
    if (g.d.status().paused()) {
      drClear.set(list::clearCh);
      g.dst.threads(r -> {
        drClear.cancel();
        list.clearCh();
        if (r==null) return;
        int c1=0, c2=0;
        for (ThreadState thr : r) {
          c1 = Math.max(c1, thr.gdbID.length());
          c2 = Math.max(c2, thr.desc.length());
        }
        c1++;
        c2 = Math.min(c2, 20);
        ThreadItemNode curr = null;
        for (ThreadState thr : r) {
          String did = thr.gdbID;
          boolean allNum = true;
          for (int i = 0; i < did.length(); i++) {
            if (did.charAt(i)<'0' || did.charAt(i)>'9') { allNum = false; break; }
          }
          if (allNum) did = "#"+did;
          String name = DebuggerLayout.padL(did,' ',c1) + " " + DebuggerLayout.padL(thr.desc, ' ', c2);
          ThreadItemNode n = new ThreadItemNode(LocationUtils.node(ctx, name, thr.frame), this, thr, thr.tid);
          list.add(n);
          if (thr.current) {
            curr = n;
            activeEntry = thr.obj;
          }
        }
        shownListCtr++;
        final long slc0 = shownListCtr;
        if (g.d.isRR()) {
          HashSet<Long> alreadyThere = new HashSet<>();
          for (ThreadState thr : r) alreadyThere.add(thr.tid);
          g.loadRRDump(d -> {
            if (slc0 != shownListCtr) return;
            Vec<Long> groupAll = r.map(c -> d.gid(c.tid));
            long group = groupAll.sz==0? -1 : groupAll.get(0);
            if (groupAll.linearFind(c -> c!=group)!=null) {
              Log.warn("rr processes", "all running threads not on one gid? "+groupAll);
            }
            Vec<Long> v = Vec.ofCollection(d.perThread.keySet());
            v.sort((a, b) -> {
              int c = Boolean.compare(group!=d.gid(a), group!=d.gid(b));
              if (c!=0) return c;
              return Long.compare(a, b);
            });
            v.forEach((tid) -> {
              if (alreadyThere.contains(tid)) return;
              String msg = group==d.gid(tid)? "(not active) " : "(not in this process) ";
              list.add(new ThreadItemNode(LocationUtils.node(ctx, msg+tid, DebuggerLayout.BAD_STACKFRAME), this, null, tid));
            });
          });
        }
        if (curr!=null) curr.select(CT.QUIET);
      });
    }
  }
  
  public static class ThreadItemNode extends SelectableEntry {
    public final ThreadState thr;
    public final long tid;
    public final ThreadListTab tab;
    
    public ThreadItemNode(Node n, ThreadListTab tab, ThreadState thr, long tid) {
      super(n);
      this.tab = tab;
      this.thr = thr;
      this.tid = tid;
    }
    
    public void onClick(CT type) {
      if (type!=CT.QUIET && tab.g.d.status().paused()) {
        if (thr!=null) tab.g.selectThread(thr.obj);
        else if (tab.g.d.isRR()) tab.g.forTabs(TimelineTab.class, t -> t.showThreadByID(tid));
      }
    }
    
    public boolean keyF(Key key, int scancode, KeyAction a) {
      switch (gc.keymap(key, a, "grr.tabs.threads")) {
        case "selectThread":
          if (tab.g.atPausedMainState()) tab.g.mainState.exe.selectThread(thr, b -> {
            if (!b) return;
            tab.activeEntry = thr.obj;
            tab.list.mRedraw();
          });
          return true;
      }
      return super.keyF(key, scancode, a);
    }
    
    public int bgColCalc() {
      if (thr!=null && tab.activeEntry.equals(thr.obj) && !isFocused()) return gc.getProp("grr.list.bgActive").col();
      return super.bgColCalc();
    }
  }
}
