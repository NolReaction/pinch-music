package music.pinch

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

internal class AndroidMedia(
    private val context: Context,
    private val preview: Boolean,
    volume: Float,
    private val foreground: () -> Boolean,
    private val onClosed: (AndroidMedia) -> Unit,
) : GameMedia {
    private var player: ExoPlayer? = null
    private var target: TextureView? = null
    private var voices: NoteSamples? = null
    private val focus = AndroidAudioFocus(context, ::interrupt)
    private var started = false
    private var frameReady = false
    private var soundsReady = false
    private var error: String? = null
    private var closed = false
    private var previewVolume = volume
    private var interruptedBeforeListener = false

    override var onInterrupted: (() -> Unit)? = null
        set(value) {
            field = value
            if (value != null && interruptedBeforeListener && !closed) {
                interruptedBeforeListener = false
                value()
            }
        }

    private val events = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            frameReady = target != null
        }

        override fun onPlayerError(error: PlaybackException) {
            fail("Не удалось воспроизвести видео (${error.errorCodeName}).")
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (started && (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)) {
                fail("Позиция видео была изменена. Начни попытку заново.")
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val media = player ?: return
            if (playbackState == Player.STATE_BUFFERING && media.playWhenReady) interrupt()
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE && player?.playWhenReady == true) {
                interrupt()
            }
        }
    }

    override fun prepare(pitches: Set<Int>) {
        if (closed || player != null || error != null) return
        try {
            focus.observe()
            val media = ExoPlayer.Builder(context).build()
            player = media
            media.addListener(events)
            media.volume = if (preview) previewVolume else 0f
            media.playWhenReady = false
            target?.let(media::setVideoTextureView)
            val filename = if (preview) "demo-preview.mp4" else "demo-ball-muted.mp4"
            media.setMediaItem(MediaItem.fromUri(Uri.parse("asset:///$filename")))
            media.prepare()
            if (preview) {
                soundsReady = true
            } else {
                val samples = NoteSamples()
                voices = samples
                samples.load(context.assets, pitches, { soundsReady = true }, ::fail)
            }
            if (!foreground()) interrupt()
        } catch (problem: Exception) {
            fail("Не удалось подготовить демо: ${problem.localizedMessage ?: "ошибка медиаплеера"}.")
        }
    }

    @Composable
    override fun Video(modifier: Modifier) {
        key(this) {
            Box(modifier, contentAlignment = Alignment.Center) {
                AndroidView(
                    modifier = Modifier.aspectRatio(9f / 16f),
                    factory = { ctx ->
                        TextureView(ctx).also { view ->
                            target = view
                            player?.setVideoTextureView(view)
                        }
                    },
                    onReset = null,
                    onRelease = { view ->
                        if (target === view) {
                            player?.clearVideoTextureView(view)
                            target = null
                            frameReady = false
                        }
                    },
                    update = {},
                )
            }
        }
    }

    override fun snapshot(): Playback {
        val media = player
        val position = media?.currentPosition ?: 0L
        val uptime = SystemClock.uptimeMillis()
        return Playback(
            ready = !closed && error == null && frameReady && soundsReady && media?.playbackState == Player.STATE_READY,
            playing = media?.isPlaying == true,
            ended = media?.playbackState == Player.STATE_ENDED,
            positionMs = position,
            observedUptimeMs = uptime,
            error = error,
        )
    }

    override fun play() {
        if (closed || error != null) return
        if (foreground() && focus.acquire()) {
            started = true
            player?.play()
        } else {
            interrupt()
        }
    }

    override fun pause() {
        if (closed) return
        player?.pause()
        voices?.silence()
        focus.abandon()
    }

    fun interrupt() {
        if (closed) return
        pause()
        val callback = onInterrupted
        if (callback == null) interruptedBeforeListener = true else callback()
    }

    private fun fail(message: String) {
        if (closed || error != null) return
        error = message
        pause()
    }

    override fun note(midi: Int, gain: Float) {
        if (!preview && player?.isPlaying == true && error == null) voices?.play(midi, gain)
    }

    override fun setPreviewVolume(volume: Float) {
        previewVolume = volume.coerceIn(0f, 1f)
        if (preview) player?.volume = previewVolume
    }

    override fun close() {
        if (closed) return
        closed = true
        onInterrupted = null
        focus.close()
        voices?.release()
        voices = null
        player?.let { media ->
            media.removeListener(events)
            target?.let(media::clearVideoTextureView)
            media.release()
        }
        player = null
        target = null
        onClosed(this)
    }
}
