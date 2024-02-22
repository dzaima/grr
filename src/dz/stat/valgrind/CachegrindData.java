package dz.stat.valgrind;

import dz.general.Binary;
import dz.layouts.GdbLayout;
import dz.stat.StatGlobal.BasicSymbol;
import dz.stat.valgrind.branch.*;
import dz.stat.valgrind.cache.*;
import dzaima.utils.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.function.Consumer;

public class CachegrindData {
  //   /I.*/+3 == /D.*r/, /I.*/+6 == /D.*w/
  public static final int Ir     = 0; // instructions retired
  public static final int I1mr   = 1; //   missed L1
  public static final int ILmr   = 2; //   missed LL
  public static final int Dr     = 3; // data reads done
  public static final int D1mr   = 4; //   missed L1
  public static final int DLmr   = 5; //   missed LL
  public static final int Dw     = 6; // data writes done
  public static final int D1mw   = 7; //   missed L1
  public static final int DLmw   = 8; //   missed LL
  public static final int Drw    = 9;  // data r/w done
  public static final int D1mrw  = 10; //   missed L1
  public static final int DLmrw  = 11; //   missed LL
  public static final int Bc     = 12; // conditional branches
  public static final int Bcm    = 13; //   missed
  public static final int Bi     = 14; // indirect branches
  public static final int Bim    = 15; //   missed
  public static final int EV_UNK = 16;
  public static final int EV_N   = 17;
  private static final Map<String, Integer> evMap = Map.ofEntries(
    Map.entry("Ir",   Ir),
    Map.entry("I1mr", I1mr),
    Map.entry("ILmr", ILmr),
    Map.entry("Dr",   Dr),
    Map.entry("D1mr", D1mr),
    Map.entry("DLmr", DLmr),
    Map.entry("Dw",   Dw),
    Map.entry("D1mw", D1mw),
    Map.entry("DLmw", DLmw),
    Map.entry("Bc",   Bc),
    Map.entry("Bcm",  Bcm),
    Map.entry("Bi",   Bi),
    Map.entry("Bim",  Bim));
  
  
    
  public static void load(Path path, Path bin, GdbLayout p, Consumer<Pair<CacheStat, BranchStat>> got) {
    try {
      boolean mine = false;
      BranchStat branch = null;
      CacheStat cache = null;
      Binary b = new Binary(bin.toString(), bin.toString(), false);
      int[] map = new int[0];
      CacheStatSymbol cacheSym = null;
      BranchStatSymbol branchSym = null;
      
      for (String l : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        if (l.isEmpty()) continue;
        if (l.startsWith("fl=")) {
          mine = l.equals("fl=CU0//direct/byte/mapped/lines");
          cacheSym = null;
          branchSym = null;
        } else if (l.startsWith("fn=") && mine) {
          BasicSymbol sym = new BasicSymbol(b, l.substring(3), 0);
          if (cache!=null) cache.map.put(sym, cacheSym = new CacheStatSymbol(cache, sym));
          if (branch!=null) branch.map.put(sym, branchSym = new BranchStatSymbol(branch, sym));
        } else if (l.charAt(0)>='0' && l.charAt(0)<='9' && mine) {
          String[] s = Tools.split(l, ' ');
          long off = Long.parseUnsignedLong(s[0])-1;
          long[] vs = new long[EV_N];
          for (int i = 1; i < s.length; i++) vs[map[i-1]] = Long.parseUnsignedLong(s[i]);
          vs[Drw]   = vs[Dr]   + vs[Dw];
          vs[D1mrw] = vs[D1mr] + vs[D1mw];
          vs[DLmrw] = vs[DLmr] + vs[DLmw];
          if (cacheSym!=null) cacheSym.m.put(off, new CacheStatInstr(cacheSym, vs));
          if (branchSym!=null) branchSym.m.put(off, new BranchStatInstr(branchSym, vs));
        } else if (l.startsWith("events: ")) {
          String[] ps = Tools.split(l.substring(8), ' ');
          map = new int[ps.length];
          for (int i = 0; i < ps.length; i++) {
            Integer j = evMap.get(ps[i]);
            map[i] = j==null? EV_UNK : j;
            if (j!=null) {
              if (j==Dr || j==Dw) cache = new CacheStat();
              if (j==Bc || j==Bim) branch = new BranchStat();
            }
          }
        }
      }
      
      if (cache!=null) cache.configUpdated();
      if (branch!=null) branch.configUpdated();
      
      got.accept(new Pair<>(cache, branch));
    } catch (Throwable e) {
      Log.stacktrace("cachegrind parser", e);
      got.accept(null);
    }
  }
}
