package org.thoughtcrime.securesms.mms;

import android.content.Context;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.util.LocaleFeatureFlags;
import org.thoughtcrime.securesms.util.Util;

import java.util.Arrays;

public class PushMediaConstraints extends MediaConstraints {

  private static final int KB = 1024;
  private static final int MB = 1024 * KB;

  private final MediaConfig currentConfig;

  public PushMediaConstraints(@Nullable SentMediaQuality sentMediaQuality) {
    currentConfig = getCurrentConfig(ApplicationDependencies.getApplication(), sentMediaQuality);
  }

  @Override
  public int getImageMaxWidth(Context context) {
    return currentConfig.imageSizeTargets[0];
  }

  @Override
  public int getImageMaxHeight(Context context) {
    return getImageMaxWidth(context);
  }

  @Override
  public int getImageMaxSize(Context context) {
    return currentConfig.maxImageFileSize;
  }

  @Override
  public int[] getImageDimensionTargets(Context context) {
    return currentConfig.imageSizeTargets;
  }

  @Override
  public int getGifMaxSize(Context context) {
    return 25 * MB;
  }

  @Override
  public int getVideoMaxSize(Context context) {
    return 300 * MB;
  }

  @Override
  public int getUncompressedVideoMaxSize(Context context) {
    return isVideoTranscodeAvailable() ? 600 * MB
                                       : getVideoMaxSize(context);
  }

  @Override
  public int getCompressedVideoMaxSize(Context context) {
    return Util.isLowMemory(context) ? 50 * MB
                                     : 100 * MB;
  }

  @Override
  public int getAudioMaxSize(Context context) {
    return 100 * MB;
  }

  @Override
  public int getDocumentMaxSize(Context context) {
    return 100 * MB;
  }

  @Override
  public int getImageCompressionQualitySetting(@NonNull Context context) {
    return currentConfig.qualitySetting;
  }

  private static @NonNull MediaConfig getCurrentConfig(@NonNull Context context, @Nullable SentMediaQuality sentMediaQuality) {
    if (Util.isLowMemory(context)) {
      return MediaConfig.LEVEL_1_LOW_MEMORY;
    }

    if (sentMediaQuality == SentMediaQuality.HIGH) {
      return MediaConfig.LEVEL_3;
    }
    return LocaleFeatureFlags.getMediaQualityLevel().orElse(MediaConfig.getDefault(context));
  }
	
  private static int[] IMAGE_DIMEN(int n) {
    int[] values = { 512 };
    for (int i = 768; i <= n; i = i + 16) {
      values = Arrays.copyOf(values, values.length + 1);
      values[values.length - 1] = i;
    }
    if (n % 16 != 0) {
      values = Arrays.copyOf(values, values.length + 1);
      values[values.length - 1] = n;
    }
    // Reverse the array into descending order
    int length = values.length;
    for (int i = 0; i < length / 2; i++) {
      values[i] = values[i] ^ values[length - i - 1];
      values[length - i - 1] = values[i] ^ values[length - i - 1];
      values[i] = values[i] ^ values[length - i - 1];
    }
    return values;
  }

  public enum MediaConfig {
    LEVEL_1_LOW_MEMORY(true, 1, (5 * MB), IMAGE_DIMEN(3000), 75),

    LEVEL_1(false, 1, (10 * MB), IMAGE_DIMEN(6000), 80),
    LEVEL_2(false, 2, (int) (15 * MB), IMAGE_DIMEN(9000), 90),
    LEVEL_3(false, 3, (int) (20 * MB), IMAGE_DIMEN(12000), 100);

    private final boolean isLowMemory;
    private final int     level;
    private final int     maxImageFileSize;
    private final int[]   imageSizeTargets;
    private final int     qualitySetting;

    MediaConfig(boolean isLowMemory,
                int level,
                int maxImageFileSize,
                @NonNull int[] imageSizeTargets,
                @IntRange(from = 0, to = 100) int qualitySetting)
    {
      this.isLowMemory      = isLowMemory;
      this.level            = level;
      this.maxImageFileSize = maxImageFileSize;
      this.imageSizeTargets = imageSizeTargets;
      this.qualitySetting   = qualitySetting;
    }

    public static @Nullable MediaConfig forLevel(int level) {
      boolean isLowMemory = Util.isLowMemory(ApplicationDependencies.getApplication());

      return Arrays.stream(values())
                   .filter(v -> v.level == level && v.isLowMemory == isLowMemory)
                   .findFirst()
                   .orElse(null);
    }

    public static @NonNull MediaConfig getDefault(Context context) {
      return Util.isLowMemory(context) ? LEVEL_1_LOW_MEMORY : LEVEL_3;
    }
  }
}
