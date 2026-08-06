package com.henrylumis.mediaprayer.ui.queue

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
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

    fun submitList(newItems: List<MediaItem>, playingIndex: Int) {
        items.clear()
        items.addAll(newItems)
        currentIndex = playingIndex
        notifyDataSetChanged()
    }

    /** Called live during drag, before the underlying player queue is told to move. */
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
        holder.binding.root.alpha = if (position == currentIndex) 1f else 0.75f
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

    fun onDragMoved(from: Int, to: Int) = onMove(from, to)

    class QueueViewHolder(val binding: ItemQueueSongBinding) : RecyclerView.ViewHolder(binding.root)
}
