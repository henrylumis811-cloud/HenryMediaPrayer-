package com.henrylumis.mediaprayer.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.henrylumis.mediaprayer.data.Song
import com.henrylumis.mediaprayer.databinding.ItemSongBinding
import java.util.concurrent.TimeUnit

class SongAdapter(
    private val onClick: (Song, Int) -> Unit
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    private val songs = mutableListOf<Song>()
    private var playingSongId: String? = null
    private var isPlaying = false

    fun submitList(newSongs: List<Song>) {
        songs.clear()
        songs.addAll(newSongs)
        notifyDataSetChanged()
    }

    fun currentList(): List<Song> = songs

    /** Called periodically from LibraryFragment with the live playback state. */
    fun updateNowPlaying(mediaId: String?, playing: Boolean) {
        if (mediaId == playingSongId && playing == isPlaying) return
        val previousId = playingSongId
        playingSongId = mediaId
        isPlaying = playing
        songs.forEachIndexed { index, song ->
            val idStr = song.id.toString()
            if (idStr == mediaId || idStr == previousId) notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val binding = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SongViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = songs[position]
        val isThisPlaying = song.id.toString() == playingSongId
        holder.bind(song, isThisPlaying, isPlaying)
        holder.itemView.setOnClickListener { onClick(song, position) }
    }

    override fun getItemCount() = songs.size

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
