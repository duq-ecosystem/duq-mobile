package com.duq.android.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * FusedLocationProviderClient-based implementation.
 * Silently no-ops if location permission is not granted.
 */
class FusedLocationDataSource(private val context: Context) : LocationDataSource {

    companion object {
        private const val TAG = "FusedLocation"
        private const val INTERVAL_MS = 24 * 60 * 60 * 1000L  // 24 hours
        private const val SMALLEST_DISPLACEMENT_M = 25_000f   // 25 km — matches LocationReporter dedup
    }

    private val client = LocationServices.getFusedLocationProviderClient(context)

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    override suspend fun getLastLocation(): Location? {
        if (!hasPermission()) return null
        return suspendCancellableCoroutine { cont ->
            try {
                client.lastLocation
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            } catch (e: SecurityException) {
                cont.resume(null)
            }
        }
    }

    override fun significantLocationUpdates(): Flow<Location> = callbackFlow {
        if (!hasPermission()) {
            Log.w(TAG, "Location permission not granted — updates skipped")
            close()
            return@callbackFlow
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, INTERVAL_MS)
            .setMinUpdateDistanceMeters(SMALLEST_DISPLACEMENT_M)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it) }
            }
        }

        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            Log.d(TAG, "Location updates started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied: ${e.message}")
            close()
            return@callbackFlow
        }

        awaitClose {
            client.removeLocationUpdates(callback)
            Log.d(TAG, "Location updates stopped")
        }
    }
}
