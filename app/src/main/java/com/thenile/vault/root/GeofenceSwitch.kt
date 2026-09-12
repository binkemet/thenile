package com.thenile.vault.root

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.util.Log
import com.thenile.vault.state.SettingsManager
import com.thenile.vault.state.VaultState
import com.thenile.vault.state.VaultStateManager
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Hides the selected vaults when the phone leaves a saved safe zone (home/work).
 *
 *  Uses LocationManager.addProximityAlert rather than the Play Services Geofencing API, so the
 *  app stays GMS-free for F-Droid. The trade-off is that proximity alerts are coarser and can be
 *  noisy, so an exit is double-checked against the last known location with [isOutsideRadius]
 *  before anything gets hidden — a single bad fix shouldn't lock the user out. */
object GeofenceSwitch {
    private const val TAG = "GeofenceSwitch"
    const val ACTION_PROXIMITY = "com.thenile.vault.action.GEOFENCE_PROXIMITY"

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(ACTION_PROXIMITY).setPackage(context.packageName)
        // addProximityAlert fills in KEY_PROXIMITY_ENTERING at delivery, so the PendingIntent MUST
        // be mutable — an immutable one throws "pending intent must be mutable" on arm. FLAG_MUTABLE
        // only exists on API 31+; pre-31 PendingIntents are mutable by default.
        val mutableFlag = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag
        )
    }

    /** Pure/testable great-circle (haversine) check — self-contained rather than
     *  Location.distanceBetween so it runs in a plain JVM unit test. Returns true when the point
     *  is further from the centre than `radiusMeters`. */
    fun isOutsideRadius(
        latitude: Double, longitude: Double,
        centerLatitude: Double, centerLongitude: Double,
        radiusMeters: Int
    ): Boolean = haversineMeters(latitude, longitude, centerLatitude, centerLongitude) > radiusMeters

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0 // mean Earth radius, metres
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Re-registers the proximity alert from the saved settings. Call on save and on boot.
     *  Silently does nothing without the location permission — the UI surfaces that state. */
    @SuppressLint("MissingPermission")
    fun reschedule(context: Context) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val pi = pendingIntent(context)
        try {
            lm.removeProximityAlert(pi)
            val settings = SettingsManager.getInstance(context)
            if (!settings.geofenceSwitchEnabled || !settings.geofenceHasLocation) return
            lm.addProximityAlert(
                settings.geofenceLatitude,
                settings.geofenceLongitude,
                settings.geofenceRadiusMeters.toFloat(),
                -1L, // never expires
                pi
            )
            SecureLog.i(TAG, "proximity alert armed: r=${settings.geofenceRadiusMeters}m")
        } catch (e: SecurityException) {
            Log.w(TAG, "location permission missing — geofence not armed", e)
        }
    }

    /** Called by GeofenceReceiver. `entering` comes from KEY_PROXIMITY_ENTERING. */
    @SuppressLint("MissingPermission")
    fun onProximityChanged(context: Context, entering: Boolean) {
        if (entering) return
        val settings = SettingsManager.getInstance(context)
        if (!settings.geofenceSwitchEnabled || !settings.geofenceHasLocation) return

        // Double-check against a real fix before acting on a possibly-noisy alert.
        val last = lastKnownLocation(context)
        if (last != null && !isOutsideRadius(
                last.latitude, last.longitude,
                settings.geofenceLatitude, settings.geofenceLongitude,
                settings.geofenceRadiusMeters
            )
        ) {
            SecureLog.i(TAG, "exit alert contradicted by last known fix — ignoring")
            return
        }

        val targetIds = settings.geofenceVaultIds
        if (targetIds.isEmpty()) return
        SecureLog.w(TAG, "geofence exit: hiding $targetIds")
        AuditLog.record(context, "Left the safe zone — hid ${targetIds.size} vault(s)")
        for (vault in settings.vaults.filter { it.id in targetIds }) {
            StorageMountManager.unmountAndLock(vault.packages, vault.directories, vault.dummyDirectories, vault.files, context = context)
        }
        VaultStateManager.getInstance(context).updateState(VaultState.LOCKED)
    }

    /** Best available fix without subscribing to updates — used to set the safe zone and to
     *  sanity-check exits. Null if there's no permission or no cached fix yet. */
    @SuppressLint("MissingPermission")
    fun lastKnownLocation(context: Context): Location? = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.getProviders(true)
            .mapNotNull { lm.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
    } catch (e: SecurityException) {
        null
    }
}
