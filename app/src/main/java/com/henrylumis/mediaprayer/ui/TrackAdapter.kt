package com.henrylumis.mediaprayer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.henrylumis.mediaprayer.data.Track
import com.henrylumis.mediaprayer.databinding.ItemTrackBinding
import com.henrylumis.mediaprayer.util.Format

class TrackAdapter(
    private val onClick: (Int) -> Unit,
    private val onRemove: (Track) -> Unit
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    private val items = mutableListOf<Track>()
    private var activeId: String? = null

    fun submitList(tracks: List<Track>, activeTrackId: String?) {
        items.clear()
        items.addAll(tracks)
        activeId = activeTrackId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val track = items[position]
        holder.binding.trackTitle.text = track.title
        holder.binding.trackArtist.text = track.artist
        holder.binding.trackDuration.text =
            if (track.durationMs > 0) Format.timeMs(track.durationMs) else "--:--"
        holder.itemView.isActivated = track.id == activeId
        holder.binding.trackTitle.setTextColor(
            if (track.id == activeId)
                holder.itemView.context.getColor(com.henrylumis.mediaprayer.R.color.cyan)
            else
                holder.itemView.context.getColor(com.henrylumis.mediaprayer.R.color.ink)
        )
        holder.itemView.setOnClickListener { onClick(position) }
        holder.binding.removeBtn.setOnClickListener { onRemove(track) }
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root)
}
