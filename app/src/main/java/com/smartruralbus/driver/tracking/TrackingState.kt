package com.smartruralbus.driver.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class GpsStatus { SEARCHING, CONNECTED, UNAVAILABLE }

/** How the last attempt to send a location went. */
enum class UploadStatus { NONE, SENT, UPLOADING_PENDING, NO_INTERNET, SERVER_PROBLEM }

/** Why tracking stopped on its own (the driver pressing END TRIP is handled by the screen). */
enum class StopReason { TRIP_ENDED_BY_SERVER, SESSION_ENDED, ACCOUNT_DISABLED }

data class TrackingSnapshot(
    val tripId: Long? = null,
    val running: Boolean = false,
    val gps: GpsStatus = GpsStatus.SEARCHING,
    val accuracyM: Float? = null,
    val lastFixAtMillis: Long? = null,
    val upload: UploadStatus = UploadStatus.NONE,
    val uploadMessage: String? = null,
    val lastSentAtMillis: Long? = null,
    val sentCount: Int = 0,
    val pendingCount: Int = 0,     // saved on the phone, waiting to be uploaded
    val rejectedCount: Int = 0,    // the server refused these points (for example a wrong phone clock)
    val discardedCount: Int = 0,   // dropped because the phone's buffer was full, or the trip ended on the server
    val stopReason: StopReason? = null,
)

/** What the tracking service is doing right now. The Active Trip screen only watches this. */
object TrackingState {
    private val _state = MutableStateFlow(TrackingSnapshot())
    val state: StateFlow<TrackingSnapshot> = _state.asStateFlow()

    /** True while the driver's END TRIP request is in flight, so a late "trip not active" reply is not an admin action. */
    @Volatile
    var endingByDriver: Boolean = false

    fun update(change: (TrackingSnapshot) -> TrackingSnapshot) = _state.update(change)

    fun reset() { _state.value = TrackingSnapshot() }
}
