package fm.forum.mlvapp.clips

import android.net.Uri
import fm.forum.mlvapp.data.Clip
import fm.forum.mlvapp.data.ClipChunk
import fm.forum.mlvapp.data.ClipLoadResult
import fm.forum.mlvapp.data.ClipRepository
import fm.forum.mlvapp.data.FocusPixelRequirement
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClipViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: ClipViewModel
    private lateinit var fakeRepository: FakeClipRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeClipRepository()
        viewModel = ClipViewModel(
            repository = fakeRepository,
            totalMemory = 1024L,
            cpuCores = 4
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `onFilesPicked with valid URI adds clip to state`() = runTest {
        val uri = mockk<Uri>()
        val chunk = createTestChunk(uri, 1L)
        fakeRepository.prepareChunkResult = chunk

        viewModel.onFilesPicked(listOf(uri))

        val clips = viewModel.uiState.first().clips
        assertEquals(1, clips.size)
        assertEquals(1L, clips.first().guid)
    }

    @Test
    fun `onFilesPicked with invalid URI emits preparation failed event`() = runTest {
        val uri = mockk<Uri>()
        fakeRepository.prepareChunkResult = null // Simulate failure

        val events = mutableListOf<ClipEvent>()
        val job = launch { viewModel.events.collect { events.add(it) } }

        viewModel.onFilesPicked(listOf(uri, uri)) // Pick 2 files, both fail

        // Advance past the launch call
        testScheduler.advanceUntilIdle()

        val event = events.first()
        assertTrue(event is ClipEvent.ClipPreparationFailed)
        assertEquals(2, (event as ClipEvent.ClipPreparationFailed).failedCount)
        assertEquals(2, event.totalCount)

        job.cancel()
    }

    @Test
    fun `onClipSelected successfully loads clip and updates state`() = runTest {
        // 1. Add a preview clip to the initial state
        val uri = mockk<Uri>()
        val chunk = createTestChunk(uri, guid = 1L)
        fakeRepository.prepareChunkResult = chunk
        viewModel.onFilesPicked(listOf(uri))

        // 2. Prepare the full clip result for the repository
        val fullClip = createFullClip(guid = 1L)
        fakeRepository.loadClipResult = ClipLoadResult(fullClip, null)

        // 3. Select the clip
        viewModel.onClipSelected(1L)
        testScheduler.advanceUntilIdle()

        // 4. Assert final state
        val state = viewModel.uiState.first()
        assertFalse(state.isClipLoading)
        assertEquals(fullClip, state.activeClip)
        assertEquals(1, state.clips.size)
        assertEquals("Full Clip", state.clips.first().cameraName) // Check that the clip in the list was also updated
    }

    @Test
    fun `onClipSelected with failure emits load failed event`() = runTest {
        // 1. Add a preview clip
        val uri = mockk<Uri>()
        val chunk = createTestChunk(uri, guid = 1L)
        fakeRepository.prepareChunkResult = chunk
        viewModel.onFilesPicked(listOf(uri))

        // 2. Simulate repository failure
        val exception = RuntimeException("Failed to load")
        fakeRepository.loadClipException = exception

        val events = mutableListOf<ClipEvent>()
        val job = launch { viewModel.events.collect { events.add(it) } }

        // 3. Select the clip
        viewModel.onClipSelected(1L)
        testScheduler.advanceUntilIdle()

        // 4. Assert state and event
        assertFalse(viewModel.uiState.first().isClipLoading)
        val event = events.first()
        assertTrue(event is ClipEvent.LoadFailed)
        assertEquals(1L, (event as ClipEvent.LoadFailed).clipGuid)

        job.cancel()
    }

    // --- Test Doubles ---

    private fun createTestChunk(uri: Uri, guid: Long) = ClipChunk(
        uri = uri, fileName = "test.mlv", guid = guid, width = 1920, height = 1080, thumbnail = mockk()
    )

    private fun createFullClip(guid: Long) = Clip(
        guid = guid,
        uris = emptyList(),
        fileNames = emptyList(),
        displayName = "test.mlv",
        width = 1920,
        height = 1080,
        thumbnail = mockk(),
        mappPath = "",
        nativeHandle = 123L,
        cameraName = "Full Clip"
    )

    class FakeClipRepository : ClipRepository(mockk(relaxed = true)) {
        var prepareChunkResult: ClipChunk? = null
        var loadClipResult: ClipLoadResult? = null
        var loadClipException: Exception? = null

        override suspend fun prepareClipChunk(uri: Uri, totalMemory: Long, cpuCores: Int): ClipChunk? {
            return prepareChunkResult
        }

        override suspend fun loadClip(clip: Clip, totalMemory: Long, cpuCores: Int): ClipLoadResult {
            loadClipException?.let { throw it }
            return loadClipResult ?: throw IllegalStateException("loadClipResult not set for test")
        }

        override suspend fun downloadFocusPixelMap(fileName: String): Boolean = true

        override fun refreshFocusPixelMap(handle: Long) {}
    }
}
