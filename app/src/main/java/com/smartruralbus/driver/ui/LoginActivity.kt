package com.smartruralbus.driver.ui

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.smartruralbus.driver.App
import com.smartruralbus.driver.R
import com.smartruralbus.driver.data.ApiResult
import com.smartruralbus.driver.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private lateinit var b: ActivityLoginBinding
    private val app get() = App.of(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.serverInput.setText(app.session.serverUrl)
        intent.getStringExtra(EXTRA_MESSAGE)?.let { showError(it) }

        b.serverToggle.setOnClickListener { b.serverLayout.isVisible = !b.serverLayout.isVisible }
        b.loginButton.setOnClickListener { submit() }
        b.passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
    }

    private fun submit() {
        val username = b.usernameInput.text?.toString()?.trim().orEmpty()
        val password = b.passwordInput.text?.toString().orEmpty()  // never logged or stored
        if (username.isEmpty() || password.isEmpty()) {
            showError(getString(R.string.error_enter_credentials))
            return
        }
        val serverUrl = normalizeServerUrl(b.serverInput.text?.toString().orEmpty())
        if (serverUrl == null) {
            b.serverLayout.isVisible = true
            showError(getString(R.string.error_server_url))
            return
        }
        app.session.serverUrl = serverUrl
        b.serverInput.setText(serverUrl)

        setBusy(true)
        lifecycleScope.launch {
            when (val result = app.repository.login(username, password)) {
                is ApiResult.Ok -> {
                    startActivity(Navigator.routeAfterAuth(this@LoginActivity))
                    finish()
                }
                is ApiResult.Failure -> {
                    setBusy(false)
                    showError(result.message)
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        b.progress.isVisible = busy
        b.loginButton.isEnabled = !busy
        if (busy) b.errorText.isVisible = false
    }

    private fun showError(message: String) {
        b.errorText.text = message
        b.errorText.isVisible = true
    }

    companion object {
        const val EXTRA_MESSAGE = "message"
    }
}
