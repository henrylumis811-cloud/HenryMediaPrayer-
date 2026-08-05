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
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: SongAdapter

    private var allSongs: List<Song> = emptyList()
    private var searchQuery: String = ""
    private var sortMode: SortMode = SortMode.TITLE_ASC

    private enum class SortMode(val label: String) {
        TITLE_ASC("Title (A-Z)"),
        TITLE_DESC("Title (Z-A)"),
        ARTIST("Artist"),
        DURATION("Duration")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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

        loadLibrary()
    }

    private fun showSortMenu() {
        val popup = PopupMenu(requireContext(), binding.btnSort)
        SortMode.values().forEachIndexed { index, mode ->
            popup.menu.add(Menu.NONE, index, index, mode.label)
        }
        popup.setOnMenuItemClickListener { item ->
            sortMode = SortMode.values()[item.itemId]
            applyFilterAndSort()
            true
        }
        popup.show()
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
        }
        adapter.submitList(result)
        binding.emptyState.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
