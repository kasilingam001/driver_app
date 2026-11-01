package com.smartruralbus.driver.data

import com.google.gson.Gson
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds the Retrofit service for the current server address and adds the login token to every request.
 * When the server answers 401 (access token expired) the Authenticator exchanges the refresh token
 * for a new pair once, then repeats the request.
 */
class ApiClient(private val session: SessionStore) {
    private val gson = Gson()
    private var cachedUrl: String? = null
    private var cachedService: ApiService? = null

    // No authenticator here: used only for the refresh call itself, so it can never loop.
    private val plainHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val authHttp: OkHttpClient = plainHttp.newBuilder()
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder().header("Accept", "application/json")
            session.accessToken?.let { builder.header("Authorization", "Bearer $it") }
            chain.proceed(builder.build())
        }
        .authenticator(TokenAuthenticator())
        .build()

    @Synchronized
    fun service(): ApiService {
        val url = session.serverUrl
        if (cachedService == null || cachedUrl != url) {
            cachedService = Retrofit.Builder()
                .baseUrl(url)
                .client(authHttp)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(ApiService::class.java)
            cachedUrl = url
        }
        return cachedService!!
    }

    private inner class TokenAuthenticator : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            val sent = response.request.header("Authorization") ?: return null  // e.g. the login call itself
            if (priorResponses(response) >= 2) return null

            synchronized(this) {
                val sentToken = sent.removePrefix("Bearer ")
                val current = session.accessToken
                // Another request refreshed the tokens while this one was waiting: just retry with them.
                if (current != null && current != sentToken) return withToken(response.request, current)

                val refresh = session.refreshToken ?: return null
                val body = gson.toJson(RefreshRequest(refresh)).toRequestBody(JSON)
                val request = Request.Builder().url(session.serverUrl + "api/auth/refresh/").post(body).build()

                // A network failure here is thrown as IOException on purpose: the caller reports
                // "no internet" and the login is kept. Only a definite refusal ends the session.
                plainHttp.newCall(request).execute().use { reply ->
                    when {
                        reply.isSuccessful -> {
                            val tokens = gson.fromJson(reply.body?.string(), RefreshResponse::class.java)
                                ?: return null
                            session.saveTokens(tokens.access, tokens.refresh)
                            return withToken(response.request, tokens.access)
                        }
                        reply.code in listOf(400, 401, 403) -> {
                            session.clearLogin()  // refresh token revoked/expired or driver disabled
                            return null
                        }
                        else -> return null  // 429 / 5xx: keep the login and let the caller retry later
                    }
                }
            }
        }

        private fun withToken(request: Request, token: String): Request =
            request.newBuilder().header("Authorization", "Bearer $token").build()

        private fun priorResponses(response: Response): Int {
            var count = 1
            var prior = response.priorResponse
            while (prior != null) { count++; prior = prior.priorResponse }
            return count
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

