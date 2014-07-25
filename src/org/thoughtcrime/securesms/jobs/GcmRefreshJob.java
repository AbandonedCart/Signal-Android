package org.thoughtcrime.securesms.jobs;

import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.path.android.jobqueue.Params;

import org.thoughtcrime.securesms.push.PushServiceSocketFactory;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.textsecure.push.PushServiceSocket;
import org.whispersystems.textsecure.push.exceptions.MismatchedDevicesException;
import org.whispersystems.textsecure.push.exceptions.NonSuccessfulResponseCodeException;
import org.whispersystems.textsecure.push.exceptions.PushNetworkException;

public class GcmRefreshJob extends ContextJob {

  private static final String TAG = GcmRefreshJob.class.getSimpleName();

  public static final String REGISTRATION_ID = "312334754206";

  public GcmRefreshJob() {
    super(new Params(Priorities.NORMAL).requireNetwork());
  }

  @Override
  public void onAdded() {}

  @Override
  public void onRun() throws Exception {
    String registrationId = TextSecurePreferences.getGcmRegistrationId(context);

    if (registrationId == null) {
      Log.w(TAG, "GCM registrationId expired, reregistering...");
      int result = GooglePlayServicesUtil.isGooglePlayServicesAvailable(context);

      if (result != ConnectionResult.SUCCESS) {
        Toast.makeText(context, "Unable to register with GCM!", Toast.LENGTH_LONG).show();
      }

      String            gcmId  = GoogleCloudMessaging.getInstance(context).register(REGISTRATION_ID);
      PushServiceSocket socket = PushServiceSocketFactory.create(context);

      socket.registerGcmId(gcmId);
      TextSecurePreferences.setGcmRegistrationId(context, gcmId);
    }
  }

  @Override
  protected void onCancel() {
    Log.w(TAG, "GCM reregistration failed after retry attempt exhaustion!");
  }

  @Override
  protected boolean shouldReRunOnThrowable(Throwable throwable) {
    if (throwable instanceof NonSuccessfulResponseCodeException) return false;
    return true;
  }
}
