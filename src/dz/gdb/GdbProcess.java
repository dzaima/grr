package dz.gdb;

import com.pty4j.*;
import dz.gdb.Dbi.Status;
import dz.gdb.GdbFormat.GVal;
import dz.utils.*;
import dzaima.utils.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;

public class GdbProcess {
  final Process p;
  public final boolean isRR;
  public final PtyProcess ttyProc;
  final OutQueue stdout, stderr, child_stdout, child_stderr;
  final InQueue stdin, child_stdin;
  public Status status;
  public StopReason stopReason;
  public Dbi.ExecMode lastRunMode;
  
  private final Vec<Runnable> onNextInputQuery = new Vec<>();
  public final Vec<Runnable> onStatusChange = new Vec<>();
  
  public enum OutWhere { GDB_LOG, INF_STDERR, INF_STDOUT }
  public final Vec<BiConsumer<String, OutWhere>> onOutput = new Vec<>();
  public final Vec<BiConsumer<String, Boolean>> onRawOut = new Vec<>();
  public final Vec<Runnable> onModifiedBreakpoints = new Vec<>();
  public final Vec<Consumer<String>> onNewArch = new Vec<>();
  
  private GdbProcess(Vec<String> cmd, boolean isRR, PtyProcess ttyProc, Status status0) throws IOException {
    this.isRR = isRR;
    this.ttyProc = ttyProc;
    ProcessBuilder pb = new ProcessBuilder(cmd.toArray(new String[0]));
    p = pb.start();
    stdout = new OutQueue(p.getInputStream(), ()->setStatus(Status.KILLED));
    stderr = new OutQueue(p.getErrorStream(), ()->{});
    stdin = new InQueue(p.getOutputStream());
    status = status0;
    if (ttyProc!=null) {
      child_stdout = new OutQueue(ttyProc.getInputStream(), ()->{});
      child_stderr = new OutQueue(ttyProc.getErrorStream(), ()->{});
      child_stdin = new InQueue(ttyProc.getOutputStream());
    } else {
      child_stdout = child_stderr = null;
      child_stdin = null;
    }
  }
  
  public void sendProcessStdin(String s) {
    (child_stdin==null? stdin : child_stdin).push(s.getBytes(StandardCharsets.UTF_8));
  }
  
  public void setStatus(Status s) {
    status = s;
    for (Runnable f : onStatusChange) f.run();
  }
  
  private final Deque<QueuedCmd> queue = new ArrayDeque<>();
  private static class QueuedCmd {
    final CmdI cmd; final Consumer<Result> f;
    final Function<String, Boolean> consume; final Vec<String> intermediate;
    boolean sent;
    private QueuedCmd(CmdI cmd, Consumer<Result> f, Function<String, Boolean> consume) {
      assert f!=null;
      this.cmd=cmd; this.f=f;
      this.consume = consume; intermediate=consume!=null? new Vec<>() : null;
    }
  }
  public void queueCommand(CmdI cmd, Function<String, Boolean> consume, Consumer<Result> onResult) {
    for (BiConsumer<String, Boolean> c : onRawOut) c.accept(cmd.cmd, true);
    queue.add(new QueuedCmd(cmd, onResult, consume));
    attemptNext();
  }
  void attemptNext() {
    if (queue.isEmpty() || currentCmd!=null) return;
    QueuedCmd c0 = queue.peek();
    if (!c0.sent && (status==Status.RUNNING || sentCommands!=0)) return; // generally we don't want to send a command while the program is running, but if it's already sent, we must act like it
    
    logInfo("cmd", "Running: "+GdbFormat.fmtCmd(c0.cmd.cmd)+(c0.sent? " (already sent)":""));
    for (QueuedCmd c : queue) if (!pushCmd(c, c0)) break;
    
    currentCmd = queue.pop();
    if (c0 != currentCmd) throw new AssertionError();
  }
  private boolean pushCmd(QueuedCmd c, QueuedCmd c0) { // returns whether following commands can & should be queued ahead-of-time
    if (c.sent) return false;
    c.sent = true;
    if (c!=c0) logInfo("cmd", "      q: "+GdbFormat.fmtCmd(c.cmd.cmd));
    stdin.push(c.cmd.cmd.getBytes(StandardCharsets.UTF_8));
    sentCommands++;
    return c.cmd.doesntStart;
  }
  
  public boolean queueEmpty() {
    return queue.isEmpty() && currentCmd==null;
  }
  
  
  String currArch;
  void gotArch(String val) {
    if (val.equals(currArch)) return;
    currArch = val;
    for (Consumer<String> c : onNewArch) c.accept(val);
  }
  
  QueuedCmd currentCmd;
  int sentCommands = 1;
  ByteVec currLine = new ByteVec();
  private void output(OutQueue q, OutWhere where) {
    if (q==null) return;
    while (q.has()) {
      byte[] c = q.takeOne();
      try {
        String m = new String(c, StandardCharsets.UTF_8);
        for (BiConsumer<String, OutWhere> f : onOutput) f.accept(m, where);
      } catch (Throwable t) {
        Log.stacktrace("GdbProcess output move", t);
      }
    }
  }
  void tick() {
    while (stdout.has()) {
      byte[] c = stdout.takeOne();
      int s = 0;
      for (int e = 0; e < c.length; e++) {
        if (c[e] == 10) {
          currLine.addAll(c, s, e);
          processLine(currLine.get());
          currLine.remove(0, currLine.sz);
          s = e+1;
        }
      }
      currLine.addAll(c, s, c.length);
    }
    output(stderr, OutWhere.INF_STDERR);
    output(child_stderr, OutWhere.INF_STDERR);
    output(child_stdout, OutWhere.INF_STDOUT);
    attemptNext();
  }
  void processLine(byte[] bs) {
    String s;
    try { s = new String(bs, StandardCharsets.UTF_8); }
    catch (Throwable e) { logInfo("out", "non-Unicode line"); return; }
    
    logInfo("out", "Line: "+s.replaceAll("(\\^done,asm_insns=).*", "$1..."));
    for (BiConsumer<String, Boolean> c : onRawOut) c.accept(s, false);
    if (currentCmd!=null && currentCmd.intermediate!=null && currentCmd.consume.apply(s)) {
      currentCmd.intermediate.add(s);
      return;
    }
    if (s.startsWith("^")) processOutput(s);
    else if (s.startsWith("*")) {
      Result r = parseResult(s, null);
      if (r.type==Result.Type.RUNNING) setStatus(Status.RUNNING); // checkpoint restart does ^running; *running; *stopped; *running; *stopped
      if (r.type==Result.Type.STOPPED) {
        setStatus(Status.PAUSED);
        stopReason = StopReason.of(r);
        GVal f = r.get("frame");
        if (f!=null && f.has("arch")) gotArch(f.getStr("arch"));
      }
    }
    else if (s.startsWith("=")) {
      Result r = parseResult(s, null);
      switch (r.type) {
        case THREAD_GROUP_STARTED:
          if (!status.started()) setStatus(Status.PAUSED);
          break;
        case BREAKPOINT_CREATED:
          breakpoints.add(new Breakpoint(r.get("bkpt")));
          onBpUpdated();
          break;
        case BREAKPOINT_MODIFIED:
          GVal o = r.get("bkpt");
          int id = Integer.parseInt(o.getStr("number"));
          Breakpoint prev = findBreakpoint(id);
          if (prev != null) prev.update(o);
          onBpUpdated();
          break;
        case BREAKPOINT_DELETED:
          onBpDeleted(r.get("id").asInt());
          break;
        case CMD_PARAM_CHANGED:
          String key = r.get("param").str();
          String val = r.get("value").str();
          if (key.equals("architecture")) gotArch(val);
          break;
      }
    }
    else if (s.startsWith("~\"") || s.startsWith("@\"") || s.startsWith("&\"")) {
      if (s.startsWith("~\"Reading symbols from")) {
        if (status == Status.NONE) setStatus(Status.LOADED);
      }
      if (onOutput.sz>0) {
        try {
          String m = GdbParser.parse(s.substring(1)).str();
          for (BiConsumer<String, OutWhere> c : onOutput) c.accept(m, OutWhere.GDB_LOG);
        } catch (Throwable t) {
          Log.stacktrace("GdbProcess line", t);
        }
      }
    }
    else if (s.startsWith("(gdb)")) {
      sentCommands = Math.max(sentCommands-1, 0);
      if (status!=Status.RUNNING) { // if running, the (gdb) doesn't actually mean we can take input now
        for (Runnable r : onNextInputQuery) r.run();
        onNextInputQuery.clear();
      }
    }
  }
  void processOutput(String s) {
    if (currentCmd!=null) {
      Result r = parseResult(s, currentCmd.intermediate);
      logInfo("cmd", "Command completed");
      if (r.type==Result.Type.RUNNING) lastRunMode = currentCmd.cmd.mode;
      try {
        currentCmd.f.accept(r);
      } catch (Throwable t) {
        Log.error("gdb cmd", "Failed to process gdb output");
        Log.stacktrace("gdb cmd", t);
      }
      currentCmd = null;
      if (r.type==Result.Type.RUNNING) setStatus(Status.RUNNING);
      if (r.type==Result.Type.EXIT) setStatus(Status.KILLED);
      attemptNext(); // must be done here to be able to immediately respond to the next ^output
    } else {
      Log.error("processLine", "Got gdb result without corresponding command request");
      if (status == Status.RUNNING && s.equals("^error,msg=\"Command aborted.\"")) { // doing -exec-finish in rr as the first thing outputs this after ^running/*running, but no *stopped; assume *stopped
        setStatus(Status.PAUSED);
      }
    }
  }
  
  static Result parseResult(String s, Vec<String> intermediate) {
    s = s.substring(1);
    int e = s.indexOf(',');
    if (e==-1) e = s.length();
    Vec<Pair<String, GVal>> raw = GdbParser.parse("[status=" + GdbFormat.quote(s.substring(0, e)) + s.substring(e) + "]").es();
    Result.Type t = Result.typeMap.getOrDefault(raw.get(0).b.str(), Result.Type.UNKNOWN);
    Vec<RE> es = new Vec<>();
    for (int i = 1; i < raw.sz; i++) {
      Pair<String, GVal> c = raw.get(i);
      es.add(new RE(c.a, c.b));
    }
    return new Result(t, es, intermediate);
  }
  
  public final Vec<Breakpoint> breakpoints = new Vec<>();
  public void onBpDeleted(int id) {
    breakpoints.filterInplace(bp -> bp.number!=id);
    onBpUpdated();
  }
  public void onBpUpdated() {
    for (Runnable c : onModifiedBreakpoints) c.run();
  }
  public Breakpoint findBreakpoint(int id) {
    for (Breakpoint b : breakpoints) if (b.number==id) return b;
    return null;
  }
  public void reloadBreakpoints(Runnable after) {
    cmd("-break-list").ds().run(r -> {
      HashMap<Integer, Breakpoint> prev = new HashMap<>();
      for (Breakpoint c : breakpoints) prev.put(c.number, c);
      for (GVal o : r.get("BreakpointTable").get("body").vs()) {
        Breakpoint pb = prev.get(o.getInt("number"));
        if (pb==null) breakpoints.add(new Breakpoint(o));
        else pb.update(o);
      }
      onBpUpdated();
      if (after!=null) after.run();
    });
  }
  
  public enum TTYMode { NONE, INHERIT, NEW }
  
  public static Pair<String, PtyProcess> makeTTY(TTYMode mode) {
    PtyProcess ttyProc = null;
    String tty;
    switch (mode) { default: throw new IllegalStateException();
      case INHERIT:
        String result;
        ProcessBuilder pb = new ProcessBuilder("tty").redirectInput(ProcessBuilder.Redirect.INHERIT);
        try {
          Process p1 = pb.start();
          String r1 = new String(p1.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
          if (r1.endsWith("\n")) r1 = r1.substring(0, r1.length()-1);
          result = p1.waitFor()==0? r1 : null;
        } catch (IOException | InterruptedException e) {
          Log.stacktrace("getPty", e);
          result = null;
        }
        tty = result;
        break;
      
      case NONE:
        tty = null;
        break;
      
      case NEW:
        PtyProcessBuilder pb1 = new PtyProcessBuilder(new String[]{"sh", "-c", "sh -c 'tty; while :; do sleep 30000; done'"});
        try {
          ttyProc = pb1.start();
          tty = new BufferedReader(new InputStreamReader(ttyProc.getInputStream())).readLine();
        } catch (IOException e) {
          Log.stacktrace("makeTTY", e);
          tty = null;
        }
        break;
    }
    if (tty == null) tty = "/dev/null";
    return new Pair<>(tty, ttyProc);
  }
  
  
  public static GdbProcess makeGdb(String gdb_bin, TTYMode mode) {
    return makeGdb(gdb_bin, mode, null, null);
  }
  public static GdbProcess makeGdb(String gdb_bin, TTYMode mode, String bin, String core) {
    assert core==null || bin!=null;
    Vec<String> cmd = new Vec<>();
    cmd.add(gdb_bin==null? "gdb" : gdb_bin);
    cmd.add("-q");
    cmd.add("--interpreter=mi3");
    
    Pair<String, PtyProcess> tty = makeTTY(mode);
    cmd.add("--tty");
    cmd.add(tty.a);
    if (bin!=null) cmd.add(bin);
    if (core!=null) cmd.add(core);
    try { return new GdbProcess(cmd, false, tty.b, bin==null? Status.NONE : Status.PAUSED); }
    catch (Throwable e) { throw new RuntimeException(e); }
  }
  
  public static GdbProcess makeRR(String rrBin, String gdbBin, TTYMode mode, String path, Vec<String> extraRRArgs) {
    Vec<String> cmd = new Vec<>();
    cmd.add(rrBin==null? "rr" : rrBin);
    cmd.add("replay");
    // cmd.add("-M");
    Pair<String, PtyProcess> tty = makeTTY(mode);
    cmd.add("--tty");
    cmd.add(tty.a);
    if (gdbBin!=null) {
      cmd.add("-d");
      cmd.add(gdbBin);
    }
    if (extraRRArgs!=null) cmd.addAll(extraRRArgs);
    if (path!=null) cmd.add(path);
    cmd.add("--");
    cmd.add("-q");
    cmd.add("--interpreter=mi3");
    try { return new GdbProcess(cmd, true, tty.b, Status.PAUSED); }
    catch (Throwable e) { throw new RuntimeException(e); }
  }
  
  
  
  public void sendInterrupt(String name) {
    try {
      Runtime.getRuntime().exec(new String[]{"kill", "-"+name, Long.toUnsignedString(p.pid())});
    } catch (IOException e) {
      Log.stacktrace("sendInterrupt", e);
    }
  }
  
  public void breakAll(Runnable after) {
    sentCommands++;
    if (after!=null) onNextInputQuery.add(after);
    sendInterrupt("SIGINT");
  }
  
  
  public CmdI cmd(Object... cmd) {
    assert cmd[0] instanceof String;
    StringBuilder r = new StringBuilder((String)cmd[0]);
    for (int i = 1; i < cmd.length; i++) addObj(r, cmd[i]);
    r.append('\n');
    return new CmdI(r.toString());
  }
  private void addObj(StringBuilder b, Object o) {
    if (o == null) return;
    if (o instanceof Number) b.append(' ').append(o);
    else if (o instanceof String) b.append(' ').append(reprGdbCmd((String) o));
    else if (o instanceof String[]) for (String s : ((String[]) o)) b.append(' ').append(reprGdbCmd(s));
    else if (o instanceof Object[]) for (Object c : ((Object[]) o)) addObj(b, c);
    else throw new RuntimeException("bad cmd arg " + o.getClass().getName());
  }
  String reprGdbCmd(String s) { // this is important, as `-stack-list-frames --thread 1` works, but quoting either `--thread` or `1` makes it break
    if (s.isEmpty()) return "\"\"";
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (!(c>='a'&c<='z'  ||  c>='A'&c<='Z'  ||  c>='0'&c<='9'  ||  c=='-')) return GdbFormat.quote(s);
    }
    return s;
  }
  
  public CmdI cmdList(String s, Vec<String> vec) {
    String[] args = new String[vec.sz+1];
    args[0] = s;
    for (int i = 0; i < vec.sz; i++) args[i+1] = vec.get(i);
    return cmd((Object[]) args);
  }
  
  public CmdI consoleCmd(String text) {
    return cmd("-interpreter-exec", "console", text);
  }
  
  public class CmdI {
    public final String cmd;
    public boolean doesntStart;
    public Dbi.ExecMode mode = Dbi.ExecMode.UNKNOWN;
    public CmdI(String cmd) { this.cmd = cmd; }
    
    public CmdI ds() { // specify that this command shouldn't result in the target running, meaning that further commands can be queued immediately after this
      doesntStart = true;
      return this;
    }
    public CmdI revExec(boolean rev) { // specify that, if this command results in running the target, that it'll run in the specified direction
      mode = rev? Dbi.ExecMode.BACKWARD : Dbi.ExecMode.FORWARD;
      return this;
    }
    
    public void run(Consumer<Result> f) {
      queueCommand(this, null, f==null? r->{} : f);
    }
    public void run(Runnable f) {
      run(f==null? r->{} : r->f.run());
    }
    public void run() { run((Runnable) null); }
    
    public void runWithIntermediate(Consumer<Result> f, Function<String, Boolean> consume) {
      queueCommand(this, consume, f);
    }
    public void runWithPrefix(String prefix, BiConsumer<Result, String> f) {
      runWithIntermediate(r -> {
        if (r.intermediate.sz!=1) f.accept(r, null);
        else f.accept(r, r.intermediate.get(0).substring(prefix.length()));
      }, s -> s.startsWith(prefix));
    }
    
    public void expect() {
      run(r -> {
        if (!r.type.ok()) Log.error("gdb command", "Expected command "+GdbFormat.fmtCmd(cmd)+" to complete successfully, instead got "+r);
      });
    }
  }
  public static class Result {
    public enum Type {
      DONE, RUNNING, CONNECTED, ERROR, EXIT, // ^, *
      STOPPED, // *
      BREAKPOINT_CREATED, BREAKPOINT_MODIFIED, BREAKPOINT_DELETED, CMD_PARAM_CHANGED, THREAD_GROUP_STARTED, // =
      UNKNOWN; // general
      public boolean ok() { return this!=ERROR; }
    }
    private static final HashMap<String, Type> typeMap = new HashMap<>();
    static {
      for (Type c : Type.values()) typeMap.put(c.name().toLowerCase().replace('_', '-'), c);
    }
    public final Type type;
    public final Vec<RE> es;
    public final Vec<String> intermediate;
    
    public Result(Type type, Vec<RE> es, Vec<String> intermediate) {
      this.type = type;
      this.es = es;
      this.intermediate = intermediate;
    }
    public GVal get(String n) {
      for (RE e : es) if (e.k.equals(n)) return e.v;
      return null;
    }
    
    public String toString() {
      StringBuilder b = new StringBuilder();
      b.append('^').append(type);
      for (RE e : es) b.append(',').append(e.k).append('=').append(e.v);
      return b.toString();
    }
  }
  public static class RE {
    public final String k;
    public final GVal v;
    public RE(String k, GVal v) { this.k = k; this.v = v; }
  }
  
  static void logInfo(String c, String m) {
    // System.out.println("["+c+"] "+m);
    Log.fine(c, "\u001b[38;5;245m"+m+"\u001b[0m");
  }
}
