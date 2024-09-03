package dz.stat.perf;

import dz.general.*;
import dz.general.symbolize.*;
import dz.layouts.GdbLayout;
import dz.stat.StatGlobal.*;
import dz.utils.*;
import dzaima.utils.*;

import java.nio.file.Path;
import java.util.*;
import java.util.function.*;

@SuppressWarnings("PointlessBitwiseExpression")
public class PerfDataManualParser extends ByteReader {
  public static void run(Path data, PerfStat.LoadMode loadMode, Function<String, String> remap, GdbLayout l, Vec<Mapping> initMappings, boolean unrelocate, Consumer<PerfStat> got) {
    try {
      PerfDataManualParser pr = new PerfDataManualParser();
      if (!pr.loadFromFile(data) || pr.size()==0) { got.accept(null); return; }
      pr.start(l, loadMode, remap, got, initMappings, unrelocate);
    } catch (Throwable e) {
      Log.stacktrace("perf parser read", e);
      got.accept(null);
    }
  }
  
  private static final boolean DEBUG_PARSER = false;
  
  public static final int PERF_RECORD_MMAP = 1;
  public static final int PERF_RECORD_MUNMAP = 2;
  public static final int PERF_RECORD_SAMPLE = 9;
  public static final int PERF_RECORD_MMAP2 = 10;
  
  public static final long PERF_SAMPLE_IP             = 1<<0;
  public static final long PERF_SAMPLE_TID            = 1<<1;
  public static final long PERF_SAMPLE_TIME           = 1<<2;
  public static final long PERF_SAMPLE_ADDR           = 1<<3;
  public static final long PERF_SAMPLE_READ           = 1<<4;
  public static final long PERF_SAMPLE_CALLCHAIN      = 1<<5;
  public static final long PERF_SAMPLE_ID             = 1<<6;
  public static final long PERF_SAMPLE_CPU            = 1<<7;
  public static final long PERF_SAMPLE_PERIOD         = 1<<8;
  public static final long PERF_SAMPLE_STREAM_ID      = 1<<9;
  public static final long PERF_SAMPLE_RAW            = 1<<10;
  public static final long PERF_SAMPLE_BRANCH_STACK   = 1<<11;
  public static final long PERF_SAMPLE_REGS_USER      = 1<<12;
  public static final long PERF_SAMPLE_STACK_USER     = 1<<13;
  public static final long PERF_SAMPLE_WEIGHT         = 1<<14;
  public static final long PERF_SAMPLE_DATA_SRC       = 1<<15;
  public static final long PERF_SAMPLE_IDENTIFIER     = 1<<16;
  public static final long PERF_SAMPLE_TRANSACTION    = 1<<17;
  public static final long PERF_SAMPLE_REGS_INTR      = 1<<18;
  public static final long PERF_SAMPLE_PHYS_ADDR      = 1<<19;
  public static final long PERF_SAMPLE_AUX            = 1<<20;
  public static final long PERF_SAMPLE_CGROUP         = 1<<21;
  public static final long PERF_SAMPLE_DATA_PAGE_SIZE = 1<<22;
  public static final long PERF_SAMPLE_CODE_PAGE_SIZE = 1<<23;
  public static final long PERF_SAMPLE_WEIGHT_STRUCT  = 1<<24;
  
  public static final long PERF_SAMPLE_BRANCH_HW       = 1<<17;
  public static final long PERF_SAMPLE_BRANCH_COUNTERS = 1<<19;
  
  public static final long PERF_FORMAT_TOTAL_TIME_ENABLED = 1<<0;
  public static final long PERF_FORMAT_TOTAL_TIME_RUNNING = 1<<1;
  public static final long PERF_FORMAT_ID                 = 1<<2;
  public static final long PERF_FORMAT_GROUP              = 1<<3;
  public static final long PERF_FORMAT_LOST               = 1<<4;
  
  private PerfDataManualParser() { }
  
  static class Section {
    final long off, size, end;
    Section(long off, long size) {
      this.off = off;
      this.size = size;
      end = off+size;
      if (DEBUG_PARSER) System.out.println("section "+off+"…"+end+": "+size+" bytes");
    }
  }
  
  long readSampleIdTs(PerfEventAttr a) {
    long ts = -1;
    if (a.sample_id_all) {
      alignUp(8);
      if (a.sample_has(PERF_SAMPLE_TID))        { u32(); u32(); } // pid; tid  
      if (a.sample_has(PERF_SAMPLE_TIME))       { ts = u64();   } // time      
      if (a.sample_has(PERF_SAMPLE_ID))         { u64();        } // id1   
      if (a.sample_has(PERF_SAMPLE_STREAM_ID))  { u64();        } // stream_id 
      if (a.sample_has(PERF_SAMPLE_CPU))        { u32(); u32(); } // cpu; res  
      if (a.sample_has(PERF_SAMPLE_IDENTIFIER)) { u64();        } // id2
    }
    return ts;
  }
  
  static abstract class Evt implements Comparable<Evt> {
    public final long ts, pid, tid;
    protected Evt(long ts, long pid, long tid) { this.ts=ts; this.pid=pid; this.tid = tid; }
    public int compareTo(Evt o) { return Long.compare(ts, o.ts); }
  }
  static class SampleEvt extends Evt {
    public final long ip;
    SampleEvt(long ts, long pid, long tid, long ip) { super(ts, pid, tid); this.ip=ip; }
  }
  static class MmapEvt extends Evt {
    public final Mapping b;
    public MmapEvt(long ts, long pid, long tid, Mapping b) { super(ts, pid, tid); this.b=b; }
  }
  
  void start(GdbLayout l, PerfStat.LoadMode loadMode, Function<String, String> remap, Consumer<PerfStat> got, Vec<Mapping> initMappings, boolean toUnreloc) {
    if (u64()!=0x32454C4946524550L) { got.accept(null); return; }
    long header_size = u64();
    long attr_size = u64();
    Section s_attrs = new Section(u64(), u64());
    Section s_data = new Section(u64(), u64());
    Section s_event_types = new Section(u64(), u64());
    
    // section: attrs
    setPos(s_attrs.off);
    PerfEventAttr a = null;
    while (getPos() < s_attrs.end) {
      long pos0 = getPos();
      a = new PerfEventAttr(this);
      setPos(pos0 + attr_size);
    }
    if (a==null) { got.accept(null); return; }
    
    Vec<Evt> evts = new Vec<>();
    // section: data
    setPos(s_data.off);
    long fakeTs = 0;
    while (getPos() < s_data.end) {
      long pos0 = getPos();
      int type = u32();
      int misc = u16();
      int size = u16();
      if (type == PERF_RECORD_MMAP) {
        int pid = u32();
        int tid = u32();
        long addr = u64();
        long len = u64();
        long pgoff = u64();
        String name = remap.apply(str());
        long ts = readSampleIdTs(a);
        if (DEBUG_PARSER) System.out.println("["+ts+"] mmap1 "+name+" @ 0x"+Long.toHexString(addr)+": len=0x"+Long.toHexString(len)+" pgoff=0x"+Long.toHexString(pgoff));
        evts.add(new MmapEvt(ts, pid, tid, Mapping.fromPerfName(name, addr, addr+len, pgoff)));
      } else if (type == PERF_RECORD_MUNMAP) {
        int pid = u32();
        int tid = u32();
        long addr = u64();
        long len = u64();
        long pgoff = u64();
        String filename = remap.apply(str());
        if (DEBUG_PARSER) System.out.println("munmap "+filename+" @ 0x"+Long.toHexString(addr)+": len=0x"+Long.toHexString(len)+" pgoff=0x"+Long.toHexString(pgoff));
      } else if (type == PERF_RECORD_MMAP2) {
        int pid = u32();
        int tid = u32();
        long addr = u64();
        long len = u64();
        long pgoff = u64();
        u64(); u64(); u64(); // maj,min,ino,ino_generation or build_id_size,build_id
        int prot = u32();
        int flags = u32();
        String name = remap.apply(str());
        long ts = readSampleIdTs(a);
        if (DEBUG_PARSER) System.out.println("["+ts+"] mmap2 "+name+" @ 0x"+Long.toHexString(addr)+": len=0x"+Long.toHexString(len)+" pgoff=0x"+Long.toHexString(pgoff));
        evts.add(new MmapEvt(ts, pid, tid, Mapping.fromPerfName(name, addr, addr+len, pgoff)));
      } else if (type == PERF_RECORD_SAMPLE) {
        long ip=0, ts=fakeTs++;
        long pid=0, tid=0;
        // https://github.com/torvalds/linux/blob/420b2d431d18a2572c8e86579e78105cb5ed45b0/include/uapi/linux/perf_event.h#L940-L1019
        // (but that has some inaccuracies, e.g. PERF_SAMPLE_STACK_USER's dyn_size isn't read if size==0)
        if (a.sample_has(PERF_SAMPLE_IDENTIFIER)) { u64();                } // id
        if (a.sample_has(PERF_SAMPLE_IP))         { ip=u64();             } // ip
        if (a.sample_has(PERF_SAMPLE_TID))        { pid=u32(); tid=u32(); } // pid; tid
        if (a.sample_has(PERF_SAMPLE_TIME))       { ts = u64();           } // time
        if (a.sample_has(PERF_SAMPLE_ADDR))       { u64();                } // addr
        if (a.sample_has(PERF_SAMPLE_ID))         { u64();                } // id
        if (a.sample_has(PERF_SAMPLE_STREAM_ID))  { u64();                } // stream_id
        if (a.sample_has(PERF_SAMPLE_CPU))        { u32(); u32();         } // cpu; res
        if (a.sample_has(PERF_SAMPLE_PERIOD))     { u64();                } // period
        if (a.sample_has(PERF_SAMPLE_READ)) { // struct read_format
          boolean group = a.format_has(PERF_FORMAT_GROUP);
          long nr = group? u64() : 1;
          if (!group) u64(); // value
          if (a.format_has(PERF_FORMAT_TOTAL_TIME_ENABLED)) u64();
          if (a.format_has(PERF_FORMAT_TOTAL_TIME_RUNNING)) u64();
          for (int i = 0; i < nr; i++) {
            if (group) u64();
            if (a.format_has(PERF_FORMAT_ID))   u64();
            if (a.format_has(PERF_FORMAT_LOST)) u64();
          }
        }
        if (a.sample_has(PERF_SAMPLE_CALLCHAIN)) {
          long nr = u64();
          for (int i = 0; i < nr; i++) u64(); // ips
        }
        if (a.sample_has(PERF_SAMPLE_RAW)) { skip((int) u64()); }
        if (a.sample_has(PERF_SAMPLE_BRANCH_STACK)) {
          long nr = u64();
          System.out.println(nr);
          if (a.branch_sample_type_has(PERF_SAMPLE_BRANCH_HW)) u64(); // hw_idx
          for (int i = 0; i < nr; i++) { u64(); u64(); u64(); } // lbr: from, to, flags
          if (a.branch_sample_type_has(PERF_SAMPLE_BRANCH_COUNTERS)) for (int i = 0; i < nr; i++) u64(); // counters
        }
        if (a.sample_has(PERF_SAMPLE_REGS_USER)) {
          long abi = u64(); // abi - enum perf_sample_regs_abi
          if (abi != 0) {
            int n = Long.bitCount(a.sample_regs_user);
            for (int i = 0; i < n; i++) u64();
          }
        }
        if (a.sample_has(PERF_SAMPLE_STACK_USER)) {
          long sz = u64();
          if (sz!=0) {
            for (int i = 0; i < sz; i++) u8(); // data
            long dyn_size = u64(); // dyn_size
          }
        }
        if (a.sample_has(PERF_SAMPLE_WEIGHT)) {
          u64(); // u32,u16,u16 give or take endianness
        }
        if (a.sample_has(PERF_SAMPLE_DATA_SRC)) { u64(); }
        if (a.sample_has(PERF_SAMPLE_TRANSACTION)) { u64(); }
        if (a.sample_has(PERF_SAMPLE_REGS_INTR)) {
          u64(); // abi - enum perf_sample_regs_abi
          int n = Long.bitCount(a.sample_regs_intr);
          for (int i = 0; i < n; i++) u64();
        }
        if (a.sample_has(PERF_SAMPLE_PHYS_ADDR)) { u64(); }
        if (a.sample_has(PERF_SAMPLE_AUX)) { skip((int) u64()); }
        if (a.sample_has(PERF_SAMPLE_DATA_PAGE_SIZE)) u64();
        if (a.sample_has(PERF_SAMPLE_CODE_PAGE_SIZE)) u64();
        // System.out.println("used "+(getPos()-pos0)+" bytes out of "+size+"-byte sample");
        evts.add(new SampleEvt(ts, pid, tid, ip));
      } else {
        if (DEBUG_PARSER) System.out.println("unk @ "+pos0+": "+type+" / "+misc+" / "+size);
      }
      
      assert getPos() <= pos0+size : "Sample was marked as "+size+"B, but parser ended up reading "+(getPos()-pos0)+"B";
      setPos(pos0 + size);
    }
    
    // sort events, group by mapping
    evts.sort();
    
    AddrMapper<Mapping> mappings = new AddrMapper<>();
    AddrMapper<Mapping> fMaps = null;
    if (initMappings.sz > 0) {
      fMaps = new AddrMapper<>();
      for (Mapping m : initMappings) fMaps.overrideRange(m);
    }
    Mapping defaultBin = new Mapping(new Binary(null, "//unknown", false), 0, -1, 0);
    
    HashMap<Binary, Vec<Pair<Mapping, SampleEvt>>> perBin = new HashMap<>();
    HashMap<Long, OverlapMapper<Mapping>> jitMapFns = new HashMap<>();
    
    for (Evt c : evts) {
      if (c instanceof MmapEvt) {
        mappings.overrideRange(((MmapEvt) c).b);
      } else if (c instanceof SampleEvt) {
        SampleEvt s = (SampleEvt) c;
        
        Mapping m = fMaps==null? null : fMaps.find(s.ip);
        if (m==null) m = mappings.find(s.ip);
        
        if (m==null || "//anon".equals(m.bin.desc)) {
          OverlapMapper<Mapping> map = jitMapFns.computeIfAbsent(c.pid, pid -> {
            OverlapMapper<Mapping> r = new OverlapMapper<>();
            for (DisasFn f : l.loadJITMap(pid)) r.addFullRange(new Mapping(Binary.virtSym(f.name, f.name), f.s, f.e, 0));
            return r;
          });
          m = map.findBase(((SampleEvt) c).ip);
          if (m==null || "//anon".equals(m.bin.desc)) m = defaultBin;
        }
        if (DEBUG_PARSER) System.out.println("["+s.ts+"] sample @ "+Long.toHexString(s.ip)+": map="+m.bin.desc);
        perBin.computeIfAbsent(m.bin, k->new Vec<>()).add(new Pair<>(m, s));
      }
    }
    
    
    PerfStat res = new PerfStat(true);
    
    HashMap<Pair<Long, Long>, PerfStat.ThreadData> threadMap = new HashMap<>();
    
    ArrayList<Map.Entry<Binary, Vec<Pair<Mapping, SampleEvt>>>> todo = new ArrayList<>(perBin.entrySet());
    RepeatRunnable.run(next -> {
      if (todo.isEmpty()) {
        res.finish();
        got.accept(res);
        return;
      }
      Map.Entry<Binary, Vec<Pair<Mapping, SampleEvt>>> e = todo.remove(todo.size()-1);
      Binary bin = e.getKey();
      Vec<Pair<Mapping, SampleEvt>> es = e.getValue();
      Vec<Symbolize.IPEntry> ips = es.map(c -> new Symbolize.IPEntry(c.a, c.b.ip));
      
      Consumer<Vec<Symbolize.Resolved>> got2 = r2 -> {
        HashMap<MappedSymbol, MappedSymbol> cache = new HashMap<>();
        for (int i = 0; i < es.sz; i++) {
          Pair<Mapping, SampleEvt> v1 = es.get(i);
          Symbolize.Resolved v2 = r2.get(i);
          PerfStat.ThreadData data = threadMap.computeIfAbsent(new Pair<>(v1.b.pid, v1.b.tid), p -> res.newThread(p.a, p.b));
          // System.out.println(Long.toHexString(v1.b.ip)+": "+v2.sym+"+"+(v1.b.ip - v2.sym_dyn)+" (static "+Long.toHexString(v2.sym_static)+", dyn "+Long.toHexString(v2.sym_dyn)+")");
          MappedSymbol s = new MappedSymbol(v1.a, v2.sym, v2.sym_static);
          data.addTime(cache.computeIfAbsent(s, c->c), v1.b.ip-v2.sym_dyn, v1.b.ts);
        }
        next.run();
      };
      
      if (bin.virtSym) {
        Vec<Symbolize.Resolved> r = new Vec<>();
        if (ips.sz>0) {
          long start = ips.get(0).m.addrS;
          Symbolize.Resolved resolved = new Symbolize.Resolved(start, start, bin.virtSymName());
          for (int i = 0; i < ips.sz; i++) r.add(resolved);
        }
        got2.accept(r);
      } else if (loadMode == PerfStat.LoadMode.GDB) {
        SymbolizeGDB.symbolize(l.d, bin, ips, got2);
      } else {
        SymbolizeElf.symbolize(l, bin, toUnreloc, ips, r2 -> {
          if (r2!=null) got2.accept(r2);
          else if (toUnreloc) SymbolizeGDB.symbolize(l.d, bin, ips, got2);
          else Symbolize.noSymbols(ips, got2);
        });
      }
    });
  }
  
  private static class PerfEventAttr {
    public final int type;
    public final long config;
    public final long sample_p_f;
    public final long sample_type;
    public final long read_format;
    public final long bitfield_mess;
    public final boolean sample_id_all;
    public final int wakeup_something;
    public final int bp_type;
    public final long union1;
    public final long union2;
    public final long branch_sample_type;
    public final long sample_regs_user;
    public final int sample_stack_user;
    public final int clockid;
    public final long sample_regs_intr;
    public final int aux_watermark;
    public final int sample_max_stack;
    public final int aux_sample_size;
    
    private PerfEventAttr(PerfDataManualParser p) {
      // https://github.com/torvalds/linux/blob/420b2d431d18a2572c8e86579e78105cb5ed45b0/include/uapi/linux/perf_event.h#L384
      type = p.u32();
      p.u32(); // size
      config = p.u64();
      sample_p_f = p.u64();
      sample_type = p.u64();
      read_format = p.u64();
      bitfield_mess = p.u64();
      wakeup_something = p.u32();
      bp_type = p.u32();
      union1 = p.u64();
      union2 = p.u64();
      branch_sample_type = p.u64();
      sample_regs_user = p.u64();
      sample_stack_user = p.u32();
      clockid = p.u32();
      sample_regs_intr = p.u64();
      aux_watermark = p.u32();
      sample_max_stack = p.u16();
      p.u16(); // __reserved_2
      aux_sample_size = p.u32();
      p.u32(); // __reserved_3
      p.u64(); // sig_data
      sample_id_all = (bitfield_mess&(1<<18)) != 0;
    }
    
    public boolean sample_has(long prop) {
      return (sample_type&prop)!=0;
    }
    public boolean format_has(long prop) {
      return (read_format&prop)!=0;
    }
    public boolean branch_sample_type_has(long prop) {
      return (branch_sample_type&prop)!=0;
    }
  }
}
