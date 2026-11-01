package com.smartruralbus.driver.buffer

/**
 * The decisions of the offline buffer, kept free of Android classes so they can be unit-tested on a plain JVM.
 */
object SyncRules {
    /** Points per upload. The server accepts up to 100 by default; a smaller server limit is learned by halving. */
    const val BATCH_SIZE = 50

    /** About 14 hours of GPS at one point per 10 s. When full, the oldest points go first, and the count is shown. */
    const val MAX_PENDING = 5000

    /** Waiting time after the n-th failed upload in a row: 2, 4, 8, 16, then 30 seconds. */
    fun backoffMillis(failures: Int): Long {
        val step = (failures - 1).coerceIn(0, 4)
        return minOf(30_000L, 2_000L shl step)
    }

    /** How many of the oldest points to delete so that at most MAX_PENDING remain. */
    fun overflow(count: Int, max: Int = MAX_PENDING): Int = (count - max).coerceAtLeast(0)

    /** After the server refuses a whole batch, try a smaller one (never below 1). */
    fun smallerBatch(size: Int): Int = (size / 2).coerceAtLeast(1)

    /**
     * Which saved points may be deleted after an upload. `statuses[i]` is the server's answer for `ids[i]`:
     * "saved" and "duplicate" mean the server has the point, "rejected" means it never will, so all three
     * are removed. Returns null when the answer does not match the request: then nothing is deleted.
     */
    fun idsToRemove(ids: List<Long>, statuses: List<String?>): List<Long>? {
        if (ids.size != statuses.size) return null
        if (statuses.any { it !in FINAL_STATUSES }) return null
        return ids
    }

    private val FINAL_STATUSES = setOf("saved", "duplicate", "rejected")
}
