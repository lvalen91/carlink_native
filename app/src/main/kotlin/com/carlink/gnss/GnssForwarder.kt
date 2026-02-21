package com.carlink.gnss

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.HandlerThread
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

/**
 * Forwards Android location data as NMEA sentences to the CPC200-CCPA adapter.
 *
 * The adapter relays NMEA data to CarPlay via iAP2 LocationInformation, enabling
 * CarPlay Maps to use the vehicle's GPS position instead of the phone's.
 *
 * Generates $GPGGA (fix data) and $GPRMC (recommended minimum) sentences at ~1Hz.
 * Firmware buffer limit: NMEA payload must be < 1024 bytes.
 */
class GnssForwarder(
    private val context: Context,
    private val sendGnssData: (String) -> Boolean,
    private val logCallback: (String) -> Unit,
) {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var locationListener: LocationListener? = null
    private var handlerThread: HandlerThread? = null

    @Volatile
    private var isRunning = false

    fun start() {
        if (isRunning) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            log("Location permission not granted - GPS forwarding disabled")
            return
        }

        val listener =
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val nmea = formatNmea(location)
                    val sent = sendGnssData(nmea)
                    val acc = if (location.hasAccuracy()) "%.1fm".format(Locale.US, location.accuracy) else "n/a"
                    log(
                        "[GNSS] lat=%.5f lon=%.5f alt=%.0f spd=%.1f acc=$acc sent=$sent".format(
                            Locale.US,
                            location.latitude,
                            location.longitude,
                            location.altitude,
                            location.speed,
                        ),
                    )
                }

                override fun onProviderEnabled(provider: String) {}

                override fun onProviderDisabled(provider: String) {
                    log("[GNSS] Provider disabled: $provider")
                }

                @Deprecated("Deprecated in API 29")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }

        try {
            // Use a dedicated background thread for location callbacks.
            // sendGnssData() does a blocking USB bulkTransfer — must never run on main thread.
            val thread = HandlerThread("GNSS-Forwarder").apply { start() }
            handlerThread = thread

            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                listener,
                thread.looper,
            )
            locationListener = listener
            isRunning = true
            log("[GNSS] Started GPS forwarding (1Hz, GPS_PROVIDER, background thread)")
        } catch (e: Exception) {
            log("[GNSS] Failed to start location updates: ${e.message}")
            handlerThread?.quitSafely()
            handlerThread = null
        }
    }

    fun stop() {
        if (!isRunning) return

        locationListener?.let {
            try {
                locationManager.removeUpdates(it)
            } catch (e: Exception) {
                log("[GNSS] Error removing location updates: ${e.message}")
            }
        }
        locationListener = null
        handlerThread?.quitSafely()
        handlerThread = null
        isRunning = false
        log("[GNSS] Stopped GPS forwarding")
    }

    private fun formatNmea(location: Location): String {
        val gpgga = formatGpgga(location)
        val gprmc = formatGprmc(location)
        return gpgga + gprmc
    }

    /**
     * Format $GPGGA sentence (GPS Fix Data).
     *
     * $GPGGA,HHMMSS.SS,DDMM.MMMM,N,DDDMM.MMMM,W,Q,NN,HDOP,ALT,M,GEOID,M,,*XX\r\n
     *
     * HDOP and satellite count are derived from Location.getAccuracy() when available,
     * rather than hardcoded, so NMEA reflects actual GPS quality (important for iPhone
     * fusion engine accuracy comparison).
     */
    private fun formatGpgga(location: Location): String {
        val time = formatUtcTime(location.time)
        val (lat, latDir) = toNmeaLatitude(location.latitude)
        val (lon, lonDir) = toNmeaLongitude(location.longitude)
        val alt = "%.1f".format(Locale.US, location.altitude)

        // Derive HDOP from accuracy: accuracy ≈ HDOP × UERE (~5m civilian GPS)
        // Clamp to 0.5-25.0 (valid NMEA range)
        val hdop = if (location.hasAccuracy()) {
            "%.1f".format(Locale.US, min(25.0, max(0.5, location.accuracy / 5.0)))
        } else {
            "1.0"
        }

        // Use real satellite count from extras if available, otherwise estimate from accuracy
        val satellites = location.extras?.getInt("satellites", 0) ?: 0
        val satCount = if (satellites > 0) {
            "%02d".format(Locale.US, min(satellites, 24))
        } else if (location.hasAccuracy()) {
            // Rough estimate: better accuracy → more satellites
            val est = when {
                location.accuracy < 3f -> 12
                location.accuracy < 10f -> 8
                location.accuracy < 30f -> 5
                else -> 4
            }
            "%02d".format(Locale.US, est)
        } else {
            "08"
        }

        val body = "GPGGA,$time,$lat,$latDir,$lon,$lonDir,1,$satCount,$hdop,$alt,M,0.0,M,,"
        val checksum = computeNmeaChecksum(body)
        return "\$$body*$checksum\r\n"
    }

    /**
     * Format $GPRMC sentence (Recommended Minimum).
     *
     * $GPRMC,HHMMSS.SS,A,DDMM.MMMM,N,DDDMM.MMMM,W,SPEED,COURSE,DDMMYY,,,A*XX\r\n
     */
    private fun formatGprmc(location: Location): String {
        val time = formatUtcTime(location.time)
        val date = formatUtcDate(location.time)
        val (lat, latDir) = toNmeaLatitude(location.latitude)
        val (lon, lonDir) = toNmeaLongitude(location.longitude)
        val speedKnots = "%.1f".format(Locale.US, location.speed * 1.94384)
        val course =
            if (location.hasBearing()) {
                "%.1f".format(Locale.US, location.bearing)
            } else {
                "0.0"
            }

        val body = "GPRMC,$time,A,$lat,$latDir,$lon,$lonDir,$speedKnots,$course,$date,,,A"
        val checksum = computeNmeaChecksum(body)
        return "\$$body*$checksum\r\n"
    }

    /**
     * NMEA checksum: XOR of all characters between $ and * (exclusive).
     */
    private fun computeNmeaChecksum(body: String): String {
        var checksum = 0
        for (c in body) {
            checksum = checksum xor c.code
        }
        return "%02X".format(checksum)
    }

    /**
     * Convert decimal degrees latitude to NMEA format DDMM.MMMM,N/S.
     */
    private fun toNmeaLatitude(lat: Double): Pair<String, String> {
        val absLat = Math.abs(lat)
        val degrees = absLat.toInt()
        val minutes = (absLat - degrees) * 60.0
        val formatted = "%02d%07.4f".format(Locale.US, degrees, minutes)
        val dir = if (lat >= 0) "N" else "S"
        return Pair(formatted, dir)
    }

    /**
     * Convert decimal degrees longitude to NMEA format DDDMM.MMMM,E/W.
     */
    private fun toNmeaLongitude(lon: Double): Pair<String, String> {
        val absLon = Math.abs(lon)
        val degrees = absLon.toInt()
        val minutes = (absLon - degrees) * 60.0
        val formatted = "%03d%07.4f".format(Locale.US, degrees, minutes)
        val dir = if (lon >= 0) "E" else "W"
        return Pair(formatted, dir)
    }

    /** Format UTC time as HHMMSS.SS */
    private fun formatUtcTime(timeMs: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = timeMs
        return "%02d%02d%02d.00".format(
            Locale.US,
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND),
        )
    }

    /** Format UTC date as DDMMYY */
    private fun formatUtcDate(timeMs: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = timeMs
        return "%02d%02d%02d".format(
            Locale.US,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.YEAR) % 100,
        )
    }

    private fun log(message: String) {
        logCallback(message)
    }
}
