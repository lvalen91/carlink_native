package com.carlink.navigation

import android.os.SystemClock
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logNavi
import com.carlink.logging.logWarn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Accumulated navigation state from incremental NaviJSON updates.
 *
 * The adapter sends partial JSON updates (1-5 fields per message, ~100-500ms apart).
 * This data class holds the merged state across all updates until a flush (NaviStatus=0).
 *
 * Next-step fields are populated when the adapter firmware sends a double-maneuver burst
 * (two maneuver messages within ~50ms). The first message is the current maneuver, the
 * second is the upcoming maneuver preview. Without burst detection the second would
 * overwrite the first, showing the wrong road name on the cluster.
 */
data class NavigationState(
    val status: Int = 0,               // 0=idle, 1=active, 2=calculating
    val maneuverType: Int = 0,         // CPManeuverType 0-53
    val orderType: Int = 0,            // 0=continue, 1=turn, 2=exit, 3=roundabout, 4=uturn, 5=keepLeft, 6=keepRight
    val roadName: String? = null,
    val remainDistance: Int = 0,        // Meters to next maneuver
    val distanceToDestination: Int = 0, // Total meters remaining
    val timeToDestination: Int = 0,    // Seconds to destination
    val destinationName: String? = null,
    val appName: String? = null,       // "Apple Maps" etc.
    val turnAngle: Int = 0,            // Turn angle in degrees
    val turnSide: Int = 0,             // 0=right-hand driving, 1=left-hand driving
    val junctionType: Int = 0,         // 0=intersection, 1=roundabout
    val roundaboutExit: Int = 0,       // NaviRoundaboutExit (1-19, 0=not roundabout)
    // Next-step fields — from firmware double-maneuver burst
    val nextManeuverType: Int? = null,
    val nextOrderType: Int? = null,
    val nextRoadName: String? = null,
    val nextTurnAngle: Int? = null,
    val nextJunctionType: Int? = null,
    val nextRoundaboutExit: Int? = null,
) {
    val isActive: Boolean get() = status == 1
    val isIdle: Boolean get() = status == 0
    val hasNextStep: Boolean get() = nextManeuverType != null
}

/**
 * Manages navigation state from CarPlay NaviJSON messages.
 *
 * Thread safety: Called from USB read thread, publishes via StateFlow (thread-safe).
 * Merge strategy matches LIVI normalizeNavigation.ts:
 * - Incremental: new fields merge into existing state
 * - Flush: NaviStatus=0 clears all state
 *
 * Burst detection: The CPC200-CCPA adapter firmware sends a double-maneuver burst
 * (two maneuver messages within 0.3–4ms) for transient maneuvers like depart and
 * merge. The first message is the current maneuver, the second is the upcoming
 * preview. Without detection the second overwrites the first, causing the cluster
 * to display the wrong road name. Bursts are detected by timestamp — a maneuver
 * message arriving within [BURST_THRESHOLD_MS] of a previous one is routed to the
 * next-step fields instead of overwriting current.
 */
object NavigationStateManager {
    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    /** Burst window: two maneuver messages within this threshold are current + next. */
    private const val BURST_THRESHOLD_MS = 50L

    /** Timestamp (elapsedRealtime) of the last maneuver-bearing message. */
    private var lastManeuverMs = 0L

    /**
     * Process an incoming NaviJSON payload (incremental update).
     *
     * @param payload Parsed JSON fields from MEDIA_DATA subtype 200
     */
    fun onNaviJson(payload: Map<String, Any>) {
        if (payload.isEmpty()) {
            logNavi { "[NAVI] Empty NaviJSON payload received — ignoring" }
            return
        }

        logNavi {
            "[NAVI] Received NaviJSON: keys=${payload.keys}, " +
                "values=${payload.entries.joinToString { "${it.key}=${it.value}" }}"
        }

        val naviStatus = (payload["NaviStatus"] as? Number)?.toInt()

        // Flush signal: NaviStatus=0 → clear entire state including next-step
        if (naviStatus == 0) {
            logInfo("[NAVI] Flush signal received (NaviStatus=0) — clearing state", tag = Logger.Tags.NAVI)
            _state.value = NavigationState()
            lastManeuverMs = 0
            return
        }

        // Burst detection: a maneuver-bearing message within BURST_THRESHOLD_MS of the
        // previous one is the adapter firmware's preview of the next maneuver.
        val isManeuverMessage = payload.containsKey("NaviManeuverType")
        val now = SystemClock.elapsedRealtime()
        val gapMs = now - lastManeuverMs
        val isBurst = isManeuverMessage && lastManeuverMs > 0 && gapMs < BURST_THRESHOLD_MS

        if (isManeuverMessage) {
            lastManeuverMs = now
        }

        val current = _state.value

        if (isBurst) {
            // Burst: route maneuver-specific fields to next-step slots.
            // Route-level fields (status, distance, destination, turnSide) still update current.
            val merged = current.copy(
                status = naviStatus ?: current.status,
                remainDistance = (payload["NaviRemainDistance"] as? Number)?.toInt() ?: current.remainDistance,
                distanceToDestination =
                    (payload["NaviDistanceToDestination"] as? Number)?.toInt()
                        ?: current.distanceToDestination,
                timeToDestination = (payload["NaviTimeToDestination"] as? Number)?.toInt() ?: current.timeToDestination,
                destinationName =
                    (payload["NaviDestinationName"] as? String)?.takeIf { it.isNotEmpty() }
                        ?: current.destinationName,
                appName = (payload["NaviAPPName"] as? String)?.takeIf { it.isNotEmpty() } ?: current.appName,
                turnSide = (payload["NaviTurnSide"] as? Number)?.toInt() ?: current.turnSide,
                // Maneuver fields → next-step
                nextManeuverType = (payload["NaviManeuverType"] as? Number)?.toInt(),
                nextOrderType = (payload["NaviOrderType"] as? Number)?.toInt(),
                nextRoadName = (payload["NaviRoadName"] as? String)?.takeIf { it.isNotEmpty() },
                nextTurnAngle = (payload["NaviTurnAngle"] as? Number)?.toInt(),
                nextJunctionType = (payload["NaviJunctionType"] as? Number)?.toInt(),
                nextRoundaboutExit = (payload["NaviRoundaboutExit"] as? Number)?.toInt(),
            )

            logInfo(
                "[NAVI] Burst detected (${gapMs}ms gap) — " +
                    "next-step: maneuver=${merged.nextManeuverType}, road=${merged.nextRoadName}; " +
                    "current preserved: maneuver=${merged.maneuverType}, road=${merged.roadName}",
                tag = Logger.Tags.NAVI,
            )

            _state.value = merged
            return
        }

        // Normal transition: update current maneuver fields, clear next-step
        val merged = current.copy(
            status = naviStatus ?: current.status,
            maneuverType = (payload["NaviManeuverType"] as? Number)?.toInt() ?: current.maneuverType,
            orderType = (payload["NaviOrderType"] as? Number)?.toInt() ?: current.orderType,
            roadName = (payload["NaviRoadName"] as? String)?.takeIf { it.isNotEmpty() } ?: current.roadName,
            remainDistance = (payload["NaviRemainDistance"] as? Number)?.toInt() ?: current.remainDistance,
            distanceToDestination =
                (payload["NaviDistanceToDestination"] as? Number)?.toInt()
                    ?: current.distanceToDestination,
            timeToDestination = (payload["NaviTimeToDestination"] as? Number)?.toInt() ?: current.timeToDestination,
            destinationName =
                (payload["NaviDestinationName"] as? String)?.takeIf { it.isNotEmpty() }
                    ?: current.destinationName,
            appName = (payload["NaviAPPName"] as? String)?.takeIf { it.isNotEmpty() } ?: current.appName,
            turnAngle = (payload["NaviTurnAngle"] as? Number)?.toInt() ?: current.turnAngle,
            turnSide = (payload["NaviTurnSide"] as? Number)?.toInt() ?: current.turnSide,
            junctionType = (payload["NaviJunctionType"] as? Number)?.toInt() ?: current.junctionType,
            roundaboutExit = (payload["NaviRoundaboutExit"] as? Number)?.toInt() ?: current.roundaboutExit,
            // Clear next-step on normal maneuver transition — previous preview is stale.
            // Distance-only updates (no NaviManeuverType) preserve existing next-step.
            nextManeuverType = if (isManeuverMessage) null else current.nextManeuverType,
            nextOrderType = if (isManeuverMessage) null else current.nextOrderType,
            nextRoadName = if (isManeuverMessage) null else current.nextRoadName,
            nextTurnAngle = if (isManeuverMessage) null else current.nextTurnAngle,
            nextJunctionType = if (isManeuverMessage) null else current.nextJunctionType,
            nextRoundaboutExit = if (isManeuverMessage) null else current.nextRoundaboutExit,
        )

        if (isManeuverMessage && current.hasNextStep) {
            logInfo(
                "[NAVI] Next-step cleared (normal transition, ${gapMs}ms gap) — " +
                    "was: maneuver=${current.nextManeuverType}, road=${current.nextRoadName}",
                tag = Logger.Tags.NAVI,
            )
        }

        logNavi {
            "[NAVI] State merged: status=${merged.status}, maneuver=${merged.maneuverType}, " +
                "road=${merged.roadName}, remainDist=${merged.remainDistance}m, " +
                "destDist=${merged.distanceToDestination}m, eta=${merged.timeToDestination}s, " +
                "dest=${merged.destinationName}, app=${merged.appName}, " +
                "turnSide=${merged.turnSide}, turnAngle=${merged.turnAngle}, junction=${merged.junctionType}, " +
                "roundaboutExit=${merged.roundaboutExit}" +
                if (merged.hasNextStep) ", nextManeuver=${merged.nextManeuverType}, nextRoad=${merged.nextRoadName}" else ""
        }

        _state.value = merged
    }

    /** Clear state on USB disconnect. */
    fun clear() {
        logNavi { "[NAVI] State cleared (USB disconnect or session end)" }
        _state.value = NavigationState()
        lastManeuverMs = 0
        ManeuverMapper.clearCache()
    }
}
