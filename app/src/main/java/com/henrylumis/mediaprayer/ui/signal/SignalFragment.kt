package com.henrylumis.mediaprayer.ui.signal

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.henrylumis.mediaprayer.MainActivity
import com.henrylumis.mediaprayer.databinding.FragmentSignalBinding
import com.henrylumis.mediaprayer.util.ListeningStatsStore
import com.henrylumis.mediaprayer.util.Prefs

/**
 * "Signal" tab = settings & controls: theme toggle, background photo,
 * equalizer, sleep timer.
 */
class SignalFragment : Fragment() {

    private var _binding: FragmentSignalBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())
    private var equalizerBuilt = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSignalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val activity = activity as? MainActivity

        binding.themeSwitch.isChecked = Prefs.isDark(ctx)
        binding.themeSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setNightMode(
                ctx,
                if (checked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
            requireActivity().recreate()
        }

        binding.btnChoosePhoto.setOnClickListener { activity?.pickBackground() }
        binding.btnClearPhoto.setOnClickListener { activity?.clearBackground() }

        binding.opacitySlider.progress = Prefs.getBackgroundOpacity(ctx)
        binding.opacitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) activity?.setBackgroundOpacity(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        setupPlaybackSection(ctx)

        val timerOptions = listOf(0, 15, 30, 45, 60)
        binding.sleepTimerGroup.removeAllViews()
        timerOptions.forEach { minutes ->
            val chip = com.google.android.material.chip.Chip(ctx).apply {
                text = if (minutes == 0) "Off" else "${minutes}m"
                isCheckable = true
                setOnClickListener {
                    val activity2 = activity as? MainActivity
                    if (minutes == 0) activity2?.cancelSleepTimer()
                    else activity2?.startSleepTimer(minutes)
                }
            }
            binding.sleepTimerGroup.addView(chip)
        }

        buildEqualizerWhenReady()
        renderAnalytics()
    }

    override fun onResume() {
        super.onResume()
        renderAnalytics()
    }

    private fun renderAnalytics() {
        if (_binding == null) return
        val ctx = requireContext()
        val totalMs = ListeningStatsStore.getTotalListenedMs(ctx)
        val totalPlays = ListeningStatsStore.getTotalPlays(ctx)

        if (totalPlays == 0) {
            binding.analyticsSummary.text = "No listening history yet -- play some songs and check back here."
            binding.analyticsTopSongsHeader.visibility = View.GONE
            binding.analyticsTopGenresHeader.visibility = View.GONE
            binding.analyticsTopSongsList.removeAllViews()
            binding.analyticsTopGenresList.removeAllViews()
            return
        }

        binding.analyticsSummary.text = "Total listening time: ${formatDuration(totalMs)}  \u2022  $totalPlays tracked plays"

        val topSongs = ListeningStatsStore.topPlayed(ctx, 5).filter { it.playCount > 0 }
        binding.analyticsTopSongsList.removeAllViews()
        if (topSongs.isEmpty()) {
            binding.analyticsTopSongsHeader.visibility = View.GONE
        } else {
            binding.analyticsTopSongsHeader.visibility = View.VISIBLE
            topSongs.forEach { stat ->
                val row = TextView(ctx).apply {
                    text = "${stat.title} \u2014 ${stat.artist}  (${stat.playCount}\u00D7)"
                    setTextColor(resources.getColor(com.henrylumis.mediaprayer.R.color.text_secondary, null))
                    textSize = 13f
                    setPadding(0, 4, 0, 4)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                binding.analyticsTopSongsList.addView(row)
            }
        }

        val topGenres = ListeningStatsStore.topGenres(ctx, 3)
        binding.analyticsTopGenresList.removeAllViews()
        if (topGenres.isEmpty()) {
            binding.analyticsTopGenresHeader.visibility = View.GONE
        } else {
            binding.analyticsTopGenresHeader.visibility = View.VISIBLE
            topGenres.forEach { (genre, count) ->
                val row = TextView(ctx).apply {
                    text = "$genre  ($count)"
                    setTextColor(resources.getColor(com.henrylumis.mediaprayer.R.color.text_secondary, null))
                    textSize = 13f
                    setPadding(0, 4, 0, 4)
                }
                binding.analyticsTopGenresList.addView(row)
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms / 60000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun setupPlaybackSection(ctx: android.content.Context) {
        binding.crossfadeSwitch.isChecked = Prefs.isCrossfadeEnabled(ctx)
        binding.crossfadeSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setCrossfadeEnabled(ctx, checked)
        }

        val savedSeconds = Prefs.getCrossfadeSeconds(ctx)
        binding.crossfadeLengthSlider.progress = (savedSeconds - 1).coerceIn(0, 11)
        binding.crossfadeLengthLabel.text = "Crossfade length: ${savedSeconds}s"
        binding.crossfadeLengthSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = progress + 1
                binding.crossfadeLengthLabel.text = "Crossfade length: ${seconds}s"
                if (fromUser) Prefs.setCrossfadeSeconds(ctx, seconds)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.normalizationSwitch.isChecked = Prefs.isNormalizationEnabled(ctx)
        binding.normalizationSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setNormalizationEnabled(ctx, checked)
        }
    }

    private fun buildEqualizerWhenReady() {
        val activity = activity as? MainActivity ?: return
        val eq = activity.equalizer
        if (eq != null) {
            if (!equalizerBuilt) renderEqualizer(eq)
            return
        }
        if (_binding == null) return
        handler.postDelayed({ buildEqualizerWhenReady() }, 500)
    }

    private fun renderEqualizer(eq: com.henrylumis.mediaprayer.audio.EqualizerController) {
        equalizerBuilt = true
        val ctx = requireContext()
        binding.equalizerContainer.removeAllViews()

        if (!eq.isAvailable || eq.bands.isEmpty()) {
            val msg = TextView(ctx).apply {
                text = "Equalizer isn't supported on this device."
                setTextColor(resources.getColor(com.henrylumis.mediaprayer.R.color.text_secondary, null))
                textSize = 13f
            }
            binding.equalizerContainer.addView(msg)
            return
        }

        eq.bands.forEach { band ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
            }
            val label = TextView(ctx).apply {
                text = if (band.centerFreqHz >= 1000) "${band.centerFreqHz / 1000}kHz" else "${band.centerFreqHz}Hz"
                setTextColor(resources.getColor(com.henrylumis.mediaprayer.R.color.text_secondary, null))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(140, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val seek = SeekBar(ctx).apply {
                max = (band.maxLevel - band.minLevel).toInt()
                progress = (band.level - band.minLevel).toInt()
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            eq.setBandLevel(band.index, (progress + band.minLevel).toShort())
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            row.addView(label)
            row.addView(seek)
            binding.equalizerContainer.addView(row)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
