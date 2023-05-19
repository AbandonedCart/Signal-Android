/**
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.calls.links.create

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.thoughtcrime.securesms.conversation.colors.AvatarColor
import org.thoughtcrime.securesms.database.CallLinkTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.service.webrtc.links.CallLinkCredentials
import org.thoughtcrime.securesms.service.webrtc.links.CreateCallLinkResult
import org.thoughtcrime.securesms.service.webrtc.links.SignalCallLinkManager

/**
 * Repository for creating new call links. This will delegate to the [SignalCallLinkManager]
 * but will also ensure the database is updated.
 */
class CreateCallLinkRepository(
  private val callLinkManager: SignalCallLinkManager = ApplicationDependencies.getSignalCallManager().callLinkManager
) {
  fun ensureCallLinkCreated(credentials: CallLinkCredentials, avatarColor: AvatarColor): Single<EnsureCallLinkCreatedResult> {
    val doesCallLinkExistInLocalDatabase = Single.fromCallable {
      SignalDatabase.callLinks.callLinkExists(credentials.roomId)
    }

    return doesCallLinkExistInLocalDatabase.flatMap { exists ->
      if (exists) {
        Single.just(EnsureCallLinkCreatedResult.Success)
      } else {
        callLinkManager.createCallLink(credentials).map {
          when (it) {
            is CreateCallLinkResult.Success -> {
              SignalDatabase.callLinks.insertCallLink(
                CallLinkTable.CallLink(
                  recipientId = RecipientId.UNKNOWN,
                  roomId = credentials.roomId,
                  credentials = credentials,
                  state = it.state,
                  avatarColor = avatarColor
                )
              )

              EnsureCallLinkCreatedResult.Success
            }

            is CreateCallLinkResult.Failure -> EnsureCallLinkCreatedResult.Failure(it)
          }
        }
      }
    }.subscribeOn(Schedulers.io())
  }
}
