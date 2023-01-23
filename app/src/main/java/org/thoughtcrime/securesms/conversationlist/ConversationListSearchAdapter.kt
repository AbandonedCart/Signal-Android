package org.thoughtcrime.securesms.conversationlist

import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.lifecycle.LifecycleOwner
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.contacts.paged.ArbitraryRepository
import org.thoughtcrime.securesms.contacts.paged.ContactSearchAdapter
import org.thoughtcrime.securesms.contacts.paged.ContactSearchConfiguration
import org.thoughtcrime.securesms.contacts.paged.ContactSearchData
import org.thoughtcrime.securesms.conversationlist.model.ConversationSet
import org.thoughtcrime.securesms.mms.GlideRequests
import org.thoughtcrime.securesms.util.adapter.mapping.LayoutFactory
import org.thoughtcrime.securesms.util.adapter.mapping.MappingModel
import org.thoughtcrime.securesms.util.adapter.mapping.MappingViewHolder
import org.thoughtcrime.securesms.util.visible
import java.util.Locale

/**
 * Adapter for ConversationList search. Adds factories to render ThreadModel and MessageModel using ConversationListItem,
 * as well as ChatFilter row support and empty state handler.
 */
class ConversationListSearchAdapter(
  displayCheckBox: Boolean,
  displaySmsTag: DisplaySmsTag,
  recipientListener: (View, ContactSearchData.KnownRecipient, Boolean) -> Unit,
  storyListener: (View, ContactSearchData.Story, Boolean) -> Unit,
  storyContextMenuCallbacks: StoryContextMenuCallbacks,
  expandListener: (ContactSearchData.Expand) -> Unit,
  threadListener: (View, ContactSearchData.Thread, Boolean) -> Unit,
  messageListener: (View, ContactSearchData.Message, Boolean) -> Unit,
  lifecycleOwner: LifecycleOwner,
  glideRequests: GlideRequests,
  clearFilterListener: () -> Unit
) : ContactSearchAdapter(displayCheckBox, displaySmsTag, recipientListener, storyListener, storyContextMenuCallbacks, expandListener) {
  init {
    registerFactory(
      ThreadModel::class.java,
      LayoutFactory({ ThreadViewHolder(threadListener, lifecycleOwner, glideRequests, it) }, R.layout.conversation_list_item_view)
    )
    registerFactory(
      MessageModel::class.java,
      LayoutFactory({ MessageViewHolder(messageListener, lifecycleOwner, glideRequests, it) }, R.layout.conversation_list_item_view)
    )
    registerFactory(
      ChatFilterMappingModel::class.java,
      LayoutFactory({ ChatFilterViewHolder(it, clearFilterListener) }, R.layout.conversation_list_item_clear_filter)
    )
    registerFactory(
      ChatFilterEmptyMappingModel::class.java,
      LayoutFactory({ ChatFilterViewHolder(it, clearFilterListener) }, R.layout.conversation_list_item_clear_filter_empty)
    )
    registerFactory(
      EmptyModel::class.java,
      LayoutFactory({ EmptyViewHolder(it) }, R.layout.conversation_list_empty_search_state)
    )
  }

  private class EmptyViewHolder(
    itemView: View
  ) : MappingViewHolder<EmptyModel>(itemView) {

    private val noResults = itemView.findViewById<TextView>(R.id.search_no_results)

    override fun bind(model: EmptyModel) {
      println("BIND")
      noResults.text = context.getString(R.string.SearchFragment_no_results, model.empty.query ?: "")
    }
  }

  private class ThreadViewHolder(
    private val threadListener: (View, ContactSearchData.Thread, Boolean) -> Unit,
    private val lifecycleOwner: LifecycleOwner,
    private val glideRequests: GlideRequests,
    itemView: View
  ) : MappingViewHolder<ThreadModel>(itemView) {
    override fun bind(model: ThreadModel) {
      itemView.setOnClickListener {
        threadListener(itemView, model.thread, false)
      }

      (itemView as ConversationListItem).bindThread(
        lifecycleOwner,
        model.thread.threadRecord,
        glideRequests,
        Locale.getDefault(),
        emptySet(),
        ConversationSet(),
        model.thread.query
      )
    }
  }

  private class MessageViewHolder(
    private val messageListener: (View, ContactSearchData.Message, Boolean) -> Unit,
    private val lifecycleOwner: LifecycleOwner,
    private val glideRequests: GlideRequests,
    itemView: View
  ) : MappingViewHolder<MessageModel>(itemView) {
    override fun bind(model: MessageModel) {
      itemView.setOnClickListener {
        messageListener(itemView, model.message, false)
      }

      (itemView as ConversationListItem).bindMessage(
        lifecycleOwner,
        model.message.messageResult,
        glideRequests,
        Locale.getDefault(),
        model.message.query
      )
    }
  }

  private open class BaseChatFilterMappingModel<T : BaseChatFilterMappingModel<T>>(val options: ChatFilterOptions) : MappingModel<T> {
    override fun areItemsTheSame(newItem: T): Boolean = true

    override fun areContentsTheSame(newItem: T): Boolean = options == newItem.options
  }

  private class ChatFilterMappingModel(options: ChatFilterOptions) : BaseChatFilterMappingModel<ChatFilterMappingModel>(options)

  private class ChatFilterEmptyMappingModel(options: ChatFilterOptions) : BaseChatFilterMappingModel<ChatFilterEmptyMappingModel>(options)

  private class ChatFilterViewHolder<T : BaseChatFilterMappingModel<T>>(itemView: View, listener: () -> Unit) : MappingViewHolder<T>(itemView) {

    private val tip = itemView.findViewById<View>(R.id.clear_filter_tip)

    init {
      itemView.findViewById<View>(R.id.clear_filter).setOnClickListener { listener() }
    }

    override fun bind(model: T) {
      tip.visible = model.options == ChatFilterOptions.WITH_TIP
    }
  }

  enum class ChatFilterOptions(val code: String) {
    WITH_TIP("with-tip"),
    WITHOUT_TIP("without-tip");

    companion object {
      fun fromCode(code: String): ChatFilterOptions {
        return values().firstOrNull { it.code == code } ?: WITHOUT_TIP
      }
    }
  }

  class ChatFilterRepository : ArbitraryRepository {
    override fun getSize(section: ContactSearchConfiguration.Section.Arbitrary, query: String?): Int = section.types.size

    override fun getData(
      section: ContactSearchConfiguration.Section.Arbitrary,
      query: String?,
      startIndex: Int,
      endIndex: Int,
      totalSearchSize: Int
    ): List<ContactSearchData.Arbitrary> {
      return section.types.map {
        ContactSearchData.Arbitrary(it, bundleOf("total-size" to totalSearchSize))
      }
    }

    override fun getMappingModel(arbitrary: ContactSearchData.Arbitrary): MappingModel<*> {
      val options = ChatFilterOptions.fromCode(arbitrary.type)
      val totalSearchSize = arbitrary.data?.getInt("total-size", -1) ?: -1
      return if (totalSearchSize == 1) {
        ChatFilterEmptyMappingModel(options)
      } else {
        ChatFilterMappingModel(options)
      }
    }
  }
}
