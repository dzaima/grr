package dz.utils;

import dzaima.utils.Box;

import java.util.function.Consumer;

public class RepeatRunnable {
  public static void run(Consumer<Runnable> f) {
    Box<Runnable> b = new Box<>();
    Runnable r = () -> f.accept(b.get());
    b.set(r);
    r.run();
  }
}
