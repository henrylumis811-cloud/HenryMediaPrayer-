package com.henrylumis.mediaprayer.ui.library

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.henrylumis.mediaprayer.data.Song
import com.henrylumis.mediaprayer.databinding.ItemLibraryGroupBinding
import java.util.concurrent.Executors

/** A compact Albums/Artists view. Clicking a group starts that group's songs. */
data class LibraryGroup(
    val key: String,
    val title: String,
    val subtitle: String,
    val songs: List<Song>,
    val artSong: Song
)

private val GROUP_DIFF = object : DiffUtil.ItemCallback<LibraryGroup>() {
    override fun areItemsTheSame(old: LibraryGroup, new: LibraryGroup) = old.key == new.key
    override fun areContentsTheSame(old: LibraryGroup, new: LibraryGroup) =
        old.title == new.title && old.subtitle == new.subtitle && old.songs.map { it.id } == new.songs.map { it.id }
}

class LibraryGroupAdapter(
    private val onClick: (LibraryGroup) -> Unit
) : ListAdapter<LibraryGroup, LibraryGroupAdapter.GroupViewHolder>(GROUP_DIFF) {

    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val cache = object : android.util.LruCache<Long, android.graphics.Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 12).toInt()
    ) {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder =
        GroupViewHolder(ItemLibraryGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        val group = getItem(position)
        holder.bind(group)
        val key = if (group.artSong.albumId >= 0L) group.artSong.albumId else group.artSong.id
        cache.get(key)?.let { holder.setArt(it) } ?: executor.execute {
            val bitmap = try {
                val retriever = MediaMetadataRetriever()
                try {
                    val uri = Uri.parse(group.artSong.uriString)
                    if (uri.scheme.isNullOrBlank()) throw IllegalArgumentException("Invalid media URI")
                    retriever.setDataSource(holder.itemView.context, uri)
                    retriever.embeddedPicture?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                } finally { retriever.release() }
            } catch (_: Exception) { null }
            if (bitmap != null) {
                cache.put(key, bitmap)
                mainHandler.post {
                    if (holder.groupKey == group.key) holder.setArt(bitmap)
                }
            }
        }
        holder.itemView.setOnClickListener { onClick(group) }
    }

    class GroupViewHolder(private val binding: ItemLibraryGroupBinding) : RecyclerView.ViewHolder(binding.root) {
        var groupKey: String = ""
        fun bind(group: LibraryGroup) {
            groupKey = group.key
            binding.groupTitle.text = group.title
            binding.groupSubtitle.text = group.subtitle
            binding.groupCount.text = when (group.songs.size) {
                1 -> "1 song"
                else -> "${group.songs.size} songs"
            }
            binding.groupArt.setImageResource(com.henrylumis.mediaprayer.R.drawable.ic_album_placeholder)
        }
        fun setArt(bitmap: android.graphics.Bitmap) { binding.groupArt.setImageBitmap(bitmap) }
    }
}
