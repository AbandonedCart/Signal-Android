package org.thoughtcrime.securesms.logsubmit;

import androidx.annotation.NonNull;

import com.annimon.stream.Stream;

import java.util.List;
import java.util.regex.Pattern;

public interface LogLine {

  long getId();
  @NonNull String getText();
  @NonNull Style getStyle();
  @NonNull Placeholder getPlaceholderType();

  enum Style {
    NONE, VERBOSE, DEBUG, INFO, WARNING, ERROR
  }

  enum Placeholder {
    NONE, TRACE
  }
}
