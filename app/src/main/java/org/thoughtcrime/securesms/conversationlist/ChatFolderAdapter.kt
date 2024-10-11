package org.thoughtcrime.securesms.conversationlist

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.chats.folders.ChatFolderRecord
import org.thoughtcrime.securesms.util.adapter.mapping.LayoutFactory
import org.thoughtcrime.securesms.util.adapter.mapping.MappingAdapter
import org.thoughtcrime.securesms.util.adapter.mapping.MappingViewHolder
import org.thoughtcrime.securesms.util.visible

/**
* RecyclerView adapter for the chat folders displayed on conversation list
*/
class ChatFolderAdapter(val callbacks: Callbacks) : MappingAdapter() {

  init {
    registerFactory(ChatFolderMappingModel::class.java, LayoutFactory({ v -> ViewHolder(v, callbacks) }, R.layout.chat_folder_item))
  }

  class ViewHolder(itemView: View, private val callbacks: Callbacks) : MappingViewHolder<ChatFolderMappingModel>(itemView) {

    private val name: TextView = findViewById(R.id.name)
    private val unreadCount: TextView = findViewById(R.id.unread_count)

    override fun bind(model: ChatFolderMappingModel) {
      itemView.isSelected = model.isSelected

      val folder = model.chatFolder
      name.text = getName(itemView.context, folder)
      unreadCount.visible = folder.unreadCount > 0
      unreadCount.text = folder.unreadCount.toString()
      itemView.setOnClickListener {
        callbacks.onChatFolderClicked(model.chatFolder)
      }
      if (model.isSelected) {
        itemView.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(itemView.context, R.color.signal_colorSurfaceVariant))
      } else {
        itemView.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(itemView.context, R.color.transparent))
      }
    }

    private fun getName(context: Context, folder: ChatFolderRecord): String {
      return if (folder.folderType == ChatFolderRecord.FolderType.ALL) {
        context.getString(R.string.ChatFoldersFragment__all_chats)
      } else {
        folder.name
      }
    }
  }

  interface Callbacks {
    fun onChatFolderClicked(chatFolder: ChatFolderRecord)
  }
}
