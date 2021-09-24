package org.thoughtcrime.securesms.giph.mp4;

import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.util.DeviceProperties;
import org.thoughtcrime.securesms.util.FeatureFlags;

import java.util.concurrent.TimeUnit;

/**
 * Central policy object for determining what kind of gifs to display, routing, etc.
 */
public final class GiphyMp4PlaybackPolicy {

  private GiphyMp4PlaybackPolicy() { }

  public static boolean sendAsMp4() {
    return FeatureFlags.mp4GifSendSupport();
  }

  public static boolean autoplay() {
    return !DeviceProperties.isLowMemoryDevice(ApplicationDependencies.getApplication());
  }

  public static int maxRepeatsOfSinglePlayback() {
    return 4;
  }

  public static long maxDurationOfSinglePlayback() {
    return TimeUnit.SECONDS.toMillis(8);
  }

  public static int maxSimultaneousPlaybackInSearchResults() {
    return 12;
  }

  public static int maxSimultaneousPlaybackInConversation() {
    return 4;
  }
}
