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
    else toFileLine(map.file, map.sourceInfo, map.line, bin);
  }
  
  private String currFilename;
  public void onSelectedFunction(Location l, boolean justFunction, boolean afterCall) {
    if (!follow) return;
    if (l==null) toFileLine(null, null, -1, null);
    else toFileLine(l.file, l.sourceInfo, l.line, null);
  }
  
  private void toNote(String note) {
    code.removeAll();
    code.setLang(g.gc.langs().defLang);
    code.append(note);
    code.um.clear();
  }
  private void noteNoFile(String file, String sourceInfo, int line, String bin) {
    String r = "Couldn't find source file:\n";
    if (file==null) r+= "  unknown path";
    else r+= "  path: " + file + (line==-1? "" : ", line " + (line+1));
    if (sourceInfo!=null) r+= " ("+sourceInfo+")";
    r+= "\n";
    String fRe = g.remap(file);
    if (!Objects.equals(fRe, file)) r+= "  remapped to: "+fRe+"\n";
    if (bin!=null) r+= "  for binary "+bin;
    toNote(r);
  }
  
  
  
  boolean srcFound;
  private void toFileLine(String file, String sourceInfo, Integer ln0, String bin) {
    int line = ln0==null? -1 : ln0-1;
    if (Objects.equals(file, currFilename) && srcFound) {
      focusLine(line);
    } else {
      srcFound = false;
      code.removeAll();
      if (file!=null) {
        String s = g.readFile(file);
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
          noteNoFile(file, sourceInfo, line, bin);
        }
      } else {
        toNote("(no attached file"+(bin==null? "" : " for "+bin) + ")");
      }
      currFilename = file;
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
      if (Objects.equals(l.file, currFilename) && l.line!=null) {
        code.setHoverHighlight(l.line-1);
        clearHighlightIn = 0;
        break;
      }
    }
  }
}
