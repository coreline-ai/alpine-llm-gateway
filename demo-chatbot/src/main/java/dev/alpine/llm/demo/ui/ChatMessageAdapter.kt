package dev.alpine.llm.demo.ui

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.alpine.llm.demo.R
import dev.alpine.llm.demo.databinding.ItemChatMessageBinding
import dev.alpine.llm.demo.model.ChatMessage
import dev.alpine.llm.demo.model.ChatMessageState
import dev.alpine.llm.demo.model.ChatRole

class ChatMessageAdapter :
    ListAdapter<ChatMessage, ChatMessageAdapter.MessageViewHolder>(MessageDiff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder =
        MessageViewHolder(
            ItemChatMessageBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            ),
        )

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class MessageViewHolder(
        private val binding: ItemChatMessageBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) = with(binding) {
            val context = root.context
            val user = message.role == ChatRole.USER
            messageRow.gravity = if (user) Gravity.END else Gravity.START
            messageBubble.setBackgroundResource(
                when (message.role) {
                    ChatRole.USER -> R.drawable.bg_user_message
                    ChatRole.ASSISTANT -> R.drawable.bg_assistant_message
                    ChatRole.ERROR -> R.drawable.bg_error_message
                },
            )
            val availableWidth = context.resources.displayMetrics.widthPixels
            messageText.maxWidth = (availableWidth * 0.78f).toInt()
            messageMetadata.maxWidth = messageText.maxWidth

            val foreground = if (user) {
                Color.WHITE
            } else {
                ContextCompat.getColor(context, R.color.demo_text_primary)
            }
            messageText.setTextColor(foreground)
            messageMetadata.setTextColor(
                if (user) {
                    Color.argb(210, 255, 255, 255)
                } else {
                    ContextCompat.getColor(context, R.color.demo_text_secondary)
                },
            )

            messageMetadata.text = when (message.role) {
                ChatRole.USER -> "You"
                ChatRole.ERROR -> "Error"
                ChatRole.ASSISTANT -> buildString {
                    append(message.providerLabel ?: "Assistant")
                    message.model?.takeIf(String::isNotBlank)?.let {
                        append(" · ")
                        append(it)
                    }
                    when (message.state) {
                        ChatMessageState.STREAMING -> append(" · Streaming…")
                        ChatMessageState.CANCELLED -> append(" · Stopped")
                        ChatMessageState.FAILED -> append(" · Failed")
                        ChatMessageState.COMPLETE -> Unit
                    }
                }
            }
            messageText.text = message.text.ifBlank {
                if (message.state == ChatMessageState.STREAMING) "…" else ""
            }
        }
    }

    private object MessageDiff : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
            oldItem == newItem
    }
}
