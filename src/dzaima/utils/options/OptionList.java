package dzaima.utils.options;

import dzaima.utils.Vec;

public class OptionList {
  public final Vec<OptionItem> items = new Vec<>();
  public boolean used = false;
  
  public static Vec<OptionItem> merge(Vec<OptionItem> a, Vec<OptionItem> b) {
    Vec<OptionItem> r = new Vec<>();
    r.addAll(a);
    r.addAll(b);
    r.sort();
    return r;
  }
}