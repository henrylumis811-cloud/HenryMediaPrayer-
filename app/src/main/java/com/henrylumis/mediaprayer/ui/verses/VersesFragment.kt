package com.henrylumis.mediaprayer.ui.verses

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.henrylumis.mediaprayer.MainActivity
import com.henrylumis.mediaprayer.audio.EmbeddedLyricsReader
import com.henrylumis.mediaprayer.data.LyricsLine
import com.henrylumis.mediaprayer.data.LyricsParser
import com.henrylumis.mediaprayer.databinding.DialogLyricSyncBinding
import com.henrylumis.mediaprayer.databinding.FragmentVersesBinding
import com.henrylumis.mediaprayer.util.LyricsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

import com.henrylumis.mediaprayer.ui.common.DialogStyler
/**
 * Shows lyrics for the current song, in priority order:
 *  1. A synced .lrc file placed next to the audio file
 *  2. Lyrics embedded directly in the file's own tags (USLT/SYLT) -- this is
 *     very likely how apps like Lark Player "just have" lyrics with zero
 *     setup for downloaded songs, entirely offline
 *  3. Manually pasted-in plain lyrics
 *  4. Empty state
 *
 * Also hosts the tap-to-sync tool: given plain pasted lyrics, tap along with
 * the song to build real timestamps, then export a proper .lrc file.
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
        adapter = LyricsAdapter { line ->
            if (line.timeMs > 0L) {
                (activity as? MainActivity)?.seekTo(line.timeMs)
            }
        }
        binding.lyricsList.layoutManager = LinearLayoutManager(requireContext())
        binding.lyricsList.adapter = adapter
        binding.btnPasteLyrics.setOnClickListener { showPasteDialog() }
        binding.btnSyncLyrics.setOnClickListener { startSyncFlow() }
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
            Toast.makeText(requireContext(), "Play a song first", Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(requireContext()).apply {
            hint = "Paste or type the lyrics here (plain text, one line per lyric)"
            minLines = 8
            gravity = Gravity.TOP or Gravity.START
            setText(currentPlainLyricsFallback(mediaId))
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(requireContext()).apply {
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
                lastLoadedMediaId = null
                loadForCurrentSongIfNeeded(force = true)
            }
            .setNegativeButton("Cancel", null)
            .let { DialogStyler.show(it) }
    }

    /** If stored lyrics are already a synced LRC export, show the plain text for editing instead. */
    private fun currentPlainLyricsFallback(mediaId: String): String {
        val stored = LyricsStore.get(requireContext(), mediaId).orEmpty()
        val parsed = LyricsParser.parse(stored)
        return if (parsed.isNotEmpty()) parsed.joinToString("\n") { it.text } else stored
    }

    private fun startSyncFlow() {
        val activity = activity as? MainActivity ?: return
        val item = activity.player?.currentMediaItem
        val mediaId = item?.mediaId
        if (mediaId == null) {
            Toast.makeText(requireContext(), "Play a song first", Toast.LENGTH_SHORT).show()
            return
        }
        val lines = currentPlainLyricsFallback(mediaId).lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            Toast.makeText(requireContext(), "Paste the lyrics first, then tap Sync", Toast.LENGTH_LONG).show()
            return
        }

        val dialogBinding = DialogLyricSyncBinding.inflate(LayoutInflater.from(requireContext()))
        val timestamps = mutableListOf<Long>()
        var index = 0

        fun render() {
            dialogBinding.syncProgressLabel.text = "Line ${index + 1} of ${lines.size}"
            dialogBinding.syncCurrentLine.text = lines[index]
        }
        render()

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Tap-to-Sync Lyrics")
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        dialogBinding.btnTapSync.setOnClickListener {
            val pos = activity.player?.currentPosition ?: 0L
            timestamps.add(pos)
            index++
            if (index >= lines.size) {
                finishSync(mediaId, item, lines, timestamps)
                dialog.dismiss()
            } else {
                render()
            }
        }

        dialogBinding.btnUndoSync.setOnClickListener {
            if (timestamps.isNotEmpty()) {
                timestamps.removeAt(timestamps.size - 1)
                index = (index - 1).coerceAtLeast(0)
                render()
            }
        }

        dialogBinding.btnCancelSync.setOnClickListener { dialog.dismiss() }

        DialogStyler.show(dialog)
    }

    private fun finishSync(
        mediaId: String,
        item: androidx.media3.common.MediaItem,
        lines: List<String>,
        timestamps: List<Long>
    ) {
        val lrcContent = lines.indices.joinToString("\n") { i ->
            "[${formatLrcTimestamp(timestamps[i])}]${lines[i]}"
        }

        // Always keep an in-app copy so it works even if the file write below fails.
        LyricsStore.set(requireContext(), mediaId, lrcContent)
        lastLoadedMediaId = null
        loadForCurrentSongIfNeeded(force = true)

        viewLifecycleOwner.lifecycleScope.launch {
            val savedToDisk = withContext(Dispatchers.IO) {
                try {
                    val dataPath = item.mediaMetadata.extras?.getString("data_path")
                    val lrcPath = LyricsParser.findLrcPath(dataPath) ?: return@withContext false
                    File(lrcPath).writeText(lrcContent)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (_binding == null) return@launch
            Toast.makeText(
                requireContext(),
                if (savedToDisk) "Synced lyrics saved as .lrc next to the song"
                else "Synced lyrics saved in-app (couldn't write next to the audio file on this device)",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun formatLrcTimestamp(ms: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hundredths = (ms % 1000) / 10
        return String.format("%02d:%02d.%02d", minutes, seconds, hundredths)
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
                    val list = binding.lyricsList
                    val targetOffset = (list.height / 2).coerceAtLeast(0)
                    (list.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(newIndex, targetOffset)
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
            val fileSynced = withContext(Dispatchers.IO) {
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

            if (fileSynced.isNotEmpty()) {
                adapter.submitLines(fileSynced, isSynced = true)
                binding.versesEmptyState.visibility = View.GONE
                return@launch
            }

            // No external .lrc -- check for lyrics embedded directly in the
            // file's own tags before falling back to anything manual.
            val embedded = withContext(Dispatchers.IO) {
                try { EmbeddedLyricsReader.read(requireContext(), item.localConfiguration?.uri?.toString() ?: item.mediaId, dataPath) } catch (e: Exception) { null }
            }
            if (_binding == null) return@launch
            if (embedded != null) {
                if (embedded.syncedLines.isNotEmpty()) {
                    adapter.submitLines(embedded.syncedLines, isSynced = true)
                    binding.versesEmptyState.visibility = View.GONE
                    return@launch
                } else if (!embedded.plainText.isNullOrBlank()) {
                    val plainLines = embedded.plainText.lines().filter { it.isNotBlank() }
                        .map { LyricsLine(0L, it) }
                    adapter.submitLines(plainLines, isSynced = false)
                    binding.versesEmptyState.visibility = View.GONE
                    return@launch
                }
            }

            val stored = LyricsStore.get(requireContext(), mediaId)
            if (!stored.isNullOrBlank()) {
                // Could be a synced export from the tap-to-sync tool, or plain pasted text.
                val storedSynced = LyricsParser.parse(stored)
                if (storedSynced.isNotEmpty()) {
                    adapter.submitLines(storedSynced, isSynced = true)
                } else {
                    val plainLines = stored.lines().filter { it.isNotBlank() }.map { LyricsLine(0L, it) }
                    adapter.submitLines(plainLines, isSynced = false)
                }
                binding.versesEmptyState.visibility = View.GONE
            } else {
                adapter.submitLines(emptyList())
                binding.versesEmptyState.text = buildNoLyricsMessage(embedded?.diagnostic)
                binding.versesEmptyState.visibility = View.VISIBLE
            }
        }
    }

    private fun buildNoLyricsMessage(embeddedDiagnostic: String?): String {
        val detail = embeddedDiagnostic?.takeIf { it.isNotBlank() }
            ?: "The embedded lyric metadata could not be read."
        return "No lyrics found for this song.\n\nChecked: a matching .lrc file, embedded audio metadata, and common end-of-file lyric tags.\n\n$detail\n\nTap \"Paste Lyrics\" to add them yourself."
    }

    override fun onDestroyView() {
        tickRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroyView()
        _binding = null
    }
}
