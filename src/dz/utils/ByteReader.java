package dz.utils;

import dzaima.utils.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class ByteReader {
  private ByteBuffer buf;
  private RandomAccessFile f;
  private long fileSize;
  private Path filePath;
  public boolean loadFromFile(Path p) {
    assert f==null;
    buf = null;
    try {
      f = new RandomAccessFile(p.toFile(), "r");
      filePath = p;
      fileSize = f.length();
      buf = f.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
      return true;
    } catch (IOException e) {
      return false;
    }
  }
  public void closeFile() {
    if (f==null) return;
    try { filePath=null; f.close(); f=null; }
    catch (IOException e) { Log.error("ByteReader", "Failed to close file"); Log.stacktrace("ByteReader", e); }
  }
  
  
  protected Path openFilePath() { return filePath; }
  protected long size() { return fileSize; }
  protected void setPos(long p) { buf.position(Math.toIntExact(p)); }
  protected long getPos() { return buf.position(); }
  protected void skip(int delta) { setPos(getPos()+delta); }
  protected void alignUp(int n) {
    n-= 1;
    setPos((getPos()+n)&~n);
  }
  
  protected byte[] readRange(long s0, int n) {
    byte[] res = new byte[n];
    int p0 = buf.position();
    buf.position(Math.toIntExact(s0));
    buf.get(res, 0, n);
    buf.position(p0);
    return res;
  }
  
  protected long nextN(int n) {
    long r = 0;
    for (int i = 0; i < n; i++) r|= (buf.get()&0xffL)<<(i*8);
    return r;
  }
  protected byte[] nextBytes(long n0) {
    int n = Math.toIntExact(n0);
    byte[] res = new byte[n];
    buf.get(res, 0, n);
    return res;
  }
  
  protected long u64() { return nextN(8); }
  protected int u32() { return (int) nextN(4); }
  protected int u16() { return (int) nextN(2); }
  protected int u8() { return (int) nextN(1); }
  protected String str() {
    ByteVec v = new ByteVec();
    while (true) {
      int c = u8();
      if (c==0) break;
      v.add((byte) c);
    }
    return new String(v.get(), StandardCharsets.UTF_8);
  }
}
