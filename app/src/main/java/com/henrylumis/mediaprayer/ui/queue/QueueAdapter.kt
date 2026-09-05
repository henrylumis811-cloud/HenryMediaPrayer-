package com.henrylumis.mediaprayer.ui.queue

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.henrylumis.mediaprayer.R
import com.henrylumis.mediaprayer.databinding.ItemQueueSongBinding

class QueueAdapter(
    private val onClick: (Int) -> Unit,
    private val onRemove: (Int) -> Unit,
    private val onMove: (Int, Int) -> Unit
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    private val items = mutableListOf<MediaItem>()
    private var currentIndex = -1

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
        items.getOrNull(position)?.mediaId?.hashCode()?.toLong() ?: RecyclerView.NO_ID

    /** Periodic/authoritative refresh -- diffed instead of a full rebuild, so
     *  the background auto-refresh (every 1.5s) doesn't visibly flicker the list. */
    fun submitList(newItems: List<MediaItem>, playingIndex: Int) {
        val oldItems = items.toList()
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                oldItems[oldPos].mediaId == newItems[newPos].mediaId
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                oldItems[oldPos].mediaId == newItems[newPos].mediaId
        })
        items.clear()
        items.addAll(newItems)
        val oldIndex = currentIndex
        currentIndex = playingIndex
        diffResult.dispatchUpdatesTo(this)
        if (oldIndex != currentIndex) {
            if (oldIndex in items.indices) notifyItemChanged(oldIndex)
            if (currentIndex in items.indices) notifyItemChanged(currentIndex)
        }
    }

    /** Called live during drag, purely a local optimistic reorder -- no player/session calls. */
    fun moveLocally(from: Int, to: Int) {
        val item = items.removeAt(from)
        items.add(to, item)
        if (currentIndex == from) currentIndex = to
        else if (from < currentIndex && to >= currentIndex) currentIndex--
        else if (from > currentIndex && to <= currentIndex) currentIndex++
        notifyItemMoved(from, to)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val binding = ItemQueueSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return QueueViewHolder(binding)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val item = items[position]
        val context = holder.binding.root.context
        holder.binding.queueSongTitle.text = item.mediaMetadata.title?.toString() ?: "Unknown"
        holder.binding.queueSongArtist.text = item.mediaMetadata.artist?.toString() ?: "Unknown Artist"
        val isCurrent = position == currentIndex
        holder.binding.root.alpha = 1f
        holder.binding.root.setBackgroundResource(
            if (isCurrent) R.drawable.bg_card_active else R.drawable.bg_card
        )
        holder.binding.queueSongTitle.setTextColor(
            ContextCompat.getColor(
                context,
                if (position == currentIndex) R.color.accent_cyan else R.color.text_primary
            )
        )
        holder.binding.root.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        holder.binding.btnRemove.setOnClickListener { onRemove(holder.bindingAdapterPosition) }
    }

    override fun getItemCount() = items.size

    class QueueViewHolder(val binding: ItemQueueSongBinding) : RecyclerView.ViewHolder(binding.root)
}
