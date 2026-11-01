package com.smartruralbus.driver.data

import android.content.Context
import androidx.core.content.edit

/** What the phone remembers about the running trip, so tracking can resume after the app restarts. */
data class ActiveTrip(
    val tripId: Long,
    val busId: Long,
    val busNumber: String,
    val routeCode: String,
    val routeName: String,
    val driverId: Long,
    val startedAt: String?,
    val intervalSeconds: Int,
)

class TripStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("active_trip", Context.MODE_PRIVATE)

    @Synchronized
    fun save(trip: ActiveTrip) = prefs.edit {
        putLong("trip_id", trip.tripId)
        putLong("bus_id", trip.busId)
        putString("bus_number", trip.busNumber)
        putString("route_code", trip.routeCode)
        putString("route_name", trip.routeName)
        putLong("driver_id", trip.driverId)
        putString("started_at", trip.startedAt)
        putInt("interval", trip.intervalSeconds)
    }

    @Synchronized
    fun load(): ActiveTrip? {
        val id = prefs.getLong("trip_id", -1L)
        if (id < 0) return null
        return ActiveTrip(
            tripId = id,
            busId = prefs.getLong("bus_id", -1L),
            busNumber = prefs.getString("bus_number", "") ?: "",
            routeCode = prefs.getString("route_code", "") ?: "",
            routeName = prefs.getString("route_name", "") ?: "",
            driverId = prefs.getLong("driver_id", -1L),
            startedAt = prefs.getString("started_at", null),
            intervalSeconds = prefs.getInt("interval", 10),
        )
    }

    @Synchronized
    fun clear() = prefs.edit { clear() }
}
