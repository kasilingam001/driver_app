package com.smartruralbus.driver.ui

import android.content.Context
import com.smartruralbus.driver.BuildConfig
import com.smartruralbus.driver.R
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Turns what the driver typed ("192.168.1.20:8000") into a base URL ("http://192.168.1.20:8000/").
 * Returns null when it is not usable. Release builds only accept https.
 */
fun normalizeServerUrl(input: String): String? {
    var text = input.trim()
    if (text.isEmpty()) return null
    if (!text.contains("://")) text = (if (BuildConfig.DEBUG) "http://" else "https://") + text
    val url = text.toHttpUrlOrNull() ?: return null
    if (url.scheme == "http" && !BuildConfig.DEBUG) return null
    val clean = url.newBuilder().query(null).fragment(null).build().toString()
    return if (clean.endsWith("/")) clean else "$clean/"
}

/** "4 s ago" / "2 min ago" / "Not yet". */
fun agoText(context: Context, thenMillis: Long?, nowMillis: Long = System.currentTimeMillis()): String {
    if (thenMillis == null) return context.getString(R.string.never)
    val seconds = ((nowMillis - thenMillis) / 1000).coerceAtLeast(0).toInt()
    return if (seconds < 60) context.getString(R.string.seconds_ago, seconds)
    else context.getString(R.string.minutes_ago, seconds / 60)
}
