package com.smartruralbus.driver.ui

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.smartruralbus.driver.App
import com.smartruralbus.driver.R
import com.smartruralbus.driver.data.ApiResult
import com.smartruralbus.driver.data.FailureKind
import com.smartruralbus.driver.databinding.ActivityActivationBinding
import com.smartruralbus.driver.tracking.TrackingState
import kotlinx.coroutines.launch

/** The driver types the 8-digit code; the backend decides whether the trip may start. */
class ActivationActivity : AppCompatActivity() {
    private lateinit var b: ActivityActivationBinding
    private val app get() = App.of(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityActivationBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.hello.text = getString(R.string.activation_hello, app.session.driverName ?: "")
        b.activateButton.setOnClickListener { submit() }
        b.codeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        b.logoutButton.setOnClickListener { logout() }
    }

    private fun submit() {
        val code = b.codeInput.text?.toString()?.trim().orEmpty()
        if (code.length != 8 || !code.all { it in '0'..'9' }) {
            showError(getString(R.string.error_code_length))
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            when (val result = app.repository.activate(code)) {
                is ApiResult.Ok -> {
                    TrackingState.reset()  // forget the previous trip's counters and stop reason
                    startActivity(Navigator.activeTrip(this@ActivationActivity))
                    finish()
                }
                is ApiResult.Failure -> {
                    setBusy(false)
                    when (result.kind) {
                        FailureKind.SESSION_ENDED, FailureKind.ACCOUNT_OFF -> {
                            if (result.kind == FailureKind.ACCOUNT_OFF) app.session.clearLogin()
                            startActivity(Navigator.login(this@ActivationActivity, result.message))
                            finish()
                        }
                        else -> showError(result.message)  // invalid / used / expired / cancelled / no internet...
                    }
                }
            }
        }
    }

    private fun logout() {
        setBusy(true)
        lifecycleScope.launch {
            app.repository.logout()
            startActivity(Navigator.login(this@ActivationActivity))
            finish()
        }
    }

    private fun setBusy(busy: Boolean) {
        b.progress.isVisible = busy
        b.activateButton.isEnabled = !busy
        b.logoutButton.isEnabled = !busy
        if (busy) b.errorText.isVisible = false
    }

    private fun showError(message: String) {
        b.errorText.text = message
        b.errorText.isVisible = true
    }
}
