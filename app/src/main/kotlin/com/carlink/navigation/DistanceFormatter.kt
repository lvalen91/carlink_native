package com.carlink.navigation

import androidx.car.app.model.Distance
import java.util.Locale

/**
 * Converts raw meter values from iAP2 NaviJSON to locale-appropriate Distance objects.
 *
 * iAP2 always sends distances in meters (NaviRemainDistance, NaviDistanceToDestination).
 * The Car App Library Distance.create() requires a display unit — it does NOT auto-convert.
 * GM's native CINEMO path provides pre-formatted strings; we must convert ourselves.
 *
 * Imperial (US/UK/MM): < 1000 ft (305m) → feet, >= 1000 ft → miles
 * Metric (everywhere else): < 1000m → meters, >= 1000m → kilometers
 */
object DistanceFormatter {
    private const val METERS_PER_FOOT = 0.3048
    private const val METERS_PER_MILE = 1609.344
    private const val FEET_THRESHOLD = 305 // ~1000 feet
    private const val KM_THRESHOLD = 1000

    /** Countries that use imperial units for road distances. */
    private val IMPERIAL_COUNTRIES = setOf("US", "GB", "MM", "LR")

    /**
     * Convert raw meters to a locale-appropriate Distance for display.
     *
     * @param meters Raw distance in meters from NaviJSON
     * @return Distance object with correct display unit and converted value
     */
    fun toDistance(meters: Int): Distance =
        if (isImperial()) {
            toImperial(meters)
        } else {
            toMetric(meters)
        }

    private fun toImperial(meters: Int): Distance =
        if (meters < FEET_THRESHOLD) {
            // Display in feet, rounded to nearest 50 for readability
            val feet = meters / METERS_PER_FOOT
            val rounded = (Math.round(feet / 50.0) * 50).coerceAtLeast(50).toDouble()
            Distance.create(rounded, Distance.UNIT_FEET)
        } else {
            // Display in miles with 1 decimal place
            val miles = meters / METERS_PER_MILE
            Distance.create(miles, Distance.UNIT_MILES_P1)
        }

    private fun toMetric(meters: Int): Distance =
        if (meters < KM_THRESHOLD) {
            // Display in meters, rounded to nearest 50 for readability
            val rounded = (Math.round(meters / 50.0) * 50).coerceAtLeast(50).toDouble()
            Distance.create(rounded, Distance.UNIT_METERS)
        } else {
            // Display in kilometers with 1 decimal place
            val km = meters / 1000.0
            Distance.create(km, Distance.UNIT_KILOMETERS_P1)
        }

    private fun isImperial(): Boolean = Locale.getDefault().country in IMPERIAL_COUNTRIES
}
