package com.henrylumis.mediaprayer.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.henrylumis.mediaprayer.R
import com.henrylumis.mediaprayer.data.Track
import com.henrylumis.mediaprayer.data.TrackRepository
import com.henrylumis.mediaprayer.databinding.FragmentLyricsBinding
import com.henrylumis.mediaprayer.player.PlayerManager
import com.henrylumis.mediaprayer.util.LyricLine
import com.henrylumis.mediaprayer.util.LyricsParser
import kotlinx.coroutines.launch

class LyricsFragment : Fragment() {

    private var _binding: FragmentLyricsBinding? = null
    private val binding get() = _binding!!

    private var currentLines: List<LyricLine> = emptyList()
    private var lineViews: List<TextView> = emptyList()
    private var lastActiveIndex = -1
    private var editing = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val highlightTick = object : Runnable {
        override fun run() {
            updateHighlight()
            uiHandler.postDelayed(this, 300)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            renderForCurrentTrack()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLyricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.lyricsEditBtn.setOnClickListener {
            val track = currentTrack()
            if (track != null) startEditing(track)
        }
        binding.lyricsCancelBtn.setOnClickListener { stopEditing() }
        binding.lyricsSaveBtn.setOnClickListener { saveEditing() }

        renderForCurrentTrack()
    }

    private fun currentTrack(): Track? =
        PlayerManager.tracks.getOrNull(PlayerManager.currentIndex())

    private fun renderForCurrentTrack() {
        if (editing) return
        val track = currentTrack()
        binding.lyricsTrackLabel.text = track?.title ?: getString(R.string.no_track)
        binding.lyricsBodyContainer.removeAllViews()

        if (track == null) {
            addPlainLabel(getString(R.string.lyrics_empty_no_track))
            currentLines = emptyList()
            lineViews = emptyList()
            return
        }

        currentLines = LyricsParser.parse(track.lyrics)
        if (currentLines.isEmpty()) {
            addPlainLabel(getString(R.string.lyrics_empty_track))
            lineViews = emptyList()
            return
        }

        val views = mutableListOf<TextView>()
        for (line in currentLines) {
            val tv = TextView(requireContext()).apply {
                text = line.text
                textSize = 15f
                setPadding(0, 14, 0, 14)
                gravity = android.view.Gravity.CENTER
                setTextColor(ContextCompat.getColor(requireContext(), R.color.muted))
            }
            binding.lyricsBodyContainer.addView(tv)
            views.add(tv)
        }
        lineViews = views
        lastActiveIndex = -1
    }

    private fun addPlainLabel(text: String) {
        val tv = TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(requireContext(), R.color.muted))
        }
        binding.lyricsBodyContainer.addView(tv)
    }

    private fun updateHighlight() {
        if (editing || currentLines.isEmpty() || lineViews.isEmpty()) return
        if (currentLines[0].timeSec == null) return // untimed lyrics: no auto highlight
        val posSec = PlayerManager.player.currentPosition / 1000.0
        val idx = LyricsParser.activeIndex(currentLines, posSec)
        if (idx == lastActiveIndex) return
        lastActiveIndex = idx
        for (i in lineViews.indices) {
            val tv = lineViews[i]
            if (i == idx) {
                tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.cyan))
                tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
            } else {
                tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.muted))
                tv.setTypeface(android.graphics.Typeface.DEFAULT)
            }
        }
        if (idx >= 0) {
            binding.lyricsScroll.smoothScrollTo(0, lineViews[idx].top - 60)
        }
    }

    private fun startEditing(track: Track) {
        editing = true
        binding.lyricsEditContainer.visibility = View.VISIBLE
        binding.lyricsScroll.visibility = View.GONE
        binding.lyricsEditText.setText(track.lyrics)
    }

    private fun stopEditing() {
        editing = false
        binding.lyricsEditContainer.visibility = View.GONE
        binding.lyricsScroll.visibility = View.VISIBLE
        renderForCurrentTrack()
    }

    private fun saveEditing() {
        val track = currentTrack() ?: return
        val newLyrics = binding.lyricsEditText.text?.toString() ?: ""
        track.lyrics = newLyrics
        viewLifecycleOwner.lifecycleScope.launch {
            TrackRepository.getInstance(requireContext()).upsert(track)
        }
        stopEditing()
    }

    override fun onStart() {
        super.onStart()
        PlayerManager.player.addListener(playerListener)
        uiHandler.post(highlightTick)
        renderForCurrentTrack()
    }

    override fun onStop() {
        super.onStop()
        PlayerManager.player.removeListener(playerListener)
        uiHandler.removeCallbacks(highlightTick)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
