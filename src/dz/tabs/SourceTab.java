package dz.tabs;

import dz.debugger.Location;
import dz.general.DisasFn;
import dz.layouts.*;
import dz.ui.SourceAreaNode;
import dzaima.ui.node.Node;
import dzaima.ui.node.prop.Prop;
import dzaima.ui.node.types.ScrollNode;
import dzaima.ui.node.types.tabs.*;
import dzaima.utils.Vec;

import java.util.*;

public class SourceTab extends GrrTab<Layout> implements SerializableTab {
  public final Node node;
  public final SourceAreaNode code;
  public final boolean follow;
  
  public SourceTab(Layout g, boolean follow) {
    super(g);
    this.follow = follow;
    node = ctx.make(ctx.gc.getProp("grr.tabs.source.ui").gr());
    code = (SourceAreaNode) node.ctx.id("code");
    code.setLang(g.gc.langs().fromName("assembly"));
    code.mutable = !follow;
  }
  
  public Node show() { return node; }
  public void onSelected() { code.focusMe(); }
  
  public String name() {
    return follow? currFilename==null? "current source" : "current - "+(currFilename.substring(currFilename.lastIndexOf('/')+1)) : "source todo";
  }
  public String serializeName() { return "source"; }
  public String serialize() { return "f="+follow; }
  public static Tab deserialize(GdbLayout g, HashMap<String, Prop> p) {
    return new SourceTab(g, p.get("f").b());
  }
  
  public void onSelectedSourceMap(DisasFn.SourceMap map, String bin) {
    if (!follow) return;
    if (map==null) toFileLine(null, null, 0, bin);
    else toFileLine(map.shortFile, map.fullFile, map.line, bin);
  }
  
  private String currFilename;
  public void onSelectedFunction(Location l, boolean justFunction, boolean afterCall) {
    if (!follow) return;
    if (l==null) toFileLine(null, null, -1);
    else toFileLine(l.shortFile, l.fullFile, l.line);
  }
  
  private void noteNoFile(String file, String fullFile, int line, String bin) {
    code.removeAll();
    String r = "Couldn't read file:\n";
    if (fullFile==null) r+= "  unknown file";
    else r+= "  path: " + fullFile + (line==-1? "" : ", line " + (line+1)) + "\n";
    String fRe = g.remap(fullFile);
    if (!Objects.equals(fRe, fullFile)) r+= "  remapped to: "+fRe+"\n";
    r+= "  for binary "+bin;
    code.append(r);
    code.um.clear();
  }
  boolean srcFound;
  public void toFileLine(String file, String fullFile, Integer ln0) {
    toFileLine(file, fullFile, ln0, null);
  }
  
  
  public void toFileLine(String file, String fullFile, Integer ln0, String bin) {
    int line = ln0==null? -1 : ln0-1;
    if (Objects.equals(fullFile, currFilename)) {
      if (file!=null && srcFound) {
        focusLine(line);
      } else if (file!=null) {
        noteNoFile(file, fullFile, line, bin);
      }
    } else {
      srcFound = false;
      code.removeAll();
      if (file!=null) {
        String s = g.readFile(fullFile);
        if (s!=null) {
          srcFound = true;
          code.setLang(g.gc.langs().fromFilename(file));
          Runnable append = () -> {
            code.append(s.replace("\t", "  "));
            code.um.clear();
            focusLine(line);
          };
          if (s.length()>30000) {
            appendFn = append;
            appendTimeLeft = 2;
          } else append.run();
        } else {
          code.setLang(g.gc.langs().defLang);
          noteNoFile(file, fullFile, line, bin);
        }
      } else {
        code.setLang(g.gc.langs().defLang);
        code.append("(no attached file"+(bin==null? "" : " for "+bin) + ")");
        code.um.clear();
      }
      currFilename = fullFile;
    }
    nameUpdated();
  }
  
  int appendTimeLeft;
  Runnable appendFn;
  int clearHighlightIn = 0;
  public void tick() {
    if (appendFn!=null && --appendTimeLeft == 0) {
      appendFn.run();
      appendFn = null;
    }
    if (clearHighlightIn > 0) {
      if (--clearHighlightIn == 0) code.setHoverHighlight(-1);
    }
  }
  
  public void focusLine(int ln) {
    ln = Math.max(0, Math.min(ln, code.lns.sz-1));
    code.scrollTo(-1, ln, ScrollNode.Mode.INSTANT);
    code.um.pushU("to selected line");
    code.cs.get(0).mv(0, ln, 0, ln);
    code.setMainHighlight(ln);
    code.um.pop();
  }
  
  public void setHover(Vec<Location> ls) {
    clearHighlightIn = 10;
    for (Location l : ls) {
      if (Objects.equals(l.fullFile, currFilename) && l.line!=null) {
        code.setHoverHighlight(l.line-1);
        clearHighlightIn = 0;
        break;
      }
    }
  }
}
