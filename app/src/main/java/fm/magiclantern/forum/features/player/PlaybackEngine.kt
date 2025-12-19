package fm.magiclantern.forum.features.player

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

data class PlaybackContext(
    val clipHandle: Long,
    val frameCount: Int,
    val fps: Float,
    val frameTimestamps: LongArray,
    val hasAudio: Boolean,
    val audioSampleRate: Int,
    val audioChannels: Int,
    val audioBytesPerSample: Int,
    val audioBufferSize: Long
)

class PlaybackEngine(
    private val scope: CoroutineScope,
    private val frameUpdater: (Int) -> Unit,
    private val onPlaybackStopped: () -> Unit,
    private val currentFrameProvider: () -> Int,
    private val averageProcessingUsProvider: () -> Long,
) {
    private val tag = "PlaybackEngine"

    private val audioController = AudioPlaybackController(scope) {
        handlePlaybackStop(cancelJob = true)
    }

    private var context: PlaybackContext? = null
    private var dropFrameMode: Boolean = true
    private var isPlaying: Boolean = false
    private var playbackJob: Job? = null

    fun updateContext(newContext: PlaybackContext?) {
        stopPlaybackJob()
        context = newContext
        isPlaying = false

        if (newContext == null || newContext.frameCount <= 0) {
            audioController.stop()
            return
        }

        if (newContext.hasAudio) {
            audioController.prepare(
                clipHandle = newContext.clipHandle,
                sampleRate = newContext.audioSampleRate,
                channelCount = newContext.audioChannels,
                bytesPerSample = newContext.audioBytesPerSample,
                audioBufferBytes = newContext.audioBufferSize
            )
            seekInternal(currentFrameProvider())
        } else {
            audioController.stop()
        }
    }

    fun setDropFrameMode(enabled: Boolean) {
        if (dropFrameMode == enabled) return
        dropFrameMode = enabled
        if (!isPlaying) {
            if (!enabled) {
                audioController.pause()
            }
            return
        }
        if (enabled) {
            startDropFrameLoop()
        } else {
            stopPlaybackJob()
            audioController.pause()
        }
    }

    fun play() {
        if (isPlaying) return
        val ctx = context ?: return
        if (ctx.frameCount <= 0) return
        isPlaying = true
        if (dropFrameMode) {
            startDropFrameLoop()
        } else {
            audioController.pause()
            advanceFrameSequential()
        }
    }

    fun pause() {
        if (!isPlaying) return
        isPlaying = false
        stopPlaybackJob()
        audioController.pause()
    }

    fun stop() {
        isPlaying = false
        stopPlaybackJob()
        audioController.pause()
        onPlaybackStopped()
    }

    fun onSeek(frameIndex: Int) {
        seekInternal(frameIndex)
    }

    fun advanceFrameSequential() {
        val ctx = context ?: return
        if (!isPlaying || dropFrameMode) return
        val current = currentFrameProvider()
        val nextFrame = (current + 1).coerceAtMost(ctx.frameCount - 1)
        if (nextFrame == current) {
            handlePlaybackStop()
            return
        }
        frameUpdater(nextFrame)
        if (ctx.hasAudio) {
            val timestampUs = ctx.frameTimestamps.getOrElse(nextFrame) { 0L }
            audioController.seekToUs(timestampUs)
        }
        if (nextFrame >= ctx.frameCount - 1) {
            handlePlaybackStop()
        }
    }

    private fun startDropFrameLoop() {
        stopPlaybackJob()

        val ctx = context ?: return
        if (!isPlaying || ctx.frameCount <= 0) return

        val frameCount = ctx.frameCount
        val timestamps = ctx.frameTimestamps
        val fps = ctx.fps
        val expectedFrameDurationUs =
            if (fps > 0f) (1_000_000f / fps).toLong().coerceAtLeast(1L) else 0L

        val job = scope.launch {
            var lastAppliedFrame = currentFrameProvider().coerceIn(0, frameCount - 1)
            var anchorTimestampUs = timestamps.getOrElse(lastAppliedFrame) { 0L }
            var anchorClockNs = System.nanoTime()
            var spinCount = 0
            var usingFallback = false

            if (ctx.hasAudio) {
                audioController.seekToUs(anchorTimestampUs)
                audioController.play()
            } else {
                audioController.pause()
            }

            while (isActive && isPlaying && dropFrameMode && !usingFallback) {
                val externalFrame = currentFrameProvider().coerceIn(0, frameCount - 1)
                if (externalFrame != lastAppliedFrame) {
                    lastAppliedFrame = externalFrame
                    anchorTimestampUs = timestamps.getOrElse(lastAppliedFrame) { 0L }
                    anchorClockNs = System.nanoTime()
                    if (ctx.hasAudio) {
                        audioController.seekToUs(anchorTimestampUs)
                    }
                }

                val elapsedUs = (System.nanoTime() - anchorClockNs) / 1000L
                val currentTimeUs = anchorTimestampUs + elapsedUs

                var candidateFrame = lastAppliedFrame
                while (
                    candidateFrame + 1 < frameCount &&
                    timestamps[candidateFrame + 1] <= currentTimeUs
                ) {
                    candidateFrame++
                }

                if (candidateFrame != lastAppliedFrame) {
                    lastAppliedFrame = candidateFrame
                    frameUpdater(candidateFrame)
                    if (ctx.hasAudio) {
                        val syncTimeUs = timestamps.getOrElse(candidateFrame) { 0L }
                        audioController.syncPosition(syncTimeUs)
                    }
                    spinCount = 0
                    if (candidateFrame >= frameCount - 1) {
                        break
                    }
                } else {
                    spinCount++
                }

                val nextTimestamp = timestamps.getOrNull(lastAppliedFrame + 1)
                if (nextTimestamp == null) {
                    break
                }

                val totalSpinTimeUs = (System.nanoTime() - anchorClockNs) / 1000L
                val deltaToNext = nextTimestamp - anchorTimestampUs
                // The timestamp-driven loop can stall if timestamps are missing, non-monotonic,
                // or have large, irregular gaps. These heuristics detect such stalls
                // and switch to a more stable, FPS-based fallback loop.
                val tweening =
                    expectedFrameDurationUs <= 0L || deltaToNext <= expectedFrameDurationUs * 4
                if (totalSpinTimeUs > 200_000 || // 1. Timeout: The loop has been spinning without advancing for 200ms.
                    spinCount >= 20 || // 2. Spin count: The loop has run 20 times without finding a new frame.
                    tweening // 3. Tweening: The gap to the next frame is small, suggesting interpolation is safer.
                ) {
                    usingFallback = true
                    break
                }

                val processingBudgetUs = averageProcessingUsProvider().coerceAtLeast(0L)
                val waitUs = (nextTimestamp - currentTimeUs - processingBudgetUs).coerceAtLeast(0L)
                val waitMillis = (waitUs / 1000L).coerceAtLeast(0L)
                delay(waitMillis.coerceAtMost(8L))
            }

            if (!isActive || !isPlaying || !dropFrameMode) {
                return@launch
            }

            if (usingFallback) {
                runFpsFallbackLoop(ctx)
            } else {
                handlePlaybackStop()
            }
        }

        playbackJob = job
        job.invokeOnCompletion {
            if (playbackJob === job) {
                playbackJob = null
            }
        }
    }

    private suspend fun runFpsFallbackLoop(ctx: PlaybackContext) {
        val frameDurationMillis =
            if (ctx.fps > 0f) (1000 / ctx.fps).toLong().coerceAtLeast(1L) else 42L
        while (
            isPlaying &&
            dropFrameMode &&
            currentFrameProvider() < ctx.frameCount - 1 &&
            coroutineContext.isActive
        ) {
            val loopStartNs = System.nanoTime()
            val nextFrame =
                (currentFrameProvider() + 1).coerceAtMost(ctx.frameCount - 1)
            frameUpdater(nextFrame)
            if (ctx.hasAudio) {
                val timestampUs = ctx.frameTimestamps.getOrElse(nextFrame) { 0L }
                audioController.syncPosition(timestampUs)
            }
            if (nextFrame >= ctx.frameCount - 1) {
                break
            }
            val elapsedMillis = (System.nanoTime() - loopStartNs) / 1_000_000
            val sleepMillis = (frameDurationMillis - elapsedMillis).coerceAtLeast(0L)
            delay(sleepMillis)
        }
        handlePlaybackStop()
    }

    private fun seekInternal(frameIndex: Int) {
        val ctx = context ?: return
        val clamped = frameIndex.coerceIn(0, ctx.frameCount - 1)
        if (!ctx.hasAudio) return
        val timestampUs = ctx.frameTimestamps.getOrElse(clamped) { 0L }
        audioController.seekToUs(timestampUs)
    }

    private fun stopPlaybackJob() {
        playbackJob?.cancel()
        playbackJob = null
    }

    private fun handlePlaybackStop(cancelJob: Boolean = false) {
        val wasPlaying = isPlaying
        isPlaying = false
        if (cancelJob) {
            stopPlaybackJob()
        }
        audioController.pause()
        if (wasPlaying || cancelJob) {
            onPlaybackStopped()
        }
    }
}
