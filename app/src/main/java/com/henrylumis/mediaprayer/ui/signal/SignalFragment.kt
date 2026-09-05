package com.henrylumis.mediaprayer.ui.signal

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.app.AlertDialog
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.henrylumis.mediaprayer.MainActivity
import com.henrylumis.mediaprayer.databinding.FragmentSignalBinding
import com.henrylumis.mediaprayer.util.ListeningStatsStore
import com.henrylumis.mediaprayer.util.Prefs
import com.henrylumis.mediaprayer.util.RecentlyPlayedStore

import com.henrylumis.mediaprayer.ui.common.DialogStyler
/**
 * "Signal" tab = settings & controls: theme toggle, background photo,
 * equalizer, sleep timer, listening analytics.
 */
class SignalFragment : Fragment() {

    private var _binding: FragmentSignalBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())
    private var equalizerBuilt = false
    private var allSongsExpanded = false
    private var selectedAnalyticsPeriod = "ALL"

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

        setupAnalyticsPeriods(ctx)

        binding.btnAnalyticsExpand.setOnClickListener {
            allSongsExpanded = !allSongsExpanded
            renderAnalytics()
        }
        binding.btnViewDetailedAnalytics.setOnClickListener {
            showDetailedAnalytics()
        }
        binding.btnViewMonthlyRecap.setOnClickListener {
            RecapDialog.show(ctx, ListeningStatsStore.currentMonthKey(), monthly = true, label = "This month")
        }
        binding.btnViewYearlyRecap.setOnClickListener {
            RecapDialog.show(ctx, ListeningStatsStore.currentYearKey(), monthly = false, label = "This year")
        }
        binding.btnClearRecentHistory.setOnClickListener {
            if (RecentlyPlayedStore.getRecentIds(ctx).isEmpty()) {
                Toast.makeText(ctx, "Recently played is already empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(ctx)
                .setTitle("Clear recently played?")
                .setMessage("This removes the Recently Played list only. Listening analytics and play counts will remain.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    RecentlyPlayedStore.clear(ctx)
                    Toast.makeText(ctx, "Recently played cleared", Toast.LENGTH_SHORT).show()
                }
                .let { DialogStyler.show(it) }
        }

        buildEqualizerWhenReady()
        renderAnalytics()
        checkForPeriodRollover(ctx)
    }

    override fun onResume() {
        super.onResume()
        renderAnalytics()
    }

    /** Shows an automatic recap the first time the Signal tab is opened after
     *  a calendar month/year has actually ended, so it reads like "here's how
     *  last month went" rather than a mid-month snapshot. */
    private fun checkForPeriodRollover(ctx: android.content.Context) {
        val completedYear = ListeningStatsStore.checkAndConsumeYearRollover(ctx)
        if (completedYear != null) {
            RecapDialog.show(ctx, completedYear, monthly = false, label = completedYear)
            return // don't stack both dialogs at once
        }
        val completedMonth = ListeningStatsStore.checkAndConsumeMonthRollover(ctx)
        if (completedMonth != null) {
            RecapDialog.show(ctx, completedMonth, monthly = true, label = completedMonth)
        }
    }

    private fun setupAnalyticsPeriods(ctx: android.content.Context) {
        binding.analyticsPeriodGroup.removeAllViews()
        val periods = listOf(
            "TODAY" to "Today",
            "7" to "7 days",
            "30" to "30 days",
            "MONTH" to "This month",
            "YEAR" to "This year",
            "ALL" to "All time"
        )
        periods.forEach { (key, label) ->
            val chip = com.google.android.material.chip.Chip(ctx).apply {
                text = label
                isCheckable = true
                isChecked = key == selectedAnalyticsPeriod
                setOnClickListener {
                    selectedAnalyticsPeriod = key
                    for (i in 0 until binding.analyticsPeriodGroup.childCount) {
                        (binding.analyticsPeriodGroup.getChildAt(i) as? com.google.android.material.chip.Chip)?.isChecked =
                            binding.analyticsPeriodGroup.getChildAt(i) === this
                    }
                    allSongsExpanded = false
                    renderAnalytics()
                }
            }
            binding.analyticsPeriodGroup.addView(chip)
        }
    }

    private fun analyticsStatsForPeriod(ctx: android.content.Context): Pair<String, List<com.henrylumis.mediaprayer.util.SongStats>> {
        return when (selectedAnalyticsPeriod) {
            "TODAY" -> "Today" to ListeningStatsStore.getRecentStats(ctx, 1)
            "7" -> "Last 7 days" to ListeningStatsStore.getRecentStats(ctx, 7)
            "30" -> "Last 30 days" to ListeningStatsStore.getRecentStats(ctx, 30)
            "MONTH" -> "This month" to ListeningStatsStore.getPeriodStats(ctx, ListeningStatsStore.currentMonthKey(), true)
            "YEAR" -> "This year" to ListeningStatsStore.getPeriodStats(ctx, ListeningStatsStore.currentYearKey(), false)
            else -> "All time" to ListeningStatsStore.getAllStats(ctx)
        }
    }

    private fun showDetailedAnalytics() {
        val ctx = requireContext()
        val (periodLabel, stats) = analyticsStatsForPeriod(ctx)
        val dialogView = layoutInflater.inflate(com.henrylumis.mediaprayer.R.layout.dialog_analytics, null)
        val totalMs = stats.sumOf { it.listenedMs }
        val totalPlays = stats.sumOf { it.playCount }
        val completions = stats.sumOf { it.completionCount }
        val skips = stats.sumOf { it.skipCount }
        val uniqueTracks = stats.count { it.listenedMs > 0 }
        val meaningful = completions + skips
        val completionRate = if (meaningful > 0) completions * 100 / meaningful else 0

        dialogView.findViewById<TextView>(com.henrylumis.mediaprayer.R.id.detail_title).text = "$periodLabel — Detailed stats"
        dialogView.findViewById<TextView>(com.henrylumis.mediaprayer.R.id.detail_summary).text =
            "${formatDuration(totalMs)} listened • $totalPlays plays • $uniqueTracks tracks"

        fun addRows(id: Int, rows: List<Pair<String, String>>) {
            val container = dialogView.findViewById<LinearLayout>(id)
            container.removeAllViews()
            rows.forEach { (left, right) -> container.addView(statRow(ctx, left, right)) }
        }

        val totalForShare = totalMs.coerceAtLeast(1L)
        addRows(com.henrylumis.mediaprayer.R.id.detail_top_songs,
            stats.sortedByDescending { it.listenedMs }.filter { it.listenedMs > 0 }.take(10).mapIndexed { i, s ->
                "${i + 1}. ${s.title} — ${s.artist}" to "${formatDuration(s.listenedMs)} • ${(s.listenedMs * 100 / totalForShare)}%"
            })

        addRows(com.henrylumis.mediaprayer.R.id.detail_top_artists,
            ListeningStatsStore.topArtists(ctx, 10, stats).mapIndexed { i, a ->
                "${i + 1}. ${a.artist}" to formatDuration(a.listenedMs)
            })

        addRows(com.henrylumis.mediaprayer.R.id.detail_top_albums,
            ListeningStatsStore.topAlbums(ctx, 10, stats).mapIndexed { i, a ->
                val artist = a.artist.takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""
                "${i + 1}. ${a.album}$artist" to formatDuration(a.listenedMs)
            })

        addRows(com.henrylumis.mediaprayer.R.id.detail_top_genres,
            ListeningStatsStore.topGenresByListeningMs(ctx, 10, stats).map { it.first to formatDuration(it.second) })

        val behaviorRows = mutableListOf(
            "Completed naturally" to completions.toString(),
            "Skipped after listening" to skips.toString(),
            "Completion rate" to if (meaningful > 0) "$completionRate%" else "—",
            "Average per tracked track" to formatDuration(if (uniqueTracks > 0) totalMs / uniqueTracks else 0L),
            "Active listening days" to activeDaysForSelectedPeriod(ctx).toString()
        )
        addRows(com.henrylumis.mediaprayer.R.id.detail_behavior, behaviorRows)

        addRows(com.henrylumis.mediaprayer.R.id.detail_all_songs,
            stats.sortedByDescending { it.listenedMs }.map { s ->
                val pct = if (s.durationMs > 0) ((s.listenedMs * 100) / s.durationMs).coerceAtMost(100) else -1
                val detail = if (pct >= 0) "${formatDuration(s.listenedMs)} • $pct% of track" else formatDuration(s.listenedMs)
                "${s.title} — ${s.artist}" to detail
            })

        val dialog = AlertDialog.Builder(ctx)
            .setView(dialogView)
            .setPositiveButton("Done", null)
            .create()
        DialogStyler.show(dialog)
    }

    private fun activeDaysForSelectedPeriod(ctx: android.content.Context): Int {
        return when (selectedAnalyticsPeriod) {
            "TODAY" -> if (ListeningStatsStore.getDayListenedMs(ctx, ListeningStatsStore.currentDayKey()) > 0) 1 else 0
            "7", "30" -> (1..selectedAnalyticsPeriod.toInt()).count { offset ->
                val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -(offset - 1)) }
                val key = "%04d-%02d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
                ListeningStatsStore.getDayListenedMs(ctx, key) > 0
            }
            else -> ListeningStatsStore.getActiveDayCount(ctx)
        }
    }

    private fun renderAnalytics() {
        if (_binding == null) return
        val ctx = requireContext()
        val (periodLabel, stats) = analyticsStatsForPeriod(ctx)
        val totalMs = stats.sumOf { it.listenedMs }
        val totalPlays = stats.sumOf { it.playCount }
        val uniqueTracks = stats.count { it.listenedMs > 0 }
        val completions = stats.sumOf { it.completionCount }
        val skips = stats.sumOf { it.skipCount }

        if (totalMs <= 0L && totalPlays <= 0 && completions <= 0 && skips <= 0) {
            binding.analyticsSummary.text = "No listening tracked for $periodLabel yet."
            binding.analyticsTopSongsHeader.visibility = View.GONE
            binding.analyticsTopGenresHeader.visibility = View.GONE
            binding.analyticsTopArtistsHeader.visibility = View.GONE
            binding.analyticsTopAlbumsHeader.visibility = View.GONE
            binding.analyticsBehaviorHeader.visibility = View.GONE
            binding.btnAnalyticsExpand.visibility = View.GONE
            binding.analyticsAllSongsList.visibility = View.GONE
            binding.analyticsTopSongsList.removeAllViews()
            binding.analyticsTopGenresList.removeAllViews()
            binding.analyticsTopArtistsList.removeAllViews()
            binding.analyticsTopAlbumsList.removeAllViews()
            binding.analyticsBehaviorList.removeAllViews()
            binding.analyticsHighlights.removeAllViews()
            return
        }

        binding.analyticsSummary.text = "$periodLabel • your listening at a glance"
        val activeDays = when (selectedAnalyticsPeriod) {
            "TODAY" -> if (ListeningStatsStore.getDayListenedMs(ctx, ListeningStatsStore.currentDayKey()) > 0) 1 else 0
            "7", "30" -> (1..selectedAnalyticsPeriod.toInt()).count { offset ->
                val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -(offset - 1)) }
                val key = "%04d-%02d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
                ListeningStatsStore.getDayListenedMs(ctx, key) > 0
            }
            else -> ListeningStatsStore.getActiveDayCount(ctx)
        }
        val streak = ListeningStatsStore.getCurrentStreakDays(ctx)
        renderAnalyticsHighlights(ctx, totalMs, totalPlays, uniqueTracks, activeDays, streak, completions, skips)
        renderWeeklyChart(ctx)
        renderMonthlyTrend(ctx)

        val topSongs = stats.sortedByDescending { it.listenedMs }.filter { it.listenedMs > 0 }.take(5)
        binding.analyticsTopSongsList.removeAllViews()
        binding.analyticsTopSongsHeader.visibility = if (topSongs.isEmpty()) View.GONE else View.VISIBLE
        topSongs.forEachIndexed { index, stat ->
            val share = if (totalMs > 0) (stat.listenedMs * 100 / totalMs).coerceAtMost(100) else 0
            binding.analyticsTopSongsList.addView(statRow(ctx, "${index + 1}. ${stat.title} — ${stat.artist}", "${formatDuration(stat.listenedMs)}  ${share}%"))
        }

        val topGenres = ListeningStatsStore.topGenresByListeningMs(ctx, 3, stats)
        binding.analyticsTopGenresList.removeAllViews()
        binding.analyticsTopGenresHeader.visibility = if (topGenres.isEmpty()) View.GONE else View.VISIBLE
        topGenres.forEach { (genre, ms) -> binding.analyticsTopGenresList.addView(statRow(ctx, genre, formatDuration(ms))) }

        val topArtists = ListeningStatsStore.topArtists(ctx, 5, stats)
        binding.analyticsTopArtistsList.removeAllViews()
        binding.analyticsTopArtistsHeader.visibility = if (topArtists.isEmpty()) View.GONE else View.VISIBLE
        topArtists.forEachIndexed { index, artistStat -> binding.analyticsTopArtistsList.addView(statRow(ctx, "${index + 1}. ${artistStat.artist}", formatDuration(artistStat.listenedMs))) }

        val topAlbums = ListeningStatsStore.topAlbums(ctx, 5, stats)
        binding.analyticsTopAlbumsList.removeAllViews()
        binding.analyticsTopAlbumsHeader.visibility = if (topAlbums.isEmpty()) View.GONE else View.VISIBLE
        topAlbums.forEachIndexed { index, albumStat ->
            val artist = albumStat.artist.takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""
            binding.analyticsTopAlbumsList.addView(statRow(ctx, "${index + 1}. ${albumStat.album}$artist", formatDuration(albumStat.listenedMs)))
        }

        val meaningful = completions + skips
        val completionRate = if (meaningful > 0) completions * 100 / meaningful else 0
        val mostSkipped = stats.maxByOrNull { it.skipCount }
        val mostCompleted = stats.maxByOrNull { it.completionCount }
        binding.analyticsBehaviorList.removeAllViews()
        binding.analyticsBehaviorHeader.visibility = if (meaningful > 0) View.VISIBLE else View.GONE
        if (meaningful > 0) {
            binding.analyticsBehaviorList.addView(statRow(ctx, "Completed naturally", completions.toString()))
            binding.analyticsBehaviorList.addView(statRow(ctx, "Skipped after listening", skips.toString()))
            binding.analyticsBehaviorList.addView(statRow(ctx, "Completion rate", "$completionRate%"))
            mostSkipped?.takeIf { it.skipCount > 0 }?.let { binding.analyticsBehaviorList.addView(statRow(ctx, "Most skipped", "${it.title} • ${it.skipCount}x")) }
            mostCompleted?.takeIf { it.completionCount > 0 }?.let { binding.analyticsBehaviorList.addView(statRow(ctx, "Most completed", "${it.title} • ${it.completionCount}x")) }
        }

        binding.btnAnalyticsExpand.visibility = if (stats.isNotEmpty()) View.VISIBLE else View.GONE
        binding.btnAnalyticsExpand.text = if (allSongsExpanded) "Hide song-by-song breakdown" else "Show every song's listening time"
        binding.analyticsAllSongsList.visibility = if (allSongsExpanded) View.VISIBLE else View.GONE
        if (allSongsExpanded) {
            binding.analyticsAllSongsList.removeAllViews()
            stats.sortedByDescending { it.listenedMs }.forEach { stat ->
                val completion = if (stat.durationMs > 0) ((stat.listenedMs * 100) / stat.durationMs).coerceAtMost(100) else -1
                val detail = if (completion >= 0) "${formatDuration(stat.listenedMs)}  •  $completion% listened" else formatDuration(stat.listenedMs)
                binding.analyticsAllSongsList.addView(statRow(ctx, "${stat.title} — ${stat.artist}", detail))
            }
        }
    }

    private fun renderAnalyticsHighlights(ctx: android.content.Context, totalMs: Long, totalPlays: Int, uniqueTracks: Int, activeDays: Int, streak: Int, completions: Int, skips: Int) {
        binding.analyticsHighlights.removeAllViews()
        val avg = if (uniqueTracks > 0) totalMs / uniqueTracks else 0L
        val cards = listOf(
            "LISTENED" to formatDuration(totalMs),
            "TRACKS" to uniqueTracks.toString(),
            "PLAYS" to totalPlays.toString(),
            "ACTIVE DAYS" to activeDays.toString(),
            "STREAK" to if (streak == 1) "1 day" else "$streak days",
            "AVG / TRACK" to formatDuration(avg),
            "COMPLETED" to completions.toString(),
            "SKIPPED" to skips.toString()
        )
        cards.forEachIndexed { index, pair ->
            if (index == 0 || index == 4) {
                binding.analyticsHighlights.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    tag = "analytics_row"
                })
            }
            val row = binding.analyticsHighlights.getChildAt(binding.analyticsHighlights.childCount - 1) as LinearLayout
            val (label, value) = pair
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(8, 10, 8, 10)
                background = resources.getDrawable(com.henrylumis.mediaprayer.R.drawable.bg_card, null)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(3, 3, 3, 3) }
            }
            card.addView(TextView(ctx).apply { text = value; textSize = 15f; gravity = android.view.Gravity.CENTER; setTextColor(resources.getColor(com.henrylumis.mediaprayer.R.color.text_primary, null)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
            card.addView(TextView(ctx).apply { text = label; textSize = 8f; gravity = android.view.Gravity.CENTER; setTextColor(resources.getColor(com.henrylumis.mediaprayer.R.color.text_secondary, null)) })
            row.addView(card)
        }
    }

    private fun renderWeeklyChart(ctx: android.content.Context) {
        binding.analyticsWeekChart.removeAllViews()
        val cal = java.util.Calendar.getInstance()
        val values = mutableListOf<Pair<String, Long>>()
        val labels = arrayOf("S", "M", "T", "W", "T", "F", "S")
        repeat(7) { offset ->
            val c = cal.clone() as java.util.Calendar
            c.add(java.util.Calendar.DAY_OF_YEAR, offset - 6)
            val key = "%04d-%02d-%02d".format(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
            values += labels[c.get(java.util.Calendar.DAY_OF_WEEK) - 1] to ListeningStatsStore.getDayListenedMs(ctx, key)
        }
        val max = values.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
        values.forEach { (label, ms) ->
            val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL; layoutParams = LinearLayout.LayoutParams(0, 120, 1f) }
            val bar = View(ctx).apply {
                val height = (18 + (72f * ms / max)).toInt().coerceIn(18, 90)
                layoutParams = LinearLayout.LayoutParams(18, height).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
                setBackgroundResource(com.henrylumis.mediaprayer.R.drawable.bg_accent_pill)
            }
            column.addView(bar)
            column.addView(TextView(ctx).apply { text = label; textSize = 10f; gravity = android.view.Gravity.CENTER; setTextColor(resources.getColor(com.henrylumis.mediaprayer.R.color.text_secondary, null)); setPadding(0, 4, 0, 0) })
            binding.analyticsWeekChart.addView(column)
        }
    }

    private fun renderMonthlyTrend(ctx: android.content.Context) {
        binding.analyticsMonthChart.removeAllViews()
        val cal = java.util.Calendar.getInstance()
        val values = (0 until 30).map { offset ->
            val end = cal.clone() as java.util.Calendar
            end.add(java.util.Calendar.DAY_OF_YEAR, -offset)
            val total = (0 until 1).sumOf {
                val key = "%04d-%02d-%02d".format(end.get(java.util.Calendar.YEAR), end.get(java.util.Calendar.MONTH) + 1, end.get(java.util.Calendar.DAY_OF_MONTH))
                ListeningStatsStore.getDayListenedMs(ctx, key)
            }
            total
        }.reversed()
        val max = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        values.forEachIndexed { index, ms ->
            val column = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, 100, 1f)
            }
            val bar = View(ctx).apply {
                val height = (8 + 62f * ms / max).toInt().coerceIn(8, 70)
                layoutParams = LinearLayout.LayoutParams(6, height).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
                setBackgroundResource(com.henrylumis.mediaprayer.R.drawable.bg_accent_pill)
            }
            column.addView(bar)
            if (index == 0 || index == 9 || index == 19 || index == 29) {
                val label = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, index - 29) }
                column.addView(TextView(ctx).apply {
                    text = label.get(java.util.Calendar.DAY_OF_MONTH).toString()
                    textSize = 8f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(resources.getColor(com.henrylumis.mediaprayer.R.color.text_secondary, null))
                })
            }
            binding.analyticsMonthChart.addView(column)
        }
    }

    private fun statRow(ctx: android.content.Context, left: String, right: String): TextView {
        return TextView(ctx).apply {
            text = "$left  \u2014  $right"
            setTextColor(resources.getColor(com.henrylumis.mediaprayer.R.color.text_secondary, null))
            textSize = 13f
            setPadding(0, 4, 0, 4)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms / 60000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun setupPlaybackSection(ctx: android.content.Context) {
        val savedSpeed = Prefs.getPlaybackSpeed(ctx)
        binding.playbackSpeedLabel.text = formatSpeed(savedSpeed)
        binding.playbackSpeedSlider.progress = ((savedSpeed - 0.5f) * 10f).toInt().coerceIn(0, 15)
        binding.playbackSpeedSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = (0.5f + progress * 0.1f).coerceIn(0.5f, 2f)
                binding.playbackSpeedLabel.text = formatSpeed(speed)
                if (fromUser) {
                    val service = com.henrylumis.mediaprayer.PlaybackService.instance
                    try {
                        service?.exoPlayer?.let { player ->
                            if (player.availableCommands.contains(androidx.media3.common.Player.COMMAND_SET_SPEED_AND_PITCH)) {
                                player.setPlaybackSpeed(speed)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

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

    private fun formatSpeed(speed: Float): String = String.format(java.util.Locale.US, "Playback speed: %.2f×", speed)

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

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val status = TextView(ctx).apply {
            text = if (eq.isEnabled) "Enabled" else "Disabled"
            setTextColor(resources.getColor(com.henrylumis.mediaprayer.R.color.text_secondary, null))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val toggle = com.google.android.material.switchmaterial.SwitchMaterial(ctx).apply {
            isChecked = eq.isEnabled
            contentDescription = "Enable equalizer"
            setOnCheckedChangeListener { _, checked ->
                eq.setEnabled(checked)
                status.text = if (checked) "Enabled" else "Disabled"
            }
        }
        header.addView(status)
        header.addView(toggle)
        binding.equalizerContainer.addView(header)

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
