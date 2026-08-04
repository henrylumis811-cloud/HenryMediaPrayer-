package com.henrylumis.mediaprayer.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.henrylumis.mediaprayer.MainActivity
import com.henrylumis.mediaprayer.data.MusicScanner
import com.henrylumis.mediaprayer.databinding.FragmentLibraryBinding
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: SongAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SongAdapter { song, position ->
            (activity as? MainActivity)?.playQueue(adapter.currentList(), position)
        }
        binding.songList.layoutManager = LinearLayoutManager(requireContext())
        binding.songList.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadLibrary() }

        loadLibrary()
    }

    fun loadLibrary() {
        binding.swipeRefresh.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val songs = MusicScanner.scan(requireContext())
            adapter.submitList(songs)
            binding.emptyState.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
            binding.swipeRefresh.isRefreshing = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
