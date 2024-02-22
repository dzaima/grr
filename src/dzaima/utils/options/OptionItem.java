package dzaima.utils.options;

public class OptionItem implements Comparable<OptionItem> {
  public final String k, v;
  public final int ki;
  public OptionItem(String k, int ki, String v) { this.k=k; this.v=v; this.ki=ki; }
  public int compareTo(OptionItem o) { return Integer.compare(ki, o.ki); }
}