package com.henrylumis.mediaprayer.ui.library

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.henrylumis.mediaprayer.MainActivity
import com.henrylumis.mediaprayer.data.MusicScanner
import com.henrylumis.mediaprayer.data.Song
import com.henrylumis.mediaprayer.data.SongSorter
import com.henrylumis.mediaprayer.databinding.FragmentLibraryBinding
import com.henrylumis.mediaprayer.trim.TrimmerDialog
import com.henrylumis.mediaprayer.ui.common.DialogStyler
import com.henrylumis.mediaprayer.ui.common.GlassPopup
import com.henrylumis.mediaprayer.util.PlaylistStore
import com.henrylumis.mediaprayer.util.Prefs
import kotlinx.coroutines.launch

@androidx.media3.common.util.UnstableApi
class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: SongAdapter

    private var allSongs: List<Song> = emptyList()
    private var searchQuery: String = ""
    private var sortMode: SongSorter.SortMode = SongSorter.SortMode.TITLE_ASC
    private var favoritesOnly: Boolean = false
    private enum class ViewMode { SONGS, ALBUMS, ARTISTS }
    private var viewMode: ViewMode = ViewMode.SONGS
    private lateinit var groupAdapter: LibraryGroupAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var nowPlayingRunnable: Runnable? = null
    private var searchDebounceRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sortMode = SongSorter.modeFromSavedName(Prefs.getSortMode(requireContext()))

        adapter = SongAdapter(
            onClick = { song, _ ->
                // Play starting from this song's position within the currently
                // displayed (filtered + sorted) list, so what you tap is what plays first.
                val displayed = adapter.currentList()
                val startIndex = displayed.indexOf(song).coerceAtLeast(0)
                (activity as? MainActivity)?.playQueue(displayed, startIndex)
            },
            onLongClick = { song -> showSongLongPressMenu(song) },
            onSelectionChanged = { count -> updateSelectionUi(count) }
        )
        groupAdapter = LibraryGroupAdapter { group ->
            val songs = group.songs
                .filter { it.uriString.isNotBlank() }
                .let { SongSorter.sort(requireContext(), it, SongSorter.SortMode.TITLE_ASC) }
            if (songs.isEmpty()) {
                Toast.makeText(requireContext(), "No playable songs found in this album", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    (activity as? MainActivity)?.playQueue(songs, 0)
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), "Couldn't open this album", Toast.LENGTH_SHORT).show()
                }
            }
        }
        binding.songList.layoutManager = LinearLayoutManager(requireContext())
        binding.songList.adapter = adapter

        updateViewModeHint()
        binding.btnViewMode.setOnClickListener { showViewModeMenu() }

        binding.btnAddToPlaylist.setOnClickListener { showAddSelectedToPlaylistDialog() }

        binding.swipeRefresh.setOnRefreshListener { loadLibrary() }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                searchDebounceRunnable?.let { handler.removeCallbacks(it) }
                searchDebounceRunnable = Runnable { applyFilterAndSort() }
                handler.postDelayed(searchDebounceRunnable!!, 200)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnSort.setOnClickListener { showSortMenu() }

        binding.btnFavoritesFilter.setOnClickListener {
            favoritesOnly = !favoritesOnly
            binding.btnFavoritesFilter.setImageResource(
                if (favoritesOnly) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            applyFilterAndSort()
        }

        loadLibrary()
        startNowPlayingTicker()
    }

    /** Keeps the small "now playing" equalizer icon in the list accurate,
     *  including auto-advance to the next track. */
    private fun startNowPlayingTicker() {
        nowPlayingRunnable = object : Runnable {
            override fun run() {
                if (_binding == null) return
                val player = (activity as? MainActivity)?.player
                adapter.updateNowPlaying(player?.currentMediaItem?.mediaId, player?.isPlaying ?: false)
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(nowPlayingRunnable!!)
    }

    private fun showSongLongPressMenu(song: Song) {
        DialogStyler.show(
            AlertDialog.Builder(requireContext())
                .setTitle(song.title)
                .setItems(arrayOf("Select for playlist", "Trim audio")) { _, which ->
                    if (which == 0) adapter.startSelection(song) else TrimmerDialog.show(requireContext(), song)
                }
        )
    }

    private fun updateSelectionUi(count: Int) {
        val active = count > 0
        binding.btnAddToPlaylist.visibility = if (active && viewMode == ViewMode.SONGS) View.VISIBLE else View.GONE
        binding.selectionCount.visibility = if (active) View.VISIBLE else View.GONE
        binding.selectionCount.text = "$count selected"
        binding.searchInput.isEnabled = !active
        binding.btnSort.isEnabled = !active
        binding.btnFavoritesFilter.isEnabled = !active
        binding.btnViewMode.isEnabled = !active
        binding.swipeRefresh.isEnabled = !active
    }

    private fun showAddSelectedToPlaylistDialog() {
        val selected = adapter.selectedSongs()
        if (selected.isEmpty()) return
        val names = PlaylistStore.getPlaylistNames(requireContext()).toMutableList()
        names.add("＋ Create new playlist")
        AlertDialog.Builder(requireContext())
            .setTitle("Add ${selected.size} songs to playlist")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == names.lastIndex) {
                    showCreatePlaylistFromSelection(selected)
                } else {
                    val added = PlaylistStore.addSongs(requireContext(), names[which], selected.map { it.id.toString() })
                    Toast.makeText(requireContext(), if (added == 0) "Songs already in playlist" else "$added song${if (added == 1) "" else "s"} added", Toast.LENGTH_SHORT).show()
                    adapter.clearSelection()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> adapter.clearSelection() }
            .let { DialogStyler.show(it) }
    }

    private fun showCreatePlaylistFromSelection(selected: List<Song>) {
        val input = EditText(requireContext()).apply { hint = "Playlist name"; setSingleLine(true); setPadding(32, 8, 32, 8) }
        AlertDialog.Builder(requireContext()).setTitle("Create playlist").setView(input)
            .setNegativeButton("Cancel") { _, _ -> adapter.clearSelection() }
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString()
                if (PlaylistStore.createPlaylist(requireContext(), name)) {
                    PlaylistStore.addSongs(requireContext(), name, selected.map { it.id.toString() })
                    Toast.makeText(requireContext(), "Playlist created", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Choose a unique playlist name", Toast.LENGTH_SHORT).show()
                }
                adapter.clearSelection()
            }.let { DialogStyler.show(it) }
    }

    private fun updateViewModeHint() {
        binding.searchInput.hint = when (viewMode) {
            ViewMode.SONGS -> "Search songs, artists or albums"
            ViewMode.ALBUMS -> "Search albums or artists"
            ViewMode.ARTISTS -> "Search artists"
        }
    }

    private fun showViewModeMenu() {
        GlassPopup.show(requireContext(), binding.btnViewMode, listOf(
            GlassPopup.Item("Songs", viewMode == ViewMode.SONGS),
            GlassPopup.Item("Albums", viewMode == ViewMode.ALBUMS),
            GlassPopup.Item("Artists", viewMode == ViewMode.ARTISTS)
        )) { index ->
            viewMode = when (index) {
                1 -> ViewMode.ALBUMS
                2 -> ViewMode.ARTISTS
                else -> ViewMode.SONGS
            }
            updateViewModeHint()
            applyFilterAndSort()
            updateSelectionUi(if (adapter.isSelectionMode()) adapter.selectedSongs().size else 0)
        }
    }

    private fun showSortMenu() {
        val modes = SongSorter.SortMode.values()
        GlassPopup.show(requireContext(), binding.btnSort, modes.map {
            GlassPopup.Item(it.label, it == sortMode)
        }) { index ->
            sortMode = modes[index]
            Prefs.setSortMode(requireContext(), sortMode.name)
            applyFilterAndSort()
        }
    }

    override fun onResume() {
        super.onResume()
        if (favoritesOnly) applyFilterAndSort()
    }

    fun loadLibrary() {
        binding.swipeRefresh.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                allSongs = MusicScanner.scan(requireContext())
                applyFilterAndSort()
            } catch (_: SecurityException) {
                allSongs = emptyList()
                adapter.submitList(emptyList())
                binding.emptyState.text = "Music permission is required to scan this device."
                binding.emptyState.visibility = View.VISIBLE
            } catch (_: Exception) {
                allSongs = emptyList()
                adapter.submitList(emptyList())
                binding.emptyState.text = "Couldn't load your music. Pull down to try again."
                binding.emptyState.visibility = View.VISIBLE
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun applyFilterAndSort() {
        if (_binding == null) return
        var result = allSongs
        if (favoritesOnly) {
            val favoriteIds = PlaylistStore.getFavoriteIds(requireContext())
            result = result.filter { favoriteIds.contains(it.id.toString()) }
        }
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim()
            result = result.filter {
                when (viewMode) {
                    ViewMode.SONGS -> it.title.contains(q, ignoreCase = true) ||
                        it.artist.contains(q, ignoreCase = true) ||
                        it.album.contains(q, ignoreCase = true)
                    ViewMode.ALBUMS -> it.album.contains(q, ignoreCase = true) ||
                        it.artist.contains(q, ignoreCase = true)
                    ViewMode.ARTISTS -> it.artist.contains(q, ignoreCase = true)
                }
            }
        }
        result = SongSorter.sort(requireContext(), result, sortMode)

        if (viewMode == ViewMode.SONGS) {
            binding.songList.adapter = adapter
            adapter.submitList(result)
            binding.songCount.text = when {
                result.isEmpty() -> ""
                result.size == 1 -> "1 song"
                else -> "${result.size} songs"
            }
        } else {
            val groups = buildGroups(result)
            binding.songList.adapter = groupAdapter
            groupAdapter.submitList(groups)
            binding.songCount.text = when {
                groups.isEmpty() -> ""
                groups.size == 1 -> "1 ${if (viewMode == ViewMode.ALBUMS) "album" else "artist"}"
                else -> "${groups.size} ${if (viewMode == ViewMode.ALBUMS) "albums" else "artists"}"
            }
            result = groups.flatMap { it.songs }
        }
        if (result.isEmpty()) {
            binding.emptyState.text = if (allSongs.isEmpty()) {
                "No music found on this device.\nAdd some songs, then pull down to rescan."
            } else {
                "No songs match your current filters."
            }
            binding.emptyState.visibility = View.VISIBLE
        } else {
            binding.emptyState.visibility = View.GONE
        }
    }

    private fun buildGroups(songs: List<Song>): List<LibraryGroup> {
        val grouped = when (viewMode) {
            ViewMode.ALBUMS -> songs.groupBy { it.album.trim().ifBlank { "Unknown album" } }
            ViewMode.ARTISTS -> songs.groupBy { it.artist.trim().ifBlank { "Unknown artist" } }
            ViewMode.SONGS -> emptyMap()
        }
        return grouped.entries
            .sortedBy { it.key.lowercase() }
            .map { (name, groupSongs) ->
                val first = groupSongs.first()
                LibraryGroup(
                    key = "${viewMode.name}:$name",
                    title = name,
                    subtitle = if (viewMode == ViewMode.ALBUMS) {
                        groupSongs.map { it.artist.trim() }.firstOrNull { it.isNotBlank() } ?: "Unknown artist"
                    } else {
                        "Artist"
                    },
                    songs = groupSongs,
                    artSong = first
                )
            }
    }

    override fun onDestroyView() {
        adapter.clearSelection()
        nowPlayingRunnable?.let { handler.removeCallbacks(it) }
        searchDebounceRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroyView()
        _binding = null
    }
}
