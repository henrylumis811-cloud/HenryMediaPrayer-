package com.henrylumis.mediaprayer.ui.playlists

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.henrylumis.mediaprayer.data.Song
import com.henrylumis.mediaprayer.databinding.ItemPlaylistSongBinding

class PlaylistSongAdapter(
    songs: List<Song>,
    private val onPlay: (Int) -> Unit,
    private val onRemove: (Song) -> Unit,
    private val onDragStart: (Holder) -> Unit
) : RecyclerView.Adapter<PlaylistSongAdapter.Holder>() {
    private val items = songs.toMutableList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemPlaylistSongBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val song = items[position]
        holder.binding.title.text = song.title
        holder.binding.artist.text = song.artist
        holder.binding.root.setOnClickListener { onPlay(holder.bindingAdapterPosition) }
        holder.binding.remove.setOnClickListener { onRemove(song) }
        holder.binding.dragHandle.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) onDragStart(holder)
            false
        }
    }

    override fun getItemCount() = items.size

    fun moveLocally(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    fun songIds(): List<String> = items.map { it.id.toString() }

    class Holder(val binding: ItemPlaylistSongBinding) : RecyclerView.ViewHolder(binding.root)
}
