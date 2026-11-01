package com.smartruralbus.driver.data

import com.google.gson.annotations.SerializedName

// JSON shapes of the Django driver API. Field names match the backend exactly.

data class LoginRequest(val username: String, val password: String)

data class DriverProfile(val id: Long, val name: String, val phone: String?)

data class LoginResponse(
    val access: String,
    val refresh: String,
    @SerializedName("access_expires_in") val accessExpiresIn: Long?,
    val id: Long,
    val username: String,
    val role: String,
    val driver: DriverProfile?,
)

data class RefreshRequest(val refresh: String)

data class RefreshResponse(val access: String, val refresh: String?)

data class ActivateRequest(@SerializedName("activation_code") val activationCode: String)

data class EndTripRequest(@SerializedName("trip_id") val tripId: Long)

data class BusInfo(val id: Long, @SerializedName("bus_number") val busNumber: String)

data class RouteInfo(
    val id: Long,
    @SerializedName("route_code") val routeCode: String,
    @SerializedName("route_name") val routeName: String,
)

data class TripInfo(
    val id: Long,
    val status: String,
    @SerializedName("actual_start") val actualStart: String?,
    val bus: BusInfo,
    val route: RouteInfo,
)

data class ActivateResponse(
    val detail: String?,
    val resumed: Boolean?,
    val trip: TripInfo,
    @SerializedName("gps_interval_seconds") val gpsIntervalSeconds: Int?,
)

data class EndTripResponse(
    val detail: String?,
    @SerializedName("already_ended") val alreadyEnded: Boolean?,
    val trip: TripInfo?,
)

data class CurrentTripResponse(
    val trip: TripInfo?,
    @SerializedName("gps_interval_seconds") val gpsIntervalSeconds: Int?,
)

data class LocationRequest(
    @SerializedName("trip_id") val tripId: Long,
    @SerializedName("bus_id") val busId: Long,
    val latitude: Double,
    val longitude: Double,
    val speed: Float?,      // metres per second; null when the phone has no speed
    val accuracy: Float?,   // metres; null when unknown
    val timestamp: String,  // ISO-8601 UTC, e.g. 2026-10-05T07:30:10.123Z
)

data class LocationResponse(val detail: String?, val duplicate: Boolean?)

// Phase 14: upload of points that were saved on the phone (POST api/driver/location/batch/)
data class LocationPoint(
    val latitude: Double,
    val longitude: Double,
    val speed: Float?,
    val accuracy: Float?,
    val timestamp: String,
)

data class BatchLocationRequest(
    @SerializedName("trip_id") val tripId: Long,
    @SerializedName("bus_id") val busId: Long,
    val points: List<LocationPoint>,
)

/** `status` is "saved", "duplicate" (server already had it) or "rejected" (it never will be accepted). */
data class BatchPointResult(val index: Int, val status: String?)

data class BatchLocationResponse(
    val detail: String?,
    val saved: Int?,
    val duplicates: Int?,
    val rejected: Int?,
    val results: List<BatchPointResult>?,
)

data class LogoutRequest(val refresh: String)
