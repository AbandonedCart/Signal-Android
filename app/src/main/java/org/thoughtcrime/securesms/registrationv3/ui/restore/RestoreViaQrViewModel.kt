/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.registrationv3.ui.restore

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.signal.registration.proto.RegistrationProvisionMessage
import org.thoughtcrime.securesms.components.settings.app.usernamelinks.QrCodeData
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.whispersystems.signalservice.api.registration.ProvisioningSocket
import org.whispersystems.signalservice.internal.crypto.SecondaryProvisioningCipher
import java.io.Closeable

class RestoreViaQrViewModel : ViewModel() {

  private val store: MutableStateFlow<RestoreViaQrState> = MutableStateFlow(RestoreViaQrState())

  val state: StateFlow<RestoreViaQrState> = store

  private var socketHandle: Closeable

  init {
    socketHandle = start()
  }

  fun restart() {
    socketHandle.close()
    socketHandle = start()
  }

  fun handleRegistrationFailure() {
    store.update {
      if (it.isRegistering) {
        it.copy(
          isRegistering = false,
          provisioningMessage = null,
          showRegistrationError = true
        )
      } else {
        it
      }
    }
  }

  fun clearRegistrationError() {
    store.update { it.copy(showRegistrationError = false) }
  }

  override fun onCleared() {
    socketHandle.close()
  }

  private fun start(): Closeable {
    store.update { it.copy(qrState = QrState.Loading) }

    return ProvisioningSocket.start(
      identityKeyPair = IdentityKeyUtil.generateIdentityKeyPair(),
      configuration = AppDependencies.signalServiceNetworkAccess.getConfiguration(),
      handler = CoroutineExceptionHandler { _, _ -> store.update { it.copy(qrState = QrState.Failed) } }
    ) { socket ->
      val url = socket.getProvisioningUrl()
      store.update { it.copy(qrState = QrState.Loaded(qrData = QrCodeData.forData(data = url, supportIconOverlay = false))) }

      val result = socket.getRegistrationProvisioningMessage()
      if (result is SecondaryProvisioningCipher.RegistrationProvisionResult.Success) {
        store.update { it.copy(isRegistering = true, provisioningMessage = result.message, qrState = QrState.Scanned) }
      } else {
        store.update { it.copy(showProvisioningError = true, qrState = QrState.Scanned) }
      }
    }
  }

  data class RestoreViaQrState(
    val isRegistering: Boolean = false,
    val qrState: QrState = QrState.Loading,
    val provisioningMessage: RegistrationProvisionMessage? = null,
    val showProvisioningError: Boolean = false,
    val showRegistrationError: Boolean = false
  )

  sealed interface QrState {
    data object Loading : QrState
    data class Loaded(val qrData: QrCodeData) : QrState
    data object Failed : QrState
    data object Scanned : QrState
  }
}
