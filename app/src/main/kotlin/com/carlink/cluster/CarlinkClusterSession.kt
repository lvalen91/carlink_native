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
import androidx.car.app.navigation.model.RoutingInfo
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
 * Cluster session — observes NavigationStateManager and pushes Trip updates
 * to the Templates Host via NavigationManager.
 *
 * Lifecycle:
 * - Templates Host creates this session for DISPLAY_TYPE_CLUSTER
 * - Collects NavigationState flow, batches rapid updates (~200ms debounce)
 * - Calls navigationStarted()/updateTrip()/navigationEnded() on state transitions
 */
class CarlinkClusterSession : Session() {

    private var screen: CarlinkClusterScreen? = null
    private var navigationManager: NavigationManager? = null
    private var scope: CoroutineScope? = null
    private var isNavigating = false

    override fun onCreateScreen(intent: Intent): Screen {
        logInfo("[CLUSTER] Cluster session screen created", tag = Logger.Tags.NAVI)

        val clusterScreen = CarlinkClusterScreen(carContext)
        screen = clusterScreen

        // Get NavigationManager from CarContext
        try {
            navigationManager = carContext.getCarService(NavigationManager::class.java)
            logNavi { "[CLUSTER] NavigationManager obtained successfully" }
        } catch (e: Exception) {
            logError("[CLUSTER] Failed to get NavigationManager: ${e.message}", tag = Logger.Tags.NAVI, throwable = e)
        }

        navigationManager?.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() {
                logInfo("[CLUSTER] onStopNavigation callback — ending navigation", tag = Logger.Tags.NAVI)
                isNavigating = false
            }

            override fun onAutoDriveEnabled() {
                logNavi { "[CLUSTER] Auto drive enabled" }
            }
        })

        // Start observing navigation state
        val sessionScope = CoroutineScope(Dispatchers.Main)
        scope = sessionScope

        sessionScope.launch {
            collectNavigationState()
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                logInfo("[CLUSTER] Session destroyed — cleaning up", tag = Logger.Tags.NAVI)
                if (isNavigating) {
                    try {
                        navigationManager?.navigationEnded()
                        logNavi { "[CLUSTER] navigationEnded() called on session destroy" }
                    } catch (e: Exception) {
                        logError("[CLUSTER] navigationEnded() failed on destroy: ${e.message}", tag = Logger.Tags.NAVI, throwable = e)
                    }
                    isNavigating = false
                }
                scope?.cancel()
                scope = null
                screen = null
                navigationManager = null
            }
        })

        return clusterScreen
    }

    /**
     * Collect navigation state with debounce for rapid partial updates.
     *
     * NaviJSON arrives in bursts (1-5 fields per message, ~100-500ms apart).
     * Debouncing prevents excessive updateTrip() calls to Templates Host.
     */
    private suspend fun collectNavigationState() {
        var debounceJob: Job? = null

        NavigationStateManager.state.collectLatest { state ->
            // Cancel pending debounce — new state arrived
            debounceJob?.cancel()

            // Debounce: wait 200ms for state to stabilize before pushing
            debounceJob = scope?.launch {
                delay(200)
                processStateUpdate(state)
            }
        }
    }

    private fun processStateUpdate(state: NavigationState) {
        val navManager = navigationManager
        if (navManager == null) {
            logWarn("[CLUSTER] NavigationManager is null — cannot push state update", tag = Logger.Tags.NAVI)
            return
        }

        if (state.isActive) {
            // Start navigation if not already started
            if (!isNavigating) {
                logInfo("[CLUSTER] Navigation started", tag = Logger.Tags.NAVI)
                try {
                    navManager.navigationStarted()
                    isNavigating = true
                } catch (e: Exception) {
                    logError("[CLUSTER] navigationStarted() failed: ${e.message}", tag = Logger.Tags.NAVI, throwable = e)
                    return
                }
            }

            // Build and push Trip
            try {
                val trip = buildTrip(state)
                navManager.updateTrip(trip)
                logNavi {
                    "[CLUSTER] Trip updated: maneuver=${state.maneuverType}, " +
                        "dist=${state.remainDistance}m, road=${state.roadName}, " +
                        "dest=${state.destinationName}, eta=${state.timeToDestination}s"
                }
            } catch (e: Exception) {
                logError("[CLUSTER] updateTrip() failed: ${e.message}", tag = Logger.Tags.NAVI, throwable = e)
            }

            // Update cluster screen
            screen?.updateState(state)
        } else if (state.isIdle && isNavigating) {
            // Navigation ended
            logInfo("[CLUSTER] Navigation ended (NaviStatus=0)", tag = Logger.Tags.NAVI)
            try {
                navManager.navigationEnded()
            } catch (e: Exception) {
                logError("[CLUSTER] navigationEnded() failed: ${e.message}", tag = Logger.Tags.NAVI, throwable = e)
            }
            isNavigating = false
            screen?.updateState(state)
        }
    }

    /**
     * Build a Trip object from current NavigationState.
     */
    private fun buildTrip(state: NavigationState): Trip {
        val tripBuilder = Trip.Builder()

        // Build current step with maneuver
        val maneuver = ManeuverMapper.buildManeuver(state, carContext)
        val stepBuilder = Step.Builder()
        stepBuilder.setManeuver(maneuver)
        state.roadName?.let { stepBuilder.setCue(it) }

        // Step travel estimate (distance + time to next maneuver)
        val stepEstimate = TravelEstimate.Builder(
            DistanceFormatter.toDistance(state.remainDistance),
            ZonedDateTime.now().plus(Duration.ofSeconds(state.timeToDestination.toLong())),
        ).build()

        tripBuilder.addStep(stepBuilder.build(), stepEstimate)

        // Destination info (if available)
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

/**
 * Screen rendered on the instrument cluster via Templates Host.
 *
 * Returns a NavigationTemplate with RoutingInfo showing:
 * - Current maneuver arrow (via Maneuver type)
 * - Distance to next maneuver
 * - Road name (via Step cue)
 */
class CarlinkClusterScreen(carContext: CarContext) : Screen(carContext) {

    private var currentState: NavigationState? = null

    private val actionStrip = ActionStrip.Builder()
        .addAction(Action.APP_ICON)
        .build()

    fun updateState(state: NavigationState) {
        currentState = state
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val state = currentState
        if (state == null || !state.isActive) {
            logNavi { "[CLUSTER_SCREEN] Returning empty NavigationTemplate (state=${state?.status ?: "null"})" }
            return NavigationTemplate.Builder()
                .setActionStrip(actionStrip)
                .build()
        }

        return try {
            val maneuver = ManeuverMapper.buildManeuver(state, carContext)

            val stepBuilder = Step.Builder()
            stepBuilder.setManeuver(maneuver)
            state.roadName?.let { stepBuilder.setCue(it) }

            val distance = DistanceFormatter.toDistance(state.remainDistance)

            val routingInfo = RoutingInfo.Builder()
                .setCurrentStep(stepBuilder.build(), distance)
                .build()

            logNavi {
                "[CLUSTER_SCREEN] Template built: maneuver=${state.maneuverType}, " +
                    "dist=${state.remainDistance}m, road=${state.roadName}"
            }

            NavigationTemplate.Builder()
                .setActionStrip(actionStrip)
                .setNavigationInfo(routingInfo)
                .build()
        } catch (e: Exception) {
            logError(
                "[CLUSTER_SCREEN] Failed to build NavigationTemplate: ${e.message}",
                tag = Logger.Tags.NAVI,
                throwable = e,
            )
            NavigationTemplate.Builder()
                .setActionStrip(actionStrip)
                .build()
        }
    }
}
