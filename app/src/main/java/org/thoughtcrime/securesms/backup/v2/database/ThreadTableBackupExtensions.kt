/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup.v2.database

import android.database.Cursor
import androidx.core.content.contentValuesOf
import com.google.protobuf.InvalidProtocolBufferException
import org.signal.core.util.SqlUtil
import org.signal.core.util.insertInto
import org.signal.core.util.logging.Log
import org.signal.core.util.requireBlob
import org.signal.core.util.requireBoolean
import org.signal.core.util.requireInt
import org.signal.core.util.requireLong
import org.signal.core.util.toInt
import org.thoughtcrime.securesms.backup.v2.ImportState
import org.thoughtcrime.securesms.backup.v2.proto.Chat
import org.thoughtcrime.securesms.backup.v2.util.BackupConverters
import org.thoughtcrime.securesms.backup.v2.util.toLocal
import org.thoughtcrime.securesms.conversation.colors.ChatColors
import org.thoughtcrime.securesms.database.RecipientTable
import org.thoughtcrime.securesms.database.ThreadTable
import org.thoughtcrime.securesms.database.model.databaseprotos.ChatColor
import org.thoughtcrime.securesms.recipients.RecipientId
import java.io.Closeable

private val TAG = Log.tag(ThreadTable::class.java)

fun ThreadTable.getThreadsForBackup(): ChatExportIterator {
  //language=sql
  val query = """
      SELECT 
        ${ThreadTable.TABLE_NAME}.${ThreadTable.ID}, 
        ${ThreadTable.RECIPIENT_ID}, 
        ${ThreadTable.PINNED}, 
        ${ThreadTable.READ}, 
        ${ThreadTable.ARCHIVED},
        ${RecipientTable.TABLE_NAME}.${RecipientTable.MESSAGE_EXPIRATION_TIME},
        ${RecipientTable.TABLE_NAME}.${RecipientTable.MESSAGE_EXPIRATION_TIME_VERSION},
        ${RecipientTable.TABLE_NAME}.${RecipientTable.MUTE_UNTIL},
        ${RecipientTable.TABLE_NAME}.${RecipientTable.MENTION_SETTING}, 
        ${RecipientTable.TABLE_NAME}.${RecipientTable.CHAT_COLORS},
        ${RecipientTable.TABLE_NAME}.${RecipientTable.CUSTOM_CHAT_COLORS_ID}
      FROM ${ThreadTable.TABLE_NAME} 
        LEFT OUTER JOIN ${RecipientTable.TABLE_NAME} ON ${ThreadTable.TABLE_NAME}.${ThreadTable.RECIPIENT_ID} = ${RecipientTable.TABLE_NAME}.${RecipientTable.ID}
      WHERE ${ThreadTable.ACTIVE} = 1
    """
  val cursor = readableDatabase.query(query)

  return ChatExportIterator(cursor)
}

fun ThreadTable.clearAllDataForBackupRestore() {
  writableDatabase.delete(ThreadTable.TABLE_NAME, null, null)
  SqlUtil.resetAutoIncrementValue(writableDatabase, ThreadTable.TABLE_NAME)
  clearCache()
}

fun ThreadTable.restoreFromBackup(chat: Chat, recipientId: RecipientId, importState: ImportState): Long? {
  val chatColor = chat.style?.toLocal(importState)

  // TODO [backup] Wallpaper

  val threadId = writableDatabase
    .insertInto(ThreadTable.TABLE_NAME)
    .values(
      ThreadTable.RECIPIENT_ID to recipientId.serialize(),
      ThreadTable.PINNED to chat.pinnedOrder,
      ThreadTable.ARCHIVED to chat.archived.toInt(),
      ThreadTable.READ to if (chat.markedUnread) ThreadTable.ReadStatus.FORCED_UNREAD.serialize() else ThreadTable.ReadStatus.READ.serialize(),
      ThreadTable.ACTIVE to 1
    )
    .run()

  writableDatabase
    .update(
      RecipientTable.TABLE_NAME,
      contentValuesOf(
        RecipientTable.MENTION_SETTING to (if (chat.dontNotifyForMentionsIfMuted) RecipientTable.MentionSetting.DO_NOT_NOTIFY.id else RecipientTable.MentionSetting.ALWAYS_NOTIFY.id),
        RecipientTable.MUTE_UNTIL to chat.muteUntilMs,
        RecipientTable.MESSAGE_EXPIRATION_TIME to chat.expirationTimerMs,
        RecipientTable.MESSAGE_EXPIRATION_TIME_VERSION to chat.expireTimerVersion,
        RecipientTable.CHAT_COLORS to chatColor?.serialize()?.encode(),
        RecipientTable.CUSTOM_CHAT_COLORS_ID to (chatColor?.id ?: ChatColors.Id.NotSet).longValue
      ),
      "${RecipientTable.ID} = ?",
      SqlUtil.buildArgs(recipientId.toLong())
    )

  return threadId
}

class ChatExportIterator(private val cursor: Cursor) : Iterator<Chat>, Closeable {
  override fun hasNext(): Boolean {
    return cursor.count > 0 && !cursor.isLast
  }

  override fun next(): Chat {
    if (!cursor.moveToNext()) {
      throw NoSuchElementException()
    }

    val serializedChatColors = cursor.requireBlob(RecipientTable.CHAT_COLORS)
    val chatColorId = ChatColors.Id.forLongValue(cursor.requireLong(RecipientTable.CUSTOM_CHAT_COLORS_ID))
    val chatColors: ChatColors? = serializedChatColors?.let { serialized ->
      try {
        ChatColors.forChatColor(chatColorId, ChatColor.ADAPTER.decode(serialized))
      } catch (e: InvalidProtocolBufferException) {
        null
      }
    }

    return Chat(
      id = cursor.requireLong(ThreadTable.ID),
      recipientId = cursor.requireLong(ThreadTable.RECIPIENT_ID),
      archived = cursor.requireBoolean(ThreadTable.ARCHIVED),
      pinnedOrder = cursor.requireInt(ThreadTable.PINNED),
      expirationTimerMs = cursor.requireLong(RecipientTable.MESSAGE_EXPIRATION_TIME),
      expireTimerVersion = cursor.requireInt(RecipientTable.MESSAGE_EXPIRATION_TIME_VERSION),
      muteUntilMs = cursor.requireLong(RecipientTable.MUTE_UNTIL),
      markedUnread = ThreadTable.ReadStatus.deserialize(cursor.requireInt(ThreadTable.READ)) == ThreadTable.ReadStatus.FORCED_UNREAD,
      dontNotifyForMentionsIfMuted = RecipientTable.MentionSetting.DO_NOT_NOTIFY.id == cursor.requireInt(RecipientTable.MENTION_SETTING),
      style = BackupConverters.constructRemoteChatStyle(chatColors, chatColorId)
    )
  }

  override fun close() {
    cursor.close()
  }
}
