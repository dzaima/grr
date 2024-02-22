package dz.utils;

import dzaima.utils.*;

public class OverlapMapper<T extends AddrMapper.Range> {
  private final AddrMapper<Portion<T>> map = new AddrMapper<>();
  
  public void addFullRange(T r) {
    addRange(r, r.s(), r.e());
  }
  public void removeRange(long s, long e) {
    for (Portion<T> c : map.findRanges(s, e)) {
      long cs = c.s;
      long ce = c.e;
      map.remove(c);
      if (Tools.ulongLT(cs,s)) map.addRange(new Portion<>(c.base, cs, s));
      if (Tools.ulongGT(ce,e)) map.addRange(new Portion<>(c.base, e, ce));
    }
  }
  
  public void addRange(T r, long s, long e) {
    removeRange(s, e);
    map.addRange(new Portion<>(r, s, e));
  }
  
  public Portion<T> find(long addr) {
    return map.find(addr);
  }
  public T findBase(long addr) {
    Portion<T> p = map.find(addr);
    return p==null? null : p.base;
  }
  public Vec<Portion<T>> findRanges(long s, long e) {
    return map.findRanges(s, e);
  }
  
  public boolean isEmpty() {
    return map.isEmpty();
  }
  
  public Vec<Portion<T>> all() {
    return map.all();
  }
  public void removeAll() {
    map.removeAll();
  }
  
  
  public static class Portion<T extends AddrMapper.Range> implements AddrMapper.Range {
    public final long s, e;
    public final T base;
    public Portion(T base, long s, long e) {
      assert Tools.ulongLT(s,e) && Tools.ulongGE(s,base.s()) && Tools.ulongLE(e,base.e()) : s+";"+e+" not within "+base;
      this.s = s;
      this.e = e;
      this.base = base;
    }
    public long s() { return s; }
    public long e() { return e; }
    
    public String toString() { return "["+s+";"+e+"): "+base; }
  }
  
  
  
  // static class Test implements AddrMapper.Range {
  //   public final long s, e;
  //   Test(long s, long e) { this.s = s; this.e = e; }
  //   public long s() { return s; }
  //   public long e() { return e; }
  //   public String toString() { return s+".."+(e-1); }
  // }
  // static {
  //   OverlapMapper<Test> a = new OverlapMapper<>();
  //   a.addFullRange(new Test(0, 100));
  //   a.addRange(new Test(0, 10), 5, 10);
  //   a.addFullRange(new Test(15, 22));
  //   a.addFullRange(new Test(22, 25));
  //   System.out.println(); for (int i=0; i<30; i++) System.out.println("at "+i+": "+a.find(i));
  //   System.out.println(); for (int i=0; i<30; i++) System.out.println("in [0;"+i+"): "+a.findRanges(0, i));
  //   System.out.println(); for (int i=0; i<30; i++) System.out.println("in ["+i+";40): "+a.findRanges(i, 40));
  //   System.out.println(); for (int i=0; i<30; i++) System.out.println("in ["+i+";"+i+"): "+a.findRanges(i, i));
  //   System.exit(0);
  // }
}
