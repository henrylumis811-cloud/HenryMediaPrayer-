package com.henrylumis.mediaprayer.ui.verses

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.henrylumis.mediaprayer.MainActivity
import com.henrylumis.mediaprayer.databinding.FragmentVersesBinding

/**
 * "Verses" = lyrics tab. Kept intentionally simple for now: shows the
 * current track title/artist and a placeholder for synced lyrics, so it
 * doesn't crash if no .lrc is present. Wire in real LRC parsing later
 * once playback is rock solid.
 */
class VersesFragment : Fragment() {

    private var _binding: FragmentVersesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVersesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        val item = (activity as? MainActivity)?.player?.currentMediaItem
        binding.versesTitle.text = item?.mediaMetadata?.title ?: "No song playing"
        binding.versesBody.text = if (item == null)
            "Play a song from your Library to see it here."
        else
            "Lyrics for this track aren't loaded yet.\n\nThis screen is ready for synced LRC lyrics — that's a good next feature to wire in once everything else is confirmed stable."
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
