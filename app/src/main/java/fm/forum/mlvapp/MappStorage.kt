package fm.forum.mlvapp

import android.content.Context
import java.io.File
import java.util.Locale

object MappStorage {
    private const val ROOT_DIR = "mapp"

    fun prepareClipPath(context: Context, guid: Long, displayName: String): String {
        val root = File(context.filesDir, ROOT_DIR).apply { if (!exists()) mkdirs() }
        val clipFolderName = when {
            guid != 0L -> guid.toString(16)
            else -> sanitize(displayName).ifEmpty { "clip" }
        }.take(80)
        val clipDir = File(root, clipFolderName.ifEmpty { "clip" }).apply {
            if (!exists()) mkdirs()
        }

        val fileName = buildFileName(guid, displayName)
        return File(clipDir, fileName).absolutePath
    }

    private fun buildFileName(guid: Long, displayName: String): String {
        val trimmed = displayName.trim().ifEmpty { "clip" }
        val base = trimmed.substringBeforeLast('.', trimmed)
        val ext = trimmed.substringAfterLast('.', "")
        val sanitizedBase = sanitize(base).ifEmpty { (if (guid != 0L) guid.toString(16) else "clip") }
        val safeBase = sanitizedBase.take(80)
        val uppercaseExt = ext.takeIf { it.isNotBlank() }?.uppercase(Locale.US) ?: "MLV"
        return "$safeBase.$uppercaseExt"
    }

    private fun sanitize(input: String): String =
        input.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
