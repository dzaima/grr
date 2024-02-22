package dz;

import dz.layouts.*;
import dz.stat.valgrind.BinPrep;
import dz.tabs.AsmTab;
import dz.ui.*;
import dzaima.ui.eval.PNodeGroup;
import dzaima.ui.gui.*;
import dzaima.ui.gui.config.GConfig;
import dzaima.ui.gui.io.*;
import dzaima.ui.node.ctx.*;
import dzaima.ui.node.types.editable.EditNode;
import dzaima.ui.node.types.tabs.SerializableTab;
import dzaima.utils.*;
import dzaima.utils.options.Options;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class Main extends NodeWindow {
  public Layout ly;
  
  public Main(GConfig gc, Ctx pctx, PNodeGroup g) {
    super(gc, pctx, g, new WindowInit("grr"));
  }
  
  public static void fail(String msg) {
    System.err.println("Error: "+msg);
    System.exit(1);
  }
  public static RuntimeException failR(String msg) {
    fail(msg);
    throw new IllegalStateException();
  }
  
  public static boolean ask(String msg) {
    try {
      System.err.println(msg);
      System.err.print("(yes/no) ");
      int c = System.in.read();
      boolean accept = c=='y' || c=='Y';
      while (c>0 && c!=10 && c!=13) c = System.in.read();
      return accept;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
  
  public static void main(String[] args) {
    Windows.setManager(Windows.Manager.JWM);
    Options o = new Options();
    Vec<String> tail = o.process(args,
      (arg, i, get) -> {
        switch (arg) { default: fail("Unknown flag -- "+arg); break;
          case "--help":
            System.out.print(
              "Usage:\n"+
              "  grr [--gdb] [--core path/to/coredump] [path/to/binary] -- [args..]\n"+
              "  grr --rr [replay file]\n"+
              "  grr --perf path/to/perf.data [--core path/to/coredump path/to/binary] # core dump allows viewing JIT code\n"+
              "Options:\n"+
              "  -x <EXPR>                execute gdb command on startup\n"+
              "  -d <NAME>                open to disassembly of specific symbol; implies --no-run\n"+
              "  --move old:new           remap file paths, e.g. '/old/file.c' to '/new/file.c'\n"+
              "  -v, --no-run             don't automatically start running the process\n"+
              "  --gdb-bin <BIN>          use specified gdb binary\n"+
              "  --tty [none|inherit|new] tty mode (default: new)\n"+
              "\n"+
              "  --rr-bin <BIN>           use specified rr binary\n"+
              "  -p, --rr-fork <PID>      run rr to specific fork\n"+
              "  -f, --rr-process <PID>   run rr to specific process\n"+
              "  --rr-arg <ARG>           add rr argument\n"+
              "\n"+
              "  --perf-symb              primary perf symbolization mode: 'manual' (default), 'gdb', or 'script' (i.e. perf script)\n"+
              // "  --perf-extra-exe <PATH>  fallback executable if instruction pointer isn't found in executable sections\n"+
              "  --jvm-out <PATH>       path to Java PrintAssembly / CompileCommand=print output\n"+
              "\n"+
              "  --icmd <EXPR>            execute internal command on startup\n"+
              "  --layout <PATH>          load layout by file name\n"+
              "  -l<NAME>                 load layout from local/<NAME>.txt (-ld for a predefined layout for simple disassembly)\n"+
              "\n"
            );
            System.exit(0);
            break;
          case "--log":
            switch (get.get().toLowerCase(Locale.ROOT)) { default: System.err.println("Unknown log level"); break;
              case "fine": Log.setLogLevel(Log.Level.FINE); break;
              case "info": Log.setLogLevel(Log.Level.INFO); break;
              case "warn": Log.setLogLevel(Log.Level.WARN); break;
              case "error": Log.setLogLevel(Log.Level.ERROR); break;
            }
            break;
          
          case "--gdb":
          case "--rr":
          case "--no-run":
            o.put(arg, i, "true"); break;
          
          case "--perf-symb": case "--prep-cachegrind": case "--cachegrind": case "--perf": case "--jvm-out":
          case "--move": case "--icmd": case "--tty":
          case "--core":
          case "--gdb-bin": case "--rr-bin": case "--perf-extra-exe":
          case "--rr-arg": case "--layout":
          case "--rr-fork": case "--rr-process":
            o.put(arg, i, get.get()); break;
        }
      },
      (arg, i, get) -> {
        switch (arg.substring(0,2)) { default: fail("Unknown flag -- "+arg); break;
          case "-l":
            Path src = arg.equals("-ld")? Tools.RES_DIR.resolve("disasLayout.dzcfg") : localDir().resolve(arg.substring(2) + ".txt");
            o.put("--layout", 0, src.toString());
            break;
          case "-d": o.put("--no-run", i, "true"); o.put(arg, i, get.get()); break;
          case "-v": o.put("--no-run", i, "true"); break;
          case "-f": o.put("--rr-fork",    i, get.get()); break;
          case "-p": o.put("--rr-process", i, get.get()); break;
          case "-x": o.put(arg, i, get.get()); break;
        }
      }
    );
    
    if (o.has("--prep-cachegrind")) {
      Path out = Paths.get(o.optOne("--prep-cachegrind"));
      if (tail.sz!=1) Main.fail("Missing input file for '--to-valgrind'");
      Path inp = Paths.get(tail.get(0));
      boolean ok = BinPrep.writeAssemblyLineDwarf(inp, out);
      if (!ok) System.exit(1);
      return;
    }
    
    Windows.start(mgr -> {
      boolean rr = o.takeBool("--rr");
      boolean gdb = o.takeBool("--gdb");
      if (rr && gdb) fail("Cannot combine --gdb and --rr");
      if (!rr && !gdb && !o.has("--cachegrind") && !o.has("--perf")) gdb = true;
      
      GConfig gc = GConfig.newConfig(gc0 -> {
        gc0.addCfg(() -> Tools.readRes("grr.dzcfg"));
      });
      BaseCtx ctx = Ctx.newCtx();
      ctx.put("liveTextfield", LiveTextFieldNode::new);
      ctx.put("sourcearea", SourceAreaNode::new);
      ctx.put("replfield", ReplFieldNode::new);
      ctx.put("asmOverlay", AsmTab.AsmOverlayNode::new);
      
      Main m = new Main(gc, ctx, gc.getProp("grr.uiMain").gr());
      if (rr || gdb) {
        if (rr && tail.sz>1) fail("--rr: Cannot specify program arguments");
        m.setLayout(new DebuggerLayout(m, o, rr, tail));
      } else {
        if (o.has("--perf")) {
          if (o.has("--core")) {
            if (tail.sz>1) fail("--perf --core: Multiple trailing arguments provided");
            if (tail.sz!=1) fail("--perf: Binary file must be specified if \"--core\" is");
          } else {
            if (tail.sz!=0 && !o.has("--cachegrind")) fail("--perf: cannot make use of binary file without \"--core\"");
          }
        }
        m.setLayout(new PerfLayout(m, o, tail.sz==0? null : tail.get(0)));
      }
      mgr.start(m);
    });
  }
  
  public void setLayout(Layout ly) {
    this.ly = ly;
    base.replace(0, ly.node);
  }
  
  public void tick() {
    if (ly!=null) ly.tick();
    super.tick();
  }
  public void eventTick() {
    if (ly!=null) ly.preEventTick();
    super.eventTick();
  }
  public void stopped() {
    ly.stopped();
    super.stopped();
  }
  
  public static Path localDir() {
    return Tools.RES_DIR.getParent().resolve("local");
  }
  
  public boolean key(Key key, int scancode, KeyAction a) {
    switch (gc.keymap(key, a, "grr")) { 
      case "saveLayout":
        String s = SerializableTab.serializeTree(ly.treePlace().ch.get(0));
        Path dir = localDir();
        try { Files.createDirectories(dir); }
        catch (IOException e) { Log.stacktrace("create local/ directory for save", e); }
        saveFile(null, dir.toAbsolutePath(), p -> {
          if (p!=null) Tools.writeFile(p, s);
        });
        return true;
      case "fontPlus":
        gc.setEM(gc.em+1);
        return true;
      case "fontMinus":
        gc.setEM(gc.em-1);
        return true;
    }
    if (a.press) {
      if (key.k_f5() && key.hasAlt()) {
        gc.reloadCfg();
        return true;
      }
      if (key.k_f12()) {
        createTools();
        return true;
      }
    }
    if (super.key(key, scancode, a)) return true;
    return ly.key(key, scancode, a);
  }
  
  
  
  public final HashMap<String, String> files = new HashMap<>();
  public String readFile(String path) {
    if (files.containsKey(path)) return files.get(path);
    String v;
    try {
      v = Files.readString(Paths.get(path));
    } catch (IOException e) {
      v = null;
    }
    files.put(path, v);
    return v;
  }
  
  public static int parseInt(EditNode n, int orig) {
    try {
      return Integer.parseInt(n.getAll());
    } catch (NumberFormatException e) {
      n.removeAll();
      n.append(String.valueOf(orig));
      return orig;
    }
  }
  
  public static Vec<Path> filesToRemoveOnClose = new Vec<>();
  static {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      for (Path c : filesToRemoveOnClose) {
        try { Files.deleteIfExists(c); }
        catch (IOException e) { Log.stacktrace("shutdown hook", e); }
      }
    }));
  }
}
