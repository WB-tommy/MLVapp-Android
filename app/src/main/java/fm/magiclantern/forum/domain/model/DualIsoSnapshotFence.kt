package fm.magiclantern.forum.domain.model

/**
 * Fences a presented-frame snapshot against asynchronous receipt changes.
 * A token is exportable only after the corresponding native update completed;
 * an older completion can never release a newer request.
 */
class DualIsoSnapshotFence {
    private var requestedGeneration = 0L
    private var completedGeneration = -1L

    @Synchronized
    fun beginUpdate(): Long {
        requestedGeneration++
        return requestedGeneration
    }

    @Synchronized
    fun completeUpdate(token: Long) {
        if (token == requestedGeneration) {
            completedGeneration = token
        }
    }

    @Synchronized
    fun readyToken(): Long? = requestedGeneration.takeIf {
        completedGeneration == requestedGeneration
    }

    @Synchronized
    fun isReady(token: Long): Boolean =
        token == requestedGeneration && completedGeneration == requestedGeneration

    /** Run a small snapshot commit atomically with respect to beginUpdate(). */
    @Synchronized
    fun commitIfReady(token: Long, commit: () -> Unit): Boolean {
        if (token != requestedGeneration || completedGeneration != requestedGeneration) {
            return false
        }
        commit()
        return true
    }
}
