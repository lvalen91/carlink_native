package com.carlink.navigation

import android.content.Context
import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.Maneuver
import androidx.core.graphics.drawable.IconCompat
import com.carlink.logging.Logger
import com.carlink.logging.logNavi
import com.carlink.logging.logWarn

/**
 * Maps CarPlay CPManeuverType values (0-53) to AAOS Car App Library Maneuver.TYPE_* constants
 * and provides resource-based maneuver icons for cluster display.
 *
 * Source: LIVI translateNavigation.ts (verified against iAP2 spec "Table 15-16").
 * Icon mapping cross-referenced with GM RouteStateMachine.mapManeuverType() from
 * decompiled DelayedWKSApp to ensure cluster icon compatibility.
 *
 * Icons use createWithResource() for stable IPC hashing. The ClusterIconShimProvider
 * intercepts Templates Host's icon caching calls, enabling tier-1 ManeuverIcon delivery
 * to the cluster.
 *
 * NaviTurnSide controls U-turn direction (LEFT vs RIGHT) and roundabout rotation (CW vs CCW).
 */
object ManeuverMapper {

    /** Cache built Maneuver objects by (cpType shl 4) or turnSide to avoid icon thrashing. */
    private val maneuverCache = HashMap<Int, Maneuver>()

    /**
     * Map a CPManeuverType + turnSide to a Maneuver.TYPE_* constant.
     *
     * @param cpType CPManeuverType value (0-53)
     * @param turnSide 0=right-hand driving (default), 1=left-hand driving
     * @return Maneuver type constant
     */
    fun mapManeuverType(cpType: Int, turnSide: Int = 0): Int {
        val isLeftDrive = turnSide == 1
        val mapped = when (cpType) {
            0  -> Maneuver.TYPE_STRAIGHT             // noTurn
            1  -> Maneuver.TYPE_TURN_NORMAL_LEFT     // left
            2  -> Maneuver.TYPE_TURN_NORMAL_RIGHT    // right
            3  -> Maneuver.TYPE_STRAIGHT             // straight
            4  -> if (isLeftDrive) Maneuver.TYPE_U_TURN_RIGHT else Maneuver.TYPE_U_TURN_LEFT // uTurn
            5  -> Maneuver.TYPE_STRAIGHT             // followRoad
            6  -> if (isLeftDrive) Maneuver.TYPE_ROUNDABOUT_ENTER_CW // enterRoundabout
                 else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
            7  -> if (isLeftDrive) Maneuver.TYPE_ROUNDABOUT_EXIT_CW // exitRoundabout
                 else Maneuver.TYPE_ROUNDABOUT_EXIT_CCW
            8  -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT // rampOff (highway exit)
            9  -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT  // rampOn (merge onto highway)
            10 -> Maneuver.TYPE_DESTINATION           // endOfNavigation
            11 -> Maneuver.TYPE_DEPART                // proceedToRoute
            12 -> Maneuver.TYPE_DESTINATION           // arrived
            13 -> Maneuver.TYPE_KEEP_LEFT             // keepLeft
            14 -> Maneuver.TYPE_KEEP_RIGHT            // keepRight
            18 -> if (isLeftDrive) Maneuver.TYPE_U_TURN_RIGHT // uTurnToRoute
                 else Maneuver.TYPE_U_TURN_LEFT
            19 -> if (isLeftDrive) Maneuver.TYPE_ROUNDABOUT_ENTER_CW // roundaboutUTurn
                 else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
            20 -> Maneuver.TYPE_TURN_NORMAL_LEFT      // endOfRoadLeft
            21 -> Maneuver.TYPE_TURN_NORMAL_RIGHT     // endOfRoadRight
            22 -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT  // rampOffLeft
            23 -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT // rampOffRight
            24 -> Maneuver.TYPE_DESTINATION_LEFT      // arrivedLeft
            25 -> Maneuver.TYPE_DESTINATION_RIGHT     // arrivedRight
            26 -> if (isLeftDrive) Maneuver.TYPE_U_TURN_RIGHT else Maneuver.TYPE_U_TURN_LEFT // uTurnWhenPossible
            27 -> Maneuver.TYPE_DESTINATION           // endOfDirections
            in 28..46 -> {
                // Roundabout exit 1-19 (type - 27 = exit number)
                if (isLeftDrive) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
                else Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
            }
            47 -> Maneuver.TYPE_TURN_SHARP_LEFT       // sharpLeft
            48 -> Maneuver.TYPE_TURN_SHARP_RIGHT      // sharpRight
            49 -> Maneuver.TYPE_TURN_SLIGHT_LEFT      // slightLeft
            50 -> Maneuver.TYPE_TURN_SLIGHT_RIGHT     // slightRight
            51 -> Maneuver.TYPE_KEEP_RIGHT            // changeHighway (fork)
            52 -> Maneuver.TYPE_KEEP_LEFT             // changeHighwayLeft
            53 -> Maneuver.TYPE_KEEP_RIGHT            // changeHighwayRight
            else -> {
                logWarn(
                    "[NAVI] Unknown CPManeuverType=$cpType, turnSide=$turnSide — falling back to TYPE_UNKNOWN",
                    tag = Logger.Tags.NAVI,
                )
                Maneuver.TYPE_UNKNOWN
            }
        }

        logNavi { "[NAVI] Mapped CPManeuverType=$cpType (turnSide=$turnSide) → Maneuver.TYPE=$mapped" }
        return mapped
    }

    /**
     * Get roundabout exit number for types 28-46.
     *
     * @return Exit number (1-19), or null if not a roundabout exit type
     */
    fun getRoundaboutExitNumber(cpType: Int): Int? {
        val exitNumber = if (cpType in 28..46) cpType - 27 else null
        if (exitNumber != null) {
            logNavi { "[NAVI] Roundabout exit number: $exitNumber (cpType=$cpType)" }
        }
        return exitNumber
    }

    /** Evict maneuver cache on disconnect. */
    fun clearCache() {
        maneuverCache.clear()
    }

    /**
     * Build a Maneuver with a resource icon for GM cluster display.
     *
     * @param state Current navigation state
     * @param context Context for loading VectorDrawable resources
     * @return Maneuver with type and resource icon set
     */
    fun buildManeuver(state: NavigationState, context: Context): Maneuver {
        return buildManeuverForType(state.maneuverType, state.turnSide, context)
    }

    /**
     * Build a Maneuver from explicit CPManeuverType and turnSide values.
     *
     * Used by [TripBuilder] for the next-step maneuver where the fields come from
     * [NavigationState.nextManeuverType] rather than the current state.
     */
    fun buildManeuverForType(cpType: Int, turnSide: Int, context: Context): Maneuver {
        val cacheKey = (cpType shl 4) or turnSide
        maneuverCache[cacheKey]?.let { return it }

        val type = mapManeuverType(cpType, turnSide)
        val builder = Maneuver.Builder(type)

        getRoundaboutExitNumber(cpType)?.let {
            builder.setRoundaboutExitNumber(it)
        }

        // Use resource icon — hashes by (packageName, resId), stable across IPC.
        // createWithBitmap() uses Bitmap.identityHashCode which changes on every
        // IPC deserialization, causing Templates Host to generate a new iconId
        // every ~1s distance update → ClusterIconShimProvider cache thrashing.
        val resId = ManeuverIconRenderer.drawableForManeuver(cpType)
        val icon = CarIcon.Builder(IconCompat.createWithResource(context, resId)).build()
        builder.setIcon(icon)

        return builder.build().also { maneuverCache[cacheKey] = it }
    }
}
