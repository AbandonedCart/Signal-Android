package org.thoughtcrime.securesms.service.webrtc;

import android.content.Context;
import android.os.ResultReceiver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.annimon.stream.Stream;

import org.signal.core.util.logging.Log;
import org.signal.ringrtc.CallException;
import org.signal.ringrtc.CallId;
import org.signal.ringrtc.CallManager;
import org.signal.ringrtc.CallManager.RingUpdate;
import org.signal.ringrtc.GroupCall;
import org.thoughtcrime.securesms.components.sensors.Orientation;
import org.thoughtcrime.securesms.components.webrtc.BroadcastVideoSink;
import org.thoughtcrime.securesms.components.webrtc.GroupCallSafetyNumberChangeNotificationUtil;
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.events.CallParticipant;
import org.thoughtcrime.securesms.events.WebRtcViewModel;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.ringrtc.CallState;
import org.thoughtcrime.securesms.ringrtc.Camera;
import org.thoughtcrime.securesms.ringrtc.CameraState;
import org.thoughtcrime.securesms.ringrtc.RemotePeer;
import org.thoughtcrime.securesms.service.webrtc.WebRtcData.CallMetadata;
import org.thoughtcrime.securesms.service.webrtc.WebRtcData.OfferMetadata;
import org.thoughtcrime.securesms.service.webrtc.WebRtcData.ReceivedOfferMetadata;
import org.thoughtcrime.securesms.service.webrtc.state.WebRtcServiceState;
import org.thoughtcrime.securesms.service.webrtc.state.WebRtcServiceStateBuilder;
import org.thoughtcrime.securesms.util.NetworkUtil;
import org.thoughtcrime.securesms.util.TelephonyUtil;
import org.thoughtcrime.securesms.webrtc.audio.SignalAudioManager;
import org.thoughtcrime.securesms.webrtc.locks.LockManager;
import org.webrtc.PeerConnection;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.messages.calls.AnswerMessage;
import org.whispersystems.signalservice.api.messages.calls.BusyMessage;
import org.whispersystems.signalservice.api.messages.calls.HangupMessage;
import org.whispersystems.signalservice.api.messages.calls.IceUpdateMessage;
import org.whispersystems.signalservice.api.messages.calls.OfferMessage;
import org.whispersystems.signalservice.api.messages.calls.SignalServiceCallMessage;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.thoughtcrime.securesms.service.webrtc.WebRtcData.AnswerMetadata;
import static org.thoughtcrime.securesms.service.webrtc.WebRtcData.HangupMetadata;
import static org.thoughtcrime.securesms.service.webrtc.WebRtcData.ReceivedAnswerMetadata;

/**
 * Base WebRTC action processor and core of the calling state machine. As actions (as intents)
 * are sent to the service, they are passed to an instance of the current state's action processor.
 * Based on the state of the system, the action processor will either handle the event or do nothing.
 * <p>
 * For example, the {@link OutgoingCallActionProcessor} responds to the the
 * {@link #handleReceivedBusy(WebRtcServiceState, CallMetadata)} event but no others do.
 * <p>
 * Processing of the actions occur in by calls from {@link SignalCallManager} and
 * result in atomic state updates that are returned to the caller. Part of the state change can be
 * the replacement of the current action processor.
 */
public abstract class WebRtcActionProcessor {

  protected final Context          context;
  protected final WebRtcInteractor webRtcInteractor;
  protected final String           tag;

  public WebRtcActionProcessor(@NonNull WebRtcInteractor webRtcInteractor, @NonNull String tag) {
    this.context          = webRtcInteractor.getContext();
    this.webRtcInteractor = webRtcInteractor;
    this.tag              = tag;
  }

  public @NonNull String getTag() {
    return tag;
  }

  protected @NonNull WebRtcServiceState handleIsInCallQuery(@NonNull WebRtcServiceState currentState, @Nullable ResultReceiver resultReceiver) {
    if (resultReceiver != null) {
      resultReceiver.send(0, null);
    }
    return currentState;
  }

  //region Pre-Join

  protected @NonNull WebRtcServiceState handlePreJoinCall(@NonNull WebRtcServiceState currentState, @NonNull RemotePeer remotePeer) {
    Log.i(tag, "handlePreJoinCall not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleCancelPreJoinCall(@NonNull WebRtcServiceState currentState) {
    Log.i(tag, "handleCancelPreJoinCall not processed");
    return currentState;
  }

  //endregion Pre-Join

  //region Outgoing Call

  protected @NonNull WebRtcServiceState handleOutgoingCall(@NonNull WebRtcServiceState currentState, @NonNull RemotePeer remotePeer, @NonNull OfferMessage.Type offerType) {
    Log.i(tag, "handleOutgoingCall not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleStartOutgoingCall(@NonNull WebRtcServiceState currentState, @NonNull RemotePeer remotePeer) {
    Log.i(tag, "handleStartOutgoingCall not processed");
    return currentState;
  }

  protected final @NonNull WebRtcServiceState handleSendOffer(@NonNull WebRtcServiceState currentState, @NonNull CallMetadata callMetadata, @NonNull OfferMetadata offerMetadata, boolean broadcast) {
    Log.i(tag, "handleSendOffer(): id: " + callMetadata.getCallId().format(callMetadata.getRemoteDevice()));

    OfferMessage             offerMessage        = new OfferMessage(callMetadata.getCallId().longValue(), offerMetadata.getSdp(), offerMetadata.getOfferType(), offerMetadata.getOpaque());
    Integer                  destinationDeviceId = broadcast ? null : callMetadata.getRemoteDevice();
    SignalServiceCallMessage callMessage         = SignalServiceCallMessage.forOffer(offerMessage, true, destinationDeviceId);

    Recipient callRecipient = currentState.getCallInfoState().getCallRecipient();
    RecipientUtil.shareProfileIfFirstSecureMessage(context, callRecipient);
    webRtcInteractor.sendCallMessage(callMetadata.getRemotePeer(), callMessage);

    return currentState;
  }

  protected @NonNull WebRtcServiceState handleRemoteRinging(@NonNull WebRtcServiceState currentState, @NonNull RemotePeer remotePeer) {
    Log.i(tag, "handleRemoteRinging not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleReceivedAnswer(@NonNull WebRtcServiceState currentState,
                                                             @NonNull CallMetadata callMetadata,
                                                             @NonNull AnswerMetadata answerMetadata,
                                                             @NonNull ReceivedAnswerMetadata receivedAnswerMetadata)
  {
    Log.i(tag, "handleReceivedAnswer not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleReceivedBusy(@NonNull WebRtcServiceState currentState, @NonNull CallMetadata callMetadata) {
    Log.i(tag, "handleReceivedBusy not processed");
    return currentState;
  }

  //endregion Outgoing call

  //region Incoming call

  protected @NonNull WebRtcServiceState handleReceivedOffer(@NonNull WebRtcServiceState currentState,
                                                            @NonNull CallMetadata callMetadata,
                                                            @NonNull OfferMetadata offerMetadata,
                                                            @NonNull ReceivedOfferMetadata receivedOfferMetadata)
  {
    Log.i(tag, "handleReceivedOffer(): id: " + callMetadata.getCallId().format(callMetadata.getRemoteDevice()));

    if (TelephonyUtil.isAnyPstnLineBusy(context)) {
      Log.i(tag, "PSTN line is busy.");
      currentState = currentState.getActionProcessor().handleSendBusy(currentState, callMetadata, true);
      webRtcInteractor.insertMissedCall(callMetadata.getRemotePeer(), receivedOfferMetadata.getServerReceivedTimestamp(), offerMetadata.getOfferType() == OfferMessage.Type.VIDEO_CALL);
      return currentState;
    }

    if (!RecipientUtil.isCallRequestAccepted(context.getApplicationContext(), callMetadata.getRemotePeer().getRecipient())) {
      Log.w(tag, "Caller is untrusted.");
      currentState = currentState.getActionProcessor().handleSendHangup(currentState, callMetadata, WebRtcData.HangupMetadata.fromType(HangupMessage.Type.NEED_PERMISSION), true);
      webRtcInteractor.insertMissedCall(callMetadata.getRemotePeer(), receivedOfferMetadata.getServerReceivedTimestamp(), offerMetadata.getOfferType() == OfferMessage.Type.VIDEO_CALL);
      return currentState;
    }

    if (offerMetadata.getOpaque() == null) {
      Log.w(tag, "Opaque data is required.");
      currentState = currentState.getActionProcessor().handleSendHangup(currentState, callMetadata, WebRtcData.HangupMetadata.fromType(HangupMessage.Type.NORMAL), true);
      webRtcInteractor.insertMissedCall(callMetadata.getRemotePeer(), receivedOfferMetadata.getServerReceivedTimestamp(), offerMetadata.getOfferType() == OfferMessage.Type.VIDEO_CALL);
      return currentState;
    }

    Log.i(tag, "add remotePeer callId: " + callMetadata.getRemotePeer().getCallId() + " key: " + callMetadata.getRemotePeer().hashCode());

    callMetadata.getRemotePeer().setCallStartTimestamp(receivedOfferMetadata.getServerReceivedTimestamp());

    currentState = currentState.builder()
                               .changeCallSetupState()
                               .isRemoteVideoOffer(offerMetadata.getOfferType() == OfferMessage.Type.VIDEO_CALL)
                               .commit()
                               .changeCallInfoState()
                               .putRemotePeer(callMetadata.getRemotePeer())
                               .build();

    long messageAgeSec = Math.max(receivedOfferMetadata.getServerDeliveredTimestamp() - receivedOfferMetadata.getServerReceivedTimestamp(), 0) / 1000;
    Log.i(tag, "messageAgeSec: " + messageAgeSec + ", serverReceivedTimestamp: " + receivedOfferMetadata.getServerReceivedTimestamp() + ", serverDeliveredTimestamp: " + receivedOfferMetadata.getServerDeliveredTimestamp());

    try {
      byte[] remoteIdentityKey = WebRtcUtil.getPublicKeyBytes(receivedOfferMetadata.getRemoteIdentityKey());
      byte[] localIdentityKey  = WebRtcUtil.getPublicKeyBytes(IdentityKeyUtil.getIdentityKey(context).serialize());

      webRtcInteractor.getCallManager().receivedOffer(callMetadata.getCallId(),
                                                      callMetadata.getRemotePeer(),
                                                      callMetadata.getRemoteDevice(),
                                                      offerMetadata.getOpaque(),
                                                      messageAgeSec,
                                                      WebRtcUtil.getCallMediaTypeFromOfferType(offerMetadata.getOfferType()),
                                                      1,
                                                      receivedOfferMetadata.isMultiRing(),
                                                      true,
                                                      remoteIdentityKey,
                                                      localIdentityKey);
    } catch (CallException | InvalidKeyException e) {
      return callFailure(currentState, "Unable to process received offer: ", e);
    }

    return currentState;
  }

  protected @NonNull WebRtcServiceState handleReceivedOfferExpired(@NonNull WebRtcServiceState currentState,
                                                                   @NonNull RemotePeer remotePeer)
  {
    Log.i(tag, "handleReceivedOfferExpired(): call_id: " + remotePeer.getCallId());

    webRtcInteractor.insertMissedCall(remotePeer, remotePeer.getCallStartTimestamp(), currentState.getCallSetupState().isRemoteVideoOffer());

    return terminate(currentState, remotePeer);
  }

  protected @NonNull WebRtcServiceState handleStartIncomingCall(@NonNull WebRtcServiceState currentState, @NonNull RemotePeer remotePeer) {
    Log.i(tag, "handleStartIncomingCall not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleAcceptCall(@NonNull WebRtcServiceState currentState, boolean answerWithVideo) {
    Log.i(tag, "handleAcceptCall not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleLocalRinging(@NonNull WebRtcServiceState currentState, @NonNull RemotePeer remotePeer) {
    Log.i(tag, "handleLocalRinging not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleDenyCall(@NonNull WebRtcServiceState currentState) {
    Log.i(tag, "handleDenyCall not processed");
    return currentState;
  }

  protected final @NonNull WebRtcServiceState handleSendAnswer(@NonNull WebRtcServiceState currentState,
                                                         @NonNull CallMetadata callMetadata,
                                                         @NonNull AnswerMetadata answerMetadata,
                                                         boolean broadcast)
  {
    Log.i(tag, "handleSendAnswer(): id: " + callMetadata.getCallId().format(callMetadata.getRemoteDevice()));

    AnswerMessage answerMessage          = new AnswerMessage(callMetadata.getCallId().longValue(), answerMetadata.getSdp(), answerMetadata.getOpaque());
    Integer       destinationDeviceId    = broadcast ? null : callMetadata.getRemoteDevice();
    SignalServiceCallMessage callMessage = SignalServiceCallMessage.forAnswer(answerMessage, true, destinationDeviceId);

    webRtcInteractor.sendCallMessage(callMetadata.getRemotePeer(), callMessage);

    return currentState;
  }

  //endregion Incoming call

  //region Active call

  protected @NonNull WebRtcServiceState handleCallConnected(@NonNull WebRtcServiceState currentState, @NonNull RemotePeer remotePeer) {
    Log.i(tag, "handleCallConnected not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleReceivedOfferWhileActive(@NonNull WebRtcServiceState currentState, @NonNull RemotePeer remotePeer) {
    Log.i(tag, "handleReceivedOfferWhileActive not processed");
    return currentState;
  }

  protected final @NonNull WebRtcServiceState handleSendBusy(@NonNull WebRtcServiceState currentState, @NonNull CallMetadata callMetadata, boolean broadcast) {
    Log.i(tag, "handleSendBusy(): id: " + callMetadata.getCallId().format(callMetadata.getRemoteDevice()));

    BusyMessage              busyMessage         = new BusyMessage(callMetadata.getCallId().longValue());
    Integer                  destinationDeviceId = broadcast ? null : callMetadata.getRemoteDevice();
    SignalServiceCallMessage callMessage         = SignalServiceCallMessage.forBusy(busyMessage, true, destinationDeviceId);

    webRtcInteractor.sendCallMessage(callMetadata.getRemotePeer(), callMessage);

    return currentState;
  }

  protected @NonNull WebRtcServiceState handleCallConcluded(@NonNull WebRtcServiceState currentState, @Nullable RemotePeer remotePeer) {
    Log.i(tag, "handleCallConcluded not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleRemoteVideoEnable(@NonNull WebRtcServiceState currentState, boolean enable) {
    Log.i(tag, "handleRemoteVideoEnable not processed");
    return currentState;
  }

  protected  @NonNull WebRtcServiceState handleScreenSharingEnable(@NonNull WebRtcServiceState currentState, boolean enable) {
    Log.i(tag, "handleScreenSharingEnable not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleReceivedHangup(@NonNull WebRtcServiceState currentState,
                                                             @NonNull CallMetadata callMetadata,
                                                             @NonNull HangupMetadata hangupMetadata)
  {
    Log.i(tag, "handleReceivedHangup(): id: " + callMetadata.getCallId().format(callMetadata.getRemoteDevice()));

    try {
      webRtcInteractor.getCallManager().receivedHangup(callMetadata.getCallId(), callMetadata.getRemoteDevice(), hangupMetadata.getCallHangupType(), hangupMetadata.getDeviceId());
    } catch (CallException e) {
      return callFailure(currentState, "receivedHangup() failed: ", e);
    }

    return currentState;
  }

  protected @NonNull WebRtcServiceState handleLocalHangup(@NonNull WebRtcServiceState currentState) {
    Log.i(tag, "handleLocalHangup not processed");
    return currentState;
  }

  protected final @NonNull WebRtcServiceState handleSendHangup(@NonNull WebRtcServiceState currentState,
                                                         @NonNull CallMetadata callMetadata,
                                                         @NonNull HangupMetadata hangupMetadata,
                                                         boolean broadcast)
  {
    Log.i(tag, "handleSendHangup(): id: " + callMetadata.getCallId().format(callMetadata.getRemoteDevice()));

    HangupMessage            hangupMessage       = new HangupMessage(callMetadata.getCallId().longValue(), hangupMetadata.getType(), hangupMetadata.getDeviceId(), hangupMetadata.isLegacy());
    Integer                  destinationDeviceId = broadcast ? null : callMetadata.getRemoteDevice();
    SignalServiceCallMessage callMessage         = SignalServiceCallMessage.forHangup(hangupMessage, true, destinationDeviceId);

    webRtcInteractor.sendCallMessage(callMetadata.getRemotePeer(), callMessage);

    return currentState;
  }

  protected @NonNull WebRtcServiceState handleMessageSentSuccess(@NonNull WebRtcServiceState currentState, @NonNull CallId callId) {
    try {
      webRtcInteractor.getCallManager().messageSent(callId);
    } catch (CallException e) {
      return callFailure(currentState, "callManager.messageSent() failed: ", e);
    }
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleMessageSentError(@NonNull WebRtcServiceState currentState,
                                                               @NonNull CallId callId,
                                                               @NonNull WebRtcViewModel.State errorCallState,
                                                               @NonNull Optional<IdentityKey> identityKey)
  {
    Log.w(tag, "handleMessageSentError():");

    try {
      webRtcInteractor.getCallManager().messageSendFailure(callId);
    } catch (CallException e) {
      currentState = callFailure(currentState, "callManager.messageSendFailure() failed: ", e);
    }

    RemotePeer activePeer = currentState.getCallInfoState().getActivePeer();
    if (activePeer == null) {
      return currentState;
    }

    WebRtcServiceStateBuilder builder = currentState.builder();

    if (errorCallState == WebRtcViewModel.State.UNTRUSTED_IDENTITY) {
      CallParticipant participant = Objects.requireNonNull(currentState.getCallInfoState().getRemoteCallParticipant(activePeer.getRecipient()));
      CallParticipant untrusted   = participant.withIdentityKey(identityKey.orNull());

      builder.changeCallInfoState()
             .callState(WebRtcViewModel.State.UNTRUSTED_IDENTITY)
             .putParticipant(activePeer.getRecipient(), untrusted)
             .commit();
    } else {
      builder.changeCallInfoState()
             .callState(errorCallState)
             .commit();
    }

    return builder.build();
  }

  protected @NonNull WebRtcServiceState handleAudioDeviceChanged(@NonNull WebRtcServiceState currentState, @NonNull SignalAudioManager.AudioDevice activeDevice, @NonNull Set<SignalAudioManager.AudioDevice> availableDevices) {
    Log.i(tag, "handleAudioDeviceChanged not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleSetUserAudioDevice(@NonNull WebRtcServiceState currentState, @NonNull SignalAudioManager.AudioDevice userDevice) {
    Log.i(tag, "handleSetUserAudioDevice not processed");
    return currentState;
  }

  //endregion Active call

  //region Call setup

  protected final @NonNull WebRtcServiceState handleSendIceCandidates(@NonNull WebRtcServiceState currentState, @NonNull CallMetadata callMetadata, boolean broadcast, @NonNull List<byte[]> iceCandidates) {
    Log.i(tag, "handleSendIceCandidates(): id: " + callMetadata.getCallId().format(callMetadata.getRemoteDevice()));

    List<IceUpdateMessage> iceUpdateMessages = Stream.of(iceCandidates)
                                                     .map(c -> new IceUpdateMessage(callMetadata.getCallId().longValue(), c, null))
                                                     .toList();

    Integer                  destinationDeviceId = broadcast ? null : callMetadata.getRemoteDevice();
    SignalServiceCallMessage callMessage         = SignalServiceCallMessage.forIceUpdates(iceUpdateMessages, true, destinationDeviceId);

    webRtcInteractor.sendCallMessage(callMetadata.getRemotePeer(), callMessage);

    return currentState;
  }

  protected @NonNull WebRtcServiceState handleReceivedIceCandidates(@NonNull WebRtcServiceState currentState, @NonNull CallMetadata callMetadata, @NonNull List<byte[]> iceCandidates) {
    Log.i(tag, "handleReceivedIceCandidates(): id: " + callMetadata.getCallId().format(callMetadata.getRemoteDevice()) + ", count: " + iceCandidates.size());

    try {
      webRtcInteractor.getCallManager().receivedIceCandidates(callMetadata.getCallId(), callMetadata.getRemoteDevice(), iceCandidates);
    } catch (CallException e) {
      return callFailure(currentState, "receivedIceCandidates() failed: ", e);
    }

    return currentState;
  }

  public @NonNull WebRtcServiceState handleTurnServerUpdate(@NonNull WebRtcServiceState currentState, @NonNull List<PeerConnection.IceServer> iceServers, boolean isAlwaysTurn) {
    Log.i(tag, "handleTurnServerUpdate not processed");
    return currentState;
  }

  //endregion Call setup

  //region Local device

  protected @NonNull WebRtcServiceState handleSetEnableVideo(@NonNull WebRtcServiceState currentState, boolean enable) {
    Log.i(tag, "handleSetEnableVideo not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleSetMuteAudio(@NonNull WebRtcServiceState currentState, boolean muted) {
    Log.i(tag, "handleSetMuteAudio not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleSetCameraFlip(@NonNull WebRtcServiceState currentState) {
    Log.i(tag, "handleSetCameraFlip not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleScreenOffChange(@NonNull WebRtcServiceState currentState) {
    Log.i(tag, "handleScreenOffChange not processed");
    return currentState;
  }

  public @NonNull WebRtcServiceState handleCameraSwitchCompleted(@NonNull WebRtcServiceState currentState, @NonNull CameraState newCameraState) {
    Log.i(tag, "handleCameraSwitchCompleted not processed");
    return currentState;
  }

  public @NonNull WebRtcServiceState handleNetworkChanged(@NonNull WebRtcServiceState currentState, boolean available) {
    Log.i(tag, "handleNetworkChanged not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleBandwidthModeUpdate(@NonNull WebRtcServiceState currentState) {
    try {
      webRtcInteractor.getCallManager().updateBandwidthMode(NetworkUtil.getCallingBandwidthMode(context));
    } catch (CallException e) {
      Log.i(tag, "handleBandwidthModeUpdate: could not update bandwidth mode.");
    }

    return currentState;
  }

  protected @NonNull WebRtcServiceState handleOrientationChanged(@NonNull WebRtcServiceState currentState, boolean isLandscapeEnabled, int orientationDegrees) {
    Camera camera = currentState.getVideoState().getCamera();
    if (camera != null) {
      camera.setOrientation(orientationDegrees);
    }

    int sinkRotationDegrees  = isLandscapeEnabled ? BroadcastVideoSink.DEVICE_ROTATION_IGNORE : orientationDegrees;
    int stateRotationDegrees = isLandscapeEnabled ? 0 : orientationDegrees;

    BroadcastVideoSink sink = currentState.getVideoState().getLocalSink();
    if (sink != null) {
      sink.setDeviceOrientationDegrees(sinkRotationDegrees);
    }

    for (CallParticipant callParticipant : currentState.getCallInfoState().getRemoteCallParticipants()) {
      callParticipant.getVideoSink().setDeviceOrientationDegrees(sinkRotationDegrees);
    }

    return currentState.builder()
                       .changeLocalDeviceState()
                       .setOrientation(Orientation.fromDegrees(stateRotationDegrees))
                       .setLandscapeEnabled(isLandscapeEnabled)
                       .setDeviceOrientation(Orientation.fromDegrees(orientationDegrees))
                       .build();
  }

  //endregion Local device

  //region End call

  protected @NonNull WebRtcServiceState handleEndedRemote(@NonNull WebRtcServiceState currentState, @NonNull CallManager.CallEvent endedRemoteEvent, @NonNull RemotePeer remotePeer) {
    Log.i(tag, "handleEndedRemote not processed");
    return currentState;
  }

  //endregion End call

  //region End call failure

  protected @NonNull WebRtcServiceState handleEnded(@NonNull WebRtcServiceState currentState, @NonNull CallManager.CallEvent endedEvent, @NonNull RemotePeer remotePeer) {
    Log.i(tag, "handleEnded not processed");
    return currentState;
  }

  //endregion

  //region Local call failure

  protected @NonNull WebRtcServiceState handleSetupFailure(@NonNull WebRtcServiceState currentState, @NonNull CallId callId) {
    Log.i(tag, "handleSetupFailure not processed");
    return currentState;
  }

  //endregion

  //region Global call operations

  public @NonNull WebRtcServiceState callFailure(@NonNull WebRtcServiceState currentState,
                                                 @Nullable String message,
                                                 @Nullable Throwable error)
  {
    Log.w(tag, "callFailure(): " + message, error);

    WebRtcServiceStateBuilder builder = currentState.builder();

    if (currentState.getCallInfoState().getActivePeer() != null) {
      builder.changeCallInfoState()
             .callState(WebRtcViewModel.State.CALL_DISCONNECTED);
    }

    try {
      webRtcInteractor.getCallManager().reset();
    } catch (CallException e) {
      Log.w(tag, "Unable to reset call manager: ", e);
    }

    currentState = builder.changeCallInfoState().clearPeerMap().build();
    return terminate(currentState, currentState.getCallInfoState().getActivePeer());
  }

  public synchronized @NonNull WebRtcServiceState terminate(@NonNull WebRtcServiceState currentState, @Nullable RemotePeer remotePeer) {
    Log.i(tag, "terminate():");

    RemotePeer activePeer = currentState.getCallInfoState().getActivePeer();

    if (activePeer == null) {
      Log.i(tag, "skipping with no active peer");
      return currentState;
    }

    if (!activePeer.callIdEquals(remotePeer)) {
      Log.i(tag, "skipping remotePeer is not active peer");
      return currentState;
    }

    ApplicationDependencies.getAppForegroundObserver().removeListener(webRtcInteractor.getForegroundListener());

    webRtcInteractor.updatePhoneState(LockManager.PhoneState.PROCESSING);
    boolean playDisconnectSound = (activePeer.getState() == CallState.DIALING) ||
                                  (activePeer.getState() == CallState.REMOTE_RINGING) ||
                                  (activePeer.getState() == CallState.RECEIVED_BUSY) ||
                                  (activePeer.getState() == CallState.CONNECTED);
    webRtcInteractor.stopAudio(playDisconnectSound);

    webRtcInteractor.updatePhoneState(LockManager.PhoneState.IDLE);
    webRtcInteractor.stopForegroundService();

    return WebRtcVideoUtil.deinitializeVideo(currentState)
                          .builder()
                          .changeCallInfoState()
                          .activePeer(null)
                          .commit()
                          .changeLocalDeviceState()
                          .commit()
                          .actionProcessor(currentState.getCallInfoState().getCallState() == WebRtcViewModel.State.CALL_DISCONNECTED ? new DisconnectingCallActionProcessor(webRtcInteractor) : new IdleActionProcessor(webRtcInteractor))
                          .terminate()
                          .build();
  }

  //endregion

  //region Group Calling

  protected @NonNull WebRtcServiceState handleGroupLocalDeviceStateChanged(@NonNull WebRtcServiceState currentState) {
    Log.i(tag, "handleGroupLocalDeviceStateChanged not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleGroupRemoteDeviceStateChanged(@NonNull WebRtcServiceState currentState) {
    Log.i(tag, "handleGroupRemoteDeviceStateChanged not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleGroupJoinedMembershipChanged(@NonNull WebRtcServiceState currentState) {
    Log.i(tag, "handleGroupJoinedMembershipChanged not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleGroupRequestMembershipProof(@NonNull WebRtcServiceState currentState, int groupCallHash, @NonNull byte[] groupMembershipToken) {
    Log.i(tag, "handleGroupRequestMembershipProof not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleGroupRequestUpdateMembers(@NonNull WebRtcServiceState currentState) {
    Log.i(tag, "handleGroupRequestUpdateMembers not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleUpdateRenderedResolutions(@NonNull WebRtcServiceState currentState) {
    Log.i(tag, "handleUpdateRenderedResolutions not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleGroupCallEnded(@NonNull WebRtcServiceState currentState, int groupCallHash, @NonNull GroupCall.GroupCallEndReason groupCallEndReason) {
    Log.i(tag, "handleGroupCallEnded not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleGroupMessageSentError(@NonNull WebRtcServiceState currentState,
                                                                    @NonNull Collection<RecipientId> recipientIds,
                                                                    @NonNull WebRtcViewModel.State errorCallState)
  {
    Log.i(tag, "handleGroupMessageSentError not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleGroupApproveSafetyNumberChange(@NonNull WebRtcServiceState currentState,
                                                                             @NonNull List<RecipientId> recipientIds)
  {
    Log.i(tag, "handleGroupApproveSafetyNumberChange not processed");
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleReceivedOpaqueMessage(@NonNull WebRtcServiceState currentState, @NonNull WebRtcData.OpaqueMessageMetadata opaqueMessageMetadata) {
    Log.i(tag, "handleReceivedOpaqueMessage():");

    try {
      webRtcInteractor.getCallManager().receivedCallMessage(opaqueMessageMetadata.getUuid(),
                                                            opaqueMessageMetadata.getRemoteDeviceId(),
                                                            1,
                                                            opaqueMessageMetadata.getOpaque(),
                                                            opaqueMessageMetadata.getMessageAgeSeconds());
    } catch (CallException e) {
      return groupCallFailure(currentState, "Unable to receive opaque message", e);
    }

    return currentState;
  }

  protected @NonNull WebRtcServiceState handleGroupCallRingUpdate(@NonNull WebRtcServiceState currentState,
                                                                  @NonNull RemotePeer remotePeerGroup,
                                                                  @NonNull GroupId.V2 groupId,
                                                                  long ringId,
                                                                  @NonNull UUID uuid,
                                                                  @NonNull RingUpdate ringUpdate)
  {
    Log.i(tag, "handleGroupCallRingUpdate(): recipient: " + remotePeerGroup.getId() + " ring: " + ringId + " update: " + ringUpdate);

    try {
      if (ringUpdate != RingUpdate.BUSY_LOCALLY && ringUpdate != RingUpdate.BUSY_ON_ANOTHER_DEVICE) {
        webRtcInteractor.getCallManager().cancelGroupRing(groupId.getDecodedId(), ringId, CallManager.RingCancelReason.Busy);
      }
      DatabaseFactory.getGroupCallRingDatabase(context).insertOrUpdateGroupRing(ringId,
                                                                                System.currentTimeMillis(),
                                                                                ringUpdate == RingUpdate.REQUESTED ? RingUpdate.BUSY_LOCALLY : ringUpdate);
    } catch (CallException e) {
      Log.w(tag, "Unable to cancel ring", e);
    }
    return currentState;
  }

  protected @NonNull WebRtcServiceState handleSetRingGroup(@NonNull WebRtcServiceState currentState, boolean ringGroup) {
    Log.i(tag, "handleSetRingGroup not processed");

    return currentState;
  }

  protected @NonNull WebRtcServiceState handleReceivedGroupCallPeekForRingingCheck(@NonNull WebRtcServiceState currentState, @NonNull GroupCallRingCheckInfo info, long deviceCount) {
    Log.i(tag, "handleReceivedGroupCallPeekForRingingCheck not processed");

    return currentState;
  }

  protected @NonNull WebRtcServiceState groupCallFailure(@NonNull WebRtcServiceState currentState, @NonNull String message, @NonNull Throwable error) {
    Log.w(tag, "groupCallFailure(): " + message, error);

    GroupCall groupCall = currentState.getCallInfoState().getGroupCall();
    Recipient recipient = currentState.getCallInfoState().getCallRecipient();

    if (recipient != null && currentState.getCallInfoState().getGroupCallState().isConnected()) {
      webRtcInteractor.sendGroupCallMessage(recipient, WebRtcUtil.getGroupCallEraId(groupCall));
    }

    currentState = currentState.builder()
                               .changeCallInfoState()
                               .callState(WebRtcViewModel.State.CALL_DISCONNECTED)
                               .groupCallState(WebRtcViewModel.GroupCallState.DISCONNECTED)
                               .build();

    webRtcInteractor.postStateUpdate(currentState);

    try {
      if (groupCall != null) {
        groupCall.disconnect();
      }
      webRtcInteractor.getCallManager().reset();
    } catch (CallException e) {
      Log.w(tag, "Unable to reset call manager: ", e);
    }

    return terminateGroupCall(currentState);
  }

  protected synchronized @NonNull WebRtcServiceState terminateGroupCall(@NonNull WebRtcServiceState currentState) {
    return terminateGroupCall(currentState, true);
  }

  protected synchronized @NonNull WebRtcServiceState terminateGroupCall(@NonNull WebRtcServiceState currentState, boolean terminateVideo) {
    webRtcInteractor.updatePhoneState(LockManager.PhoneState.PROCESSING);
    boolean playDisconnectSound = currentState.getCallInfoState().getCallState() == WebRtcViewModel.State.CALL_DISCONNECTED;
    webRtcInteractor.stopAudio(playDisconnectSound);
    webRtcInteractor.updatePhoneState(LockManager.PhoneState.IDLE);
    webRtcInteractor.stopForegroundService();

    if (terminateVideo) {
      WebRtcVideoUtil.deinitializeVideo(currentState);
    }

    GroupCallSafetyNumberChangeNotificationUtil.cancelNotification(context, currentState.getCallInfoState().getCallRecipient());

    return new WebRtcServiceState(new IdleActionProcessor(webRtcInteractor));
  }

  //endregion
}
