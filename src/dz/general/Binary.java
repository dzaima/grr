package dz.general;

import java.util.Objects;

public class Binary { // general executable file
  public final String file, desc;
  public final boolean relocate;
  public final boolean virtSym; // whether this binary actually represents a single symbol with the name of this.desc
  
  public Binary(String file, String desc, boolean relocate) { this(file, desc, relocate, false); }
  public Binary(String file, String desc, boolean relocate, boolean virtSym) {
    this.file = file;
    this.desc = desc;
    this.relocate = relocate;
    this.virtSym = virtSym;
  }
  
  public static Binary virtSym(String path, String name) {
    return new Binary(path, name, false, true);
  }
  
  public String virtSymName() {
    assert virtSym;
    return desc;
  }
  
  public boolean equals(Object o) {
    if (!(o instanceof Binary)) return false;
    Binary b = (Binary) o;
    return relocate == b.relocate && Objects.equals(file, b.file) && desc.equals(b.desc);
  }
  
  public int hashCode() {
    int result = file != null? file.hashCode() : 0;
    return 31*(31*result + desc.hashCode()) + (relocate? 1 : 0);
  }
}
