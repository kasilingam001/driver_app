package com.smartruralbus.driver.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.smartruralbus.driver.App
import com.smartruralbus.driver.R
import com.smartruralbus.driver.data.ActiveTrip
import com.smartruralbus.driver.buffer.FlushOutcome
import com.smartruralbus.driver.buffer.SyncRules
import com.smartruralbus.driver.data.FailureKind
import com.smartruralbus.driver.data.GpsFix
import com.smartruralbus.driver.ui.ActiveTripActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that keeps the phone's GPS running while a trip is active (even with the screen off)
 * and sends a location to the backend about every 10 seconds.
 *
 * It only runs while a trip is stored as active. It stops itself when the server says the trip is over,
 * when the login is no longer valid, or when the screen asks it to stop (END TRIP).
 *
 * Every GPS point is first saved in the phone's Room database, then uploaded in batches. When there is no
 * internet the points simply stay saved; when the connection returns (or the retry timer fires) the backlog
 * is uploaded, newest first. A point is deleted only after the server has answered for it.
 */
class TrackingService : Service() {
    private lateinit var fused: FusedLocationProviderClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var uploader: Job? = null
    private var trip: ActiveTrip? = null

    // "Something new is saved": wakes the uploader. Conflated, because one wake-up covers any number of points.
    private val newData = Channel<Unit>(Channel.CONFLATED)
    // "The connection came back": ends the waiting period after a failed upload early.
    private val networkBack = Channel<Unit>(Channel.CONFLATED)
    private var network: NetworkMonitor? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) {
                val fix = GpsFix(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    speedMps = if (location.hasSpeed()) location.speed else null,
                    accuracyM = if (location.hasAccuracy()) location.accuracy else null,
                    timeMillis = location.time,
                )
                TrackingState.update {
                    it.copy(gps = GpsStatus.CONNECTED, accuracyM = fix.accuracyM, lastFixAtMillis = System.currentTimeMillis())
                }
                save(fix)
            }
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            if (!availability.isLocationAvailable) {
                TrackingState.update { it.copy(gps = GpsStatus.UNAVAILABLE) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        network = NetworkMonitor(this) { online ->
            if (online) networkBack.trySend(Unit)
            else TrackingState.update { it.copy(upload = UploadStatus.NO_INTERNET) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutDown()
            return START_NOT_STICKY
        }

        // After a system restart the intent is null: the trip is read back from the phone's storage.
        val stored = App.of(this).tripStore.load()
        if (stored == null) {
            shutDown()
            return START_NOT_STICKY
        }
        if (trip?.tripId == stored.tripId && uploader?.isActive == true) {
            try { startInForeground(stored) } catch (_: Exception) { }  // satisfies startForegroundService() again
            return START_STICKY  // already running
        }

        if (!hasLocationPermission()) {
            TrackingState.update { it.copy(gps = GpsStatus.UNAVAILABLE) }
            shutDown()
            return START_NOT_STICKY
        }

        try {
            startInForeground(stored)
        } catch (e: Exception) {
            // The system refused a foreground start (e.g. restarted in the background). The driver
            // continues by opening the app, which starts tracking again from the screen.
            shutDown()
            return START_NOT_STICKY
        }
        begin(stored)
        return START_STICKY
    }

    override fun onDestroy() {
        stopLocationUpdates()
        network?.stop()
        scope.cancel()
        TrackingState.update { it.copy(running = false) }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- tracking
    @SuppressLint("MissingPermission")  // checked in onStartCommand
    private fun begin(active: ActiveTrip) {
        trip = active
        TrackingState.reset()
        TrackingState.update { it.copy(tripId = active.tripId, running = true) }

        val interval = active.intervalSeconds * 1000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(interval)
            .setWaitForAccurateLocation(false)
            .build()
        fused.requestLocationUpdates(request, callback, mainLooper)

        network?.start()
        if (network?.isOnline() == false) TrackingState.update { it.copy(upload = UploadStatus.NO_INTERNET) }

        uploader?.cancel()
        uploader = scope.launch { uploadLoop(active) }
    }

    /** Write-ahead: the point is stored on the phone before anything is sent, so it survives every failure. */
    private fun save(fix: GpsFix) {
        val active = trip ?: return
        scope.launch {
            val sync = App.of(this@TrackingService).sync
            val dropped = try { sync.add(active, fix) } catch (e: Exception) { return@launch }
            TrackingState.update {
                it.copy(pendingCount = it.pendingCount + 1 - dropped, discardedCount = it.discardedCount + dropped)
            }
            newData.trySend(Unit)
        }
    }

    /** Uploads whatever is saved, forever, until tracking must stop. Returns only when the service is shutting down. */
    private suspend fun uploadLoop(active: ActiveTrip) {
        val app = App.of(this)
        var failures = 0
        val startPending = app.sync.pending(active.tripId)
        TrackingState.update { it.copy(pendingCount = startPending) }

        while (scope.isActive) {
            when (val outcome = app.sync.flushOnce(active)) {
                FlushOutcome.Empty -> {
                    failures = 0
                    newData.receive()  // nothing to do until the next GPS point is saved
                }
                is FlushOutcome.Progress -> {
                    failures = 0
                    val more = outcome.remaining > 0
                    TrackingState.update {
                        it.copy(
                            upload = if (more) UploadStatus.UPLOADING_PENDING else UploadStatus.SENT,
                            uploadMessage = null,
                            lastSentAtMillis = if (outcome.saved + outcome.duplicates > 0) System.currentTimeMillis() else it.lastSentAtMillis,
                            sentCount = it.sentCount + outcome.saved + outcome.duplicates,
                            rejectedCount = it.rejectedCount + outcome.rejected,
                            pendingCount = outcome.remaining,
                        )
                    }
                }
                is FlushOutcome.Retry -> {
                    failures++
                    val status = if (outcome.kind == FailureKind.NETWORK) UploadStatus.NO_INTERNET else UploadStatus.SERVER_PROBLEM
                    TrackingState.update { it.copy(upload = status, uploadMessage = outcome.message) }
                    // Wait, but wake early when Android says the connection is back.
                    withTimeoutOrNull(SyncRules.backoffMillis(failures)) { networkBack.receive() }
                }
                is FlushOutcome.Stop -> {
                    stopFor(app, outcome.kind)
                    return
                }
            }
        }
    }

    private suspend fun stopFor(app: App, kind: FailureKind) {
        when (kind) {
            FailureKind.TRIP_ENDED -> {
                // The driver is ending the trip right now: the screen finishes the job.
                if (TrackingState.endingByDriver) return
                // The server will never accept these points (the trip is over): count them, do not hide them.
                val lost = app.sync.discardTrip(trip?.tripId ?: return)
                TrackingState.update { it.copy(pendingCount = 0, discardedCount = it.discardedCount + lost) }
                finishOnServerRequest(app, StopReason.TRIP_ENDED_BY_SERVER, clearTrip = true)
            }
            // The trip is still running on the server: keep it, and the saved points, so it resumes after login.
            FailureKind.SESSION_ENDED -> finishOnServerRequest(app, StopReason.SESSION_ENDED, clearTrip = false)
            FailureKind.ACCOUNT_OFF -> finishOnServerRequest(app, StopReason.ACCOUNT_DISABLED, clearTrip = false)
            else -> Unit
        }
    }

    private fun finishOnServerRequest(app: App, reason: StopReason, clearTrip: Boolean) {
        // The trip was already cleared on the phone (driver pressed END TRIP): nothing to report.
        if (app.tripStore.load()?.tripId != trip?.tripId) { shutDown(); return }
        if (clearTrip) app.tripStore.clear()
        TrackingState.update { it.copy(stopReason = reason, running = false) }
        notifyStopped(reason)
        shutDown()
    }

    private fun stopLocationUpdates() {
        try { fused.removeLocationUpdates(callback) } catch (_: Exception) { }
    }

    private fun shutDown() {
        stopLocationUpdates()
        uploader?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- notifications
    private fun ensureChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, getString(R.string.channel_tracking), NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, getString(R.string.channel_alert), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, ActiveTripActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun startInForeground(active: ActiveTrip) {
        ensureChannels()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, active.busNumber, active.routeCode))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent())
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun notifyStopped(reason: StopReason) {
        val text = when (reason) {
            StopReason.TRIP_ENDED_BY_SERVER -> getString(R.string.stopped_trip_ended)
            StopReason.SESSION_ENDED -> getString(R.string.stopped_session_ended)
            StopReason.ACCOUNT_DISABLED -> getString(R.string.stopped_account_disabled)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.tracking_stopped))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_STOPPED_ID, notification)
        } catch (_: SecurityException) { /* notifications not allowed: the screen shows the same message */ }
    }

    companion object {
        private const val ACTION_STOP = "com.smartruralbus.driver.STOP"
        private const val CHANNEL_TRACKING = "tracking"
        private const val CHANNEL_ALERT = "tracking_alert"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_STOPPED_ID = 1002

        /** Start (or continue) tracking the trip stored in TripStore. Call from a visible screen. */
        fun start(context: Context) {
            // Starting a foreground location service without these would crash, so check first.
            val permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (!permitted || App.of(context).tripStore.load() == null) return
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        /** Stop GPS and uploading. */
        fun stop(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
        }
    }
}
