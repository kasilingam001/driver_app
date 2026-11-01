package com.smartruralbus.driver.ui

import android.content.Context
import android.content.Intent
import com.smartruralbus.driver.App
import com.smartruralbus.driver.data.ApiResult
import com.smartruralbus.driver.data.FailureKind

/** Decides which screen to show after the app starts or the driver logs in. */
object Navigator {

    fun login(context: Context, message: String? = null): Intent =
        Intent(context, LoginActivity::class.java)
            .putExtra(LoginActivity.EXTRA_MESSAGE, message)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    fun activation(context: Context): Intent =
        Intent(context, ActivationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    fun activeTrip(context: Context): Intent =
        Intent(context, ActiveTripActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    /**
     * Not logged in -> Login. Otherwise ask the server whether this driver has a running trip:
     *   trip running -> Active Trip, none -> Trip Activation.
     * Without internet the phone's own record of the trip is trusted, so a driver in a dead zone
     * can still reopen the app and keep tracking.
     */
    suspend fun routeAfterAuth(context: Context): Intent {
        val app = App.of(context)
        if (!app.session.isLoggedIn) return login(context)

        return when (val result = app.repository.currentTrip()) {
            is ApiResult.Ok ->
                if (result.value != null) activeTrip(context) else activation(context)
            is ApiResult.Failure -> when (result.kind) {
                FailureKind.SESSION_ENDED, FailureKind.ACCOUNT_OFF -> {
                    if (result.kind == FailureKind.ACCOUNT_OFF) app.session.clearLogin()
                    login(context, result.message)
                }
                else ->
                    if (app.tripStore.load() != null) activeTrip(context) else activation(context)
            }
        }
    }
}
