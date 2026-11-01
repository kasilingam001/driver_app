# Smart Rural Bus - Android driver app

Kotlin app for bus drivers. The driver logs in, enters the 8-digit trip code from the administrator, and the
phone's GPS position is sent to the Django backend about every 10 seconds until the trip is ended.
No GPS hardware is needed in the bus.

**Status (Phase 14):** login, trip activation, active trip with GPS tracking, trip completed and
**offline GPS buffering with Room** are implemented (see "Offline buffering" below).

## Screens

| Screen | What it does |
|---|---|
| Login | Username + password -> `POST /api/driver/login/`. A hidden "Server settings" section sets the backend address. |
| Trip activation | 8 digits -> `POST /api/driver/trips/activate/`. The server decides if the code is valid, used, expired or cancelled and its message is shown. |
| Active trip | Shows bus, route, trip status `ACTIVE`, GPS status, internet status, last upload time and counters. **END TRIP** asks for confirmation, calls `POST /api/driver/trips/end/`, then stops GPS and clears the trip. |
| Trip completed | Summary and "Start another trip". Also shown if an administrator completed/cancelled the trip. |

`SplashActivity` has no screen; it only picks where to go (not logged in -> Login; running trip -> Active trip; otherwise Activation).

## How tracking works

* `TrackingService` is a **foreground service** (location type) with a visible notification, so GPS keeps running when the screen is off or the app is in the background.
* It uses the **Fused Location Provider** (high accuracy, interval from the server's `gps_interval_seconds`, default 10 s).
* Each fix is sent to `POST /api/driver/location/` with `trip_id, bus_id, latitude, longitude, speed (m/s), accuracy (m), timestamp` (ISO-8601 UTC).
* It only runs while a trip is stored as active on the phone. It stops itself when the server answers that the trip is no longer active, when the login is revoked, or when the driver ends the trip.
* Login tokens are in `EncryptedSharedPreferences`. The password is never stored. An expired access token is renewed automatically with the refresh token.
* After an app restart the trip is restored from `GET /api/driver/trips/current/` (or from the phone if there is no internet).

## Requirements

* Android Studio (Ladybug 2024.2 or newer) with JDK 17+ (the bundled JDK is fine)
* Android SDK 35; the app runs on Android 8.0 (API 26) or newer
* The Django backend running (see the main project README)

## Open and run

1. Android Studio -> **Open** -> choose this `android-driver` folder. Let Gradle sync (internet needed the first time). If asked about the Gradle wrapper, accept the suggested one (Gradle 8.9).
2. Start the backend so a phone/emulator can reach it:
   ```bash
   cd ..                                   # project root
   python manage.py migrate
   python manage.py seed_demo              # prints demo admin/driver logins
   python manage.py runserver 0.0.0.0:8000
   ```
3. Pick the server address:
   * **Emulator:** the default `http://10.0.2.2:8000/` already points at your PC. `10.0.2.2` is in `.env.example`'s `ALLOWED_HOSTS`.
   * **Real phone on the same Wi-Fi:** find your PC's address (e.g. `192.168.1.20`), add it to `ALLOWED_HOSTS` in `.env`, restart Django, and on the login screen open **Server settings** and type `http://192.168.1.20:8000/`. Allow port 8000 in your PC firewall.
4. Press **Run**. Allow location when asked (and notifications on Android 13+).

Debug builds allow plain `http://` for local development only. A release build refuses anything but `https://`; set the real address in `app/build.gradle.kts` (`DEFAULT_SERVER_URL` under `release`).

## Try the whole flow

1. Dashboard (`/dashboard/`) -> **Trips** -> **Create trip** (route R1, bus K1) -> note the 8-digit code.
2. App: log in with the seeded driver -> enter the code -> the Active trip screen appears and GPS starts.
   On an emulator open **Extended controls (...) -> Location** and play a route or set a point; the app needs a GPS fix to send anything.
3. Dashboard -> **Live buses**: the bus appears and moves. The passenger site finds it by From/To.
4. App: **END TRIP** -> Trip completed. The code no longer works and the bus disappears from passenger search.

## Project layout

```
app/src/main/java/com/smartruralbus/driver/
  App.kt                      app-wide objects
  data/   ApiModels.kt        JSON shapes (match the Django API)
          ApiService.kt       Retrofit endpoints
          ApiClient.kt        OkHttp + automatic token refresh
          Repository.kt       every backend call, errors turned into clear results
          SessionStore.kt     encrypted tokens + server address
          TripStore.kt        the running trip, so tracking survives a restart
  tracking/ TrackingService.kt   foreground GPS service + uploads
            TrackingState.kt     live status shown on the Active trip screen
  ui/     Splash/Login/Activation/ActiveTrip/TripCompleted activities, Navigator, Util
```

## Known limits (honest list)

* **This app has not been compiled or run on a device or emulator by the author of this phase** (the build servers were not reachable from the build environment). Open it in Android Studio, let Gradle sync, and run it; report any compile error. Only the pure logic in `buffer/SyncRules.kt` was compiled and run with `kotlinc`.
* **Buffer limits.** At most 5000 points (about 14 hours at one point per 10 s) are kept; when full the oldest are dropped and the count is shown ("Not delivered"). Points the server refuses (for example a wrong phone clock) are removed and counted, not retried forever.
* **Trip ended by an admin while offline.** The server never accepts points for a finished trip, so the saved points are discarded and shown as "Not delivered" on the completed screen.
* **Android may stop background work** on some phones (aggressive battery savers). Keep the phone charging, do not force-stop the app, and exclude it from battery optimisation on such devices. If the system kills the app, open it again; tracking resumes from the stored trip.
* **Background restarts and location:** Android 12+ may refuse to give a service location when the system restarts it while the app is not visible. Reopening the app restores tracking. (Always-on background location is deliberately not requested.)
* The app was written against the backend's tested API and the backend has a contract test for it (`trips/test_android_contract.py`), but this build has **not been compiled or run on a device yet** by the author of this phase. Please do the first build in Android Studio and report any error.


## Offline buffering (Phase 14)

* Every GPS fix is first written to a Room database (`gps_buffer.db`, table `pending_location`) and only
  deleted after the server answered for it (`saved`, `duplicate` or `rejected`). Crashes, restarts and lost
  connections therefore cannot lose a point. The same fix can never be stored twice (unique trip + time).
* Uploads use `POST /api/driver/location/batch/` with up to 50 points, **newest first**, so passengers see where the
  bus is now before the older trail arrives. Re-sending a batch is safe (the server reports `duplicate`).
* When an upload fails the app waits 2, 4, 8, 16 then 30 seconds, and retries immediately when Android reports the
  network is back (`NetworkMonitor`).
* Active trip screen: **GPS connected**, **Internet unavailable** (points are saved), **Uploading pending
  locations (N left)**, and a "Waiting to upload" count.
* END TRIP first uploads everything that is saved. If that is not possible the driver is shown how many points
  are waiting and can keep tracking or end anyway (those points are then reported as not delivered).
* Logging out, or a different driver logging in, deletes saved points; activating a new trip deletes points of older trips.
* Unit test: `app/src/test/.../buffer/SyncRulesTest.kt` (run `./gradlew testDebugUnitTest`).
