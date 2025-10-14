package fm.forum.mlvapp.videoPlayer

import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import android.util.Log
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun VideoPlayerScreen(
    viewModel: VideoViewModel,
    cpuCores: Int,
) {
    val clipGUID by viewModel.clipGUID.collectAsState()
    val clipHandle by viewModel.clipHandle.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isDrawing by viewModel.isDrawing.collectAsState()
    val currentFrame by viewModel.currentFrame.collectAsState()
    val totalFrames by viewModel.totalFrames.collectAsState()
    val fps by viewModel.fps.collectAsState()
    val isDropFrameMode by viewModel.isDropFrameMode.collectAsState()
    val frameTimestamps by viewModel.frameTimestamps.collectAsState()
    val hasAudio by viewModel.hasAudio.collectAsState()
    val audioSampleRate by viewModel.audioSampleRate.collectAsState()
    val audioChannels by viewModel.audioChannels.collectAsState()
    val audioBytesPerSample by viewModel.audioBytesPerSample.collectAsState()
    val audioBufferSize by viewModel.audioBufferSize.collectAsState()
    val averageProcessingUs by viewModel.averageProcessingUs.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val audioController = remember(viewModel, coroutineScope) {
        AudioPlaybackController(
            scope = coroutineScope,
            onPlaybackCompleted = { viewModel.stopPlayback() }
        )
    }

    DisposableEffect(Unit) {
        onDispose { audioController.stop() }
    }

    LaunchedEffect(
        clipHandle,
        hasAudio,
        audioSampleRate,
        audioChannels,
        audioBytesPerSample,
        audioBufferSize,
        frameTimestamps
    ) {
        if (
            hasAudio &&
            clipHandle != 0L &&
            audioSampleRate > 0 &&
            audioChannels > 0 &&
            audioBytesPerSample > 0 &&
            audioBufferSize > 0L &&
            frameTimestamps.isNotEmpty()
        ) {
            audioController.prepare(
                clipHandle = clipHandle,
                sampleRate = audioSampleRate,
                channelCount = audioChannels,
                bytesPerSample = audioBytesPerSample,
                audioBufferBytes = audioBufferSize
            )
            val startFrame = currentFrame.coerceIn(0, frameTimestamps.size - 1)
            val startUs = frameTimestamps.getOrElse(startFrame) { 0L }
            audioController.seekToUs(startUs)
        } else {
            audioController.stop()
        }
    }

    LaunchedEffect(currentFrame, isPlaying, hasAudio, clipHandle, frameTimestamps) {
        if (!hasAudio || clipHandle == 0L || frameTimestamps.isEmpty()) {
            return@LaunchedEffect
        }
        if (isPlaying) {
            return@LaunchedEffect
        }
        val targetFrame = currentFrame.coerceIn(0, frameTimestamps.size - 1)
        val targetUs = frameTimestamps.getOrElse(targetFrame) { 0L }
        audioController.seekToUs(targetUs)
    }

    LaunchedEffect(isPlaying, isDropFrameMode, hasAudio, clipHandle, frameTimestamps) {
        if (!hasAudio || clipHandle == 0L || frameTimestamps.isEmpty()) {
            audioController.pause()
            return@LaunchedEffect
        }
        if (!isDropFrameMode) {
            audioController.pause()
            return@LaunchedEffect
        }
        if (isPlaying) {
            val targetFrame = viewModel.currentFrame.value.coerceIn(0, frameTimestamps.size - 1)
            val targetUs = frameTimestamps.getOrElse(targetFrame) { 0L }
            audioController.seekToUs(targetUs)
            audioController.play()
        } else {
            audioController.pause()
        }
    }

    // Effect for fast, frame-by-frame playback (isDropFrameMode == false)
    LaunchedEffect(isPlaying, isDropFrameMode, isDrawing, clipHandle) {
        if (isPlaying && !isDropFrameMode && !isDrawing) {
            if (currentFrame < totalFrames) {
                viewModel.changeDrawingStatus(true)
                val nextFrame = currentFrame + 1
                viewModel.setCurrentFrame(nextFrame)
            }
        }
    }

    // Effect for drop-frame playback. Use timestamp loop, fall back to FPS pacing if it stalls.
    LaunchedEffect(isPlaying, isDropFrameMode, clipHandle, frameTimestamps, hasAudio) {
        if (!isPlaying || !isDropFrameMode || clipHandle == 0L) {
            return@LaunchedEffect
        }

        // For mcraw, ensure audio is playing so we can seek it.
        if (viewModel.isMcraw.value && hasAudio) {
            audioController.play()
        }

        val timestamps = frameTimestamps
        if (timestamps.isEmpty()) {
            runFpsFallbackLoop(viewModel, totalFrames, fps)
            return@LaunchedEffect
        }

        val frameCount = timestamps.size
        if (frameCount <= 0) {
            return@LaunchedEffect
        }

        var lastAppliedFrame = viewModel.currentFrame.value.coerceIn(0, frameCount - 1)
        var anchorTimestampUs = timestamps.getOrElse(lastAppliedFrame) { 0L }
        var anchorClockNs = System.nanoTime()
        var debugLoopCount = 0
        var spinCount = 0
        val expectedFrameDurationUs = if (fps > 0f) (1_000_000f / fps).toLong().coerceAtLeast(1L) else 0L
        var lastAudioClockUs = anchorTimestampUs
        var stagnantAudioIterations = 0
        var usingFallback = false

        while (isActive && viewModel.isPlaying.value && viewModel.isDropFrameMode.value && !usingFallback) {
            val usingAudioClock = hasAudio && audioController.isRunning() && !viewModel.isMcraw.value
            val externalFrame = viewModel.currentFrame.value.coerceIn(0, frameCount - 1)
            if (!usingAudioClock && externalFrame != lastAppliedFrame) {
                lastAppliedFrame = externalFrame
                anchorTimestampUs = timestamps.getOrElse(lastAppliedFrame) { 0L }
                anchorClockNs = System.nanoTime()
            } else if (usingAudioClock && externalFrame != lastAppliedFrame) {
                lastAppliedFrame = externalFrame
                anchorTimestampUs = timestamps.getOrElse(lastAppliedFrame) { 0L }
            }

            val currentTimeUs = if (usingAudioClock) {
                audioController.playbackPositionUs()
            } else {
                val elapsedUs = (System.nanoTime() - anchorClockNs) / 1000L
                anchorTimestampUs + elapsedUs
            }

            var candidateFrame = lastAppliedFrame
            while (
                candidateFrame + 1 < frameCount &&
                timestamps[candidateFrame + 1] <= currentTimeUs
            ) {
                candidateFrame++
            }

            if (candidateFrame != lastAppliedFrame) {
                lastAppliedFrame = candidateFrame
                viewModel.setCurrentFrame(candidateFrame)

                // If using video-master clock for mcraw, sync audio to video.
                if (viewModel.isMcraw.value && hasAudio) {
                    val syncTimeUs = timestamps.getOrElse(candidateFrame) { 0L }
                    audioController.syncPosition(syncTimeUs)
                }
                spinCount = 0
                stagnantAudioIterations = 0
                if (candidateFrame >= frameCount - 1) {
                    viewModel.stopPlayback()
                    break
                }
            } else {
                spinCount++
            }

            val nextTimestamp = timestamps.getOrNull(lastAppliedFrame + 1)
            if (nextTimestamp == null) {
                viewModel.stopPlayback()
                break
            }

            if (!usingAudioClock) {
                val totalSpinTimeUs = (System.nanoTime() - anchorClockNs) / 1000L
                val nextTimestamp = timestamps.getOrNull(lastAppliedFrame + 1) ?: Long.MAX_VALUE
                val deltaToNext = nextTimestamp - anchorTimestampUs
                val tweening = expectedFrameDurationUs <= 0L || deltaToNext <= expectedFrameDurationUs * 4

                if (totalSpinTimeUs > 200_000 || spinCount >= 20 || tweening) {
                    Log.w(
                        "DropFrameLoop",
                        "Timestamp loop stalled at frame=$lastAppliedFrame (spinCount=$spinCount, elapsedUs=$totalSpinTimeUs, deltaToNext=$deltaToNext); switching to FPS fallback"
                    )
                    usingFallback = true
                    break
                }
            } else {
                if (expectedFrameDurationUs > 0L) {
                    if (currentTimeUs <= lastAudioClockUs + expectedFrameDurationUs / 4) {
                        stagnantAudioIterations++
                    } else {
                        stagnantAudioIterations = 0
                    }
                    lastAudioClockUs = max(currentTimeUs, lastAudioClockUs)
                    if (stagnantAudioIterations >= 20) {
                        Log.w(
                            "DropFrameLoop",
                            "Audio clock stalled at frame=$lastAppliedFrame (audioUs=$currentTimeUs); switching to FPS fallback"
                        )
                        audioController.pause()
                        usingFallback = true
                        break
                    }
                }
            }



            if (usingAudioClock) {
                delay(4L)
            } else {
                val processingBudgetUs = averageProcessingUs.coerceAtLeast(0L)
                val waitUs = (nextTimestamp - currentTimeUs - processingBudgetUs).coerceAtLeast(0L)
                val waitMillis = (waitUs / 1000L).coerceAtLeast(0L)
                delay(waitMillis.coerceAtMost(8L))
            }
        }

        if (isActive && viewModel.isPlaying.value && viewModel.isDropFrameMode.value && usingFallback) {
            runFpsFallbackLoop(viewModel, totalFrames, fps)
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(16f / 9f)
            .background(Color.Black)
    ) {
        key(clipGUID) {
            if (clipHandle != 0L) {
                AndroidView(
                    factory = { context ->
                        GLSurfaceView(context).apply {
                            setEGLContextClientVersion(3)
                            setZOrderOnTop(true)
                            holder.setFormat(PixelFormat.TRANSLUCENT)
                            setRenderer(MlvRenderer(cpuCores, viewModel))
                            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                        }
                    },
                    update = { glSurfaceView ->
                        // When recomposition happens (because currentFrame changed),
                        // we tell the GLSurfaceView that it needs to redraw.
                        currentFrame.let { // This just ensures we are using the state value
                            glSurfaceView.requestRender()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private suspend fun runFpsFallbackLoop(
    viewModel: VideoViewModel,
    totalFrames: Int,
    fps: Float
) {
    val frameDurationMillis = if (fps > 0f) (1000 / fps).toLong().coerceAtLeast(1L) else 42L
    Log.i("DropFrameLoop", "Running FPS fallback loop (fps=$fps)")
    while (
        viewModel.isPlaying.value &&
        viewModel.isDropFrameMode.value &&
        viewModel.currentFrame.value < totalFrames - 1
    ) {
        val loopStartNs = System.nanoTime()
        val nextFrame = (viewModel.currentFrame.value + 1).coerceAtMost(totalFrames - 1)
        viewModel.setCurrentFrame(nextFrame)
        if (nextFrame >= totalFrames - 1) {
            viewModel.stopPlayback()
            break
        }
        val elapsedMillis = (System.nanoTime() - loopStartNs) / 1_000_000
        val sleepMillis = (frameDurationMillis - elapsedMillis).coerceAtLeast(0L)
        delay(sleepMillis)
    }
}
