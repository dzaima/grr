package dz.utils;

import dzaima.utils.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

public class LineRequeue {
  private static final byte SPLIT = '\n';
  private final ByteVec curr = new ByteVec();
  private final Consumer<byte[]> onLine;
  public LineRequeue(Consumer<byte[]> line) { onLine = line; }
  
  private void split(int s) {
    int c = 0;
    search: while (true) {
      int e = Math.max(s, c);
      while (true) {
        if (e == curr.sz) break search;
        if (curr.get(e) == SPLIT) break;
        e++;
      }
      onLine.accept(curr.get(c, e));
      c = e+1;
    }
    curr.remove(0, c);
  }
  
  public void add(byte[] n) {
    add(n, 0, n.length);
  }
  public void add(byte[] n, int s, int e) {
    int li = curr.sz;
    curr.addAll(n, s, e);
    split(li);
  }
  
  public void end() {
    assert curr.sz==0;
  }
  
  
  public static Iterable<byte[]> iterable(Predicate<LineRequeue> add) {
    return new It(add);
  }
  private static class It implements Iterable<byte[]>, Iterator<byte[]> {
    private final Predicate<LineRequeue> add;
    private final Deque<byte[]> vs = new ArrayDeque<>();
    private final LineRequeue l = new LineRequeue(vs::add);
    public It(Predicate<LineRequeue> add) { this.add = add; }
    public Iterator<byte[]> iterator() { return this; }
    private void prep() {
      while (vs.isEmpty()) if (!add.test(l)) break;
    }
    
    public boolean hasNext() {
      prep();
      return !vs.isEmpty();
    }
    public byte[] next() {
      prep();
      return vs.remove();
    }
  }
  
  
  public static Iterable<byte[]> iterable(Path p) { // no point
    byte[] buf = new byte[32768];
    try {
      InputStream s = Files.newInputStream(p);
      return iterable(l -> {
        try {
          int r = s.read(buf);
          if (r<=0) {
            s.close();
            return false;
          }
          l.add(buf, 0, r);
          return true;
        } catch (IOException e) { throw new UncheckedIOException(e); }
      });
    } catch (IOException e) { throw new UncheckedIOException(e); }
  }
}
