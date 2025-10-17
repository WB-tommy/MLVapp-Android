package fm.forum.mlvapp.videoPlayer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEngineTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var engine: PlaybackEngine

    // Fakes to observe the engine's output
    private val updatedFrames = mutableListOf<Int>()
    private var playbackStoppedCallCount = 0
    private var currentFrame = 0

    @Before
    fun setUp() {
        updatedFrames.clear()
        playbackStoppedCallCount = 0
        currentFrame = 0

        engine = PlaybackEngine(
            scope = testScope,
            frameUpdater = { updatedFrames.add(it) },
            onPlaybackStopped = { playbackStoppedCallCount++ },
            currentFrameProvider = { currentFrame },
            averageProcessingUsProvider = { 0L }
        )
    }

    @Test
    fun `play() in drop-frame mode advances frames over time`() = testScope.runTest {
        val context = createTestContext(frameCount = 100, fps = 30f)
        engine.updateContext(context)
        engine.setDropFrameMode(true)

        engine.play()

        // Initial frame should not be updated immediately
        assertTrue(updatedFrames.isEmpty())

        // Advance time by a little over 3 frame durations (33.3ms * 3 = 100ms)
        advanceTimeBy(101_000) // microseconds
        currentFrame = updatedFrames.lastOrNull() ?: 0

        // Should have advanced roughly 3 frames
        assertTrue(updatedFrames.size in 2..4)
        assertEquals(3, currentFrame)

        engine.pause()
        val frameCountAfterPause = updatedFrames.size

        // Advance time again, no new frames should be produced
        advanceTimeBy(100_000)
        assertEquals(frameCountAfterPause, updatedFrames.size)
    }

    @Test
    fun `playback stops automatically at the last frame`() = testScope.runTest {
        val context = createTestContext(frameCount = 5, fps = 30f)
        engine.updateContext(context)
        engine.setDropFrameMode(true)

        engine.play()

        // Advance time enough to play all frames
        advanceTimeBy(500_000)
        currentFrame = updatedFrames.lastOrNull() ?: 0

        assertEquals(4, updatedFrames.last())
        assertEquals(1, playbackStoppedCallCount)
    }

    @Test
    fun `advanceFrameSequential in single-step mode advances one frame`() {
        val context = createTestContext(frameCount = 10)
        engine.updateContext(context)
        engine.setDropFrameMode(false)

        engine.play() // Enables sequential mode

        currentFrame = 0
        engine.advanceFrameSequential()
        assertEquals(1, updatedFrames.size)
        assertEquals(1, updatedFrames.last())

        currentFrame = 1
        engine.advanceFrameSequential()
        assertEquals(2, updatedFrames.size)
        assertEquals(2, updatedFrames.last())
    }

    @Test
    fun `onSeek moves audio and affects next playback position`() = testScope.runTest {
        val context = createTestContext(frameCount = 100, fps = 30f, hasAudio = true)
        engine.updateContext(context)
        engine.setDropFrameMode(true)

        // Seek while paused
        engine.onSeek(50)
        currentFrame = 50

        engine.play()
        advanceTimeBy(34_000) // Just over one frame duration
        currentFrame = updatedFrames.lastOrNull() ?: 50

        // The next frame should be 51
        assertTrue(updatedFrames.isNotEmpty())
        assertEquals(51, updatedFrames.last())
    }

    @Test
    fun `engine does not play if context is null or has no frames`() = testScope.runTest {
        // Null context
        engine.updateContext(null)
        engine.play()
        advanceTimeBy(100_000)
        assertTrue(updatedFrames.isEmpty())
        assertEquals(0, playbackStoppedCallCount)

        // Zero frames
        val context = createTestContext(frameCount = 0)
        engine.updateContext(context)
        engine.play()
        advanceTimeBy(100_000)
        assertTrue(updatedFrames.isEmpty())
        assertEquals(0, playbackStoppedCallCount)
    }

    private fun createTestContext(
        frameCount: Int,
        fps: Float = 30f,
        hasAudio: Boolean = false
    ): PlaybackContext {
        val frameDurationUs = if (fps > 0) (1_000_000 / fps).toLong() else 0L
        val timestamps = LongArray(frameCount) { it * frameDurationUs }
        return PlaybackContext(
            clipHandle = 1L,
            frameCount = frameCount,
            fps = fps,
            frameTimestamps = timestamps,
            hasAudio = hasAudio,
            audioSampleRate = 48000,
            audioChannels = 2,
            audioBytesPerSample = 2,
            audioBufferSize = 1024
        )
    }
}
