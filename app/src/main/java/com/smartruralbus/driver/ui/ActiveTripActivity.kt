package com.smartruralbus.driver.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smartruralbus.driver.App
import com.smartruralbus.driver.R
import com.smartruralbus.driver.buffer.DrainResult
import com.smartruralbus.driver.data.ActiveTrip
import com.smartruralbus.driver.data.ApiResult
import com.smartruralbus.driver.data.FailureKind
import com.smartruralbus.driver.databinding.ActivityActiveTripBinding
import com.smartruralbus.driver.tracking.GpsStatus
import com.smartruralbus.driver.tracking.StopReason
import com.smartruralbus.driver.tracking.TrackingService
import com.smartruralbus.driver.tracking.TrackingSnapshot
import com.smartruralbus.driver.tracking.TrackingState
import com.smartruralbus.driver.tracking.UploadStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shows the running trip and the live GPS/upload status. The foreground TrackingService does the real
 * work, so tracking continues when this screen is closed or the phone is locked.
 */
class ActiveTripActivity : AppCompatActivity() {
    private lateinit var b: ActivityActiveTripBinding
    private val app get() = App.of(this)
    private lateinit var trip: ActiveTrip
    private var latest = TrackingSnapshot()
    private var leaving = false
    private var ending = false
    private var askedOnce = false
    private var askedNotification = false

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission()) beginTracking() else showPermissionPanel(permanentlyDenied = !shouldShowRationale())
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Works either way: without the permission the tracking notification is only hidden.
        TrackingService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stored = app.tripStore.load()
        if (stored == null) {  // nothing to track
            startActivity(Navigator.activation(this)); finish(); return
        }
        trip = stored
        b = ActivityActiveTripBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.busValue.text = trip.busNumber
        b.routeValue.text = "${trip.routeCode} - ${trip.routeName}"
        b.endButton.setOnClickListener { confirmEnd() }
        b.permissionButton.setOnClickListener { onPermissionButton() }
        b.locationSettingsButton.setOnClickListener { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }

        // Back must not end the trip or kill tracking: just move the app away.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })

        render()

        // repeatOnLifecycle restarts these every time the screen becomes visible again.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TrackingState.state.collect { latest = it; onStateChanged(it); render() } }
                launch { while (true) { render(); delay(1000) } }  // keeps "x s ago" moving
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!::trip.isInitialized) return
        if (hasLocationPermission()) beginTracking() else if (!askedOnce) { askedOnce = true; requestLocation() }
        else showPermissionPanel(permanentlyDenied = !shouldShowRationale())

        verifyWithServer()
    }

    // ---------------------------------------------------------------- permissions and start
    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun shouldShowRationale() =
        shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun requestLocation() {
        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun onPermissionButton() {
        if (b.permissionButton.tag == "settings") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else requestLocation()
    }

    private fun showPermissionPanel(permanentlyDenied: Boolean) {
        b.permissionPanel.isVisible = true
        b.permissionButton.tag = if (permanentlyDenied) "settings" else "ask"
        b.permissionButton.setText(if (permanentlyDenied) R.string.open_app_settings else R.string.allow_location)
    }

    private fun beginTracking() {
        b.permissionPanel.isVisible = false
        val needsNotificationPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission && !askedNotification) {
            askedNotification = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else TrackingService.start(this)
    }

    // ---------------------------------------------------------------- reacting to the service
    private fun onStateChanged(state: TrackingSnapshot) {
        val reason = state.stopReason ?: return
        if (leaving || ending) return
        leaving = true
        when (reason) {
            StopReason.TRIP_ENDED_BY_SERVER -> {
                TrackingState.reset()
                startActivity(completedIntent(byServer = true, sent = state.sentCount,
                    notDelivered = state.discardedCount + state.rejectedCount + state.pendingCount))
            }
            StopReason.SESSION_ENDED -> {
                TrackingState.reset()
                startActivity(Navigator.login(this, getString(R.string.stopped_session_ended)))
            }
            StopReason.ACCOUNT_DISABLED -> {
                app.session.clearLogin()
                TrackingState.reset()
                startActivity(Navigator.login(this, getString(R.string.stopped_account_disabled)))
            }
        }
        finish()
    }

    /** If an admin completed the trip while the phone was offline, find out now instead of at the next GPS send. */
    private fun verifyWithServer() {
        lifecycleScope.launch {
            val result = app.repository.currentTrip()
            if (leaving || ending) return@launch
            when {
                result is ApiResult.Ok && result.value == null -> {
                    leaving = true
                    TrackingService.stop(this@ActiveTripActivity)
                    // The server will never accept points for a finished trip: count them instead of hiding them.
                    val lost = app.sync.discardTrip(trip.tripId)
                    startActivity(completedIntent(byServer = true, sent = latest.sentCount,
                        notDelivered = lost + latest.discardedCount + latest.rejectedCount))
                    TrackingState.reset()
                    finish()
                }
                result is ApiResult.Failure && result.kind == FailureKind.SESSION_ENDED -> {
                    leaving = true
                    TrackingService.stop(this@ActiveTripActivity)
                    startActivity(Navigator.login(this@ActiveTripActivity, result.message))
                    finish()
                }
                result is ApiResult.Failure && result.kind == FailureKind.ACCOUNT_OFF -> {
                    leaving = true
                    TrackingService.stop(this@ActiveTripActivity)
                    app.session.clearLogin()
                    startActivity(Navigator.login(this@ActiveTripActivity, result.message))
                    finish()
                }
                // No internet or a server hiccup: keep going, the trip is still stored on the phone.
            }
        }
    }

    // ---------------------------------------------------------------- END TRIP
    private fun confirmEnd() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.end_trip_confirm_title)
            .setMessage(R.string.end_trip_confirm_message)
            .setNegativeButton(R.string.keep_going, null)
            .setPositiveButton(R.string.end_trip) { _, _ -> endTrip() }
            .show()
    }

    /**
     * END TRIP: first upload every saved point (the trip is still active, so the server accepts them),
     * then end the trip. If the saved points cannot be uploaded the driver is told how many are waiting
     * and chooses: keep tracking, or end anyway (those points are then reported as not delivered).
     */
    private fun endTrip(endAnyway: Boolean = false) {
        if (ending) return
        ending = true
        TrackingState.endingByDriver = true  // the service must not treat a late "trip ended" reply as an admin action
        b.endButton.isEnabled = false
        lifecycleScope.launch {
            if (!endAnyway) {
                b.endButton.setText(R.string.sending_saved_locations)
                when (val drained = app.sync.drain(trip)) {
                    DrainResult.Complete -> Unit
                    is DrainResult.Incomplete -> {
                        resetEndButton()
                        askEndWithUnsent(drained.remaining)
                        return@launch
                    }
                    is DrainResult.Stop -> {
                        if (drained.kind == FailureKind.TRIP_ENDED) {
                            finishAfterServerEnded()
                        } else {
                            TrackingState.endingByDriver = false
                            TrackingService.stop(this@ActiveTripActivity)
                            if (drained.kind == FailureKind.ACCOUNT_OFF) app.session.clearLogin()
                            startActivity(Navigator.login(this@ActiveTripActivity, drained.message))
                            finish()
                        }
                        return@launch
                    }
                }
            }

            b.endButton.setText(R.string.ending_trip)
            // Tell the server: if there is no connection the trip is still active and GPS must keep running.
            when (val result = app.repository.endTrip(trip.tripId)) {
                is ApiResult.Ok -> {
                    val sent = latest.sentCount
                    val notDelivered = app.sync.discardTrip(trip.tripId) + latest.discardedCount + latest.rejectedCount
                    app.tripStore.clear()                          // clear the active trip state
                    TrackingService.stop(this@ActiveTripActivity)  // stops GPS and uploads
                    TrackingState.reset()
                    TrackingState.endingByDriver = false
                    startActivity(completedIntent(byServer = false, sent = sent, notDelivered = notDelivered))
                    finish()
                }
                is ApiResult.Failure -> when (result.kind) {
                    FailureKind.SESSION_ENDED, FailureKind.ACCOUNT_OFF -> {
                        TrackingState.endingByDriver = false
                        TrackingService.stop(this@ActiveTripActivity)
                        if (result.kind == FailureKind.ACCOUNT_OFF) app.session.clearLogin()
                        startActivity(Navigator.login(this@ActiveTripActivity, result.message))
                        finish()
                    }
                    else -> {
                        resetEndButton()
                        Toast.makeText(this@ActiveTripActivity, getString(R.string.end_trip_failed, result.message), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun resetEndButton() {
        ending = false
        TrackingState.endingByDriver = false
        b.endButton.isEnabled = true
        b.endButton.setText(R.string.end_trip)
    }

    private fun askEndWithUnsent(remaining: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsent_title)
            .setMessage(getString(R.string.unsent_message, remaining))
            .setNegativeButton(R.string.keep_going, null)
            .setPositiveButton(R.string.end_anyway) { _, _ -> endTrip(endAnyway = true) }
            .show()
    }

    /** The server says the trip is already over (an admin ended it while the driver pressed END TRIP). */
    private suspend fun finishAfterServerEnded() {
        val lost = app.sync.discardTrip(trip.tripId)
        app.tripStore.clear()
        TrackingService.stop(this)
        val sent = latest.sentCount
        val notDelivered = lost + latest.discardedCount + latest.rejectedCount
        TrackingState.reset()
        TrackingState.endingByDriver = false
        startActivity(completedIntent(byServer = true, sent = sent, notDelivered = notDelivered))
        finish()
    }

    private fun completedIntent(byServer: Boolean, sent: Int, notDelivered: Int) =
        Intent(this, TripCompletedActivity::class.java)
            .putExtra(TripCompletedActivity.EXTRA_BUS, trip.busNumber)
            .putExtra(TripCompletedActivity.EXTRA_ROUTE, "${trip.routeCode} - ${trip.routeName}")
            .putExtra(TripCompletedActivity.EXTRA_BY_SERVER, byServer)
            .putExtra(TripCompletedActivity.EXTRA_SENT, sent)
            .putExtra(TripCompletedActivity.EXTRA_NOT_SENT, notDelivered)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    // ---------------------------------------------------------------- display
    private fun render() {
        if (!::b.isInitialized) return
        val s = latest
        setValue(b.gpsValue, when (s.gps) {
            GpsStatus.SEARCHING -> getString(R.string.gps_searching) to R.color.warn
            GpsStatus.CONNECTED ->
                (s.accuracyM?.let { getString(R.string.gps_connected_accuracy, it.toInt()) } ?: getString(R.string.gps_connected)) to R.color.ok
            GpsStatus.UNAVAILABLE -> getString(R.string.gps_unavailable) to R.color.bad
        })
        b.locationSettingsButton.isVisible = s.gps == GpsStatus.UNAVAILABLE && hasLocationPermission()

        setValue(b.netValue, when (s.upload) {
            UploadStatus.NONE -> getString(R.string.net_waiting) to R.color.warn
            UploadStatus.SENT -> getString(R.string.net_ok) to R.color.ok
            UploadStatus.UPLOADING_PENDING -> getString(R.string.net_uploading_pending, s.pendingCount) to R.color.warn
            UploadStatus.NO_INTERNET -> getString(R.string.net_unavailable) to R.color.bad
            UploadStatus.SERVER_PROBLEM -> getString(R.string.net_server_problem) to R.color.bad
        })
        b.lastSentValue.text = agoText(this, s.lastSentAtMillis)
        b.sentValue.text = s.sentCount.toString()
        b.notSentValue.text = s.pendingCount.toString()
        val problems = s.rejectedCount + s.discardedCount
        b.lostRow.isVisible = problems > 0
        b.lostValue.text = getString(R.string.lost_detail, s.rejectedCount, s.discardedCount)
    }

    private fun setValue(view: TextView, pair: Pair<String, Int>) {
        view.text = pair.first
        view.setTextColor(ContextCompat.getColor(this, pair.second))
    }
}
