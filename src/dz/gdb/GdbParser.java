package dz.gdb;

import dz.gdb.GdbFormat.*;
import dzaima.utils.*;

import java.nio.charset.StandardCharsets;

public class GdbParser {
  public static GVal parse(String s) {
    GdbParser p = new GdbParser(s);
    p.skip();
    GVal r = p.get();
    p.skip();
    if (p.i != s.length()) throw new RuntimeException("Input contained text after end");
    return r;
  }
  
  int i = 0;
  final String s;
  GdbParser(String s) {
    this.s = s;
  }
  
  void skip() {
    char c;
    while (i<s.length() && ((c=s.charAt(i))==' ' || c=='\t' || c=='\n' || c=='\r')) i++;
  }
  char peek() { if (i>=s.length()) throw new RuntimeException("Input ended early"); return s.charAt(i); }
  char next() { if (i>=s.length()) throw new RuntimeException("Input ended early"); return s.charAt(i++); }
  GVal get() {
    if (i>=s.length()) throw new RuntimeException(i==0? "Empty input" : "Input ended early");
    switch (s.charAt(i)) {
      case '{': {
        i++;
        skip();
        if (peek()=='}') { i++; return GKVList.emptyUnordered; }
        Vec<Pair<String, GVal>> es = new Vec<>();
        while (true) {
          int li = nameEnd(s, i);
          if (li==i) throw new RuntimeException("Expected name at "+i);
          String k = s.substring(i, li);
          i = li;
          if (next()!='=') throw new RuntimeException("Expected '=' at "+i);
          GVal v = get(); skip();
          es.add(new Pair<>(k, v));
          char n = next(); skip();
          if (n=='}') break;
          else if (n!=',') throw new RuntimeException("Expected ',' at "+i);
        }
        return new GKVList(es, false);
      }
      case '[': {
        i++;
        skip();
        if (peek()==']') { i++; return GList.empty; }
        Vec<GVal> vs = new Vec<>();
        Vec<Pair<String, GVal>> es = new Vec<>();
        while (true) {
          int id = nameEnd(s, i);
          if (s.charAt(id)=='=') {
            String k = s.substring(i, id);
            i = id+1;
            GVal v = get();
            es.add(new Pair<>(k, v));
          } else {
            vs.add(get());
          }
          char n = next();
          if (n==']') break;
          else if (n!=',') throw new RuntimeException("Expected ',' at "+i);
        }
        assert vs.sz==0 || es.sz==0;
        return vs.sz!=0? new GList(vs) : new GKVList(es, true);
      }
      case '"':
        i++;
        ByteVec r = new ByteVec();
        while (true) {
          char c = next();
          if (c=='"') break;
          if (c=='\\') {
            char n = next();
            if (n=='"') r.add((byte)'"');
            else if (n=='\\')r.add((byte)'\\');
            else if (n=='/') r.add((byte)'/');
            else if (n=='b') r.add((byte)'\b');
            else if (n=='f') r.add((byte)'\f');
            else if (n=='n') r.add((byte)'\n');
            else if (n=='r') r.add((byte)'\r');
            else if (n=='t') r.add((byte)'\t');
            else if (n>='0'&n<='8') {
              i--;
              if (i+3 > s.length()) throw new RuntimeException("Unfinished string");
              int v = 0;
              for (int j = 0; j < 3; j++) {
                char d = s.charAt(i++);
                v<<= 3;
                if (d>='0' & d<='7') v|= d-'0';
                else throw new RuntimeException("Bad \\ value at "+i+": "+d);
              }
              r.add((byte)v);
            } else {
              throw new RuntimeException("Unknown escape \\"+n);
            }
          } else r.add((byte)c);
        }
        return new GStr(new String(r.get(), StandardCharsets.UTF_8));
      default:
        throw new RuntimeException("Unknown character "+s.charAt(i)+" at "+i);
    }
  }
  
  private static int nameEnd(String str, int i0) {
    int i = i0;
    while (i<str.length() && (Character.isJavaIdentifierPart(str.charAt(i)) || str.charAt(i)=='-')) i++;
    return i;
  }
}
