package com.carlink.cluster

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.carlink.logging.Logger
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import com.carlink.logging.logNavi
import com.carlink.logging.logWarn
import com.carlink.navigation.DistanceFormatter
import com.carlink.navigation.ManeuverMapper
import com.carlink.navigation.NavigationState
import com.carlink.navigation.NavigationStateManager
import androidx.car.app.model.Distance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZonedDateTime

/**
 * Navigation relay session for DISPLAY_TYPE_MAIN.
 *
 * This session exists to trigger the Templates Host binding chain:
 * 1. CarAppActivity binds to Templates Host → creates this session (DISPLAY_TYPE_MAIN)
 * 2. This session calls navigationStarted() → Templates Host creates cluster display
 * 3. Templates Host creates CarlinkClusterSession (DISPLAY_TYPE_CLUSTER)
 *
 * No video, no audio, no USB. Returns an empty NavigationTemplate.
 * The actual cluster UI is rendered by CarlinkClusterScreen in CarlinkClusterSession.
 * MainActivity owns all projection pipelines.
 */
class ClusterMainSession : Session() {

    private var navigationManager: NavigationManager? = null
    private var scope: CoroutineScope? = null
    private var isNavigating = false
    /** Only call navigationEnded() after we've seen at least one active state transition to idle.
     *  Without this, the initial idle state from NavigationStateManager kills the binding chain
     *  before Templates Host can create the cluster session (displayType=1). */
    private var hasSeenActiveNav = false

    override fun onCreateScreen(intent: Intent): Screen {
        ClusterBindingState.sessionAlive = true
        logInfo("[CLUSTER_MAIN] Navigation relay session screen created", tag = Logger.Tags.CLUSTER)

        // Get NavigationManager — needed for navigationStarted() which triggers cluster creation
        try {
            navigationManager = carContext.getCarService(NavigationManager::class.java)
            logInfo("[CLUSTER_MAIN] NavigationManager obtained", tag = Logger.Tags.CLUSTER)
        } catch (e: Exception) {
            logError(
                "[CLUSTER_MAIN] Failed to get NavigationManager: ${e.message}",
                tag = Logger.Tags.CLUSTER,
                throwable = e,
            )
        }

        // Set NavigationManagerCallback BEFORE calling navigationStarted() — Templates Host
        // requires the callback to be set first, otherwise navigationStarted() throws.
        // Matches CarlinkProjectionScreen init pattern in carlink_native.
        navigationManager?.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() {
                logInfo("[CLUSTER_MAIN] onStopNavigation callback", tag = Logger.Tags.CLUSTER)
                isNavigating = false
            }

            override fun onAutoDriveEnabled() {
                logNavi { "[CLUSTER_MAIN] Auto drive enabled" }
            }
        })

        // Call navigationStarted() IMMEDIATELY — this is the critical trigger that causes
        // Templates Host to create ClusterTurnCardActivity on the cluster display, which
        // in turn creates CarlinkClusterSession (DISPLAY_TYPE_CLUSTER).
        // Without this, Templates Host never creates the cluster session.
        // Matches CarlinkProjectionScreen:101 in carlink_native.
        try {
            navigationManager?.navigationStarted()
            isNavigating = true
            logInfo("[CLUSTER_MAIN] navigationStarted() called", tag = Logger.Tags.CLUSTER)
        } catch (e: Exception) {
            logWarn("[CLUSTER_MAIN] navigationStarted() failed: ${e.message}", tag = Logger.Tags.CLUSTER)
        }

        // Observe NavigationStateManager to relay Trip updates
        val sessionScope = CoroutineScope(Dispatchers.Main)
        scope = sessionScope

        sessionScope.launch {
            collectNavigationState()
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                ClusterBindingState.sessionAlive = false
                logInfo("[CLUSTER_MAIN] Session destroyed — cleaning up", tag = Logger.Tags.CLUSTER)
                if (isNavigating) {
                    try {
                        navigationManager?.navigationEnded()
                        logNavi { "[CLUSTER_MAIN] navigationEnded() called on destroy" }
                    } catch (e: Exception) {
                        logError(
                            "[CLUSTER_MAIN] navigationEnded() failed on destroy: ${e.message}",
                            tag = Logger.Tags.CLUSTER,
                            throwable = e,
                        )
                    }
                    isNavigating = false
                }
                scope?.cancel()
                scope = null
                navigationManager = null
            }
        })

        return RelayScreen(carContext)
    }

    /**
     * Collect navigation state with 200ms debounce, matching CarlinkClusterSession.
     */
    private suspend fun collectNavigationState() {
        var debounceJob: Job? = null

        NavigationStateManager.state.collectLatest { state ->
            debounceJob?.cancel()

            debounceJob = scope?.launch {
                delay(200)
                processStateUpdate(state)
            }
        }
    }

    private fun processStateUpdate(state: NavigationState) {
        val navManager = navigationManager
        if (navManager == null) {
            logWarn("[CLUSTER_MAIN] NavigationManager is null — cannot relay", tag = Logger.Tags.CLUSTER)
            return
        }

        if (state.isActive) {
            hasSeenActiveNav = true

            // Re-start navigation if it was ended by a previous flush
            if (!isNavigating) {
                logInfo("[CLUSTER_MAIN] navigationStarted() (re-start)", tag = Logger.Tags.CLUSTER)
                try {
                    navManager.navigationStarted()
                    isNavigating = true
                } catch (e: Exception) {
                    logError(
                        "[CLUSTER_MAIN] navigationStarted() failed: ${e.message}",
                        tag = Logger.Tags.CLUSTER,
                        throwable = e,
                    )
                    return
                }
            }

            try {
                val trip = buildTrip(state)
                navManager.updateTrip(trip)
                logNavi {
                    "[CLUSTER_MAIN] Trip relayed: maneuver=${state.maneuverType}, " +
                        "dist=${state.remainDistance}m, road=${state.roadName}, " +
                        "nextStep=${state.nextStep?.let { "maneuver=${it.maneuverType}, road=${it.roadName}" } ?: "null"}"
                }
            } catch (e: Exception) {
                logError(
                    "[CLUSTER_MAIN] updateTrip() failed: ${e.message}",
                    tag = Logger.Tags.CLUSTER,
                    throwable = e,
                )
            }
        } else if (state.isIdle && isNavigating && hasSeenActiveNav) {
            // Only end navigation if we previously saw active nav data.
            // The initial idle state must NOT kill the binding chain.
            logInfo("[CLUSTER_MAIN] navigationEnded() (NaviStatus=0)", tag = Logger.Tags.CLUSTER)
            try {
                navManager.navigationEnded()
            } catch (e: Exception) {
                logError(
                    "[CLUSTER_MAIN] navigationEnded() failed: ${e.message}",
                    tag = Logger.Tags.CLUSTER,
                    throwable = e,
                )
            }
            isNavigating = false
        }
    }

    /**
     * Build a Trip from NavigationState — same logic as CarlinkClusterSession.buildTrip().
     */
    private fun buildTrip(state: NavigationState): Trip {
        val tripBuilder = Trip.Builder()

        val maneuver = ManeuverMapper.buildManeuver(state, carContext)
        val stepBuilder = Step.Builder()
        stepBuilder.setManeuver(maneuver)
        state.roadName?.let { stepBuilder.setCue(it) }

        val stepEstimate = TravelEstimate.Builder(
            DistanceFormatter.toDistance(state.remainDistance),
            ZonedDateTime.now().plus(Duration.ofSeconds(state.timeToDestination.toLong())),
        ).build()

        tripBuilder.addStep(stepBuilder.build(), stepEstimate)

        // Secondary step (upcoming maneuver from second message in burst)
        state.nextStep?.let { next ->
            val nextManeuver = ManeuverMapper.buildManeuver(next, carContext)
            val nextStepBuilder = Step.Builder()
            nextStepBuilder.setManeuver(nextManeuver)
            next.roadName?.let { nextStepBuilder.setCue(it) }

            val nextEstimate = TravelEstimate.Builder(
                Distance.create(0.0, Distance.UNIT_METERS),
                ZonedDateTime.now(),
            ).build()

            tripBuilder.addStep(nextStepBuilder.build(), nextEstimate)
        }

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

    /**
     * Empty screen — no RoutingInfo, no video. Just a valid NavigationTemplate
     * to satisfy Templates Host requirements for the main session.
     */
    private class RelayScreen(carContext: CarContext) : Screen(carContext) {
        override fun onGetTemplate(): Template {
            return NavigationTemplate.Builder()
                .setActionStrip(
                    ActionStrip.Builder()
                        .addAction(Action.APP_ICON)
                        .build()
                )
                .build()
        }
    }
}
