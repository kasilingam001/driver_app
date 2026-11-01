package com.smartruralbus.driver.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.smartruralbus.driver.BuildConfig

/**
 * Login tokens and the server address.
 * Tokens are kept in EncryptedSharedPreferences (Android Keystore). The password is never stored.
 */
class SessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = openPrefs()

    private fun openPrefs(): SharedPreferences {
        fun create(): SharedPreferences {
            val key = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                appContext, FILE, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        return try {
            create()
        } catch (e: Exception) {
            // A damaged keystore entry (e.g. after a backup restore) cannot be repaired: start clean.
            // The driver simply has to log in again.
            appContext.deleteSharedPreferences(FILE)
            create()
        }
    }

    val accessToken: String? get() = prefs.getString(ACCESS, null)
    val refreshToken: String? get() = prefs.getString(REFRESH, null)
    val driverId: Long get() = prefs.getLong(DRIVER_ID, -1L)
    val driverName: String? get() = prefs.getString(DRIVER_NAME, null)
    val isLoggedIn: Boolean get() = !refreshToken.isNullOrEmpty()

    @Synchronized
    fun saveLogin(response: LoginResponse) = prefs.edit {
        putString(ACCESS, response.access)
        putString(REFRESH, response.refresh)
        putLong(DRIVER_ID, response.driver?.id ?: -1L)
        putString(DRIVER_NAME, response.driver?.name ?: response.username)
    }

    @Synchronized
    fun saveTokens(access: String, refresh: String?) = prefs.edit {
        putString(ACCESS, access)
        if (!refresh.isNullOrEmpty()) putString(REFRESH, refresh)  // the server rotates refresh tokens
    }

    @Synchronized
    fun clearLogin() = prefs.edit {
        remove(ACCESS); remove(REFRESH); remove(DRIVER_ID); remove(DRIVER_NAME)
    }

    /** Address of the Django server, always ending with "/". */
    var serverUrl: String
        get() = prefs.getString(SERVER, null) ?: BuildConfig.DEFAULT_SERVER_URL
        set(value) = prefs.edit { putString(SERVER, value) }

    private companion object {
        const val FILE = "driver_session"
        const val ACCESS = "access"
        const val REFRESH = "refresh"
        const val DRIVER_ID = "driver_id"
        const val DRIVER_NAME = "driver_name"
        const val SERVER = "server_url"
    }
}
