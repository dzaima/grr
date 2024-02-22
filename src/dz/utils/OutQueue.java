package dz.utils;

import dzaima.utils.*;

import java.io.*;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingDeque;

public class OutQueue {
  LinkedBlockingDeque<byte[]> q = new LinkedBlockingDeque<>();
  Thread t;
  
  public OutQueue(InputStream s, Runnable onClosed) {
    t = Tools.thread(() -> {
      byte[] buf = new byte[16384];
      try {
        while (true) {
          int r = s.read(buf);
          if (r<=0) break;
          q.addLast(Arrays.copyOf(buf, r));
        }
        onClosed.run();
      } catch (IOException e) { Log.stacktrace("OutQueue", e); }
    }, true);
    t.setName("OutQueue");
  }
  
  public void stop() {
    t.interrupt();
  }
  
  public boolean has() {
    return !q.isEmpty();
  }
  
  public byte[] takeOne() {
    try { return q.takeFirst(); }
    catch (InterruptedException e) { throw new RuntimeException(e); }
  }
  
}
