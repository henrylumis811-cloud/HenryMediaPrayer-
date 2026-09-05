package com.henrylumis.mediaprayer.ui.playlists

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.henrylumis.mediaprayer.MainActivity
import com.henrylumis.mediaprayer.R
import com.henrylumis.mediaprayer.data.MusicScanner
import com.henrylumis.mediaprayer.data.Song
import com.henrylumis.mediaprayer.databinding.FragmentPlaylistsBinding
import com.henrylumis.mediaprayer.databinding.ItemPlaylistBinding
import com.henrylumis.mediaprayer.util.PlaylistStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.henrylumis.mediaprayer.ui.common.DialogStyler
class PlaylistsFragment : Fragment() {
    private var _binding: FragmentPlaylistsBinding? = null
    private val binding get() = _binding!!
    private var openPlaylist: String? = null
    private var songsById: Map<String, Song> = emptyMap()
    private var playlistSongAdapter: PlaylistSongAdapter? = null
    private var draggedPlaylistName: String? = null
    private val playlistTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val adapter = playlistSongAdapter ?: return false
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            adapter.moveLocally(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                draggedPlaylistName = openPlaylist
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            val name = draggedPlaylistName ?: return
            val adapter = playlistSongAdapter ?: return
            PlaylistStore.setPlayableSongOrder(requireContext(), name, adapter.songIds())
            draggedPlaylistName = null
        }
    })

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlaylistsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnBack.visibility = View.GONE
        binding.btnBack.setOnClickListener { closePlaylist() }
        binding.btnCreate.setOnClickListener { showCreateDialog() }
        playlistTouchHelper.attachToRecyclerView(binding.list)
        loadLibraryAndRefresh()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) loadLibraryAndRefresh()
    }

    private fun loadLibraryAndRefresh() {
        lifecycleScope.launch {
            val songs = withContext(Dispatchers.IO) { MusicScanner.scan(requireContext()) }
            songsById = songs.associateBy { it.id.toString() }
            render()
        }
    }

    private fun render() {
        val playlist = openPlaylist
        if (playlist == null) renderPlaylists() else renderPlaylistSongs(playlist)
    }

    private fun renderPlaylists() {
        binding.title.text = "PLAYLISTS"
        binding.btnBack.visibility = View.GONE
        binding.btnCreate.visibility = View.VISIBLE
        val names = PlaylistStore.getPlaylistNames(requireContext())
        binding.emptyState.visibility = if (names.isEmpty()) View.VISIBLE else View.GONE
        binding.list.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
        binding.list.adapter = PlaylistAdapter(names) { openPlaylist(it) }
    }

    private fun renderPlaylistSongs(name: String) {
        binding.title.text = name
        binding.btnBack.visibility = View.VISIBLE
        binding.btnCreate.visibility = View.GONE
        val ids = PlaylistStore.getSongIds(requireContext(), name)
        val songs = ids.mapNotNull { songsById[it] }
        binding.emptyState.text = if (ids.isEmpty()) "This playlist is empty." else "Some songs are no longer available on this device."
        binding.emptyState.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
        binding.list.visibility = if (songs.isEmpty()) View.GONE else View.VISIBLE
        playlistSongAdapter = PlaylistSongAdapter(
            songs = songs,
            onPlay = { index ->
                if (index != RecyclerView.NO_POSITION) (activity as? MainActivity)?.playQueue(playlistSongAdapter?.let {
                    it.songIds().mapNotNull { id -> songsById[id] }
                } ?: songs, index)
            },
            onRemove = { song ->
                PlaylistStore.removeSong(requireContext(), name, song.id.toString())
                renderPlaylistSongs(name)
            },
            onDragStart = { holder -> playlistTouchHelper.startDrag(holder) }
        )
        draggedPlaylistName = null
        binding.list.adapter = playlistSongAdapter

        binding.btnPlaylistPlay.setOnClickListener {
            if (songs.isNotEmpty()) (activity as? MainActivity)?.playQueue(songs, 0)
            else Toast.makeText(requireContext(), "No playable songs in this playlist", Toast.LENGTH_SHORT).show()
        }
        binding.btnPlaylistMenu.setOnClickListener { showPlaylistMenu(name) }
    }

    private fun openPlaylist(name: String) { openPlaylist = name; render() }
    private fun closePlaylist() { openPlaylist = null; render() }

    private fun showCreateDialog() {
        val input = EditText(requireContext()).apply { hint = "Playlist name"; setSingleLine(true); setPadding(32, 8, 32, 8) }
        AlertDialog.Builder(requireContext()).setTitle("New playlist").setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString()
                if (!PlaylistStore.createPlaylist(requireContext(), name)) Toast.makeText(requireContext(), "Choose a unique name", Toast.LENGTH_SHORT).show()
                render()
            }.let { DialogStyler.show(it) }
    }

    private fun showPlaylistMenu(name: String) {
        AlertDialog.Builder(requireContext()).setTitle(name)
            .setItems(arrayOf("Rename", "Delete")) { _, which -> if (which == 0) showRenameDialog(name) else confirmDelete(name) }
            .let { DialogStyler.show(it) }
    }

    private fun showRenameDialog(oldName: String) {
        val input = EditText(requireContext()).apply { setText(oldName); selectAll(); setSingleLine(true) }
        AlertDialog.Builder(requireContext()).setTitle("Rename playlist").setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString()
                if (PlaylistStore.renamePlaylist(requireContext(), oldName, newName)) { openPlaylist = newName; render() }
                else Toast.makeText(requireContext(), "Choose a unique name", Toast.LENGTH_SHORT).show()
            }.let { DialogStyler.show(it) }
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(requireContext()).setTitle("Delete playlist?").setMessage("\"$name\" will be removed, but your music files will not be deleted.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                PlaylistStore.deletePlaylist(requireContext(), name); closePlaylist()
            }.let { DialogStyler.show(it) }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    private class PlaylistAdapter(private val names: List<String>, private val click: (String) -> Unit) : androidx.recyclerview.widget.RecyclerView.Adapter<PlaylistAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val name = names[position]
            holder.binding.name.text = name
            holder.binding.count.text = "${PlaylistStore.getSongIds(holder.binding.root.context, name).size} songs"
            holder.binding.root.setOnClickListener { click(name) }
        }
        override fun getItemCount() = names.size
        class Holder(val binding: ItemPlaylistBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)
    }
}
