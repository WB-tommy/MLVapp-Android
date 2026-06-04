package fm.magiclantern.forum.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class MlvFileRolesTest {
    @Test
    fun parsesSupportedMlvRoles() {
        assertEquals(MlvFileRole.BaseMlv, mlvFileRole("clip.MLV"))
        assertEquals(MlvFileRole.Chunk(0), mlvFileRole("clip.M00"))
        assertEquals(MlvFileRole.Chunk(9), mlvFileRole("clip.M09"))
        assertEquals(MlvFileRole.Chunk(10), mlvFileRole("clip.M10"))
        assertEquals(MlvFileRole.Mcraw, mlvFileRole("clip.mcraw"))
    }

    @Test
    fun rejectsUnsupportedExtensions() {
        assertEquals(MlvFileRole.Unsupported, mlvFileRole("clip.mov"))
        assertEquals(MlvFileRole.Unsupported, mlvFileRole("clip.M1"))
        assertEquals(MlvFileRole.Unsupported, mlvFileRole("clip.M100"))
        assertEquals(MlvFileRole.Unsupported, mlvFileRole("clip"))
    }

    @Test
    fun sortsBaseBeforeNumberedChunks() {
        val sorted = listOf(
            "clip.M10",
            "clip.M01",
            "clip.MLV",
            "clip.M09",
            "clip.M00"
        ).sortedByMlvFileRole { it }

        assertEquals(
            listOf("clip.MLV", "clip.M00", "clip.M01", "clip.M09", "clip.M10"),
            sorted
        )
    }

    @Test
    fun dedupesDuplicateBaseAndChunkRoles() {
        val sorted = listOf(
            "clip.M01",
            "duplicate.M01",
            "clip.MLV",
            "duplicate.MLV",
            "clip.M00"
        ).dedupeAndSortByMlvFileRole { it }

        assertEquals(
            listOf("clip.MLV", "clip.M00", "clip.M01"),
            sorted
        )
    }

    @Test
    fun extractsSharedStemForBaseAndChunks() {
        assertEquals("m17-2045", mlvClipStem("M17-2045.MLV"))
        assertEquals("m17-2045", mlvClipStem("M17-2045.M00"))
        assertEquals("m17-2045", mlvClipStem("M17-2045.M10"))
    }
}
