package com.smartruralbus.driver

import android.app.Application
import com.smartruralbus.driver.buffer.BufferDatabase
import com.smartruralbus.driver.buffer.LocationSync
import com.smartruralbus.driver.data.ApiClient
import com.smartruralbus.driver.data.Repository
import com.smartruralbus.driver.data.SessionStore
import com.smartruralbus.driver.data.TripStore

/** Creates the app-wide objects once; screens and the tracking service reach them through `App.of(context)`. */
class App : Application() {
    lateinit var session: SessionStore
        private set
    lateinit var tripStore: TripStore
        private set
    lateinit var repository: Repository
        private set
    lateinit var sync: LocationSync
        private set

    override fun onCreate() {
        super.onCreate()
        session = SessionStore(this)
        tripStore = TripStore(this)
        val pending = BufferDatabase.create(this).pendingLocations()
        repository = Repository(ApiClient(session), session, tripStore, pending)
        sync = LocationSync(pending, repository)
    }

    companion object {
        fun of(context: android.content.Context): App = context.applicationContext as App
    }
}
