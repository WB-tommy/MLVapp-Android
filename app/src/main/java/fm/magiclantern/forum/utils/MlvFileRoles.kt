package fm.magiclantern.forum.utils

import java.util.Locale

sealed interface MlvFileRole {
    data object BaseMlv : MlvFileRole
    data class Chunk(val index: Int) : MlvFileRole
    data object Mcraw : MlvFileRole
    data object Unsupported : MlvFileRole
}

fun mlvFileRole(fileName: String): MlvFileRole {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        extension == "mlv" -> MlvFileRole.BaseMlv
        extension == "mcraw" -> MlvFileRole.Mcraw
        extension.length == 3 &&
                extension[0] == 'm' &&
                extension[1].isDigit() &&
                extension[2].isDigit() -> {
            MlvFileRole.Chunk(extension.substring(1).toInt())
        }
        else -> MlvFileRole.Unsupported
    }
}

fun mlvClipStem(fileName: String): String =
    fileName.substringBeforeLast('.', fileName).lowercase(Locale.ROOT)

fun MlvFileRole.isImportableClipFile(): Boolean =
    this != MlvFileRole.Unsupported

fun MlvFileRole.semanticSortKey(): Int = when (this) {
    MlvFileRole.BaseMlv -> 0
    is MlvFileRole.Chunk -> 1 + index
    MlvFileRole.Mcraw -> Int.MAX_VALUE - 1
    MlvFileRole.Unsupported -> Int.MAX_VALUE
}

fun MlvFileRole.semanticDedupeKey(fileName: String): String = when (this) {
    MlvFileRole.BaseMlv -> "mlv-base"
    is MlvFileRole.Chunk -> "mlv-chunk-$index"
    MlvFileRole.Mcraw -> "mcraw-${fileName.lowercase(Locale.ROOT)}"
    MlvFileRole.Unsupported -> "unsupported-${fileName.lowercase(Locale.ROOT)}"
}

fun <T> Iterable<T>.sortedByMlvFileRole(fileName: (T) -> String): List<T> =
    sortedWith(
        compareBy<T> { mlvFileRole(fileName(it)).semanticSortKey() }
            .thenBy { fileName(it).lowercase(Locale.ROOT) }
    )

fun <T> Iterable<T>.dedupeAndSortByMlvFileRole(fileName: (T) -> String): List<T> =
    distinctBy { mlvFileRole(fileName(it)).semanticDedupeKey(fileName(it)) }
        .sortedByMlvFileRole(fileName)
