package org.slf4j;

// miniature stub for pty4j dependency
public final class LoggerFactory {
  public static Logger getLogger(String name) {
    return new Logger() {
      public String getName() { return name; }
      public boolean isTraceEnabled() { return false; }
      public void trace(String msg) { }
      public void trace(String format, Object arg) { }
      public void trace(String format, Object arg1, Object arg2) { }
      public void trace(String format, Object... arguments) { }
      public void trace(String msg, Throwable t) { }
      public boolean isDebugEnabled() { return false; }
      public void debug(String msg) { }
      public void debug(String format, Object arg) { }
      public void debug(String format, Object arg1, Object arg2) { }
      public void debug(String format, Object... arguments) { }
      public void debug(String msg, Throwable t) { }
      public boolean isInfoEnabled() { return false; }
      public void info(String msg) { }
      public void info(String format, Object arg) { }
      public void info(String format, Object arg1, Object arg2) { }
      public void info(String format, Object... arguments) { }
      public void info(String msg, Throwable t) { }
      public boolean isWarnEnabled() { return false; }
      public void warn(String msg) { }
      public void warn(String format, Object arg) { }
      public void warn(String format, Object... arguments) { }
      public void warn(String format, Object arg1, Object arg2) { }
      public void warn(String msg, Throwable t) { }
      public boolean isErrorEnabled() { return false; }
      public void error(String msg) { }
      public void error(String format, Object arg) { }
      public void error(String format, Object arg1, Object arg2) { }
      public void error(String format, Object... arguments) { }
      public void error(String msg, Throwable t) { }
    };
  }
  
  public static Logger getLogger(Class<?> clazz) {
    return getLogger(clazz.getName());
  }
}