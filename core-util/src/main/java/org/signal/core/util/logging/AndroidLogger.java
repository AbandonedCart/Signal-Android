package org.signal.core.util.logging;

import android.annotation.SuppressLint;

import org.signal.core.util.logging.Log;

import org.signal.core.util.BuildConfig;

@SuppressLint("LogNotSignal")
public final class AndroidLogger extends Log.Logger {

  private boolean isLogVerbose() {
    return BuildConfig.VERBOSE_LOGGING;
  }

  @Override
  public void v(String tag, String message, Throwable t, boolean keepLonger) {
    if (isLogVerbose()) android.util.Log.v(tag, message, t);
  }

  @Override
  public void d(String tag, String message, Throwable t, boolean keepLonger) {
    if (isLogVerbose()) android.util.Log.d(tag, message, t);
  }

  @Override
  public void i(String tag, String message, Throwable t, boolean keepLonger) {
    if (isLogVerbose()) android.util.Log.i(tag, message, t);
  }

  @Override
  public void w(String tag, String message, Throwable t, boolean keepLonger) {
    android.util.Log.w(tag, message, t);
  }

  @Override
  public void e(String tag, String message, Throwable t, boolean keepLonger) {
    android.util.Log.e(tag, message, t);
  }

  @Override
  public void flush() {
  }
}
