package com.carlink.navigation

import android.content.Context
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import com.carlink.logging.logNavi
import java.time.Duration
import java.time.ZonedDateTime

/**
 * Shared Trip builder for cluster navigation display.
 *
 * Builds a [Trip] with the current maneuver step and, when the adapter firmware sends
 * a double-maneuver burst, an additional next step. Trip steps are ordered — the first
 * is what the driver needs to do now, the second is what comes after.
 */
object TripBuilder {

    fun buildTrip(state: NavigationState, context: Context): Trip {
        val tripBuilder = Trip.Builder()
        val eta = ZonedDateTime.now().plus(Duration.ofSeconds(state.timeToDestination.toLong()))

        // Current step
        val maneuver = ManeuverMapper.buildManeuver(state, context)
        val stepBuilder = Step.Builder()
        stepBuilder.setManeuver(maneuver)
        state.roadName?.let { stepBuilder.setCue(it) }

        val stepEstimate = TravelEstimate.Builder(
            DistanceFormatter.toDistance(state.remainDistance),
            eta,
        ).build()

        tripBuilder.addStep(stepBuilder.build(), stepEstimate)

        // Next step — from firmware double-maneuver burst
        if (state.hasNextStep) {
            val nextManeuver = ManeuverMapper.buildManeuverForType(
                state.nextManeuverType!!,
                state.turnSide,
                context,
            )
            val nextStepBuilder = Step.Builder()
            nextStepBuilder.setManeuver(nextManeuver)
            state.nextRoadName?.let { nextStepBuilder.setCue(it) }

            // No meaningful distance to the next-next maneuver — use destination estimate
            // as a placeholder. The cluster primarily shows the current step's distance;
            // the next step is a preview (icon + road name).
            val nextStepEstimate = TravelEstimate.Builder(
                DistanceFormatter.toDistance(state.distanceToDestination),
                eta,
            ).build()

            tripBuilder.addStep(nextStepBuilder.build(), nextStepEstimate)

            logNavi {
                "[TRIP] Next step added: maneuver=${state.nextManeuverType}, road=${state.nextRoadName}"
            }
        }

        if (state.destinationName != null || state.distanceToDestination > 0) {
            val destBuilder = Destination.Builder()
            state.destinationName?.let { destBuilder.setName(it) }

            val destEstimate = TravelEstimate.Builder(
                DistanceFormatter.toDistance(state.distanceToDestination),
                eta,
            ).build()

            tripBuilder.addDestination(destBuilder.build(), destEstimate)
        }

        tripBuilder.setLoading(false)

        return tripBuilder.build()
    }
}
