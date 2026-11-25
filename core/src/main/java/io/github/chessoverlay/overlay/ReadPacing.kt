package io.github.chessoverlay.overlay

/**
 * When auto mode may read: after a misread or no board it tries again
 * soon, then less often (RETRY_MS, doubled per miss up to 5 times), and a
 * good read ends the pause. Without a board it looks every FIND_MS.
 */
class ReadPacing(private val retryMs: Long = RETRY_MS, private val findMs: Long = FIND_MS) {
    /** When the last read finished (0 = none yet). */
    var lastRead = 0L
        private set
    var misses = 0
        private set
    private var retryAt = 0L

    fun reset() { lastRead = 0L; retryAt = 0L; misses = 0 }

    /** Not in a pause after misses. */
    fun mayRead(now: Long) = now >= retryAt

    /** No board known: time to look for one. */
    fun mayFind(now: Long) = now - lastRead >= findMs

    /** A read ended with `r` (in auto mode: a miss starts or lengthens the pause). */
    fun done(now: Long, r: Look, auto: Boolean) {
        lastRead = now
        if (r == Look.NEW || r == Look.SAME) {
            misses = 0; retryAt = 0L
        } else if (auto) {
            misses = minOf(misses + 1, MAX_MISSES)
            retryAt = now + (retryMs shl (misses - 1))
        }
    }

    companion object {
        const val RETRY_MS = 500L       // after a misread: 0.5, 1, 2, 4, 8 s
        const val FIND_MS = 1000L
        const val MAX_MISSES = 5
    }
}
