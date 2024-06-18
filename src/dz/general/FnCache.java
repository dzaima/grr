package dz.general;

import dz.debugger.Location;
import dz.gdb.*;
import dz.utils.OverlapMapper;
import dzaima.utils.Vec;

import java.util.*;
import java.util.function.Consumer;

public class FnCache {
  public static final Set<String> DISAS_BLACKLIST = Set.of(
    "_breakpoint_table_entry_end",
    "code_gen_buffer" // QEMU's multi-megabyte buffer of all its JITted things
  );
  
  private static final int JIT_BYTES = 1000;
  OverlapMapper<DisasFn> jitMapGlobal = new OverlapMapper<>();
  public final HashMap<Long, OverlapMapper<DisasFn>> jitMaps = new HashMap<>();
  public final OverlapMapper<DisasFn> disasCache = new OverlapMapper<>();
  
  public void prepRead(DisasFn f, Dbi d, boolean isLive, Consumer<DisasFn> got) {
    if (!isLive) {
      got.accept(f);
    } else if (f.e-f.s < 100000) {
      d.curr.disasSegment(f.s, f.e, Executable.DisasMode.OPS, ins -> {
        if (ins==null) {
          got.accept(f);
        } else {
          DisasFn r = new DisasFn(f.s, f.e, f.name, insns(ins), true, null);
          if (shouldCacheJIT) disasCache.addFullRange(r);
          got.accept(r);
        }
      });
    }
  }
  
  public enum NameMode { RANGE_ONLY, NONE, EXACT, PREFIX }
  public void disas(long pid, Dbi d, Location l, NameMode mode, Consumer<DisasFn> got, boolean isLive) {
    if (l.sym!=null && DISAS_BLACKLIST.contains(l.sym)) mode = NameMode.RANGE_ONLY;
    
    if (l.addr!=null && disasCache.find(l.addr)!=null) {
      got.accept(disasCache.findBase(l.addr));
      return;
    }
    
    DisasFn a = getJIT(pid, l);
    if (a!=null) { prepRead(a, d, isLive, got); return; }
    
    d.curr.disasLocation(l, false, Executable.DisasMode.OPS, mode, JIT_BYTES, (name, s, e, ins, proper) -> {
      if (proper==Executable.Properness.NONE) {
        got.accept(null);
      } else {
        DisasFn r = new DisasFn(s, e, name, insns(ins), proper==Executable.Properness.DYN, null);
        disasCache.addFullRange(r);
        got.accept(r);
      }
    });
  }
  
  public DisasFn addJITRange(long pid, long s, long e, String name) {
    DisasFn fn = new DisasFn(s, e, name, null, true, null);
    // TODO when GdbLayout.getDisas doesn't use -1, enable the below
    // if (pid==-1) jitMapGlobal.addFullRange(fn);
    // else jitMaps.computeIfAbsent(pid, l->new OverlapMapper<>()).addFullRange(fn);
    jitMapGlobal.addFullRange(fn);
    return fn;
  }
  
  public DisasFn getJIT(long pid, Location l) {
    if (l.addr==null) return null;
    OverlapMapper<DisasFn> m = jitMaps.get(pid);
    if (m!=null) {
      DisasFn a = m.findBase(l.addr);
      if (a!=null) return a;
    }
    
    return jitMapGlobal.findBase(l.addr);
  }
  
  
  public static DisasFn.ParsedIns[] insns(Vec<Executable.Ins> is) {
    if (is==null) return null;
    Vec<DisasFn.ParsedIns> insns = new Vec<>();
    for (Executable.Ins o : is) insns.add(new DisasFn.ParsedIns(o.addr, o.raw, o.repr));
    return insns.toArray(new DisasFn.ParsedIns[0]);
  }
  
  public boolean shouldCacheJIT = true;
  public void setShouldCacheJIT(boolean should) {
    shouldCacheJIT = should;
    disasCache.removeAll();
  }
}
