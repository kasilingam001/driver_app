package com.smartruralbus.driver.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.smartruralbus.driver.buffer.PendingLocation
import com.smartruralbus.driver.buffer.PendingLocationDao
import retrofit2.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Every call to the backend goes through here, so errors are handled the same way everywhere. */
class Repository(
    private val client: ApiClient,
    private val session: SessionStore,
    private val trips: TripStore,
    private val pending: PendingLocationDao,
) {
    // ------------------------------------------------------------------ session
    suspend fun login(username: String, password: String): ApiResult<LoginResponse> {
        val result = call(authenticated = false) { client.service().login(LoginRequest(username, password)) }
        if (result is ApiResult.Ok) {
            val newDriverId = result.value.driver?.id ?: -1L
            // A different driver on this phone must never inherit the previous driver's trip.
            trips.load()?.let {
                if (it.driverId != newDriverId) {
                    trips.clear()
                    pending.deleteAll()  // never upload one driver's saved points under another driver
                }
            }
            session.saveLogin(result.value)
        }
        return result
    }

    /** Ends the session on the server (best effort) and wipes everything stored on the phone. */
    suspend fun logout() {
        val refresh = session.refreshToken
        if (refresh != null) {
            try { client.service().logout(LogoutRequest(refresh)) } catch (_: Exception) { /* offline: still sign out locally */ }
        }
        session.clearLogin()
        trips.clear()
        pending.deleteAll()
    }

    // ------------------------------------------------------------------ trips
    suspend fun activate(code: String): ApiResult<ActiveTrip> {
        val result = call { client.service().activate(ActivateRequest(code)) }
        return when (result) {
            is ApiResult.Ok -> {
                val trip = result.value.trip.toActiveTrip(result.value.gpsIntervalSeconds)
                trips.save(trip)
                pending.deleteAllExcept(trip.tripId)  // leftovers of an older trip can never be accepted
                ApiResult.Ok(trip)
            }
            is ApiResult.Failure -> result
        }
    }

    /** The trip the server says this driver is running (null = none). Updates the stored trip to match. */
    suspend fun currentTrip(): ApiResult<ActiveTrip?> {
        val result = call { client.service().currentTrip() }
        return when (result) {
            is ApiResult.Ok -> {
                val info = result.value.trip
                if (info == null) {
                    trips.clear()
                    ApiResult.Ok(null)
                } else {
                    val trip = info.toActiveTrip(result.value.gpsIntervalSeconds)
                    trips.save(trip)
                    ApiResult.Ok(trip)
                }
            }
            is ApiResult.Failure -> result
        }
    }

    /** Success also covers "this trip was already over": either way GPS must stop. */
    suspend fun endTrip(tripId: Long): ApiResult<Unit> {
        val result = call { client.service().endTrip(EndTripRequest(tripId)) }
        return when {
            result is ApiResult.Ok -> ApiResult.Ok(Unit)
            result is ApiResult.Failure && (result.kind == FailureKind.TRIP_ENDED || result.code == "no_active_trip") ->
                ApiResult.Ok(Unit)
            else -> result as ApiResult.Failure
        }
    }

    suspend fun sendLocation(trip: ActiveTrip, fix: GpsFix): ApiResult<Unit> {
        val body = LocationRequest(
            tripId = trip.tripId,
            busId = trip.busId,
            latitude = fix.latitude,
            longitude = fix.longitude,
            speed = fix.speedMps,
            accuracy = fix.accuracyM,
            timestamp = isoUtc(fix.timeMillis),
        )
        return when (val result = call { client.service().sendLocation(body) }) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.Failure -> result
        }
    }

    /**
     * Uploads saved points. On success the list holds the server's answer for every point, in the same
     * order as `points` (null entries when the reply does not cover a point).
     */
    suspend fun sendBatch(trip: ActiveTrip, points: List<PendingLocation>): ApiResult<List<String?>> {
        val body = BatchLocationRequest(
            tripId = trip.tripId,
            busId = trip.busId,
            points = points.map {
                LocationPoint(it.latitude, it.longitude, it.speedMps, it.accuracyM, isoUtc(it.timeMillis))
            },
        )
        return when (val result = call { client.service().sendLocations(body) }) {
            is ApiResult.Ok -> {
                val byIndex = result.value.results.orEmpty().associate { it.index to it.status }
                ApiResult.Ok(points.indices.map { byIndex[it] })
            }
            is ApiResult.Failure -> result
        }
    }

    // ------------------------------------------------------------------ plumbing
    private suspend fun <T> call(authenticated: Boolean = true, block: suspend () -> Response<T>): ApiResult<T> {
        val response = try {
            block()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e  // never swallow cancellation
        } catch (e: IOException) {
            return ApiResult.Failure(FailureKind.NETWORK, "No internet connection or the server cannot be reached.")
        } catch (e: RuntimeException) {
            // e.g. an unreadable reply (a proxy login page, for instance)
            return ApiResult.Failure(FailureKind.TEMPORARY, "The server sent an unexpected reply. Try again.")
        }

        if (response.isSuccessful) {
            @Suppress("UNCHECKED_CAST")
            val body = response.body() ?: (Unit as T)
            return ApiResult.Ok(body)
        }

        val parsed = parseError(response)
        val status = response.code()
        val kind = when {
            authenticated && status == 401 -> FailureKind.SESSION_ENDED
            parsed.code == "driver_disabled" -> if (authenticated) FailureKind.ACCOUNT_OFF else FailureKind.REJECTED
            parsed.code == "trip_not_active" || parsed.code == "trip_not_assigned" -> FailureKind.TRIP_ENDED
            status == 429 || status >= 500 -> FailureKind.TEMPORARY
            else -> FailureKind.REJECTED
        }
        return ApiResult.Failure(kind, parsed.message, parsed.code, status)
    }

    private class ParsedError(val message: String, val code: String?)

    private fun parseError(response: Response<*>): ParsedError {
        val fallback = when (response.code()) {
            401 -> "Your login has expired. Please log in again."
            403 -> "You do not have permission to do this."
            404 -> "Not found."
            429 -> "Too many requests. Wait a moment and try again."
            in 500..599 -> "The server had a problem. Try again in a moment."
            else -> "The request could not be completed."
        }
        return try {
            val text = response.errorBody()?.string().orEmpty()
            val json = JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
                ?: return ParsedError(fallback, null)
            val code = json.get("code")?.takeIf { it.isJsonPrimitive }?.asString
            var message = json.get("detail")?.takeIf { it.isJsonPrimitive }?.asString ?: fallback
            // Validation errors carry the useful sentence under "errors": {"field": ["..."]}
            val errors = json.get("errors")?.takeIf { it.isJsonObject }?.asJsonObject
            firstFieldMessage(errors)?.let { message = it }
            ParsedError(message, code)
        } catch (e: Exception) {
            ParsedError(fallback, null)
        }
    }

    private fun firstFieldMessage(errors: JsonObject?): String? {
        if (errors == null) return null
        for ((_, value) in errors.entrySet()) {
            val text = firstText(value)
            if (text != null) return text
        }
        return null
    }

    private fun firstText(element: JsonElement): String? = when {
        element.isJsonPrimitive -> element.asString
        element.isJsonArray -> element.asJsonArray.firstNotNullOfOrNull { firstText(it) }
        else -> null
    }

    private fun TripInfo.toActiveTrip(intervalSeconds: Int?) = ActiveTrip(
        tripId = id,
        busId = bus.id,
        busNumber = bus.busNumber,
        routeCode = route.routeCode,
        routeName = route.routeName,
        driverId = session.driverId,
        startedAt = actualStart,
        intervalSeconds = (intervalSeconds ?: 10).coerceIn(5, 60),
    )

    companion object {
        /** ISO-8601 in UTC with a "Z": the backend rejects timestamps without a time zone. */
        fun isoUtc(millis: Long): String {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            return format.format(Date(millis))
        }
    }
}

/** One reading from the phone's GPS. */
data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val speedMps: Float?,
    val accuracyM: Float?,
    val timeMillis: Long,
)
