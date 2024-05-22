/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.registration.v2.data

import android.app.backup.BackupManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.signal.core.util.Base64
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.util.KeyHelper
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.thoughtcrime.securesms.AppCapabilities
import org.thoughtcrime.securesms.crypto.PreKeyUtil
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil
import org.thoughtcrime.securesms.crypto.SenderKeyUtil
import org.thoughtcrime.securesms.crypto.storage.PreKeyMetadataStore
import org.thoughtcrime.securesms.crypto.storage.SignalServiceAccountDataStoreImpl
import org.thoughtcrime.securesms.database.IdentityTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.gcm.FcmUtil
import org.thoughtcrime.securesms.jobs.DirectoryRefreshJob
import org.thoughtcrime.securesms.jobs.PreKeysSyncJob
import org.thoughtcrime.securesms.jobs.RotateCertificateJob
import org.thoughtcrime.securesms.keyvalue.PhoneNumberPrivacyValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.notifications.NotificationIds
import org.thoughtcrime.securesms.pin.SvrRepository
import org.thoughtcrime.securesms.push.AccountManagerFactory
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.registration.PushChallengeRequest
import org.thoughtcrime.securesms.registration.RegistrationData
import org.thoughtcrime.securesms.registration.VerifyAccountRepository
import org.thoughtcrime.securesms.registration.v2.data.network.BackupAuthCheckResult
import org.thoughtcrime.securesms.registration.v2.data.network.RegisterAccountResult
import org.thoughtcrime.securesms.registration.v2.data.network.RegistrationSessionCheckResult
import org.thoughtcrime.securesms.registration.v2.data.network.RegistrationSessionCreationResult
import org.thoughtcrime.securesms.registration.v2.data.network.RegistrationSessionResult
import org.thoughtcrime.securesms.registration.v2.data.network.VerificationCodeRequestResult
import org.thoughtcrime.securesms.registration.viewmodel.SvrAuthCredentialSet
import org.thoughtcrime.securesms.service.DirectoryRefreshListener
import org.thoughtcrime.securesms.service.RotateSignedPreKeyListener
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.signalservice.api.NetworkResult
import org.whispersystems.signalservice.api.account.AccountAttributes
import org.whispersystems.signalservice.api.account.PreKeyCollection
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccess
import org.whispersystems.signalservice.api.kbs.MasterKey
import org.whispersystems.signalservice.api.kbs.PinHashUtil
import org.whispersystems.signalservice.api.push.ServiceId
import org.whispersystems.signalservice.api.push.ServiceId.ACI
import org.whispersystems.signalservice.api.push.ServiceId.PNI
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.registration.RegistrationApi
import org.whispersystems.signalservice.internal.push.AuthCredentials
import org.whispersystems.signalservice.internal.push.PushServiceSocket
import org.whispersystems.signalservice.internal.push.RegistrationSessionMetadataHeaders
import org.whispersystems.signalservice.internal.push.RegistrationSessionMetadataResponse
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * A repository that deals with disk I/O during account registration.
 */
object RegistrationRepository {

  private val TAG = Log.tag(RegistrationRepository::class.java)

  private val PUSH_REQUEST_TIMEOUT = 5.seconds.inWholeMilliseconds

  /**
   * Retrieve the FCM token from the Firebase service.
   */
  suspend fun getFcmToken(context: Context): String? =
    withContext(Dispatchers.Default) {
      FcmUtil.getToken(context).orElse(null)
    }

  /**
   * Queries the local store for whether a PIN is set.
   */
  @JvmStatic
  fun hasPin(): Boolean {
    return SignalStore.svr().hasPin()
  }

  /**
   * Queries, and creates if needed, the local registration ID.
   */
  @JvmStatic
  fun getRegistrationId(): Int {
    // TODO [regv2]: make creation more explicit instead of hiding it in this getter
    var registrationId = SignalStore.account().registrationId
    if (registrationId == 0) {
      registrationId = KeyHelper.generateRegistrationId(false)
      SignalStore.account().registrationId = registrationId
    }
    return registrationId
  }

  /**
   * Queries, and creates if needed, the local PNI registration ID.
   */
  @JvmStatic
  fun getPniRegistrationId(): Int {
    // TODO [regv2]: make creation more explicit instead of hiding it in this getter
    var pniRegistrationId = SignalStore.account().pniRegistrationId
    if (pniRegistrationId == 0) {
      pniRegistrationId = KeyHelper.generateRegistrationId(false)
      SignalStore.account().pniRegistrationId = pniRegistrationId
    }
    return pniRegistrationId
  }

  /**
   * Queries, and creates if needed, the local profile key.
   */
  @JvmStatic
  suspend fun getProfileKey(e164: String): ProfileKey =
    withContext(Dispatchers.IO) {
      // TODO [regv2]: make creation more explicit instead of hiding it in this getter
      val recipientTable = SignalDatabase.recipients
      val recipient = recipientTable.getByE164(e164)
      var profileKey = if (recipient.isPresent) {
        ProfileKeyUtil.profileKeyOrNull(Recipient.resolved(recipient.get()).profileKey)
      } else {
        null
      }
      if (profileKey == null) {
        profileKey = ProfileKeyUtil.createNew()
        Log.i(TAG, "No profile key found, created a new one")
      }
      profileKey
    }

  /**
   * Takes a server response from a successful registration and persists the relevant data.
   */
  @JvmStatic
  suspend fun registerAccountLocally(context: Context, registrationData: RegistrationData, response: AccountRegistrationResult, reglockEnabled: Boolean) =
    withContext(Dispatchers.IO) {
      val aciPreKeyCollection: PreKeyCollection = response.aciPreKeyCollection
      val pniPreKeyCollection: PreKeyCollection = response.pniPreKeyCollection
      val aci: ACI = ACI.parseOrThrow(response.uuid)
      val pni: PNI = PNI.parseOrThrow(response.pni)
      val hasPin: Boolean = response.storageCapable

      SignalStore.account().setAci(aci)
      SignalStore.account().setPni(pni)

      AppDependencies.resetProtocolStores()

      AppDependencies.protocolStore.aci().sessions().archiveAllSessions()
      AppDependencies.protocolStore.pni().sessions().archiveAllSessions()
      SenderKeyUtil.clearAllState()

      val aciProtocolStore = AppDependencies.protocolStore.aci()
      val aciMetadataStore = SignalStore.account().aciPreKeys

      val pniProtocolStore = AppDependencies.protocolStore.pni()
      val pniMetadataStore = SignalStore.account().pniPreKeys

      storeSignedAndLastResortPreKeys(aciProtocolStore, aciMetadataStore, aciPreKeyCollection)
      storeSignedAndLastResortPreKeys(pniProtocolStore, pniMetadataStore, pniPreKeyCollection)

      val recipientTable = SignalDatabase.recipients
      val selfId = Recipient.trustedPush(aci, pni, registrationData.e164).id

      recipientTable.setProfileSharing(selfId, true)
      recipientTable.markRegisteredOrThrow(selfId, aci)
      recipientTable.linkIdsForSelf(aci, pni, registrationData.e164)
      recipientTable.setProfileKey(selfId, registrationData.profileKey)

      AppDependencies.recipientCache.clearSelf()

      SignalStore.account().setE164(registrationData.e164)
      SignalStore.account().fcmToken = registrationData.fcmToken
      SignalStore.account().fcmEnabled = registrationData.isFcm

      val now = System.currentTimeMillis()
      saveOwnIdentityKey(selfId, aci, aciProtocolStore, now)
      saveOwnIdentityKey(selfId, pni, pniProtocolStore, now)

      SignalStore.account().setServicePassword(registrationData.password)
      SignalStore.account().setRegistered(true)
      TextSecurePreferences.setPromptedPushRegistration(context, true)
      TextSecurePreferences.setUnauthorizedReceived(context, false)
      NotificationManagerCompat.from(context).cancel(NotificationIds.UNREGISTERED_NOTIFICATION_ID)

      SvrRepository.onRegistrationComplete(response.masterKey, response.pin, hasPin, reglockEnabled)

      AppDependencies.resetNetwork()
      AppDependencies.incomingMessageObserver
      PreKeysSyncJob.enqueue()

      val jobManager = AppDependencies.jobManager
      jobManager.add(DirectoryRefreshJob(false))
      jobManager.add(RotateCertificateJob())

      DirectoryRefreshListener.schedule(context)
      RotateSignedPreKeyListener.schedule(context)
    }

  @JvmStatic
  private fun saveOwnIdentityKey(selfId: RecipientId, serviceId: ServiceId, protocolStore: SignalServiceAccountDataStoreImpl, now: Long) {
    protocolStore.identities().saveIdentityWithoutSideEffects(
      selfId,
      serviceId,
      protocolStore.identityKeyPair.publicKey,
      IdentityTable.VerifiedStatus.VERIFIED,
      true,
      now,
      true
    )
  }

  @JvmStatic
  private fun storeSignedAndLastResortPreKeys(protocolStore: SignalServiceAccountDataStoreImpl, metadataStore: PreKeyMetadataStore, preKeyCollection: PreKeyCollection) {
    PreKeyUtil.storeSignedPreKey(protocolStore, metadataStore, preKeyCollection.signedPreKey)
    metadataStore.isSignedPreKeyRegistered = true
    metadataStore.activeSignedPreKeyId = preKeyCollection.signedPreKey.id
    metadataStore.lastSignedPreKeyRotationTime = System.currentTimeMillis()

    PreKeyUtil.storeLastResortKyberPreKey(protocolStore, metadataStore, preKeyCollection.lastResortKyberPreKey)
    metadataStore.lastResortKyberPreKeyId = preKeyCollection.lastResortKyberPreKey.id
    metadataStore.lastResortKyberPreKeyRotationTime = System.currentTimeMillis()
  }

  fun canUseLocalRecoveryPassword(): Boolean {
    val recoveryPassword = SignalStore.svr().recoveryPassword
    val pinHash = SignalStore.svr().localPinHash
    return recoveryPassword != null && pinHash != null
  }

  fun doesPinMatchLocalHash(pin: String): Boolean {
    val pinHash = SignalStore.svr().localPinHash ?: throw IllegalStateException("Local PIN hash is not present!")
    return PinHashUtil.verifyLocalPinHash(pinHash, pin)
  }

  suspend fun fetchMasterKeyFromSvrRemote(pin: String, authCredentials: AuthCredentials): MasterKey =
    withContext(Dispatchers.IO) {
      val masterKey = SvrRepository.restoreMasterKeyPreRegistration(SvrAuthCredentialSet(null, authCredentials), pin)
      SignalStore.svr().setMasterKey(masterKey, pin)
      return@withContext masterKey
    }

  /**
   * Validates a session ID.
   */
  suspend fun validateSession(context: Context, sessionId: String, e164: String, password: String): RegistrationSessionCheckResult =
    withContext(Dispatchers.IO) {
      val api: RegistrationApi = AccountManagerFactory.getInstance().createUnauthenticated(context, e164, SignalServiceAddress.DEFAULT_DEVICE_ID, password).registrationApi
      Log.d(TAG, "Validating registration session with service.")
      val registrationSessionResult = api.getRegistrationSessionStatus(sessionId)
      return@withContext RegistrationSessionCheckResult.from(registrationSessionResult)
    }

  /**
   * Initiates a new registration session on the service.
   */
  suspend fun createSession(context: Context, e164: String, password: String, mcc: String?, mnc: String?): RegistrationSessionCreationResult =
    withContext(Dispatchers.IO) {
      val fcmToken: String? = FcmUtil.getToken(context).orElse(null)
      val api: RegistrationApi = AccountManagerFactory.getInstance().createUnauthenticated(context, e164, SignalServiceAddress.DEFAULT_DEVICE_ID, password).registrationApi

      val registrationSessionResult = if (fcmToken == null) {
        Log.d(TAG, "Creating registration session without FCM token.")
        api.createRegistrationSession(null, mcc, mnc)
      } else {
        Log.d(TAG, "Creating registration session with FCM token.")
        createSessionAndBlockForPushChallenge(api, fcmToken, mcc, mnc)
      }
      val result = RegistrationSessionCreationResult.from(registrationSessionResult)
      if (result is RegistrationSessionCreationResult.Success) {
        Log.d(TAG, "Updating registration session and E164 in value store.")
        SignalStore.registrationValues().sessionId = result.getMetadata().body.id
        SignalStore.registrationValues().sessionE164 = e164
      }

      return@withContext result
    }

  /**
   * Validates an existing session, if its ID is provided. If the session is expired/invalid, or none is provided, it will attempt to initiate a new session.
   */
  suspend fun createOrValidateSession(context: Context, sessionId: String?, e164: String, password: String, mcc: String?, mnc: String?): RegistrationSessionResult {
    if (sessionId != null) {
      Log.d(TAG, "Validating existing registration session.")
      val sessionValidationResult = validateSession(context, sessionId, e164, password)
      when (sessionValidationResult) {
        is RegistrationSessionCheckResult.Success -> {
          Log.d(TAG, "Existing registration session is valid.")
          return sessionValidationResult
        }
        is RegistrationSessionCheckResult.UnknownError -> {
          Log.w(TAG, "Encountered error when validating existing session.", sessionValidationResult.getCause())
          return sessionValidationResult
        }

        is RegistrationSessionCheckResult.SessionNotFound -> {
          Log.i(TAG, "Current session is invalid or has expired. Must create new one.")
          // fall through to creation
        }
      }
    }
    return createSession(context, e164, password, mcc, mnc)
  }

  /**
   * Asks the service to send a verification code through one of our supported channels (SMS, phone call).
   */
  suspend fun requestSmsCode(context: Context, sessionId: String, e164: String, password: String, mode: Mode): VerificationCodeRequestResult =
    withContext(Dispatchers.IO) {
      val api: RegistrationApi = AccountManagerFactory.getInstance().createUnauthenticated(context, e164, SignalServiceAddress.DEFAULT_DEVICE_ID, password).registrationApi

      val codeRequestResult = api.requestSmsVerificationCode(sessionId, Locale.getDefault(), mode.isSmsRetrieverSupported, mode.transport)

      return@withContext VerificationCodeRequestResult.from(codeRequestResult)
    }

  /**
   * Submits the user-entered verification code to the service.
   */
  suspend fun submitVerificationCode(context: Context, e164: String, password: String, sessionId: String, registrationData: RegistrationData): VerificationCodeRequestResult =
    withContext(Dispatchers.IO) {
      val api: RegistrationApi = AccountManagerFactory.getInstance().createUnauthenticated(context, e164, SignalServiceAddress.DEFAULT_DEVICE_ID, password).registrationApi
      val result = api.verifyAccount(sessionId = sessionId, verificationCode = registrationData.code)
      return@withContext VerificationCodeRequestResult.from(result)
    }

  /**
   * Submits the solved captcha token to the service.
   */
  suspend fun submitCaptchaToken(context: Context, e164: String, password: String, sessionId: String, captchaToken: String): VerificationCodeRequestResult =
    withContext(Dispatchers.IO) {
      val api: RegistrationApi = AccountManagerFactory.getInstance().createUnauthenticated(context, e164, SignalServiceAddress.DEFAULT_DEVICE_ID, password).registrationApi
      val captchaSubmissionResult = api.submitCaptchaToken(sessionId = sessionId, captchaToken = captchaToken)
      return@withContext VerificationCodeRequestResult.from(captchaSubmissionResult)
    }

  /**
   * Submit the necessary assets as a verified account so that the user can actually use the service.
   */
  suspend fun registerAccount(context: Context, sessionId: String?, registrationData: RegistrationData, pin: String? = null, masterKeyProducer: VerifyAccountRepository.MasterKeyProducer? = null): RegisterAccountResult =
    withContext(Dispatchers.IO) {
      val api: RegistrationApi = AccountManagerFactory.getInstance().createUnauthenticated(context, registrationData.e164, SignalServiceAddress.DEFAULT_DEVICE_ID, registrationData.password).registrationApi

      val universalUnidentifiedAccess: Boolean = TextSecurePreferences.isUniversalUnidentifiedAccess(context)
      val unidentifiedAccessKey: ByteArray = UnidentifiedAccess.deriveAccessKeyFrom(registrationData.profileKey)

      val masterKey: MasterKey? = masterKeyProducer?.produceMasterKey()
      val registrationLock: String? = masterKey?.deriveRegistrationLock()

      val accountAttributes = AccountAttributes(
        signalingKey = null,
        registrationId = registrationData.registrationId,
        fetchesMessages = registrationData.isNotFcm,
        registrationLock = registrationLock,
        unidentifiedAccessKey = unidentifiedAccessKey,
        unrestrictedUnidentifiedAccess = universalUnidentifiedAccess,
        capabilities = AppCapabilities.getCapabilities(true),
        discoverableByPhoneNumber = SignalStore.phoneNumberPrivacy().phoneNumberDiscoverabilityMode == PhoneNumberPrivacyValues.PhoneNumberDiscoverabilityMode.DISCOVERABLE,
        name = null,
        pniRegistrationId = registrationData.pniRegistrationId,
        recoveryPassword = registrationData.recoveryPassword
      )

      SignalStore.account().generateAciIdentityKeyIfNecessary()
      val aciIdentity: IdentityKeyPair = SignalStore.account().aciIdentityKey

      SignalStore.account().generatePniIdentityKeyIfNecessary()
      val pniIdentity: IdentityKeyPair = SignalStore.account().pniIdentityKey

      val aciPreKeyCollection = org.thoughtcrime.securesms.registration.RegistrationRepository.generateSignedAndLastResortPreKeys(aciIdentity, SignalStore.account().aciPreKeys)
      val pniPreKeyCollection = org.thoughtcrime.securesms.registration.RegistrationRepository.generateSignedAndLastResortPreKeys(pniIdentity, SignalStore.account().pniPreKeys)

      val result: NetworkResult<AccountRegistrationResult> = api.registerAccount(sessionId, registrationData.recoveryPassword, accountAttributes, aciPreKeyCollection, pniPreKeyCollection, registrationData.fcmToken, true)
        .map { accountRegistrationResponse ->
          AccountRegistrationResult(
            uuid = accountRegistrationResponse.uuid,
            pni = accountRegistrationResponse.pni,
            storageCapable = accountRegistrationResponse.storageCapable,
            number = accountRegistrationResponse.number,
            masterKey = masterKey,
            pin = pin,
            aciPreKeyCollection = aciPreKeyCollection,
            pniPreKeyCollection = pniPreKeyCollection
          )
        }

      return@withContext RegisterAccountResult.from(result)
    }

  suspend fun createSessionAndBlockForPushChallenge(accountManager: RegistrationApi, fcmToken: String, mcc: String?, mnc: String?): NetworkResult<RegistrationSessionMetadataResponse> =
    withContext(Dispatchers.IO) {
      // TODO [regv2]: do not use event bus nor latch
      val subscriber = PushTokenChallengeSubscriber()
      val eventBus = EventBus.getDefault()
      eventBus.register(subscriber)

      try {
        Log.d(TAG, "Requesting a registration session with FCM token…")
        val sessionCreationResponse = accountManager.createRegistrationSession(fcmToken, mcc, mnc)
        if (sessionCreationResponse !is NetworkResult.Success) {
          return@withContext sessionCreationResponse
        }

        val receivedPush = subscriber.latch.await(PUSH_REQUEST_TIMEOUT, TimeUnit.MILLISECONDS)
        eventBus.unregister(subscriber)

        if (receivedPush) {
          val challenge = subscriber.challenge
          if (challenge != null) {
            Log.w(TAG, "Push challenge token received.")
            return@withContext accountManager.submitPushChallengeToken(sessionCreationResponse.result.body.id, challenge)
          } else {
            Log.w(TAG, "Push received but challenge token was null.")
          }
        } else {
          Log.i(TAG, "Push challenge timed out.")
        }
        Log.i(TAG, "Push challenge unsuccessful. Updating registration state accordingly.")
        return@withContext NetworkResult.ApplicationError<RegistrationSessionMetadataResponse>(NullPointerException())
      } catch (ex: Exception) {
        Log.w(TAG, "Exception caught, but the earlier try block should have caught it?", ex) // TODO [regv2]: figure out why this exception is not caught
        return@withContext NetworkResult.ApplicationError<RegistrationSessionMetadataResponse>(ex)
      }
    }

  @JvmStatic
  fun deriveTimestamp(headers: RegistrationSessionMetadataHeaders, deltaSeconds: Int?): Long {
    if (deltaSeconds == null) {
      return 0L
    }

    val timestamp: Long = headers.timestamp
    return timestamp + deltaSeconds.seconds.inWholeMilliseconds
  }

  suspend fun hasValidSvrAuthCredentials(context: Context, e164: String, password: String): BackupAuthCheckResult =
    withContext(Dispatchers.IO) {
      val usernamePasswords = async { retrieveLocalSvrCredentials() }
      val api: RegistrationApi = AccountManagerFactory.getInstance().createUnauthenticated(context, e164, SignalServiceAddress.DEFAULT_DEVICE_ID, password).registrationApi

      val authTokens = usernamePasswords.await()

      if (authTokens.isEmpty()) {
        return@withContext BackupAuthCheckResult.SuccessWithoutCredentials()
      }

      val result = api.getSvrAuthCredential(e164, authTokens)
        .runIfSuccessful {
          val removedInvalidTokens = SignalStore.svr().removeAuthTokens(it.invalid)
          if (removedInvalidTokens) {
            BackupManager(context).dataChanged()
          }
        }

      return@withContext BackupAuthCheckResult.from(result)
    }

  private suspend fun retrieveLocalSvrCredentials(): List<String> = withContext(Dispatchers.IO) {
    return@withContext SignalStore.svr()
      .authTokenList
      .asSequence()
      .filterNotNull()
      .take<String>(10)
      .map<String, String> {
        it.replace("Basic ", "").trim()
      }
      .mapNotNull<String, ByteArray> {
        try {
          Base64.decode(it)
        } catch (e: IOException) {
          Log.w(TAG, "Encountered error trying to decode a token!", e)
          null
        }
      }
      .map<ByteArray, String> {
        String(it, StandardCharsets.ISO_8859_1)
      }
      .toList()
  }

  /**
   * Starts an SMS listener to auto-enter a verification code.
   *
   * The listener [lives for 5 minutes](https://developers.google.com/android/reference/com/google/android/gms/auth/api/phone/SmsRetrieverApi).
   *
   * @return whether or not the Play Services SMS Listener was successfully registered.
   */
  suspend fun registerSmsListener(context: Context): Boolean {
    Log.d(TAG, "Attempting to start verification code SMS retriever.")
    val started = withTimeoutOrNull(5.seconds.inWholeMilliseconds) {
      try {
        SmsRetriever.getClient(context).startSmsRetriever().await()
        Log.d(TAG, "Successfully started verification code SMS retriever.")
        return@withTimeoutOrNull true
      } catch (ex: Exception) {
        Log.w(TAG, "Could not start verification code SMS retriever due to exception.", ex)
        return@withTimeoutOrNull false
      }
    }

    if (started == null) {
      Log.w(TAG, "Could not start verification code SMS retriever due to timeout.")
    }

    return started == true
  }

  enum class Mode(val isSmsRetrieverSupported: Boolean, val transport: PushServiceSocket.VerificationCodeTransport) {
    SMS_WITH_LISTENER(true, PushServiceSocket.VerificationCodeTransport.SMS),
    SMS_WITHOUT_LISTENER(false, PushServiceSocket.VerificationCodeTransport.SMS),
    PHONE_CALL(false, PushServiceSocket.VerificationCodeTransport.VOICE),
    NONE(false, PushServiceSocket.VerificationCodeTransport.SMS)
  }

  private class PushTokenChallengeSubscriber {
    var challenge: String? = null
    val latch = CountDownLatch(1)

    @Subscribe
    fun onChallengeEvent(pushChallengeEvent: PushChallengeRequest.PushChallengeEvent) {
      Log.d(TAG, "Push challenge received!")
      challenge = pushChallengeEvent.challenge
      latch.countDown()
    }
  }

  data class AccountRegistrationResult(
    val uuid: String,
    val pni: String,
    val storageCapable: Boolean,
    val number: String,
    val masterKey: MasterKey?,
    val pin: String?,
    val aciPreKeyCollection: PreKeyCollection,
    val pniPreKeyCollection: PreKeyCollection
  )
}
