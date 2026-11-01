package com.smartruralbus.driver.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** No screen of its own: it only decides where to go (see Navigator). */
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            startActivity(Navigator.routeAfterAuth(this@SplashActivity))
            finish()
        }
    }
}
