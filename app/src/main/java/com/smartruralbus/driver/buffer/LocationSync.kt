package com.smartruralbus.driver.buffer

import com.smartruralbus.driver.data.ActiveTrip
import com.smartruralbus.driver.data.ApiResult
import com.smartruralbus.driver.data.FailureKind
import com.smartruralbus.driver.data.GpsFix
import com.smartruralbus.driver.data.Repository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What one upload attempt achieved. */
sealed interface FlushOutcome {
    /** Nothing waits to be uploaded. */
    data object Empty : FlushOutcome

    /** Progress was made (or the batch was made smaller); call again straight away if `remaining` > 0. */
    data class Progress(val saved: Int, val duplicates: Int, val rejected: Int, val remaining: Int) : FlushOutcome

    /** No connection or a server hiccup: the points stay saved, try again later. */
    data class Retry(val kind: FailureKind, val message: String) : FlushOutcome

    /** Uploading must stop: the trip ended on the server, or the login / account is no longer valid. */
    data class Stop(val kind: FailureKind, val message: String) : FlushOutcome
}

/** Result of trying to upload everything before the trip is ended. */
sealed interface DrainResult {
    data object Complete : DrainResult
    data class Incomplete(val remaining: Int, val message: String) : DrainResult
    data class Stop(val kind: FailureKind, val message: String) : DrainResult
}

/**
 * The phone-side queue of GPS points. A point is saved here before any upload is attempted and is deleted
 * only after the server has answered for it, so a lost connection, a crash or a restart cannot lose it.
 *
 * The tracking service and the END TRIP button both upload through this one object; the mutex makes sure two
 * uploads of the same points never run at once.
 */
class LocationSync(
    private val dao: PendingLocationDao,
    private val repository: Repository,
) {
    private val mutex = Mutex()
    private var batchSize = SyncRules.BATCH_SIZE

    /** Saves a fix. Returns how many of the oldest points had to be dropped because the buffer was full. */
    suspend fun add(trip: ActiveTrip, fix: GpsFix): Int {
        dao.insert(
            PendingLocation(
                tripId = trip.tripId, busId = trip.busId,
                latitude = fix.latitude, longitude = fix.longitude,
                speedMps = fix.speedMps, accuracyM = fix.accuracyM, timeMillis = fix.timeMillis,
            )
        )
        val over = SyncRules.overflow(dao.count(trip.tripId))
        if (over > 0) dao.deleteOldest(trip.tripId, over)
        return over
    }

    suspend fun pending(tripId: Long): Int = dao.count(tripId)

    /** Throws away the saved points of a trip (after the server completed it, or after the driver chose to end). */
    suspend fun discardTrip(tripId: Long): Int = mutex.withLock {
        val n = dao.count(tripId)
        dao.deleteForTrip(tripId)
        n
    }

    /** Sends the newest waiting points (one batch). */
    suspend fun flushOnce(trip: ActiveTrip): FlushOutcome = mutex.withLock {
        val rows = dao.newest(trip.tripId, batchSize)
        if (rows.isEmpty()) return@withLock FlushOutcome.Empty

        when (val result = repository.sendBatch(trip, rows)) {
            is ApiResult.Ok -> {
                val statuses = result.value
                val remove = SyncRules.idsToRemove(rows.map { it.id }, statuses)
                    ?: return@withLock FlushOutcome.Retry(FailureKind.TEMPORARY, "The server sent an unexpected reply. Try again.")
                dao.deleteByIds(remove)
                batchSize = SyncRules.BATCH_SIZE
                FlushOutcome.Progress(
                    saved = statuses.count { it == "saved" },
                    duplicates = statuses.count { it == "duplicate" },
                    rejected = statuses.count { it == "rejected" },
                    remaining = dao.count(trip.tripId),
                )
            }
            is ApiResult.Failure -> when (result.kind) {
                FailureKind.NETWORK, FailureKind.TEMPORARY -> FlushOutcome.Retry(result.kind, result.message)
                FailureKind.REJECTED ->
                    if (rows.size > 1) {
                        // The server refused the whole request (too many points, one unreadable point...).
                        // Smaller batches find the cause without throwing good points away.
                        batchSize = SyncRules.smallerBatch(rows.size)
                        FlushOutcome.Progress(0, 0, 0, remaining = dao.count(trip.tripId))
                    } else {
                        // A single point the server refuses will never be accepted; it is counted, not hidden.
                        dao.deleteByIds(rows.map { it.id })
                        batchSize = SyncRules.BATCH_SIZE
                        FlushOutcome.Progress(0, 0, 1, remaining = dao.count(trip.tripId))
                    }
                FailureKind.TRIP_ENDED, FailureKind.SESSION_ENDED, FailureKind.ACCOUNT_OFF ->
                    FlushOutcome.Stop(result.kind, result.message)
            }
        }
    }

    /** Uploads everything that waits. Used before the trip is ended. Stops at the first connection problem. */
    suspend fun drain(trip: ActiveTrip): DrainResult {
        var rounds = 0
        while (rounds++ < MAX_DRAIN_ROUNDS) {
            when (val outcome = flushOnce(trip)) {
                FlushOutcome.Empty -> return DrainResult.Complete
                is FlushOutcome.Progress -> if (outcome.remaining == 0) return DrainResult.Complete
                is FlushOutcome.Retry -> return DrainResult.Incomplete(pending(trip.tripId), outcome.message)
                is FlushOutcome.Stop -> return DrainResult.Stop(outcome.kind, outcome.message)
            }
        }
        return DrainResult.Incomplete(pending(trip.tripId), "Locations are still being saved. Try again.")
    }

    private companion object {
        // 5000 points / 50 per batch = 100 rounds, plus a few for halved batches and points arriving meanwhile.
        const val MAX_DRAIN_ROUNDS = 400
    }
}
