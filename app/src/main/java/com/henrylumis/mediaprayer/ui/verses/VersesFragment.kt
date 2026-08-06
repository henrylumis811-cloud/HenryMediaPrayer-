package com.henrylumis.mediaprayer.ui.verses

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.henrylumis.mediaprayer.MainActivity
import com.henrylumis.mediaprayer.data.LyricsLine
import com.henrylumis.mediaprayer.data.LyricsParser
import com.henrylumis.mediaprayer.databinding.FragmentVersesBinding
import com.henrylumis.mediaprayer.util.LyricsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shows lyrics for the current song, preferring a synced .lrc file placed
 * next to the audio file (highlighted + auto-scrolled live), falling back
 * to manually pasted-in plain lyrics, and finally an empty state.
 */
class VersesFragment : Fragment() {

    private var _binding: FragmentVersesBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: LyricsAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null
    private var lastLoadedMediaId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVersesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = LyricsAdapter()
        binding.lyricsList.layoutManager = LinearLayoutManager(requireContext())
        binding.lyricsList.adapter = adapter
        binding.btnPasteLyrics.setOnClickListener { showPasteDialog() }
        startTicking()
    }

    override fun onResume() {
        super.onResume()
        loadForCurrentSongIfNeeded(force = false)
    }

    private fun showPasteDialog() {
        val activity = activity as? MainActivity ?: return
        val item = activity.player?.currentMediaItem
        val mediaId = item?.mediaId
        if (mediaId == null) {
            android.widget.Toast.makeText(requireContext(), "Play a song first", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(requireContext()).apply {
            hint = "Paste or type the lyrics here"
            minLines = 8
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setText(LyricsStore.get(requireContext(), mediaId).orEmpty())
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(requireContext()).apply {
            setPadding(padding, padding, padding, padding)
            addView(input)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Lyrics for ${item.mediaMetadata.title ?: "this song"}")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString()
                if (text.isBlank()) {
                    LyricsStore.clear(requireContext(), mediaId)
                } else {
                    LyricsStore.set(requireContext(), mediaId, text)
                }
                lastLoadedMediaId = null // force a reload
                loadForCurrentSongIfNeeded(force = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startTicking() {
        tickRunnable = object : Runnable {
            override fun run() {
                if (_binding == null) return
                loadForCurrentSongIfNeeded(force = false)
                val activity = activity as? MainActivity
                val posMs = activity?.player?.currentPosition ?: 0L
                val newIndex = adapter.updateActiveIndex(posMs)
                if (newIndex >= 0) {
                    (binding.lyricsList.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(newIndex, 200)
                }
                handler.postDelayed(this, 400)
            }
        }
        handler.post(tickRunnable!!)
    }

    private fun loadForCurrentSongIfNeeded(force: Boolean) {
        val activity = activity as? MainActivity ?: return
        val item = activity.player?.currentMediaItem
        binding.versesHeader.text = item?.mediaMetadata?.title?.toString() ?: "No song playing"

        val mediaId = item?.mediaId
        if (!force && mediaId == lastLoadedMediaId) return
        lastLoadedMediaId = mediaId

        if (mediaId == null) {
            adapter.submitLines(emptyList())
            binding.versesEmptyState.visibility = View.VISIBLE
            return
        }

        val dataPath = item.mediaMetadata.extras?.getString("data_path")
        val lrcPath = LyricsParser.findLrcPath(dataPath)

        viewLifecycleOwner.lifecycleScope.launch {
            val synced = withContext(Dispatchers.IO) {
                try {
                    if (lrcPath != null) {
                        val file = File(lrcPath)
                        if (file.exists()) LyricsParser.parse(file.readText()) else emptyList()
                    } else emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            }

            if (_binding == null) return@launch

            if (synced.isNotEmpty()) {
                adapter.submitLines(synced, isSynced = true)
                binding.versesEmptyState.visibility = View.GONE
                return@launch
            }

            val pasted = LyricsStore.get(requireContext(), mediaId)
            if (!pasted.isNullOrBlank()) {
                val plainLines = pasted.lines().filter { it.isNotBlank() }
                    .map { LyricsLine(0L, it) }
                adapter.submitLines(plainLines, isSynced = false)
                binding.versesEmptyState.visibility = View.GONE
            } else {
                adapter.submitLines(emptyList())
                binding.versesEmptyState.visibility = View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        tickRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroyView()
        _binding = null
    }
}
