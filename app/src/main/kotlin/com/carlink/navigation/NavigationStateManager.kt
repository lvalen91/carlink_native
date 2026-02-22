package com.carlink.navigation

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
) {
    val isActive: Boolean get() = status == 1
    val isIdle: Boolean get() = status == 0
}

/**
 * Manages navigation state from CarPlay NaviJSON messages.
 *
 * Thread safety: Called from USB read thread, publishes via StateFlow (thread-safe).
 * Merge strategy matches LIVI normalizeNavigation.ts:
 * - Incremental: new fields merge into existing state
 * - Flush: NaviStatus=0 clears all state
 */
object NavigationStateManager {
    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

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

        // Flush signal: NaviStatus=0 → clear entire state
        if (naviStatus == 0) {
            logInfo("[NAVI] Flush signal received (NaviStatus=0) — clearing state", tag = Logger.Tags.NAVI)
            _state.value = NavigationState()
            return
        }

        // Incremental merge: update only fields present in this payload
        val current = _state.value
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
        )

        logNavi {
            "[NAVI] State merged: status=${merged.status}, maneuver=${merged.maneuverType}, " +
                "road=${merged.roadName}, remainDist=${merged.remainDistance}m, " +
                "destDist=${merged.distanceToDestination}m, eta=${merged.timeToDestination}s, " +
                "dest=${merged.destinationName}, app=${merged.appName}, " +
                "turnSide=${merged.turnSide}, turnAngle=${merged.turnAngle}, junction=${merged.junctionType}, " +
                "roundaboutExit=${merged.roundaboutExit}"
        }

        _state.value = merged
    }

    /** Clear state on USB disconnect. */
    fun clear() {
        logNavi { "[NAVI] State cleared (USB disconnect or session end)" }
        _state.value = NavigationState()
        ManeuverMapper.clearCache()
    }
}
