package dz.utils;

import dzaima.utils.*;

import java.io.*;
import java.util.concurrent.*;

public class InQueue {
  BlockingQueue<byte[]> q = new LinkedBlockingQueue<>();
  Thread t;
  
  public InQueue(OutputStream s) {
    t = Tools.thread(() -> {
      try {
        //noinspection InfiniteLoopStatement
        while(true) {
          s.write(q.take());
          s.flush();
        }
      } catch (IOException e) { Log.stacktrace("InQueue", e); }
    }, true);
    t.setName("InQueue");
  }
  
  public void stop() {
    t.interrupt();
  }
  
  public void push(byte[] b) {
    q.add(b);
  }
}
