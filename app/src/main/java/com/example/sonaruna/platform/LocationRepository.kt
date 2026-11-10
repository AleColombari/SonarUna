package com.example.sonaruna.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.sonaruna.guia.Position
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Obtains a foreground reading to suggest where the person is; never subscribes to background updates.
 * Location is optional: any failure returns null and the conversation simply asks where the person is.
 *
 * The first reading after leaving a building is often rough (Wi-Fi, or GPS still blocked by the walls): instead of
 * taking it, readings are gathered for a few seconds and the best one is kept, stopping as soon as one is good.
 */
class LocationRepository(context: Context) {
    private val context = context.applicationContext

    @SuppressLint("MissingPermission") // Grants are checked below; a revocation is caught as SecurityException.
    suspend fun currentPosition(): Position? {
        val precise = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!precise && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        if (!isLocationEnabled()) return null
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) return null

        // Approximate location is accurate to about 3 km², too coarse to tell entrances apart; it is still read so
        // the person can be told they are not on campus.
        val request = LocationRequest.Builder(
            if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            UPDATE_INTERVAL_MILLIS,
        )
            .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MILLIS / 2)
            .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            .setWaitForAccurateLocation(false)
            .setDurationMillis(DURATION_MILLIS)
            .build()
        val client = LocationServices.getFusedLocationProviderClient(context)
        var best: Location? = null
        var readings = 0
        var callback: LocationCallback? = null
        return try {
            withTimeoutOrNull(DURATION_MILLIS) {
                suspendCancellableCoroutine { continuation ->
                    val updates = object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            for (location in result.locations) {
                                readings++
                                // Old cached fixes do not say where the person is now.
                                val ageMillis = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
                                if (ageMillis > MAX_AGE_MILLIS) continue
                                if (best == null || location.accuracy < best!!.accuracy) best = location
                            }
                            if ((best?.accuracy ?: Float.MAX_VALUE) <= GOOD_ACCURACY_METERS && continuation.isActive) {
                                continuation.resume(Unit)
                            }
                        }
                    }
                    callback = updates
                    client.requestLocationUpdates(request, updates, Looper.getMainLooper())
                }
            }
            // Only the accuracy and how many readings were needed are logged, never where the person is.
            Log.i(TAG, "Localização: leituras=$readings melhor_precisao=${best?.accuracy?.toInt() ?: -1} m")
            best?.let { Position(it.latitude, it.longitude, it.accuracy.toDouble()) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } finally {
            callback?.let { runCatching { client.removeLocationUpdates(it) } }
        }
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun isLocationEnabled(): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private companion object {
        const val TAG = "SonarUna"
        const val MAX_AGE_MILLIS = 10_000L

        /** Gather readings for up to this long; the conversation already listens meanwhile. */
        const val DURATION_MILLIS = 10_000L
        const val UPDATE_INTERVAL_MILLIS = 1_000L

        /** A reading this good tells even the closest entrances apart: no need to wait for more. */
        const val GOOD_ACCURACY_METERS = 10f
    }
}
