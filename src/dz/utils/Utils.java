package dz.utils;

import dzaima.utils.Tools;

import java.text.DecimalFormat;

public class Utils {
  private static final DecimalFormat f = new DecimalFormat("0.#########");
  
  private static String trySize(long sz, int sh, String post) {
    long full = sz>>>sh;
    if (full >= 1000 && sh!=50) return null;
    if ((full<<sh)==sz) return full+post;
    double v = (double) sz / (1L << sh);
    v = Math.round(v*10)/10.0;
    return v+post;
  }
  public static String fmtRoughSize(long sz) {
    if (sz < 0 && sz!=Long.MIN_VALUE) return "-"+fmtRoughSize(-sz);
    if (sz < 1000) return sz+"B";
    String r = trySize(sz, 10, "KiB");
    if (r != null) return r;
    r = trySize(sz, 20, "MiB");
    if (r != null) return r;
    r = trySize(sz, 30, "GiB");
    if (r != null) return r;
    r = trySize(sz, 40, "TiB");
    if (r != null) return r;
    r = trySize(sz, 50, "PiB");
    return r;
  }
    
  public static String fmtPaddedRoughSize(long sz) {
    String s = fmtRoughSize(sz);
    return pad(s, ' ', 8);
  }
  
  public static String pad(String v, char s, int len) {
    if (v.length()>=len) return v;
    return Tools.repeat(s, len-v.length())+v;
  }
  
  public static String padL(String v, char s, int len) {
    if (v.length()>=len) return v;
    return v+Tools.repeat(s, len-v.length());
  }
  
  public static String hexLong(long l) { return hexLong(l, 16); }
  
  public static String hexLong(long l, int len) {
    return pad(Long.toUnsignedString(l, 16), '0', len);
  }
  
  public static int hexLength(long l) {
    return Math.max(16 - Long.numberOfLeadingZeros(l)/4, 2);
  }
  
  public static String fmtSeconds(double s) {
    return f.format(s);
  }
  
  public static String fmtNanos(long ns) {
    return fmtSeconds(ns/1e9);
  }
}
