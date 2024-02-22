package dz.layouts;

import dz.Main;
import dz.tabs.GrrTab;
import dz.utils.DelayedRun;
import dzaima.ui.gui.config.GConfig;
import dzaima.ui.gui.io.*;
import dzaima.ui.node.Node;
import dzaima.utils.Vec;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

public abstract class Layout {
  public final Vec<GrrTab<?>> tabs = new Vec<>();
  public final Main m;
  public final GConfig gc;
  public Node node;
  
  public Layout(Main m) {
    this.m = m;
    gc = m.gc;
  }
  
  public void preEventTick() { }
  public Instant approxNow = Instant.now();
  public SortedSet<DelayedRun> delayedRuns = new TreeSet<>();
  public Vec<Runnable> runLater = new Vec<>();
  public void tick() {
    approxNow = Instant.now();
    //noinspection StatementWithEmptyBody
    while (!delayedRuns.isEmpty() && delayedRuns.first().maybeRun());
    for (GrrTab<?> tab : tabs) {
      tab.tick();
    }
    if (runLater.sz>0) {
      Vec<Runnable> todo = runLater;
      runLater = new Vec<>();
      for (Runnable c : todo) c.run();
    }
  }
  
  public Node treePlace() { return node.ctx.id("tree"); }
  
  @SuppressWarnings("unchecked")
  public <T> T findTab(Class<T> c) {
    for (GrrTab<?> t : tabs) if (t.getClass().equals(c)) return (T) t;
    return null;
  }
  @SuppressWarnings("unchecked")
  public <T extends GrrTab<?>> void forTabs(Class<T> c, Consumer<T> f) {
    for (GrrTab<?> t : tabs) if (t.getClass().equals(c)) f.accept((T) t);
  }
  
  public abstract void stopped();
  
  public String remap(String p) {
    return p;
  }
  public String readFile(String file) {
    return m.readFile(remap(file));
  }
  
  public abstract boolean key(Key key, int scancode, KeyAction a);
}
