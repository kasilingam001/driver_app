package com.smartruralbus.driver.buffer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingLocationDao {
    /** Returns the new row id, or -1 when this exact fix is already stored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(point: PendingLocation): Long

    /** Newest first: after an outage the bus's current position reaches passengers before the old trail. */
    @Query("SELECT * FROM pending_location WHERE tripId = :tripId ORDER BY timeMillis DESC LIMIT :limit")
    suspend fun newest(tripId: Long, limit: Int): List<PendingLocation>

    @Query("SELECT COUNT(*) FROM pending_location WHERE tripId = :tripId")
    suspend fun count(tripId: Long): Int

    @Query("DELETE FROM pending_location WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** Removes the `count` oldest points of a trip (only used when the buffer is full). */
    @Query(
        "DELETE FROM pending_location WHERE id IN " +
            "(SELECT id FROM pending_location WHERE tripId = :tripId ORDER BY timeMillis ASC LIMIT :count)"
    )
    suspend fun deleteOldest(tripId: Long, count: Int)

    @Query("DELETE FROM pending_location WHERE tripId = :tripId")
    suspend fun deleteForTrip(tripId: Long)

    @Query("DELETE FROM pending_location WHERE tripId != :tripId")
    suspend fun deleteAllExcept(tripId: Long)

    @Query("DELETE FROM pending_location")
    suspend fun deleteAll()
}
