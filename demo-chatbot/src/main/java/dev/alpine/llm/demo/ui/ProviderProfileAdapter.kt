package dev.alpine.llm.demo.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.alpine.llm.demo.R
import dev.alpine.llm.demo.databinding.ItemProviderProfileBinding
import dev.alpine.llm.demo.llm.ProviderConnection
import dev.alpine.llm.demo.llm.ProviderConnectionState

class ProviderProfileAdapter(
    private val onEdit: (ProviderConnection) -> Unit,
    private val onConnectionAction: (ProviderConnection) -> Unit,
    private val onDelete: (ProviderConnection) -> Unit,
) : ListAdapter<ProviderConnection, ProviderProfileAdapter.ProfileViewHolder>(ProfileDiff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder =
        ProfileViewHolder(
            ItemProviderProfileBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            ),
        )

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ProfileViewHolder(
        private val binding: ItemProviderProfileBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(connection: ProviderConnection) = with(binding) {
            val context = root.context
            val profile = connection.profile
            profileName.text = profile.label
            profileSummary.text = "${profile.type.displayName} · ${profile.model}"

            val (statusText, statusColor, actionText) = when (connection.state) {
                ProviderConnectionState.AUTHENTICATED -> Triple(
                    R.string.connected,
                    R.color.demo_connected,
                    R.string.logout,
                )
                ProviderConnectionState.SIGNED_OUT -> Triple(
                    R.string.signed_out,
                    R.color.demo_text_secondary,
                    R.string.connect,
                )
                ProviderConnectionState.REAUTHENTICATION_REQUIRED -> Triple(
                    R.string.reauthentication_required,
                    R.color.demo_warning,
                    R.string.reconnect,
                )
            }
            connectionStatus.setText(statusText)
            connectionStatus.setTextColor(ContextCompat.getColor(context, statusColor))
            connectButton.setText(actionText)

            editButton.setOnClickListener { onEdit(connection) }
            connectButton.setOnClickListener { onConnectionAction(connection) }
            deleteButton.setOnClickListener { onDelete(connection) }
        }
    }

    private object ProfileDiff : DiffUtil.ItemCallback<ProviderConnection>() {
        override fun areItemsTheSame(
            oldItem: ProviderConnection,
            newItem: ProviderConnection,
        ): Boolean = oldItem.profile.id == newItem.profile.id

        override fun areContentsTheSame(
            oldItem: ProviderConnection,
            newItem: ProviderConnection,
        ): Boolean =
            oldItem.profile == newItem.profile && oldItem.state == newItem.state
    }
}
