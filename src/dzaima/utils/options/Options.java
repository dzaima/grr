package dzaima.utils.options;

import dz.Main;
import dzaima.utils.*;

import java.util.HashMap;
import java.util.function.*;

public class Options {
  private void fail(String s) {
    Main.fail(s);
  }
  
  public final HashMap<String, OptionList> knownOptions = new HashMap<>();
  public OptionList optItem(String k) {
    OptionList r = knownOptions.get(k);
    if (r!=null) r.used = true;
    return r;
  }
  
  public String optOne(String k) {
    OptionList o = optItem(k);
    if (o==null) return null;
    if (o.items.sz!=1) fail("Option '"+k+"' can only be specified once");
    return o.items.get(0).v;
  }
  public String reqOne(String k) { // TODO use more
    String s = optOne(k);
    if (s == null) fail("Option '"+k+"' required");
    return s;
  }
  
  public Vec<OptionItem> optList(String k) {
    OptionList o = optItem(k);
    if (o==null) return Vec.of();
    return o.items;
  }
  public Vec<String> optArgs(String k) {
    Vec<String> r = new Vec<>();
    for (OptionItem c : optList(k)) r.addAll(r.sz, Tools.split(c.v, ' '));
    return r;
  }
  public void put(String k, int ki, String v) {
    OptionList i = knownOptions.computeIfAbsent(k, k2 -> new OptionList());
    i.items.add(new OptionItem(k, ki, v));
  }
  public boolean has(String k) { // won't mark as used
    return knownOptions.containsKey(k);
  }
  public boolean takeBool(String k) {
    return optList(k).sz>0;
  }
  
  public void used() {
    knownOptions.forEach((k, v) -> {
      if (v.used) return;
      System.err.println("Warning: Unused option \""+k+"\"");
    });
    knownOptions.clear();
  }
  
  @FunctionalInterface public interface ArgFn { void run(String arg, int i, Supplier<String> get); }
  @FunctionalInterface private interface DoFn { void run(String arg, int i0, int i1, ArgFn f); }
  public Vec<String> process(String[] args, ArgFn longArg, ArgFn shortArg) {
    Box<Integer> i = new Box<>(0);
    Function<String, String> get = msg -> {
      int iv = i.get();
      if (iv==args.length) fail(msg);
      i.set(iv+1);
      return args[iv];
    };
    DoFn doArg = (c, i0, i1, f) -> {
      if (i0==-1 || i0==c.length()) {
        f.run(c, i.get(), () -> get.apply("No value provided for "+c));
      } else {
        String arg = c.substring(0, i0);
        String val = c.substring(i1);
        Box<Boolean> taken = new Box<>(false);
        f.run(arg, i.get(), () -> {
          if (taken.get()) fail(arg+": Attempting to consume multiple arguments");
          taken.set(true);
          return val;
        });
        if (!taken.get()) fail(arg+": Unexpected argument");
      }
    };
    
    Vec<String> tail = new Vec<>();
    while (i.get() < args.length) {
      String c = get.apply("??");
      if (c.startsWith("--")) {
        if (c.equals("--")) break;
        int s = c.indexOf("=");
        doArg.run(c, s, s+1, longArg);
      } else if (c.startsWith("-")) {
        int s = c.indexOf("=");
        doArg.run(c, s, s+1, shortArg);
      } else {
        tail.add(c);
      }
    }
    tail.addAll(tail.sz, args, i.get(), args.length);
    return tail;
  }
}
  
