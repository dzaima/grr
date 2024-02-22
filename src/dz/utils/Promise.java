package dz.utils;

import dzaima.utils.Vec;

import java.util.function.*;

public class Promise<T> {
  Vec<Consumer<T>> requesters = new Vec<>();
  T result;
  
  public boolean isResolved() {
    return requesters==null;
  }
  
  public Promise<T> then(Consumer<T> f) {
    if (isResolved()) f.accept(result);
    else requesters.add(f);
    return this;
  }
  public void set(T val) {
    assert !isResolved();
    result = val;
    Vec<Consumer<T>> tmp = requesters;
    requesters = null;
    for (Consumer<T> c : tmp) c.accept(val);
  }
  public T get() { // assumes is already resolved
    assert isResolved();
    return result;
  }
  
  public static <T> Promise<T> create(Consumer<Promise<T>> r) { // create a new promise `a`, immediately invoke `r` with it, and return `a`
    Promise<T> a = new Promise<>();
    r.accept(a);
    return a;
  }
  
  public static <T> Promise<T> all(Supplier<T> f, Promise<?>... ps) {
    Promise<T> res = new Promise<>();
    int[] box = new int[]{ps.length}; 
    for (Promise<?> c : ps) {
      c.then(x -> {
        if (0 == --box[0]) res.set(f.get());
      });
    }
    return res;
  }
  
  public static <A,B> void run2(Promise<A> a, Promise<B> b, BiConsumer<A,B> f) {
    a.then(ar -> b.then(br -> f.accept(ar, br)));
  }
  public static <A,B,R> Promise<R> merge2(Promise<A> a, Promise<B> b, BiFunction<A,B,R> f) {
    return Promise.all(() -> f.apply(a.get(), b.get()), a, b);
  }
}
