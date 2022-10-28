package org.thoughtcrime.securesms.mms;

import android.content.Context;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.util.RemoteConfig;
import org.thoughtcrime.securesms.util.LocaleRemoteConfig;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.video.TranscodingPreset;
import org.thoughtcrime.securesms.video.videoconverter.utils.DeviceCapabilities;

import java.util.Arrays;

public class PushMediaConstraints extends MediaConstraints {

  private static final int KB = 1024;
  private static final int MB = 1024 * KB;

  private final MediaConfig currentConfig;

  public PushMediaConstraints(@Nullable SentMediaQuality sentMediaQuality) {
    currentConfig = getCurrentConfig(AppDependencies.getApplication(), sentMediaQuality);
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
    return (int) Math.min(currentConfig.maxImageFileSize, getMaxAttachmentSize());
  }

  @Override
  public int[] getImageDimensionTargets(Context context) {
    return currentConfig.imageSizeTargets;
  }

  @Override
  public long getGifMaxSize(Context context) {
    return Math.min(25 * MB, getMaxAttachmentSize());
  }

  @Override
  public long getVideoMaxSize() {
    return getMaxAttachmentSize();
  }

  @Override
  public long getUncompressedVideoMaxSize(Context context) {
    return isVideoTranscodeAvailable() ? RemoteConfig.maxSourceTranscodeVideoSizeBytes()
                                       : getVideoMaxSize();
  }

  @Override
  public long getCompressedVideoMaxSize(Context context) {
    return getMaxAttachmentSize();
  }

  @Override
  public long getAudioMaxSize(Context context) {
    return getMaxAttachmentSize();
  }

  @Override
  public long getDocumentMaxSize(Context context) {
    return getMaxAttachmentSize();
  }

  @Override
  public int getImageCompressionQualitySetting(@NonNull Context context) {
    return currentConfig.qualitySetting;
  }

  @Override
  public TranscodingPreset getVideoTranscodingSettings() {
    return currentConfig.videoPreset;
  }

  private static @NonNull MediaConfig getCurrentConfig(@NonNull Context context, @Nullable SentMediaQuality sentMediaQuality) {
    if (Util.isLowMemory(context)) {
      return MediaConfig.LEVEL_1_LOW_MEMORY;
    }

    if (sentMediaQuality == SentMediaQuality.HIGH) {
      if (DeviceCapabilities.canEncodeHevc() && (RemoteConfig.useHevcEncoder() || SignalStore.internal().getHevcEncoding())) {
        return MediaConfig.LEVEL_3_H265;
      } else {
        return MediaConfig.LEVEL_3;
      }
    }
    return LocaleRemoteConfig.getMediaQualityLevel().orElse(MediaConfig.getDefault(context));
  }

  private static int[] IMAGE_DIMEN(int n) {
    int[] values = { 512 };
    for (int i = 768; i <= n; i = i + 64) {
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
    LEVEL_1_LOW_MEMORY(true, 1, (5 * MB), IMAGE_DIMEN(3000), 75, TranscodingPreset.LEVEL_1),

    LEVEL_1(false, 1, (10 * MB), IMAGE_DIMEN(6000), 75, TranscodingPreset.LEVEL_1),
    LEVEL_2(false, 2, (int) (15 * MB), IMAGE_DIMEN(9000), 75, TranscodingPreset.LEVEL_2),
    LEVEL_3(false, 3, (int) (20 * MB), IMAGE_DIMEN(12000), 100, TranscodingPreset.LEVEL_3),
    LEVEL_3_H265(false, 4, (int) (20 * MB), IMAGE_DIMEN(12000), 100, TranscodingPreset.LEVEL_3_H265);

    private final boolean           isLowMemory;
    private final int               level;
    private final int               maxImageFileSize;
    private final int[]             imageSizeTargets;
    private final int               qualitySetting;
    private final TranscodingPreset videoPreset;

    MediaConfig(boolean isLowMemory,
                int level,
                int maxImageFileSize,
                @NonNull int[] imageSizeTargets,
                @IntRange(from = 0, to = 100) int qualitySetting,
                TranscodingPreset videoPreset)
    {
      this.isLowMemory      = isLowMemory;
      this.level            = level;
      this.maxImageFileSize = maxImageFileSize;
      this.imageSizeTargets = imageSizeTargets;
      this.qualitySetting   = qualitySetting;
      this.videoPreset      = videoPreset;
    }

    public int getMaxImageFileSize() {
      return maxImageFileSize;
    }

    public int[] getImageSizeTargets() {
      return imageSizeTargets;
    }

    public int getImageQualitySetting() {
      return qualitySetting;
    }

    public TranscodingPreset getVideoPreset() {
      return videoPreset;
    }

    public static @Nullable MediaConfig forLevel(int level) {
      boolean isLowMemory = Util.isLowMemory(AppDependencies.getApplication());

      return Arrays.stream(values())
                   .filter(v -> v.level == level && v.isLowMemory == isLowMemory)
                   .findFirst()
                   .orElse(null);
    }

    public static @NonNull MediaConfig getDefault(Context context) {
      return Util.isLowMemory(context) ? LEVEL_1_LOW_MEMORY : LEVEL_3_H265;
    }
  }
}
