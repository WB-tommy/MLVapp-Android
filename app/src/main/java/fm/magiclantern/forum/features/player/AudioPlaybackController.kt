package fm.magiclantern.forum.features.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log
import fm.magiclantern.forum.nativeInterface.NativeLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Streams PCM audio from the native MLV clip into an [android.media.AudioTrack] and exposes the
 * playback clock.
 *
 * **Audio-video sync design**
 * The native layer (`audio_mlv.c`) already pre-aligns the flat PCM buffer to the video timeline
 * at load time by computing the byte offset that corresponds to the time delta between the first
 * audio and video blocks.  Therefore, byte 0 of the audio buffer corresponds exactly to video
 * frame 0, and the buffer only needs to be played forward from the byte offset that matches the
 * seek position — no mid-stream byte-offset corrections are needed or safe on a streaming
 * [AudioTrack].
 *
 * [syncPosition] is intentionally a **no-op while the track is playing**.  Jumping [nextReadOffset]
 * while the hardware buffer still contains audio from a different position causes the
 * pitch-shifting distortion ("hawling") that was observed with both MLV and mcraw clips.
 * Use [seekToUs] for any actual repositioning; it stops, flushes, and restarts the stream.
 */
class AudioPlaybackController(
    private val scope: CoroutineScope,
    private val onPlaybackCompleted: () -> Unit = {}
) {
    private val tag = "AudioController"

    private var clipHandle: Long = 0L
    private var sampleRate: Int = 0
    private var channelCount: Int = 0
    private var bytesPerSample: Int = 0
    private var audioBufferBytes: Long = 0L

    private var audioTrack: AudioTrack? = null
    private var streamingJob: Job? = null

    private var nextReadOffset = AtomicLong(0L)
    private var playbackBaseUs: Long = 0L

    private var chunkSizeBytes: Int = 0
    private var audioEncoding: Int = AudioFormat.ENCODING_PCM_16BIT
    private var channelMask: Int = AudioFormat.CHANNEL_OUT_STEREO

    // Reused AudioTimestamp instance to avoid GC pressure on the hot path.
    private val audioTimestamp = AudioTimestamp()

    private val ioDispatcher = Dispatchers.IO

    fun prepare(
        clipHandle: Long,
        sampleRate: Int,
        channelCount: Int,
        bytesPerSample: Int,
        audioBufferBytes: Long
    ) {
        if (
            clipHandle == 0L ||
            sampleRate <= 0 ||
            channelCount <= 0 ||
            bytesPerSample <= 0 ||
            audioBufferBytes <= 0L
        ) {
            stop()
            return
        }

        val paramsChanged =
            this.clipHandle != clipHandle ||
                this.sampleRate != sampleRate ||
                this.channelCount != channelCount ||
                this.bytesPerSample != bytesPerSample

        this.clipHandle = clipHandle
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        this.bytesPerSample = bytesPerSample
        this.audioBufferBytes = audioBufferBytes

        if (paramsChanged || audioTrack == null) {
            rebuildAudioTrack()
        }

        // Reset offsets for new clip or updated params.
        nextReadOffset.set(nextReadOffset.get().coerceIn(0L, audioBufferBytes))
        playbackBaseUs = playbackBaseUs.coerceAtLeast(0L)
    }

    fun play() {
        val track = audioTrack ?: return
        if (streamingJob?.isActive == true) {
            track.play()
            return
        }

        track.play()

        streamingJob = scope.launch(ioDispatcher) {
            val buffer = ByteBuffer.allocateDirect(chunkSizeBytes).order(ByteOrder.nativeOrder())

            while (isActive) {
                val currentOffset = nextReadOffset.get()
                if (currentOffset >= audioBufferBytes) {
                    break // End of stream
                }

                buffer.clear()
                val bytesRemaining = audioBufferBytes - currentOffset
                val bytesToRead = min(bytesRemaining, chunkSizeBytes.toLong()).toInt()

                val read = NativeLib.readAudioBuffer(
                    clipHandle,
                    currentOffset,
                    bytesToRead,
                    buffer
                )

                if (read <= 0) {
                    break // Error or end of stream
                }

                buffer.position(0)
                buffer.limit(read)

                var bytesPending = read
                while (bytesPending > 0 && isActive) {
                    val written = track.write(buffer, bytesPending, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) { // A real error
                        Log.e(tag, "AudioTrack write error: $written")
                        break
                    }
                    if (written == 0) { // Buffer is full, just wait and retry
                        delay(1)
                        continue
                    }
                    bytesPending -= written
                }

                // Advance offset atomically. Only move forward — never jump backward.
                nextReadOffset.compareAndSet(currentOffset, currentOffset + read)
            }

            if (isActive) {
                waitForTrackDrain()
                onPlaybackCompleted()
            }
        }
    }

    fun pause() {
        streamingJob?.cancel()
        streamingJob = null
        audioTrack?.pause()
    }

    fun stop() {
        streamingJob?.cancel()
        streamingJob = null
        audioTrack?.apply {
            try {
                stop()
            } catch (ignored: IllegalStateException) {
            }
            flush()
            release()
        }
        audioTrack = null
        clipHandle = 0L
        playbackBaseUs = 0L
        nextReadOffset.set(0L)
    }

    fun seekToUs(positionUs: Long) {
        val track = audioTrack ?: return
        if (sampleRate <= 0 || bytesPerSample <= 0) {
            return
        }

        val wasPlaying = track.playState == AudioTrack.PLAYSTATE_PLAYING

        streamingJob?.cancel()
        streamingJob = null
        track.pause()
        track.flush()

        val clamped = positionUs.coerceAtLeast(0L)
        playbackBaseUs = clamped

        val bytesPerSecond = sampleRate.toLong() * bytesPerSample
        var offset = if (bytesPerSecond > 0) {
            (clamped * bytesPerSecond) / 1_000_000L
        } else {
            0L
        }

        if (bytesPerSample > 0) {
            offset -= offset % bytesPerSample
        }

        nextReadOffset.set(offset.coerceIn(0L, audioBufferBytes))

        if (wasPlaying) {
            play()
        }
    }

    /**
     * Returns the current playback position in microseconds.
     *
     * Uses [AudioTrack.getTimestamp] (API 19+) for a monotonic, accurate clock.  Falls back to
     * the frame-based estimate if the hardware timestamp is not yet available (e.g., before the
     * first audio chunk has been rendered by the DSP).
     */
    fun playbackPositionUs(): Long {
        val track = audioTrack ?: return playbackBaseUs

        if (track.getTimestamp(audioTimestamp)) {
            // audioTimestamp.framePosition: frames presented to audio hardware up to this point.
            // audioTimestamp.nanoTime: the monotonic clock value at which that frame position was valid.
            val elapsedSinceAnchorNs = System.nanoTime() - audioTimestamp.nanoTime
            val elapsedSinceAnchorUs = elapsedSinceAnchorNs / 1_000L
            val framesUs = if (sampleRate > 0) {
                (audioTimestamp.framePosition * 1_000_000L) / sampleRate
            } else {
                0L
            }
            return playbackBaseUs + framesUs + elapsedSinceAnchorUs
        }

        // Fallback: estimate from the 32-bit head-position counter (less accurate).
        val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val elapsedUs = if (sampleRate > 0) (head * 1_000_000L) / sampleRate else 0L
        return playbackBaseUs + elapsedUs
    }

    fun isRunning(): Boolean = streamingJob?.isActive == true

    /**
     * Hints the desired playback byte position for the **next** streaming window.
     *
     * **This is intentionally a no-op while the AudioTrack is actively playing.**
     * Jumping [nextReadOffset] mid-stream without flushing the hardware buffer causes
     * pitch-shifting distortion ("hawling") because the hardware continues consuming audio that
     * was already written at the previous byte offset.
     *
     * If you need to reposition during active playback, call [seekToUs] instead — it properly
     * stops the stream, flushes the hardware buffer, and restarts from the new offset.
     */
    fun syncPosition(positionUs: Long) {
        val track = audioTrack ?: return
        // Only adjust the read pointer when the track is not actively playing.
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            return
        }
        if (sampleRate <= 0 || bytesPerSample <= 0) return

        val bytesPerSecond = sampleRate.toLong() * bytesPerSample
        var offset = if (bytesPerSecond > 0) {
            (positionUs * bytesPerSecond) / 1_000_000L
        } else {
            0L
        }

        if (bytesPerSample > 0) {
            offset -= offset % bytesPerSample
        }

        nextReadOffset.set(offset.coerceIn(0L, audioBufferBytes))
    }

    private fun rebuildAudioTrack() {
        streamingJob?.cancel()
        streamingJob = null
        audioTrack?.apply {
            try {
                stop()
            } catch (ignored: IllegalStateException) {
            }
            flush()
            release()
        }

        channelMask = when (channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            4 -> AudioFormat.CHANNEL_OUT_QUAD
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }

        val bytesPerChannel = bytesPerSample / channelCount
        audioEncoding = when (bytesPerChannel) {
            2 -> AudioFormat.ENCODING_PCM_16BIT
            4 -> AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }

        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, audioEncoding)
        chunkSizeBytes = min(64 * 1024, max(minBuffer, bytesPerSample * 1024))

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .setLegacyStreamType(AudioManager.STREAM_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setChannelMask(channelMask)
            .setSampleRate(sampleRate)
            .setEncoding(audioEncoding)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(chunkSizeBytes * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        nextReadOffset.set(0L)
        playbackBaseUs = 0L
    }

    private suspend fun waitForTrackDrain() {
        val track = audioTrack ?: return
        var idleCount = 0
        while (scope.isActive && track.playState == AudioTrack.PLAYSTATE_PLAYING && idleCount < 100) {
            delay(20)
            idleCount++
        }
    }
}