package com.smartruralbus.driver.buffer

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One GPS point saved on the phone until the server has confirmed it.
 *
 * Every fix is written here first and only deleted after the server answered "saved", "duplicate" or
 * "rejected" for it. The unique (tripId, timeMillis) index mirrors the server's unique (trip, timestamp)
 * rule, so the same fix can never be queued twice.
 */
@Entity(
    tableName = "pending_location",
    indices = [Index(value = ["tripId", "timeMillis"], unique = true)],
)
data class PendingLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val busId: Long,
    val latitude: Double,
    val longitude: Double,
    val speedMps: Float?,
    val accuracyM: Float?,
    val timeMillis: Long,
)
