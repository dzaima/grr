package dz.general.file;

import dzaima.utils.Vec;

import java.util.Comparator;
import java.util.function.LongConsumer;

import static dz.general.file.Elf.*;

public class Dwarf {
  private static int elfBits = 64;
  
  public abstract static class DwarfSection extends ElfWriter.Section {
    private ElfWriter.Data data;
    private byte[] cdata;
    protected abstract byte[] computeData();
    public ElfWriter.Data getData() {
      if (data==null) {
        cdata = computeData();
        data = new ElfWriter.Data() {
          public void writeTo(Writer w) { w.w(cdata); }
        };
      }
      return data;
    }
    public byte[] directData() {
      getData();
      return cdata;
    }
    protected final long addr() { return 0; }
    protected final int type() { return SHT_PROGBITS; }
  }
  public abstract static class DwarfStrSection extends DwarfSection {
    protected int flags() { return SHF_STRINGS | SHF_MERGE; }
  }
  
  
  
  public static final class DwarfSym {
    private final long s, e;
    private final String name;
    DwarfSym(long s, long e, String name) { this.s=s; this.e=e; this.name=name; }
  }
  public static class DebugInfo {
    public final Vec<DwarfSym> syms = new Vec<>();
    public void addSym(long s, long size, String name) { syms.add(new DwarfSym(s, s+size, name));  }
    
    public Vec<DwarfSection> finish() {
      Strtab tab = strtab(syms.map(c -> c.name));
      return Vec.of(
        new DwarfSection() {
          protected byte[] computeData() {
            Writer w = new Writer(32);
            Runnable sz = w.dwarfSz();
            w.w2(5);
            w.w1(1); // DW_UT_compile
            w.w1(elfBits==64? 8 : 4); // address_size
            w.wS(0); // .debug_abbrev offset?
            
            w.w1(1); // id 1
            w.wSb(0, elfBits); // DW_AT_low_pc
            w.wSb(1L<<48, elfBits); // DW_AT_high_pc
            w.wS(0); // DW_AT_stmt_list
            
            
            for (int i = 0; i < syms.sz; i++) {
              DwarfSym s = syms.get(i);
              w.w1(2); // id 2
              w.wSb(s.s, elfBits); // DW_AT_low_pc
              w.wSb(s.e, elfBits); // DW_AT_high_pc
              w.w4(tab.offs[i]);
              w.w1(0); // end thing? idk
            }
            w.w1(0); // ?
            
            sz.run();
            
            return w.get();
          }
          public String name() { return ".debug_info"; }
        },
        
        new DwarfStrSection() {
          protected byte[] computeData() { return tab.data(); }
          public String name() { return ".debug_str"; }
        },
        
        new DwarfSection() {
          protected byte[] computeData() {
            Writer w = new Writer(32);
            w.wULEB128(1); // id 1: CU
            w.w1(17); // DW_TAG_compile_unit
            w.w1(1); // DW_CHILDREN_yes
            // w.w1(19); // DW_AT_language
            // w.w1(8); // DW_FORM_string
            
            w.wv(17, 1); // DW_AT_low_pc, DW_FORM_addr
            w.wv(18, 1); // DW_AT_high_pc, DW_FORM_addr
            w.wv(16, 23); // DW_AT_stmt_list, DW_FORM_sec_offset
            w.wv(0, 0); // end?
            
            w.wULEB128(2); // id 2: fn desc
            w.w1(46); // DW_TAG_subprogram
            w.w1(1); // DW_children_yes
            w.wv(17, 1); // DW_AT_low_pc, DW_FORM_addr
            w.wv(18, 1); // DW_AT_high_pc, DW_FORM_addr
            w.wv(3, 14); // DW_AT_name, DW_FORM_strp
            w.wv(0, 0); // end?
            
            w.w1(0); // ?
            return w.get();
          }
          public String name() { return ".debug_abbrev"; }
        }
      );
    }
  }
  
  
  
  public static class LineInfo {
    private final Vec<Line> lines = new Vec<>();
    
    static final class Line {
      private final long addr;
      private final String file;
      private final int line, col;
      Line(long addr, String file, int line, int col) {
        this.addr = addr;
        this.file = file;
        this.line = line;
        this.col = col;
      }
    }
    public void line(long addr, String file, int line, int col) {
      lines.add(new Line(addr, file, line, col));
    }
    
    public Vec<DwarfSection> finish() {
      lines.sort(Comparator.comparing(c -> c.addr));
      Vec<String> strs = new Vec<>();
      strs.add("CU0");
      for (Line c : lines) strs.add(c.file);
      Strtab tab = strtab(strs);
      
      return Vec.of(
        new DwarfSection() {
          protected byte[] computeData() {
            Writer w = new Writer(32);
            
            Runnable sz = w.dwarfSz();
            w.w2(5); // version
            w.w1(elfBits==64? 8 : 4); // address_size
            w.w1(0); // segment_selector_size
            LongConsumer hl = w.prepS(); // bytes of header left
            int hl0 = w.off();
            w.w1(1); // min instr length
            w.w1(1); // max instr length
            w.w1(1); // default is stmt
            w.w1(-5); // line_base
            w.w1(14); // line_range
            
            byte[] sop = new byte[]{0,1,1,1,1,0,0,0,1,0,0,1};
            w.w1(sop.length+1); // opcode_base
            w.w(sop); // standard_opcode_lengths
            
            w.w1(1); // directory_entry_format_count
            w.wULEB128(1);
            w.wULEB128(31);
            
            w.wULEB128(1); // directories_count
            w.wS(tab.offs[0]); // ⊑tab.offs
            
            w.w1(1); // file_name_entry_format_count
            w.wULEB128(1); // DW_LNCT_path
            w.wULEB128(31); // DW_FORM_line_strp
            
            w.wULEB128(tab.uoffs.length); // file_names_count
            for (long c : tab.uoffs) w.wS(c);
            
            hl.accept(w.off()-hl0);
            
            int state_ln = 0;
            long state_pc = 0;
            int state_cl = 0;
            int state_fl = -1;
            
            for (int i = 0; i < lines.sz; i++) {
              Line l = lines.get(i);
              int cln = l.line;
              long cpc = l.addr;
              int cfl = tab.idxs[i+1];
              int ccl = l.col;
              if (cpc!=state_pc) { w.w1(2); w.wULEB128(cpc-state_pc); state_pc=cpc; } // advance pc
              if (cfl!=state_fl) { w.w1(4); w.wULEB128(cfl);          state_fl=cfl; } // set file
              if (cln!=state_ln) { w.w1(3); w.wSLEB128(cln-state_ln); state_ln=cln; } // advance line
              if (ccl!=state_cl) { w.w1(5); w.wULEB128(ccl);          state_cl=ccl; } // set column
              w.w1(1); // copy
            }
            
            w.w(new byte[]{0,1,1}); // end of sequence
            sz.run();
            return w.get();
          }
          public String name() { return ".debug_line"; }
        },
        
        new DwarfStrSection() {
          protected byte[] computeData() { return tab.data(); }
          public String name() { return ".debug_line_str"; }
        }
      );
    }
  }
}
