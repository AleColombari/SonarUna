package com.example.sonaruna.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import com.example.sonaruna.conversation.ApproximateLocation
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

enum class LocationFailure {
    PERMISSION_DENIED, LOCATION_DISABLED, SERVICES_UNAVAILABLE, TIMEOUT, UNAVAILABLE,
}

class LocationException(val reason: LocationFailure, cause: Throwable? = null) :
    Exception(reason.name, cause)

/** Obtains one approximate reading; never subscribes to background location updates. */
class LocationRepository(context: Context) {
    private val context = context.applicationContext

    @SuppressLint("MissingPermission") // Both foreground grants are checked below; revocation is caught.
    suspend fun currentLocation(): ApproximateLocation {
        if (!hasLocationPermission()) throw LocationException(LocationFailure.PERMISSION_DENIED)
        if (!isLocationEnabled()) throw LocationException(LocationFailure.LOCATION_DISABLED)
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) !=
            ConnectionResult.SUCCESS
        ) {
            throw LocationException(LocationFailure.SERVICES_UNAVAILABLE)
        }

        val cancellation = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setGranularity(Granularity.GRANULARITY_COARSE)
            .setMaxUpdateAgeMillis(30_000L)
            .setDurationMillis(20_000L)
            .build()

        try {
            val location = withTimeoutOrNull(22_000L) {
                LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(request, cancellation.token)
                    .await()
            } ?: throw LocationException(LocationFailure.TIMEOUT)

            return ApproximateLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy,
                timestampMillis = location.time,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: LocationException) {
            throw error
        } catch (error: SecurityException) {
            throw LocationException(LocationFailure.PERMISSION_DENIED, error)
        } catch (error: ApiException) {
            throw LocationException(LocationFailure.UNAVAILABLE, error)
        } catch (error: Exception) {
            throw LocationException(LocationFailure.UNAVAILABLE, error)
        } finally {
            cancellation.cancel()
        }
    }

    private fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun isLocationEnabled(): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }
}
