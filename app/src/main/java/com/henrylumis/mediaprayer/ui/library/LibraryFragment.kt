package com.henrylumis.mediaprayer.ui.library

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager
import com.henrylumis.mediaprayer.MainActivity
import com.henrylumis.mediaprayer.data.MusicScanner
import com.henrylumis.mediaprayer.data.Song
import com.henrylumis.mediaprayer.databinding.FragmentLibraryBinding
import com.henrylumis.mediaprayer.util.PlaylistStore
import com.henrylumis.mediaprayer.util.Prefs
import com.henrylumis.mediaprayer.util.RecentlyPlayedStore
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: SongAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var nowPlayingListenerAttached = false

    private var allSongs: List<Song> = emptyList()
    private var searchQuery: String = ""
    private var sortMode: SortMode = SortMode.TITLE_ASC
    private var favoritesOnly: Boolean = false

    private enum class SortMode(val label: String) {
        TITLE_ASC("Title (A-Z)"),
        TITLE_DESC("Title (Z-A)"),
        ARTIST("Artist"),
        DURATION("Duration"),
        DATE_ADDED("Date Added (Newest)"),
        RECENTLY_PLAYED("Recently Played")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sortMode = Prefs.getSortMode(requireContext())
            ?.let { saved -> SortMode.values().find { it.name == saved } }
            ?: SortMode.TITLE_ASC

        adapter = SongAdapter { song, _ ->
            // Play starting from this song's position within the currently
            // displayed (filtered + sorted) list, so what you tap is what plays first.
            val displayed = adapter.currentList()
            val startIndex = displayed.indexOf(song).coerceAtLeast(0)
            (activity as? MainActivity)?.playQueue(displayed, startIndex)
        }
        binding.songList.layoutManager = LinearLayoutManager(requireContext())
        binding.songList.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadLibrary() }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                applyFilterAndSort()
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
        attachNowPlayingListenerWhenReady()
    }

    /** Keeps the Library list's small "now playing" indicator in sync with
     *  the player, using the same ready-check/retry pattern as the Altar
     *  screen's listener attachment. Purely visual -- doesn't touch playback. */
    private fun attachNowPlayingListenerWhenReady() {
        val activity = activity as? MainActivity ?: return
        val player = activity.player
        if (player != null) {
            player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    updateNowPlayingIndicator()
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateNowPlayingIndicator()
                }
            })
            nowPlayingListenerAttached = true
            updateNowPlayingIndicator()
            return
        }
        if (_binding == null) return
        handler.postDelayed({ if (!nowPlayingListenerAttached) attachNowPlayingListenerWhenReady() }, 300)
    }

    private fun updateNowPlayingIndicator() {
        if (_binding == null) return
        val activity = activity as? MainActivity ?: return
        val player = activity.player
        adapter.setNowPlaying(player?.currentMediaItem?.mediaId, player?.isPlaying ?: false)
    }

    private fun showSortMenu() {
        val popup = PopupMenu(requireContext(), binding.btnSort)
        SortMode.values().forEachIndexed { index, mode ->
            popup.menu.add(Menu.NONE, index, index, mode.label)
        }
        popup.setOnMenuItemClickListener { item ->
            sortMode = SortMode.values()[item.itemId]
            Prefs.setSortMode(requireContext(), sortMode.name)
            applyFilterAndSort()
            true
        }
        popup.show()
    }

    override fun onResume() {
        super.onResume()
        if (favoritesOnly) applyFilterAndSort()
        updateNowPlayingIndicator()
    }

    fun loadLibrary() {
        binding.swipeRefresh.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            allSongs = MusicScanner.scan(requireContext())
            applyFilterAndSort()
            binding.swipeRefresh.isRefreshing = false
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
                it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true)
            }
        }
        result = when (sortMode) {
            SortMode.TITLE_ASC -> result.sortedBy { it.title.lowercase() }
            SortMode.TITLE_DESC -> result.sortedByDescending { it.title.lowercase() }
            SortMode.ARTIST -> result.sortedBy { it.artist.lowercase() }
            SortMode.DURATION -> result.sortedBy { it.durationMs }
            SortMode.DATE_ADDED -> result.sortedByDescending { it.dateAdded }
            SortMode.RECENTLY_PLAYED -> {
                val order = RecentlyPlayedStore.getRecentIds(requireContext())
                result.sortedBy { song ->
                    val pos = order.indexOf(song.id.toString())
                    if (pos == -1) Int.MAX_VALUE else pos
                }
            }
        }
        adapter.submitList(result)
        binding.emptyState.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
