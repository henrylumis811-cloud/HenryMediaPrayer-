package com.henrylumis.mediaprayer.ui.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.henrylumis.mediaprayer.data.Song
import com.henrylumis.mediaprayer.databinding.ItemSongBinding
import java.util.concurrent.TimeUnit

class SongAdapter(
    private val onClick: (Song, Int) -> Unit
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    private val songs = mutableListOf<Song>()

    fun submitList(newSongs: List<Song>) {
        songs.clear()
        songs.addAll(newSongs)
        notifyDataSetChanged()
    }

    fun currentList(): List<Song> = songs

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val binding = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SongViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = songs[position]
        holder.bind(song)
        holder.itemView.setOnClickListener { onClick(song, position) }
    }

    override fun getItemCount() = songs.size

    class SongViewHolder(private val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(song: Song) {
            binding.songTitle.text = song.title
            binding.songArtist.text = song.artist
            val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(song.durationMs)
            binding.songDuration.text = String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
        }
    }
}
