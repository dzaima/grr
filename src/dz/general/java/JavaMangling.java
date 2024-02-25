package dz.general.java;

import dzaima.utils.Log;

public class JavaMangling {
  public static String demangleArguments(String s, String name) {
    try {
      JavaMangling c = new JavaMangling(s);
      String r = c.getArgs(name);
      assert c.off == s.length();
      return r;
    } catch (Throwable t) {
      Log.error("JavaMangling", "parsing "+name);
      Log.stacktrace("JavaMangling", t);
      return name+s;
    }
  }
  public static String demanglePath(String name) {
    return name.replace('/', '.').replaceAll("[+.]0x[0-9a-f]+", "");
  }
  
  
  
  private final String s;
  private int off = 0;
  private JavaMangling(String s) {
    this.s = s;
  }
  
  private char next() {
    return s.charAt(off++);
  }
  private char peek() {
    if (off>=s.length()) return 0;
    return s.charAt(off);
  }
  private void req(char x) {
    char c = next();
    assert c==x;
  }
  
  private String getType() {
    char c = next();
    switch (c) {
      default: throw new IllegalStateException(String.valueOf(c));
      case 'B': return "byte";
      case 'C': return "char";
      case 'D': return "double";
      case 'F': return "float";
      case 'I': return "int";
      case 'J': return "long";
      case 'S': return "short";
      case 'Z': return "boolean";
      case 'V': return "void";
      case '[': return getType()+"[]";
      case 'L':
        int i = s.indexOf(';', off);
        String r = demanglePath(s.substring(off, i));
        int j = r.lastIndexOf('.');
        if (j!=-1) r = r.substring(j+1);
        off = i+1;
        return r;
    }
  }
  
  private String getArgs(String name) {
    req('(');
    StringBuilder res = new StringBuilder();
    boolean first = true;
    while (peek()!=')') {
      if (first) first = false;
      else res.append(", ");
      res.append(getType());
    }
    req(')');
    res.insert(0, getType()+" "+name+"(");
    res.append(')');
    return res.toString();
  }
}
