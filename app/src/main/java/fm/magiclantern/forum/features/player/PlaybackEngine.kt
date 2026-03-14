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
    val audioBufferSize: Long,
    // Cut bounds (0-based, already resolved from 1-based cut marks)
    val effectiveStartFrame: Int = 0,
    val effectiveEndFrame: Int = -1  // -1 means use frameCount
) {
    /** The last valid frame index for playback (inclusive) */
    val lastPlayableFrame: Int get() = 
        (if (effectiveEndFrame >= 0) effectiveEndFrame else frameCount - 1).coerceAtMost(frameCount - 1)
    /** The first valid frame index for playback */
    val firstPlayableFrame: Int get() = effectiveStartFrame.coerceIn(0, lastPlayableFrame)
}

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

    /**
     * Update only the cut bounds without stopping playback or re-initialising audio.
     * Safe to call while playing — the new bounds take effect on the next loop iteration.
     */
    fun updateCutBounds(effectiveStart: Int, effectiveEnd: Int) {
        val old = context ?: return
        context = old.copy(
            effectiveStartFrame = effectiveStart,
            effectiveEndFrame = effectiveEnd
        )
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
        
        // Snap playhead into the cut range if it's currently outside
        val current = currentFrameProvider()
        if (current < ctx.firstPlayableFrame) {
            frameUpdater(ctx.firstPlayableFrame)
        } else if (current > ctx.lastPlayableFrame) {
            frameUpdater(ctx.lastPlayableFrame)
        }
        
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
        // If current frame is below the cut range, jump to the start of it
        val effective = if (current < ctx.firstPlayableFrame) ctx.firstPlayableFrame - 1 else current
        val nextFrame = (effective + 1).coerceAtMost(ctx.lastPlayableFrame)
        if (nextFrame == current && current >= ctx.firstPlayableFrame) {
            handlePlaybackStop()
            return
        }
        frameUpdater(nextFrame)
        if (ctx.hasAudio) {
            val timestampUs = ctx.frameTimestamps.getOrElse(nextFrame) { 0L }
            audioController.seekToUs(timestampUs)
        }
        if (nextFrame >= ctx.lastPlayableFrame) {
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
            // Read live bounds from context (may be updated by updateCutBounds)
            var liveCtx = context ?: return@launch
            var lastAppliedFrame = currentFrameProvider().coerceIn(liveCtx.firstPlayableFrame, liveCtx.lastPlayableFrame)
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
                // Re-read live bounds each iteration so updateCutBounds() takes effect
                liveCtx = context ?: break
                val firstPlayable = liveCtx.firstPlayableFrame
                val lastPlayable = liveCtx.lastPlayableFrame

                val externalFrame = currentFrameProvider().coerceIn(firstPlayable, lastPlayable)
                if (externalFrame != lastAppliedFrame) {
                    lastAppliedFrame = externalFrame
                    // Push the snapped frame to UI so it doesn't stay on an out-of-range frame
                    frameUpdater(externalFrame)
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
                    candidateFrame < lastPlayable &&
                    timestamps[candidateFrame + 1] <= currentTimeUs
                ) {
                    candidateFrame++
                }

                if (candidateFrame != lastAppliedFrame) {
                    lastAppliedFrame = candidateFrame
                    frameUpdater(candidateFrame)
                    spinCount = 0
                    if (candidateFrame >= lastPlayable) {
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
                runFpsFallbackLoop()
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

    private suspend fun runFpsFallbackLoop() {
        val initCtx = context ?: return
        val frameDurationMillis =
            if (initCtx.fps > 0f) (1000 / initCtx.fps).toLong().coerceAtLeast(1L) else 42L
        while (
            isPlaying &&
            dropFrameMode &&
            coroutineContext.isActive
        ) {
            // Re-read live bounds each iteration
            val liveCtx = context ?: break
            val firstPlayable = liveCtx.firstPlayableFrame
            val lastPlayable = liveCtx.lastPlayableFrame
            val current = currentFrameProvider()
            if (current >= lastPlayable) break

            // If the current frame is below the cut range, snap forward
            if (current < firstPlayable) {
                frameUpdater(firstPlayable)
                // Re-seek audio to match the snapped video position
                if (initCtx.hasAudio) {
                    val timestampUs = initCtx.frameTimestamps.getOrElse(firstPlayable) { 0L }
                    audioController.seekToUs(timestampUs)
                }
                continue
            }

            val loopStartNs = System.nanoTime()
            val nextFrame =
                (current + 1).coerceIn(firstPlayable, lastPlayable)
            frameUpdater(nextFrame)
            // No syncPosition() here — audio streams continuously from the seekToUs() position.
            if (nextFrame >= lastPlayable) {
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
