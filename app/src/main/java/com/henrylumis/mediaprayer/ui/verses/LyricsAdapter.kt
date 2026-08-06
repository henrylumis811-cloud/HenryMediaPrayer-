package com.henrylumis.mediaprayer.ui.verses

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.henrylumis.mediaprayer.R
import com.henrylumis.mediaprayer.data.LyricsLine
import com.henrylumis.mediaprayer.databinding.ItemLyricLineBinding

class LyricsAdapter : RecyclerView.Adapter<LyricsAdapter.LineViewHolder>() {

    private val lines = mutableListOf<LyricsLine>()
    private var activeIndex = -1
    private var synced = true

    fun submitLines(newLines: List<LyricsLine>, isSynced: Boolean = true) {
        lines.clear()
        lines.addAll(newLines)
        synced = isSynced
        activeIndex = -1
        notifyDataSetChanged()
    }

    /** Returns the new active index if it changed, or -1 if unchanged. Only meaningful when synced. */
    fun updateActiveIndex(positionMs: Long): Int {
        if (!synced) return -1
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs) idx = i else break
        }
        if (idx != activeIndex) {
            val old = activeIndex
            activeIndex = idx
            if (old in lines.indices) notifyItemChanged(old)
            if (idx in lines.indices) notifyItemChanged(idx)
            return idx
        }
        return -1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineViewHolder {
        val binding = ItemLyricLineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LineViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LineViewHolder, position: Int) {
        val line = lines[position]
        val context = holder.binding.root.context
        holder.binding.lyricText.text = line.text
        if (!synced) {
            // Plain pasted lyrics: no timing data, so render uniformly readable
            // instead of pretending to highlight a line that isn't actually current.
            holder.binding.lyricText.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            holder.binding.lyricText.textSize = 17f
            holder.binding.lyricText.alpha = 1f
            return
        }
        val isActive = position == activeIndex
        holder.binding.lyricText.setTextColor(
            ContextCompat.getColor(context, if (isActive) R.color.accent_cyan else R.color.text_secondary)
        )
        holder.binding.lyricText.textSize = if (isActive) 20f else 17f
        holder.binding.lyricText.alpha = if (isActive) 1f else 0.65f
    }

    override fun getItemCount() = lines.size

    class LineViewHolder(val binding: ItemLyricLineBinding) : RecyclerView.ViewHolder(binding.root)
}
