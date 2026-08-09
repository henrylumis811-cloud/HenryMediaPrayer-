package com.henrylumis.mediaprayer.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.henrylumis.mediaprayer.data.Song
import com.henrylumis.mediaprayer.databinding.ItemSongBinding
import java.util.concurrent.TimeUnit

private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Song>() {
    override fun areItemsTheSame(old: Song, new: Song) = old.id == new.id
    override fun areContentsTheSame(old: Song, new: Song) = old == new
}

/**
 * ListAdapter + DiffUtil instead of notifyDataSetChanged(): search-as-you-type,
 * sort changes, and favorite toggles now animate a minimal diff instead of
 * rebinding/re-laying-out the entire visible list every time, which is what
 * was making scrolling and list updates feel janky.
 */
class SongAdapter(
    private val onClick: (Song, Int) -> Unit,
    private val onLongClick: (Song) -> Unit
) : ListAdapter<Song, SongAdapter.SongViewHolder>(DIFF_CALLBACK) {

    private var playingSongId: String? = null
    private var isPlaying = false

    init {
        setHasStableIds(true)
    }

    fun currentList(): List<Song> = currentList

    /** Called periodically from LibraryFragment with the live playback state. */
    fun updateNowPlaying(mediaId: String?, playing: Boolean) {
        if (mediaId == playingSongId && playing == isPlaying) return
        val previousId = playingSongId
        playingSongId = mediaId
        isPlaying = playing
        currentList.forEachIndexed { index, song ->
            val idStr = song.id.toString()
            if (idStr == mediaId || idStr == previousId) notifyItemChanged(index)
        }
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val binding = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SongViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = getItem(position)
        val isThisPlaying = song.id.toString() == playingSongId
        holder.bind(song, isThisPlaying, isPlaying)
        holder.itemView.setOnClickListener { onClick(song, holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener { onLongClick(song); true }
    }

    class SongViewHolder(private val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(song: Song, isCurrent: Boolean, isPlaying: Boolean) {
            binding.songTitle.text = song.title
            binding.songArtist.text = song.artist

            if (isCurrent) {
                binding.miniEq.visibility = View.VISIBLE
                binding.miniEq.setPlaying(isPlaying)
                binding.songDuration.visibility = View.GONE
            } else {
                binding.miniEq.visibility = View.GONE
                binding.miniEq.setPlaying(false)
                binding.songDuration.visibility = View.VISIBLE
                val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(song.durationMs)
                binding.songDuration.text = String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
            }
        }
    }
}
