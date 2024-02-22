package dz.gdb;

import dzaima.utils.*;

import java.util.HashMap;

public class GdbFormat {
  public static boolean dig(char c) { return c>='0' && c<='9'; }
  
  private static char octal(int i) { return (char) (i+'0'); }
  public static void escape(StringBuilder b, String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if      (c=='\"') b.append("\\\"");
      else if (c=='\\') b.append("\\\\");
      else b.append(c);
    }
  }
  public static String quote(String s) {
    StringBuilder b = new StringBuilder("\"");
    escape(b, s);
    return b.append('"').toString();
  }
  
  public static String fmtCmd(String cmd) {
    return "'"+cmd.substring(0, cmd.length()-1)+"'";
  }
  
  public static int readHex(char c) {
    return c>='0'&c<='9'? c-'0' : c>='a'&&c<='f'? c-'a'+10 : c>='A'&&c<='F'? c-'A'+10 : -1;
  }
  public static byte readByte(String s, int off) {
    return (byte) (readHex(s.charAt(off))*16 + readHex(s.charAt(off+1)));
  }
  
  public static long parseHex(String s) {
    if (s.startsWith("0x")) s = s.substring(2);
    return Long.parseUnsignedLong(s, 16);
  }
  public static Long numFromPrefix(String s) {
    if (s==null || s.isEmpty()) return null;
    if (s.startsWith("0x")) {
      int i = 2;
      while (i<s.length() && readHex(s.charAt(i))!=-1) i++;
      return Long.parseUnsignedLong(s, 2, i, 16);
    }
    int i = 0;
    while (i<s.length() && dig(s.charAt(i))) i++;
    return Long.parseUnsignedLong(s.substring(0, i));
  }
  
  
  
  public abstract static class GVal {
    public String str() { return ((GStr)this).val; }
    public long longS() { return Long.parseLong(str()); }
    public long addr() { return parseHex(str()); }
    public int asInt() { return Math.toIntExact(longS()); }
    
    public int size() { throw new RuntimeException("this isn't an object with size"); }
    
    public GVal get(int i) { throw new RuntimeException("this isn't an ordered list"); }
    public Vec<GVal> vs() { throw new RuntimeException("this isn't a key-value object"); }
    
    public boolean has(String k) { throw new RuntimeException("this isn't a key-value object"); }
    public GVal get(String k) { throw new RuntimeException("this isn't a key-value object"); }
    public GVal get(String k, GVal def) { GVal v = get(k); return v==null? def : v; }
    public long getAddr(String k) { return get(k).addr(); }
    public int getInt(String k) { return get(k).asInt(); }
    public String getStr(String k) { return get(k).str(); }
    public Long optAddr(String k) { GVal v = get(k); return v==null? null : v.addr(); }
    public Integer optInt(String k) { GVal v = get(k); return v==null? null : v.asInt(); }
    public int optInt(String k, int def) { GVal v = get(k); return v==null? def : v.asInt(); }
    public String optStr(String k) { GVal v = get(k); return v==null? null : v.str(); }
    public Vec<Pair<String, GVal>> es() { throw new RuntimeException("this isn't a key-value object"); }
  }
  public static class GStr extends GVal {
    final String val;
    public GStr(String val) { this.val = val; }
    public String toString() { return GdbFormat.quote(val); }
  }
  public static class GList extends GVal {
    public static final GList empty = new GList(new Vec<>());
    final Vec<GVal> vals;
    public GList(Vec<GVal> vals) { this.vals = vals; }
    public int size() { return vals.sz; }
    public GVal get(int i) { return vals.get(i); }
    public String toString() { return vals.toString(); }
    public Vec<GVal> vs() { return vals; }
  }
  public static class GKVList extends GVal {
    public static final GKVList emptyUnordered = new GKVList(new Vec<>(), false);
    public static final GKVList emptyOrdered = new GKVList(new Vec<>(), true);
    final Vec<Pair<String, GVal>> es;
    public final boolean ordered;
    public GKVList(Vec<Pair<String, GVal>> es, boolean ordered) {
      this.es = es;
      this.ordered = ordered;
    }
    public int size() {
      return es.sz;
    }
    public GVal get(int i) {
      return es.get(i).b;
    }
    
    private HashMap<String, GVal> map;
    private void initMap() {
      if (map!=null) return;
      map = new HashMap<>();
      for (Pair<String, GVal> c : es) map.put(c.a, c.b);
    }
    public boolean has(String k) {
      initMap();
      return map.containsKey(k);
    }
    public GVal get(String k) {
      initMap();
      return map.get(k);
    }
    public Vec<Pair<String, GVal>> es() {
      return es;
    }
    
    public Vec<GVal> vs() {
      Vec<GVal> res = new Vec<>();
      for (Pair<String, GVal> c : es) res.add(c.b);
      return res;
    }
    
    public String toString() {
      StringBuilder b = new StringBuilder();
      b.append(ordered? '[' : '{');
      boolean first = true;
      for (Pair<String, GVal> e : es) {
        if (first) first=false;
        else b.append(',');
        b.append(e.a).append('=').append(e.b);
      }
      b.append(ordered? ']' : '}');
      return b.toString();
    }
  }
}
