package dz.utils;

import dzaima.utils.ByteVec;

import java.util.function.Consumer;

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
    int li = curr.sz;
    curr.addAll(n);
    split(li);
  }
  
  public void end() {
    assert curr.sz==0;
  }
}
