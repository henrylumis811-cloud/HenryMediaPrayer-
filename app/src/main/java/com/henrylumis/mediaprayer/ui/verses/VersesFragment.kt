package com.henrylumis.mediaprayer.ui.verses

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.henrylumis.mediaprayer.MainActivity
import com.henrylumis.mediaprayer.data.LyricsParser
import com.henrylumis.mediaprayer.databinding.FragmentVersesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shows synced lyrics from a .lrc file placed next to the audio file (same
 * base filename). Scrolling / highlighting follows playback position live.
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
        startTicking()
    }

    override fun onResume() {
        super.onResume()
        loadForCurrentSongIfNeeded()
    }

    private fun startTicking() {
        tickRunnable = object : Runnable {
            override fun run() {
                if (_binding == null) return
                loadForCurrentSongIfNeeded()
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

    private fun loadForCurrentSongIfNeeded() {
        val activity = activity as? MainActivity ?: return
        val item = activity.player?.currentMediaItem
        binding.versesHeader.text = item?.mediaMetadata?.title?.toString() ?: "No song playing"

        val mediaId = item?.mediaId
        if (mediaId == lastLoadedMediaId) return
        lastLoadedMediaId = mediaId

        val dataPath = item?.mediaMetadata?.extras?.getString("data_path")
        val lrcPath = LyricsParser.findLrcPath(dataPath)

        if (lrcPath == null) {
            adapter.submitLines(emptyList())
            binding.versesEmptyState.visibility = View.VISIBLE
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val lines = withContext(Dispatchers.IO) {
                try {
                    val file = File(lrcPath)
                    if (file.exists()) LyricsParser.parse(file.readText()) else emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            }
            if (_binding == null) return@launch
            adapter.submitLines(lines)
            binding.versesEmptyState.visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        tickRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroyView()
        _binding = null
    }
}
