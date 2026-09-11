package com.henrylumis.mediaprayer.trim

import android.content.Context
import android.view.LayoutInflater
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.henrylumis.mediaprayer.data.Song
import com.henrylumis.mediaprayer.databinding.DialogAudioTrimmerBinding
import java.util.concurrent.TimeUnit

import com.henrylumis.mediaprayer.ui.common.DialogStyler
/**
 * Trim UI for a single song: two range sliders (start/end), a live preview
 * that actually plays just the selected clip, and an export that saves a
 * real trimmed audio file into the system Ringtones collection.
 */
@UnstableApi
object TrimmerDialog {

    fun show(context: Context, song: Song) {
        val binding = DialogAudioTrimmerBinding.inflate(LayoutInflater.from(context))
        val durationMs = song.durationMs.coerceAtLeast(1000L)

        binding.trimSongTitle.text = song.title
        binding.trimStartSlider.max = durationMs.toInt()
        binding.trimEndSlider.max = durationMs.toInt()
        binding.trimStartSlider.progress = 0
        binding.trimEndSlider.progress = (durationMs.coerceAtMost(30_000L)).toInt()

        var previewPlayer: ExoPlayer? = null

        fun stopPreview() {
            previewPlayer?.release()
            previewPlayer = null
            binding.btnTrimPreview.text = "PREVIEW CLIP"
        }

        fun currentRange(): Pair<Long, Long> {
            val start = binding.trimStartSlider.progress.toLong()
            var end = binding.trimEndSlider.progress.toLong()
            if (end <= start) end = (start + 1000L).coerceAtMost(durationMs)
            return start to end
        }

        fun updateLabel() {
            val (start, end) = currentRange()
            fun fmt(ms: Long): String {
                val s = TimeUnit.MILLISECONDS.toSeconds(ms)
                return String.format("%d:%02d", s / 60, s % 60)
            }
            val clipSeconds = TimeUnit.MILLISECONDS.toSeconds(end - start)
            binding.trimRangeLabel.text = "${fmt(start)} - ${fmt(end)}  (${clipSeconds}s clip)"
        }
        updateLabel()

        val sliderListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) updateLabel()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        binding.trimStartSlider.setOnSeekBarChangeListener(sliderListener)
        binding.trimEndSlider.setOnSeekBarChangeListener(sliderListener)

        binding.btnTrimPreview.setOnClickListener {
            if (previewPlayer != null) {
                stopPreview()
                return@setOnClickListener
            }
            val (start, end) = currentRange()
            val player = ExoPlayer.Builder(context).build()
            val clipped = MediaItem.Builder()
                .setUri(song.uriString)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(start)
                        .setEndPositionMs(end)
                        .build()
                )
                .build()
            player.setMediaItem(clipped)
            player.prepare()
            player.play()
            previewPlayer = player
            binding.btnTrimPreview.text = "STOP PREVIEW"
            player.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == androidx.media3.common.Player.STATE_ENDED) stopPreview()
                }
            })
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Trim & Save as Ringtone")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .setOnDismissListener { stopPreview() }
            .create()

        binding.btnTrimExport.setOnClickListener {
            stopPreview()
            val (start, end) = currentRange()

            val nameInput = EditText(context).apply {
                setText(song.title.take(40))
            }
            val padding = (16 * context.resources.displayMetrics.density).toInt()
            val nameContainer = FrameLayout(context).apply {
                setPadding(padding, padding, padding, padding)
                addView(nameInput)
            }

            AlertDialog.Builder(context)
                .setTitle("Name this ringtone")
                .setView(nameContainer)
                .setPositiveButton("Save") { _, _ ->
                    val name = nameInput.text.toString().ifBlank { song.title }
                    binding.trimStatus.visibility = android.view.View.VISIBLE
                    binding.trimStatus.text = "Trimming and saving..."
                    binding.btnTrimExport.isEnabled = false

                    AudioTrimmer.trimAndSaveAsRingtone(
                        context, android.net.Uri.parse(song.uriString), start, end, name
                    ) { result ->
                        binding.btnTrimExport.isEnabled = true
                        when (result) {
                            is AudioTrimmer.Result.Success -> {
                                binding.trimStatus.text = "Saved! Find it under Settings > Sound > Ringtone."
                                Toast.makeText(context, "Ringtone saved: ${result.displayName}", Toast.LENGTH_LONG).show()
                            }
                            is AudioTrimmer.Result.Failure -> {
                                binding.trimStatus.text = "Failed: ${result.message}"
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .let { DialogStyler.show(it) }
        }

        DialogStyler.show(dialog)
    }
}
