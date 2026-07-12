package fm.magiclantern.forum.features.export

import kotlinx.coroutines.CancellationException

internal fun Throwable.exportCancellationCause(): CancellationException? {
    var current: Throwable? = this
    while (current != null) {
        if (current is CancellationException) return current
        current = current.cause
    }
    return null
}

internal fun Throwable.exportFailureMessage(default: String): String {
    var current: Throwable? = this
    while (current != null) {
        val message = current.message
        if (!message.isNullOrBlank()) return message
        current = current.cause
    }
    return default
}
