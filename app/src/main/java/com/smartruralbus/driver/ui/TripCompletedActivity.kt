package com.smartruralbus.driver.ui

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.smartruralbus.driver.App
import com.smartruralbus.driver.R
import com.smartruralbus.driver.databinding.ActivityTripCompletedBinding
import kotlinx.coroutines.launch

/** Shown after the trip is over. Nothing here tracks anything: GPS and the stored trip are already cleared. */
class TripCompletedActivity : AppCompatActivity() {
    private lateinit var b: ActivityTripCompletedBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTripCompletedBinding.inflate(layoutInflater)
        setContentView(b.root)

        val byServer = intent.getBooleanExtra(EXTRA_BY_SERVER, false)
        b.reasonText.setText(if (byServer) R.string.completed_by_server else R.string.completed_by_driver)
        b.busValue.text = intent.getStringExtra(EXTRA_BUS).orEmpty()
        b.routeValue.text = intent.getStringExtra(EXTRA_ROUTE).orEmpty()
        b.sentText.text = getString(R.string.completed_locations_sent, intent.getIntExtra(EXTRA_SENT, 0))
        val notSent = intent.getIntExtra(EXTRA_NOT_SENT, 0)
        b.notSentText.text = getString(R.string.completed_locations_not_sent, notSent)
        b.notSentText.visibility = if (notSent > 0) android.view.View.VISIBLE else android.view.View.GONE

        b.anotherButton.setOnClickListener { startActivity(Navigator.activation(this)); finish() }
        b.logoutButton.setOnClickListener {
            lifecycleScope.launch {
                App.of(this@TripCompletedActivity).repository.logout()
                startActivity(Navigator.login(this@TripCompletedActivity))
                finish()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { startActivity(Navigator.activation(this@TripCompletedActivity)); finish() }
        })
    }

    companion object {
        const val EXTRA_BUS = "bus"
        const val EXTRA_ROUTE = "route"
        const val EXTRA_BY_SERVER = "by_server"
        const val EXTRA_SENT = "sent"
        const val EXTRA_NOT_SENT = "not_sent"
    }
}
