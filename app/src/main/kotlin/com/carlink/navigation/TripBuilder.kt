package com.carlink.navigation

import android.content.Context
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import java.time.Duration
import java.time.ZonedDateTime

/**
 * Shared Trip builder used by both ClusterMainSession and CarlinkClusterSession.
 */
object TripBuilder {

    fun buildTrip(state: NavigationState, context: Context): Trip {
        val tripBuilder = Trip.Builder()

        val maneuver = ManeuverMapper.buildManeuver(state, context)
        val stepBuilder = Step.Builder()
        stepBuilder.setManeuver(maneuver)
        state.roadName?.let { stepBuilder.setCue(it) }

        val stepEstimate = TravelEstimate.Builder(
            DistanceFormatter.toDistance(state.remainDistance),
            ZonedDateTime.now().plus(Duration.ofSeconds(state.timeToDestination.toLong())),
        ).build()

        tripBuilder.addStep(stepBuilder.build(), stepEstimate)

        if (state.destinationName != null || state.distanceToDestination > 0) {
            val destBuilder = Destination.Builder()
            state.destinationName?.let { destBuilder.setName(it) }

            val destEstimate = TravelEstimate.Builder(
                DistanceFormatter.toDistance(state.distanceToDestination),
                ZonedDateTime.now().plus(Duration.ofSeconds(state.timeToDestination.toLong())),
            ).build()

            tripBuilder.addDestination(destBuilder.build(), destEstimate)
        }

        tripBuilder.setLoading(false)

        return tripBuilder.build()
    }
}
