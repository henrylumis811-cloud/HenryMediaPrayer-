package com.henrylumis.mediaprayer.ui.library

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.henrylumis.mediaprayer.MainActivity
import com.henrylumis.mediaprayer.data.MusicScanner
import com.henrylumis.mediaprayer.data.Song
import com.henrylumis.mediaprayer.databinding.FragmentLibraryBinding
import com.henrylumis.mediaprayer.util.PlaylistStore
import com.henrylumis.mediaprayer.util.Prefs
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: SongAdapter

    private var allSongs: List<Song> = emptyList()
    private var searchQuery: String = ""
    private var sortMode: SortMode = SortMode.TITLE_ASC
    private var favoritesOnly: Boolean = false

    private enum class SortMode(val label: String) {
        TITLE_ASC("Title (A-Z)"),
        TITLE_DESC("Title (Z-A)"),
        ARTIST("Artist"),
        DURATION("Duration"),
        DATE_ADDED("Date Added (Newest)")
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
        }
        adapter.submitList(result)
        binding.emptyState.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
