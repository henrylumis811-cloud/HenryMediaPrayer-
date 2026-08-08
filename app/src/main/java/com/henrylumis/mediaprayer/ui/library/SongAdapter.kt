package com.henrylumis.mediaprayer.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.henrylumis.mediaprayer.data.Song
import com.henrylumis.mediaprayer.databinding.ItemSongBinding
import com.henrylumis.mediaprayer.ui.VisualizerStyle
import java.util.concurrent.TimeUnit

class SongAdapter(
    private val onClick: (Song, Int) -> Unit
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    private val songs = mutableListOf<Song>()
    private var nowPlayingId: String? = null
    private var nowPlayingIsPlaying: Boolean = false

    fun submitList(newSongs: List<Song>) {
        songs.clear()
        songs.addAll(newSongs)
        notifyDataSetChanged()
    }

    fun currentList(): List<Song> = songs

    /** Marks which song (by mediaId, i.e. song.id.toString()) is the current
     *  track in the player, and whether it's actively playing, so its row can
     *  show the small "now playing" visualizer. Doesn't affect playback. */
    fun setNowPlaying(mediaId: String?, isPlaying: Boolean) {
        if (nowPlayingId == mediaId && nowPlayingIsPlaying == isPlaying) return
        nowPlayingId = mediaId
        nowPlayingIsPlaying = isPlaying
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val binding = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SongViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = songs[position]
        val isNowPlaying = nowPlayingId != null && song.id.toString() == nowPlayingId
        holder.bind(song, isNowPlaying, nowPlayingIsPlaying)
        holder.itemView.setOnClickListener { onClick(song, position) }
    }

    override fun getItemCount() = songs.size

    class SongViewHolder(private val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(song: Song, isNowPlaying: Boolean, isPlaying: Boolean) {
            binding.songTitle.text = song.title
            binding.songArtist.text = song.artist
            val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(song.durationMs)
            binding.songDuration.text = String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)

            if (isNowPlaying) {
                binding.nowPlayingVisualizer.visibility = View.VISIBLE
                binding.nowPlayingVisualizer.style = VisualizerStyle.VU_METER
                binding.nowPlayingVisualizer.setPlaying(isPlaying)
            } else {
                binding.nowPlayingVisualizer.visibility = View.GONE
                binding.nowPlayingVisualizer.setPlaying(false)
            }
        }
    }
}
