package com.smartruralbus.driver.data

/** Why a request did not succeed. The screens decide what to do from this, not from raw HTTP codes. */
enum class FailureKind {
    NETWORK,       // no internet / server unreachable / timeout: try again later, nothing is wrong with the request
    TEMPORARY,     // server busy or erroring (5xx, 429): try again later
    SESSION_ENDED, // login expired or revoked: the driver must log in again
    ACCOUNT_OFF,   // the driver account was disabled by the admin
    TRIP_ENDED,    // the trip is no longer active on the server (completed/cancelled by an admin)
    REJECTED,      // the server refused this request (bad code, bad data...): show its message
}

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    data class Failure(
        val kind: FailureKind,
        val message: String,
        val code: String? = null,
        val httpStatus: Int = 0,
    ) : ApiResult<Nothing>
}
