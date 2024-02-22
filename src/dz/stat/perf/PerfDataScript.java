package dz.stat.perf;

import dz.stat.StatGlobal;
import dz.stat.StatGlobal.MappedSymbol;
import dzaima.utils.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.regex.*;

public class PerfDataScript {
  private static final Pattern offPattern = Pattern.compile("\\+0x([0-9a-f]+) \\(");
  
  private static final HashMap<String, String> dedup = new HashMap<>();
  @SuppressWarnings("Java8MapApi") // we want fast
  private static String dedup(String s) {
    String p = dedup.get(s);
    if (p==null) dedup.put(s, p = s);
    return p;
  }
  
  public static PerfStat run(Path data) {
    ProcessBuilder pb = new ProcessBuilder("perf", "script", "-F", "ip,sym,symoff,dso", /*"--header",*/ "-i", data.toString());
    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
    String[] lns;
    try {
      Process p = pb.start();
      lns = Tools.split(new String(p.getInputStream().readAllBytes()), '\n');
    } catch (IOException e) {
      Log.stacktrace("perf script read", e);
      return null;
    }
    PerfStat res = new PerfStat(false);
    PerfStat.ThreadData td = res.newThread(0, 0);
    
    HashMap<String, Pair<StatGlobal.Mapping,HashMap<MappedSymbol, MappedSymbol>>> binMap = new HashMap<>();
    
    int fakeTs = 0;
    for (int i = 0; i < lns.length-1; i++) {
      String ln = lns[i];
      int aS = 0;
      while (ln.charAt(aS)==' ') aS++;
      int aE = ln.indexOf(' ', aS);
      long sample = Long.parseUnsignedLong(ln.substring(aS, aE), 16);
      
      int sS = aE+1;
      int sE, bS;
      boolean hasOff;
      long off;
      Matcher m = offPattern.matcher(ln);
      if (m.find(aE)) {
        hasOff = true;
        off = Long.parseUnsignedLong(m.group(1), 16);
        sE = m.start();
        bS = m.end();
      } else {
        hasOff = false;
        sE = ln.indexOf(" (", aE);
        bS = sE+2;
        off = -1;
      }
      
      int bE = ln.length()-1;
      
      long symAddr = hasOff? sample-off : -1;
      String name = dedup(ln.substring(sS, sE));
      var bin = binMap.computeIfAbsent(ln.substring(bS, bE), k -> new Pair<>(StatGlobal.Mapping.fromPerfName(k, 0, 0, 0), new HashMap<>()));
      MappedSymbol sym = bin.b.computeIfAbsent(new MappedSymbol(bin.a, name, symAddr), c->c);
      
      td.addTime(sym, off, fakeTs++);
    }
  
    return res;
  }
}
