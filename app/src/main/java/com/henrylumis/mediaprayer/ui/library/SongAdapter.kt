package com.henrylumis.mediaprayer.ui.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.henrylumis.mediaprayer.data.Song
import com.henrylumis.mediaprayer.databinding.ItemSongBinding
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Song>() {
    override fun areItemsTheSame(old: Song, new: Song) = old.id == new.id
    override fun areContentsTheSame(old: Song, new: Song) = old == new
}

class SongAdapter(
    private val onClick: (Song, Int) -> Unit,
    private val onLongClick: (Song) -> Unit,
    private val onSelectionChanged: (Int) -> Unit = {}
) : ListAdapter<Song, SongAdapter.SongViewHolder>(DIFF_CALLBACK) {

    private var playingSongId: String? = null
    private var isPlaying = false
    private var selectionMode = false
    private val selectedIds = LinkedHashSet<Long>()
    private val artworkExecutor = Executors.newFixedThreadPool(2)
    private val artworkCache = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {}
    private val mainHandler = Handler(Looper.getMainLooper())

    init { setHasStableIds(true) }

    fun currentList(): List<Song> = currentList
    fun selectedSongs(): List<Song> = currentList.filter { selectedIds.contains(it.id) }
    fun isSelectionMode() = selectionMode

    fun startSelection(song: Song) {
        if (!selectionMode) selectionMode = true
        toggleSelection(song)
    }

    fun toggleSelection(song: Song) {
        if (!selectionMode) selectionMode = true
        if (!selectedIds.add(song.id)) selectedIds.remove(song.id)
        if (selectedIds.isEmpty()) selectionMode = false
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    fun clearSelection() {
        if (selectedIds.isEmpty() && !selectionMode) return
        selectedIds.clear(); selectionMode = false
        notifyDataSetChanged(); onSelectionChanged(0)
    }

    fun updateNowPlaying(mediaId: String?, playing: Boolean) {
        if (mediaId == playingSongId && playing == isPlaying) return
        val previousId = playingSongId
        playingSongId = mediaId; isPlaying = playing
        currentList.forEachIndexed { index, song ->
            val idStr = song.id.toString()
            if (idStr == mediaId || idStr == previousId) notifyItemChanged(index)
        }
    }

    override fun getItemId(position: Int): Long = getItem(position).id
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder =
        SongViewHolder(ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = getItem(position)
        holder.bind(song, song.id.toString() == playingSongId, isPlaying, selectionMode, selectedIds.contains(song.id))
        loadArtwork(holder, song)
        holder.itemView.setOnClickListener {
            if (selectionMode) toggleSelection(song) else onClick(song, holder.bindingAdapterPosition)
        }
        holder.itemView.setOnLongClickListener {
            if (selectionMode) toggleSelection(song) else onLongClick(song)
            true
        }
        holder.binding.selectSong.setOnClickListener { toggleSelection(song) }
    }

    private fun loadArtwork(holder: SongViewHolder, song: Song) {
        holder.artworkSongId = song.id
        val key = if (song.albumId >= 0L) song.albumId else song.id
        artworkCache.get(key)?.let { holder.setArtwork(it); return }
        artworkExecutor.execute {
            val bitmap = try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(holder.itemView.context, Uri.parse(song.uriString))
                    retriever.embeddedPicture?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                } finally { retriever.release() }
            } catch (_: Exception) { null }
            bitmap?.let { artworkCache.put(key, it) }
            if (bitmap != null) mainHandler.post { if (holder.artworkSongId == song.id) holder.setArtwork(bitmap) }
        }
    }

    class SongViewHolder(val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root) {
        var artworkSongId: Long = -1L
        fun setArtwork(bitmap: Bitmap) { binding.albumArt.setImageBitmap(bitmap) }
        fun bind(song: Song, isCurrent: Boolean, isPlaying: Boolean, selecting: Boolean, selected: Boolean) {
            binding.songTitle.text = song.title
            binding.songArtist.text = song.artist
            binding.albumArt.setImageResource(com.henrylumis.mediaprayer.R.drawable.ic_album_placeholder)
            binding.selectSong.visibility = if (selecting) View.VISIBLE else View.GONE
            binding.selectSong.setOnCheckedChangeListener(null)
            binding.selectSong.isChecked = selected
            if (isCurrent && !selecting) {
                binding.miniEq.visibility = View.VISIBLE; binding.miniEq.setPlaying(isPlaying)
                binding.songDuration.visibility = View.GONE
            } else {
                binding.miniEq.visibility = View.GONE; binding.miniEq.setPlaying(false)
                binding.songDuration.visibility = if (selecting) View.GONE else View.VISIBLE
                val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(song.durationMs)
                binding.songDuration.text = String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
            }
        }
    }
}
