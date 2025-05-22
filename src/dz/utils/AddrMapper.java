package dz.utils;

import dzaima.utils.*;

import java.util.*;

public class AddrMapper<T extends AddrMapper.Range> {
  public interface Range {
    long s(); // inclusive
    long e(); // exclusive
  }
  public static class ULong implements Comparable<ULong> {
    public final long l;
    public ULong(long l) { this.l = l; }
    public boolean equals(Object o) { return o instanceof ULong && l == ((ULong) o).l; }
    public int hashCode() { return Long.hashCode(l); }
    public int compareTo(ULong o) { return Long.compareUnsigned(l, o.l); }
    public String toString() { return "0x"+Long.toHexString(l); }
  }
  private final NavigableMap<ULong, T> ranges = new TreeMap<>();
  
  public void addRange(T r) {
    assert r.s()!=r.e();
    assert findRanges(r.s(), r.e()).sz == 0 : "Entries already present in ["+r.s()+";"+r.e()+"): "+ findRanges(r.s(), r.e());
    T prev = ranges.put(new ULong(r.e()), r);
    assert prev==null;
  }
  public void overrideRangeDangerous(T n) {
    for (T c : findRanges(n.s(), n.e())) remove(c);
    addRange(n);
  }
  public T find(long addr) {
    Map.Entry<ULong, T> c = ranges.higherEntry(new ULong(addr));
    if (c==null || c.getValue()==null) return null;
    T r = c.getValue();
    if (Tools.ulongGE(addr,r.s()) && Tools.ulongLT(addr,r.e())) return r;
    return null;
  }
  public boolean has(long addr) {
    return find(addr)!=null;
  }
  public void remove(T r) {
    boolean rm = ranges.remove(new ULong(r.e()), r);
    assert rm;
  }
  public void clear() {
    ranges.clear();
  }
  
  public Vec<T> all() {
    return Vec.ofCollection(ranges.values());
  }
  
  public void removeAll() {
    ranges.clear();
  }
  
  public boolean isEmpty() {
    return ranges.isEmpty();
  }
  
  public Vec<T> findRanges(long s, long e) {
    Vec<T> res = new Vec<>();
    NavigableMap<ULong, T> m = ranges.tailMap(new ULong(s), false);
    for (ULong c : m.navigableKeySet()) {
      T t = m.get(c);
      if (Tools.ulongGE(t.s(), e)) break;
      res.add(t);
    }
    return res;
  }
  
  public String toString() {
    return ranges.toString();
  }



  // static class Test implements Range {
  //   public final long s, e;
  //   Test(long s, long e) { this.s = s; this.e = e; }
  //   public long s() { return s; }
  //   public long e() { return e; }
  //   public String toString() { return "["+s+";"+e+")"; }
  // }
  // static {
  //   AddrMapper<Test> a = new AddrMapper<>();
  //   a.addRange(new Test(0, 10));
  //   a.addRange(new Test(15, 22));
  //   a.addRange(new Test(22, 25));
  //   System.out.println(); for (int i=0; i<30; i++) System.out.println("at "+i+": "+a.find(i));
  //   System.out.println(); for (int i=0; i<30; i++) System.out.println("in [0;"+i+"): "+a.findRanges(0, i));
  //   System.out.println(); for (int i=0; i<30; i++) System.out.println("in ["+i+";40): "+a.findRanges(i, 40));
  //   System.out.println(); for (int i=0; i<30; i++) System.out.println("in ["+i+";"+i+"): "+a.findRanges(i, i));
  //   System.exit(0);
  // }
}
