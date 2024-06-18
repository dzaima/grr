package dz.gdb;

import dz.debugger.Location;
import dz.gdb.Executable.*;
import dz.gdb.GdbFormat.GVal;
import dz.gdb.GdbProcess.Result;
import dz.gdb.ProcThread.*;
import dz.utils.LocationUtils;
import dzaima.utils.*;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.*;
import java.util.regex.*;

public class Dbi {
  public final GdbProcess p;
  public Executable curr;
  
  public Dbi(GdbProcess p) {
    this.p = p;
    if (p.isRR) curr = makeExe(null);
  }
  
  public void tick() {
    if (status()==Status.KILLED) return;
    p.tick();
  }
  
  public void addOutputListener(BiConsumer<String, GdbProcess.OutWhere> listener) { p.onOutput.add(listener); }
  public void addRawLogListener(BiConsumer<String, Boolean> listener) { p.onRawOut.add(listener); } // boolean is whether this is input
  public void addBreakpointUpdateListener(Runnable listener) { p.onModifiedBreakpoints.add(listener); }
  public void addArchListener(Consumer<String> listener) { p.onNewArch.add(listener); }
  
  public Executable makeExe(Path p) {
    return new Executable(this, p);
  }
  
  public Executable getRRExe() {
    assert p.isRR;
    return curr;
  }
  
  public void toExe(Executable e, Consumer<Boolean> got) {
    if (curr!=e) {
      curr = e;
      Status prevStatus = p.status;
      p.setStatus(Status.LOADED); // set ahead-of-time to allow ahead-of-time follow-up things to run; TODO don't, and move assertLoaded() etc checks to when they're actually ran
      p.cmd("-file-exec-and-symbols", e.p.toString()).run(r -> {
        boolean ok = r.type.ok();
        if (ok) {
          p.setStatus(Status.LOADED);
          notifyNewArchitecture();
        } else {
          p.setStatus(prevStatus);
        }
        got.accept(ok);
      });
    }
  }
  public void notifyNewArchitecture() {
    getArchitecture(a -> {
      if (a!=null) for (Consumer<String> c : p.onNewArch) c.accept(a);
    });
  }
  public void getArchitecture(Consumer<String> arch) {
    p.consoleCmd("show architecture").ds().runWithPrefix("~\"The target architecture is set to", (r2, match) -> {
      getArch: if (match!=null) {
        int i = match.indexOf('"', match.indexOf(" (currently"));
        if (i==-1) break getArch;
        int j = match.indexOf("\\\"", i+1);
        if (j==-1) break getArch;
        arch.accept(match.substring(i+1, j));
        return;
      }
      arch.accept(null);
    });
  }
  public void assumeExe(Executable e) {
    curr = e;
    p.setStatus(Status.LOADED);
  }
  
  public void exit() {
    curr = null;
    Runnable r = () -> p.cmd("-gdb-exit").expect();
    if (status().running()) p.breakAll(r);
    else r.run();
  }
  
  public boolean isRR() {
    return p.isRR;
  }
  
  public StopReason stopReason() { return p.stopReason; }
  
  
  
  public enum Status {
    KILLED, // shut down
    NONE, // no executable known
    LOADED, // executable loaded, but not started
    PAUSED, // executable started, execution paused
    RUNNING; // running
    public boolean hasexe() { return this!=KILLED && this!=NONE; }
    public boolean started() { return this==PAUSED || this==RUNNING; }
    public boolean paused() { return this==PAUSED; }
    public boolean running() { return this==RUNNING; }
  }
  public Status status() { return p.status; }
  public boolean queueEmpty() { return p.queueEmpty(); }
  
  
  
  public enum ExecMode { FORWARD, BACKWARD, UNKNOWN }
  public ExecMode lastRunMode() { return p.lastRunMode; }
  
  
  
  private String o(boolean b, String s) { return b? s : null; }
  private Object[] o(boolean b, Object... s) { return b? s : null; }
  void _open(String[] args, Runnable after) {
    if (args!=null) p.cmdList("-exec-arguments", Vec.ofNew(args)).ds().run();
    p.cmd("-exec-run", "--start").run(r -> {
      if (r.type.ok()) p.setStatus(Status.PAUSED);
      if (after!=null) after.run();
    });
  }
  
  void _continue(boolean rev, Runnable after) {
    p.cmd("-exec-continue", o(rev,"--reverse")).revExec(rev).run(after);
  }
  
  void _breakAll(Runnable after) {
    p.breakAll(after);
  }
  
  void _step(int n, boolean lines, boolean over, Consumer<Boolean> after) {
    if (n == 0) return;
    assert status().paused();
    int a = Math.abs(n);
    p.cmd("-exec-"+(over? "next" : "step")+(lines? "" : "-instruction"), o(n<0,"--reverse"), o(a!=1,a)).revExec(n<0).run(b -> {
      if (after!=null) after.accept(b.type.ok());
    });
  }
  
  void _finish(boolean rev, Runnable after) {
    p.cmd("-exec-finish", o(rev,"--reverse")).revExec(rev).run(after);
  }
  
  public void _listThreads(ProcThread t0, Consumer<Vec<ThreadState>> got) {
    p.cmd("-thread-info").ds().run(r -> {
      GVal currID0 = r.get("current-thread-id");
      String currID = currID0==null? null : currID0.str();
      if (r.type.ok()) got.accept(r.get("threads").vs().map(c -> {
        String gdbID = c.getStr("id");
        String globalID = c.getStr("target-id");
        long tid = -1;
        if (globalID!=null) {
          String[] ps = globalID.split("\\.");
          if (ps.length==2) {
            try { tid = Long.parseLong(ps[1]); }
            catch (NumberFormatException ignored) { }
          }
        }
        
        String desc = c.optStr("name");
        if (desc==null) desc = globalID;
        if (desc==null) desc = c.optStr("details");
        if (desc==null) desc = "";
        
        GVal fo = c.get("frame");
        StackFrame f = readFrame(fo, -1, null);
        if (fo.has("args")) addArgs(f, fo.get("args"));
        boolean isCurr = gdbID.equals(currID);
        return new ThreadState(isCurr? t0 : new ProcThread(curr, gdbID), f, desc, gdbID, globalID, tid, isCurr);
      }));
    });
    if (isRR()) p.consoleCmd("maintenance flush register-cache").ds().runWithPrefix("~\"Register cache flushed", (r,m)->{});
  }
  
  public void _selectThread(ThreadState thr, Consumer<Boolean> after) {
    p.cmd("-thread-select", thr.gdbID).run(r -> after.accept(r.type.ok()));
  }
  
  private StackFrame readFrame(GVal c, long topAddr, StackFrame higherFrame) {
    Location l = LocationUtils.readFrom(LocationUtils.LocMode.M1, c);
    boolean afterCall = topAddr!=-1 && !Objects.equals(l.addr, topAddr);
    if (higherFrame!=null && "<signal handler called>".equals(higherFrame.l.sym)) afterCall = false;
    return new StackFrame(c.getInt("level"), l, afterCall);
  }
  
  public void _stacktrace(ProcThread t, boolean args, int start, int end, Consumer<Vec<StackFrame>> got) {
    if (end==-1) end = Integer.MAX_VALUE;
    if (start==end) {
      got.accept(new Vec<>());
      return;
    }
    Box<Result> r1 = new Box<>(); // TODO replace with promises
    Box<Result> r2 = new Box<>();
    Runnable done = () -> {
      if (!r1.has() || !r1.v.type.ok()) { got.accept(null); return; }
      Vec<StackFrame> res = new Vec<>();
      long topAddr = -1;
      int i = 0;
      for (GVal c : r1.v.get("stack").vs()) {
        StackFrame f = readFrame(c, topAddr, res.peek());
        if (i==0) topAddr = f.l.addr;
        res.add(f);
        i++;
        assert res.sz == i;
      }
      
      if (r2.has() && r2.v.type.ok()) {
        for (GVal v : r2.v.get("stack-args").vs()) addArgs(res.get(v.getInt("level")-start), v.get("args"));
      }
      got.accept(res);
    };
    
    p.cmd("-stack-list-frames", t.arg, start, end-1).ds().run(r1i -> {
      r1.v = r1i;
      if (!args) done.run();
    });
    
    if (args) p.cmd("-stack-list-arguments", t.arg, 2, start, end-1).ds().run(r2i -> {
      r2.v = r2i;
      done.run();
    });
  }
  public void _stackHeight(ProcThread t, IntConsumer got) {
    p.cmd("-stack-info-depth", t.arg).ds().run(r -> got.accept(r.get("depth").asInt()));
  }
  
  private static void addArgs(StackFrame f, GVal argList) {
    Vec<Arg> args = f.args = new Vec<>();
    for (GVal arg : argList.vs()) {
      String val = arg.optStr("value");
      if ("<optimized out>".equals(val)) val = null;
      String type = arg.optStr("type");
      if (type!=null) type = type.replace(" *", "*");
      args.add(new Arg(type, arg.optStr("name"), val));
    }
  }
  
  public void _disas(int ref, DisasMode mode, Object a, Long b, Consumer<Vec<Ins>> got) {
    Vec<String> os = new Vec<>();
    os.add(ref==2? "-s" : "-a"); os.add(a instanceof String? "'"+a+"'" : a.toString());
    if (ref==2) { os.add("-e"); os.add(b.toString()); }
    os.add(mode==DisasMode.SRC? "5" : "2");
    p.cmdList("-data-disassemble", os).ds().run(r -> {
      if (r.type.ok()) {
        GVal asm_insns = r.get("asm_insns");
        Vec<Ins> res = new Vec<>();
        if (asm_insns instanceof GdbFormat.GKVList) {
          for (GVal srcInfo : asm_insns.vs()) {
            for (GVal v : srcInfo.get("line_asm_insn").vs()) res.add(parseAsm(v, srcInfo, mode!=DisasMode.DISAS));
          }
        } else {
          for (GVal v : asm_insns.vs()) res.add(parseAsm(v, v, mode!=DisasMode.DISAS));
        }
        got.accept(res);
      } else {
        got.accept(null);
      }
    });
  }
  private Ins parseAsm(GVal v, GVal srcInfo, boolean opcodes) {
    byte[] opcodeRaw = readBytes(v.getStr("opcodes"));
    return new Ins(
      v.getAddr("address"),
      opcodeRaw.length,
      v.getStr("inst"),
      opcodes? opcodeRaw : null,
      LocationUtils.readFrom(LocationUtils.LocMode.M2, srcInfo, v)
    );
  }
  private byte[] readBytes(String s) {
    byte[] res = new byte[(s.length()+1)/3];
    for (int r=0,w=0; r < s.length(); r+= 3, w++) res[w] = GdbFormat.readByte(s, r);
    return res;
  }
  
  public void _setPrintDemangle(String value, Runnable after) {
    p.cmd("-gdb-set", "print", "demangle", value).ds().run(after);
  }
  
  private boolean supportsLWCP = true;
  public void _addCheckpoint(boolean lightweight, Consumer<Integer> got) {
    if (lightweight && !supportsLWCP) { got.accept(null); return; }
    
    String badPrefix = p.isRR && lightweight? "~\"Checkpoint " : null;
    String prefix = p.isRR? (lightweight? "~\"Lightweight checkpoint " : "~\"Checkpoint ") : "~\"checkpoint ";
    
    
    p.consoleCmd(lightweight? "checkpoint lightweight" : "checkpoint").ds().runWithIntermediate(r -> {
      String match = r.intermediate.sz==1? r.intermediate.peek() : null;
      if (match==null) {
        got.accept(null);
        return;
      }
      
      String curr = prefix;
      boolean bad = false;
      if (badPrefix!=null && match.startsWith(badPrefix)) {
        Log.info("grr", "lightweight checkpoints not supported; deleting");
        supportsLWCP = false;
        curr = badPrefix;
        bad = true;
      }
      match = match.substring(curr.length());
      
      int i = 0;
      while (GdbFormat.dig(match.charAt(i))) i++;
      Integer num = i==0? null : Integer.parseInt(match.substring(0, i));
      
      if (num!=null && bad) _rmCheckpoint(num, null);
      got.accept(num);
    }, s -> s.startsWith(prefix) || (badPrefix!=null && s.startsWith(badPrefix)));
  }
  public void _toCheckpoint(int n, Runnable after) {
    p.consoleCmd("restart "+n).run(after);
  }
  public void _rmCheckpoint(int n, Runnable after) {
    p.consoleCmd("delete checkpoint "+n).ds().run(after);
  }
  public void _addLWCP(Consumer<Integer> got) {
    if (!isRR()) { got.accept(null); return; }
    _addCheckpoint(true, got);
  }
  public void _gotoLWCP(int lwcp, Consumer<Boolean> after0) {
    _toCheckpoint(lwcp, after0==null? null : () -> after0.accept(true));
  }
  
  public void toTicks(long ticks, Runnable after) {
    p.consoleCmd("seek-ticks "+ticks).run(after);
  }
  public void toTicksQuick(long ticks, Consumer<Boolean> after) {
    p.consoleCmd("quick-seek-ticks "+ticks).ds().run(after==null? null : r -> after.accept(r.type.ok()));
  }
  
  public void toEvent(long event, Runnable after) {
    p.consoleCmd("run "+event).run(after);
  }
  
  public void _addWatchpoint(String expr, boolean read, boolean write, Runnable after) {
    assert read || write;
    p.cmd("-break-watch", o(read&&write, "-a"), o(read&&!write, "-r"), expr).ds().run();
    p.reloadBreakpoints(after);
  }
  
  public void evalExpr(String expr, Consumer<String> got) {
    evalExpr(expr, false, got);
  }
  public void evalExpr(String expr, boolean wontRun, Consumer<String> got) {
    GdbProcess.CmdI cmd = p.cmd("-data-evaluate-expression", expr);
    if (wontRun) cmd.ds();
    cmd.run(r -> {
      got.accept(r.type.ok()? r.get("value").str() : null);
    });
  }
  
  public void listRegisters(Consumer<Vec<Pair<Integer,String>>> got) {
    p.cmd("-data-list-register-names").ds().run(r -> {
      if (!r.type.ok()) got.accept(null);
      Vec<Pair<Integer, String>> res = new Vec<>();
      Vec<GVal> ns = r.get("register-names").vs();
      for (int i = 0; i < ns.sz; i++) {
        String s = ns.get(i).str();
        if (!s.isEmpty()) res.add(new Pair<>(i, s));
      }
      got.accept(res);
    });
  }
  
  public static class Section { public final String name; public final long s,e;
    public Section(String name, long s, long e) { this.name=name; this.s=s; this.e=e; }
  }
  public static Pattern sectionPattern = Pattern.compile("^~\"\\\\t(0x([0-9a-fA-F]+) - 0x([0-9a-fA-F]+) is (.*))\\\\n\"$");
  public void getSections(Consumer<Vec<Section>> got) {
    Vec<Section> res = new Vec<>();
    p.consoleCmd("info file").ds().runWithIntermediate(r -> got.accept(res), s -> {
      Matcher m = sectionPattern.matcher(s);
      if (!m.find()) return false;
      res.add(new Section(m.group(4), Long.parseUnsignedLong(m.group(2), 16), Long.parseUnsignedLong(m.group(3), 16)));
      return true;
    });
  }
  
  public void _readMem(long s, long e, BiConsumer<byte[], boolean[]> got) {
    p.cmd("-data-read-memory-bytes", s, e-s).ds().run(r -> {
      int l = Math.toIntExact(e-s);
      boolean[] avail = new boolean[l];
      byte[] mem = new byte[l];
      if (r.type.ok()) {
        for (GVal seg : r.get("memory").vs()) {
          int cs = (int)(seg.getAddr("begin")-s);
          int ce = (int)(seg.getAddr("end")-s);
          String hexRepr = seg.getStr("contents");
          for (int i = 0; i < ce-cs; i++) {
            avail[cs+i] = true;
            mem[cs+i] = GdbFormat.readByte(hexRepr, i*2);
          }
        }
      }
      got.accept(mem, avail);
    });
  }
  
  // todo move to Executable & make more debugger-generic
  public void addBreakpoint(boolean hw, boolean temp, boolean enabled, String loc, Runnable after) {
    p.cmd("-break-insert", o(hw,"-h"), o(temp,"-t"), o(!enabled,"-d"), loc).ds().run();
    p.reloadBreakpoints(after);
  }
  public void toggleBreakpoint(int n, boolean enabled, Runnable after) {
    p.cmd(enabled?"-break-enable":"-break-disable", n).ds().run();
    p.reloadBreakpoints(after);
  }
  public void deleteBreakpoint(int id) {
    p.cmd("-break-delete", id).ds().run(r -> p.onBpDeleted(id));
  }
  
  
  
  
  public void _symbolInfo(boolean nondebug, int max, String name, Consumer<Vec<Pair<Location, Vec<Arg>>>> got) {
    p.cmd(
      "-symbol-info-functions",
      o(nondebug, "--include-nondebug"),
      o(max>0, "--max-results", max),
      o(name!=null, "--name", name)
    ).ds().run(r -> {
      if (!r.type.ok()) { got.accept(null); return; }
      Vec<Pair<Location, Vec<Arg>>> res = new Vec<>();
      GVal s = r.get("symbols");
      if (s.has("debug")) {
        for (GVal e0 : s.get("debug").vs()) {
          for (GVal e1 : e0.get("symbols").vs()) {
            Vec<Arg> args = null;
            String ty = e1.optStr("type");
            if (ty!=null) {
              args = new Vec<>();
              for (String c : ty.substring(ty.indexOf('(')+1, ty.length()-1).split(", ")) args.add(new Arg(c, null, null));
            }
            res.add(new Pair<>(LocationUtils.readFrom(LocationUtils.LocMode.M3, e0, e1), args));
          }
        }
      }
      if (s.has("nondebug")) {
        for (GVal e1 : s.get("nondebug").vs()) {
          res.add(new Pair<>(LocationUtils.readFrom(LocationUtils.LocMode.M3, e1), null));
        }
      }
      got.accept(res);
    });
  }
}
