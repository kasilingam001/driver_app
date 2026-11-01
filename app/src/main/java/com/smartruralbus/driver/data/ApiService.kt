package com.smartruralbus.driver.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/** Endpoints of the Django backend (paths are relative to the server address). */
interface ApiService {
    @POST("api/driver/login/")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    @POST("api/auth/logout/")
    suspend fun logout(@Body body: LogoutRequest): Response<Unit>

    @POST("api/driver/trips/activate/")
    suspend fun activate(@Body body: ActivateRequest): Response<ActivateResponse>

    @POST("api/driver/trips/end/")
    suspend fun endTrip(@Body body: EndTripRequest): Response<EndTripResponse>

    @GET("api/driver/trips/current/")
    suspend fun currentTrip(): Response<CurrentTripResponse>

    @POST("api/driver/location/")
    suspend fun sendLocation(@Body body: LocationRequest): Response<LocationResponse>

    @POST("api/driver/location/batch/")
    suspend fun sendLocations(@Body body: BatchLocationRequest): Response<BatchLocationResponse>
}
