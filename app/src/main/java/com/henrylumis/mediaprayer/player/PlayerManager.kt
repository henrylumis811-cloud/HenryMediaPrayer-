package com.henrylumis.mediaprayer.player

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Visualizer
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.henrylumis.mediaprayer.data.Track

/**
 * One ExoPlayer instance shared by the whole app, matching the single
 * `<audio>` element + Web Audio graph the original web app used. ExoPlayer
 * decodes each source file to PCM at *its own* sample rate/bit-depth and only
 * touches gain via the EQ/bass-boost effects below -- there is no additional
 * resampling or lossy re-encoding, which is what "best available quality"
 * means for local playback.
 */
object PlayerManager {

    const val REPEAT_OFF = 0
    const val REPEAT_ALL = 1
    const val REPEAT_ONE = 2

    private const val BAND_SUB_BASS = 60
    private const val BAND_BASS = 250
    private const val BAND_MID = 1000
    private const val BAND_UPPER_MID = 4000
    private const val BAND_TREBLE = 12000
    val BAND_FREQS = intArrayOf(BAND_SUB_BASS, BAND_BASS, BAND_MID, BAND_UPPER_MID, BAND_TREBLE)

    private var appContext: Context? = null
    private var _player: ExoPlayer? = null
    val player: ExoPlayer get() = _player ?: error("PlayerManager.init() was not called")

    var audioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
        private set

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var visualizer: Visualizer? = null

    var visualizerListener: ((waveform: ByteArray, fft: ByteArray) -> Unit)? = null

    var tracks: List<Track> = emptyList()
        private set

    var reduceMotion: Boolean = false

    @Synchronized
    fun init(context: Context) {
        if (_player != null) return
        val ctx = context.applicationContext
        appContext = ctx

        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exo = ExoPlayer.Builder(ctx).build().apply {
            setAudioAttributes(attrs, /* handleAudioFocus= */ true)
        }

        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sessionId = audioManager.generateAudioSessionId()
        exo.setAudioSessionId(sessionId)
        audioSessionId = sessionId
        _player = exo

        setupEffects(sessionId)
    }

    private fun setupEffects(sessionId: Int) {
        try {
            equalizer = Equalizer(0, sessionId).apply { enabled = true }
        } catch (t: Throwable) {
            equalizer = null
        }
        try {
            bassBoost = BassBoost(0, sessionId).apply { enabled = false }
        } catch (t: Throwable) {
            bassBoost = null
        }
        try {
            visualizer = Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                val rate = (Visualizer.getMaxCaptureRate() * 0.6).toInt().coerceAtLeast(4000)
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        if (waveform != null) visualizerListener?.invoke(waveform, lastFft)
                    }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft != null) {
                            lastFft = fft
                        }
                    }
                }, rate, true, true)
                enabled = true
            }
        } catch (t: Throwable) {
            visualizer = null
        }
    }

    private var lastFft: ByteArray = ByteArray(0)

    // ---------------------------------------------------------------- queue

    fun setQueue(newTracks: List<Track>, startIndex: Int, playWhenReady: Boolean = true) {
        tracks = newTracks
        val items = newTracks.map { MediaItem.fromUri(Uri.parse(it.uriString)) }
        player.setMediaItems(items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)), 0L)
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    fun currentIndex(): Int = player.currentMediaItemIndex

    fun playAt(index: Int) {
        if (index < 0 || index >= tracks.size) return
        player.seekTo(index, 0L)
        player.playWhenReady = true
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun next() {
        if (player.hasNextMediaItem()) player.seekToNext() else if (player.repeatMode == Player.REPEAT_MODE_ALL) {
            player.seekTo(0, 0L)
        }
    }

    fun previous() {
        if (player.currentPosition > 3000) {
            player.seekTo(0L)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPrevious()
        } else {
            player.seekTo(0L)
        }
    }

    fun setShuffle(enabled: Boolean) {
        player.shuffleModeEnabled = enabled
    }

    /** repeatMode: 0=off, 1=all, 2=one (mirrors the web app's cycle order). */
    fun setRepeatMode(repeatMode: Int) {
        player.repeatMode = when (repeatMode) {
            REPEAT_ALL -> Player.REPEAT_MODE_ALL
            REPEAT_ONE -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
    }

    fun setVolume(volume01: Float) {
        player.volume = volume01.coerceIn(0f, 1f)
    }

    fun seekToFraction(fraction: Float) {
        val dur = player.duration
        if (dur > 0) player.seekTo((dur * fraction).toLong())
    }

    // -------------------------------------------------------------------- eq

    fun setBandGainDb(freqHz: Int, gainDb: Float) {
        val eq = equalizer ?: return
        val bandIndex = eq.getBand((freqHz * 1000))
        val range = eq.bandLevelRange
        val millibels = (gainDb * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
        eq.setBandLevel(bandIndex, millibels.toShort())
    }

    fun setBoost(enabled: Boolean) {
        bassBoost?.let {
            it.enabled = enabled
            if (enabled) it.setStrength(1000)
        }
    }

    fun release() {
        equalizer?.release()
        bassBoost?.release()
        visualizer?.release()
        _player?.release()
        _player = null
        equalizer = null
        bassBoost = null
        visualizer = null
    }
}
