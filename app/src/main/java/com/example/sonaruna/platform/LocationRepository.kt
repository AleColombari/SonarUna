package com.example.sonaruna.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import com.example.sonaruna.guia.Position
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Obtains one foreground reading to suggest where the person is; never subscribes to background updates.
 * Location is optional: any failure returns null and the conversation simply asks where the person is.
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
        val request = CurrentLocationRequest.Builder()
            .setPriority(if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            .setMaxUpdateAgeMillis(MAX_AGE_MILLIS)
            .setDurationMillis(DURATION_MILLIS)
            .build()
        val cancellation = CancellationTokenSource()
        return try {
            withTimeoutOrNull(DURATION_MILLIS + 1_000L) {
                LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(request, cancellation.token)
                    .await()
            }?.let { Position(it.latitude, it.longitude, it.accuracy.toDouble()) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } finally {
            cancellation.cancel()
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
        const val MAX_AGE_MILLIS = 10_000L
        const val DURATION_MILLIS = 8_000L
    }
}
