package com.carlink.navigation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.car.app.model.CarIcon
import androidx.core.content.res.ResourcesCompat
import androidx.car.app.navigation.model.Maneuver
import androidx.core.graphics.drawable.IconCompat
import com.carlink.R
import com.carlink.logging.logNavi
import com.carlink.logging.logWarn
import com.carlink.logging.Logger

/**
 * Maps CarPlay CPManeuverType values (0-53) to AAOS Car App Library Maneuver.TYPE_* constants
 * and provides bitmap maneuver icons for cluster display.
 *
 * Source: LIVI translateNavigation.ts (verified against iAP2 spec "Table 15-16").
 * Icon mapping cross-referenced with GM RouteStateMachine.mapManeuverType() from
 * decompiled DelayedWKSApp to ensure cluster icon compatibility.
 *
 * Icons are rasterized to 128x128 bitmaps and delivered via createWithBitmap(). With the
 * ClusterIconShimProvider claiming Templates Host's orphaned authority, these bitmaps are
 * cached and served as ManeuverIcon (contentUri) in the navstate2 protobuf — tier 1 in
 * GM's ActiveGuidanceScreen priority (ManeuverIcon > ManeuverByteArray > TurnType).
 *
 * NaviTurnSide controls U-turn direction (LEFT vs RIGHT) and roundabout rotation (CW vs CCW).
 */
object ManeuverMapper {

    private const val ICON_SIZE_PX = 128
    private val bitmapCache = HashMap<Int, Bitmap>()

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
            6  -> if (isLeftDrive) Maneuver.TYPE_ROUNDABOUT_ENTER_CW else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW // enterRoundabout
            7  -> if (isLeftDrive) Maneuver.TYPE_ROUNDABOUT_EXIT_CW else Maneuver.TYPE_ROUNDABOUT_EXIT_CCW // exitRoundabout
            8  -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT // rampOff (highway exit)
            9  -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT  // rampOn (merge onto highway)
            10 -> Maneuver.TYPE_DESTINATION           // endOfNavigation
            11 -> Maneuver.TYPE_DEPART                // proceedToRoute
            12 -> Maneuver.TYPE_DESTINATION           // arrived
            13 -> Maneuver.TYPE_KEEP_LEFT             // keepLeft
            14 -> Maneuver.TYPE_KEEP_RIGHT            // keepRight
            15 -> Maneuver.TYPE_FERRY_BOAT            // enterFerry
            16 -> Maneuver.TYPE_FERRY_BOAT            // exitFerry
            17 -> Maneuver.TYPE_FERRY_BOAT            // changeFerry
            18 -> if (isLeftDrive) Maneuver.TYPE_U_TURN_RIGHT else Maneuver.TYPE_U_TURN_LEFT // uTurnToRoute
            19 -> if (isLeftDrive) Maneuver.TYPE_ROUNDABOUT_ENTER_CW else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW // roundaboutUTurn
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
                logWarn("[NAVI] Unknown CPManeuverType=$cpType, turnSide=$turnSide — falling back to TYPE_UNKNOWN", tag = Logger.Tags.NAVI)
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

    /**
     * Map a CPManeuverType to the appropriate drawable resource for cluster display.
     */
    fun getIconResource(cpType: Int, turnSide: Int = 0): Int {
        val isLeftDrive = turnSide == 1
        return when (cpType) {
            0  -> R.drawable.ic_nav_straight
            1  -> R.drawable.ic_nav_turn_left
            2  -> R.drawable.ic_nav_turn_right
            3  -> R.drawable.ic_nav_straight
            4  -> if (isLeftDrive) R.drawable.ic_nav_uturn_right else R.drawable.ic_nav_uturn_left
            5  -> R.drawable.ic_nav_straight
            6  -> if (isLeftDrive) R.drawable.ic_nav_roundabout_cw else R.drawable.ic_nav_roundabout_ccw
            7  -> if (isLeftDrive) R.drawable.ic_nav_roundabout_cw else R.drawable.ic_nav_roundabout_ccw
            8  -> R.drawable.ic_nav_off_ramp_right
            9  -> R.drawable.ic_nav_fork_right
            10 -> R.drawable.ic_nav_destination
            11 -> R.drawable.ic_nav_depart
            12 -> R.drawable.ic_nav_destination
            13 -> R.drawable.ic_nav_fork_left
            14 -> R.drawable.ic_nav_fork_right
            15, 16, 17 -> R.drawable.ic_nav_ferry
            18 -> if (isLeftDrive) R.drawable.ic_nav_uturn_right else R.drawable.ic_nav_uturn_left
            19 -> if (isLeftDrive) R.drawable.ic_nav_roundabout_cw else R.drawable.ic_nav_roundabout_ccw
            20 -> R.drawable.ic_nav_turn_left
            21 -> R.drawable.ic_nav_turn_right
            22 -> R.drawable.ic_nav_off_ramp_left
            23 -> R.drawable.ic_nav_off_ramp_right
            24 -> R.drawable.ic_nav_destination
            25 -> R.drawable.ic_nav_destination
            26 -> if (isLeftDrive) R.drawable.ic_nav_uturn_right else R.drawable.ic_nav_uturn_left
            27 -> R.drawable.ic_nav_destination
            in 28..46 -> if (isLeftDrive) R.drawable.ic_nav_roundabout_cw else R.drawable.ic_nav_roundabout_ccw
            47 -> R.drawable.ic_nav_sharp_left
            48 -> R.drawable.ic_nav_sharp_right
            49 -> R.drawable.ic_nav_slight_left
            50 -> R.drawable.ic_nav_slight_right
            51 -> R.drawable.ic_nav_fork_right
            52 -> R.drawable.ic_nav_fork_left
            53 -> R.drawable.ic_nav_fork_right
            else -> R.drawable.ic_nav_straight
        }
    }

    /**
     * Rasterize a vector drawable resource to a bitmap, with caching.
     */
    private fun rasterizeIcon(context: Context, resId: Int): Bitmap {
        bitmapCache[resId]?.let { return it }
        val drawable = ResourcesCompat.getDrawable(context.resources, resId, context.theme)!!
        val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, ICON_SIZE_PX, ICON_SIZE_PX)
        drawable.draw(canvas)
        bitmapCache[resId] = bitmap
        return bitmap
    }

    /**
     * Build a Maneuver with a bitmap icon for GM cluster display.
     *
     * Uses createWithBitmap() — the ClusterIconShimProvider intercepts Templates Host's
     * icon caching calls, enabling tier-1 ManeuverIcon delivery to the cluster.
     *
     * @param state Current navigation state
     * @param context Context for loading drawable resources (CarContext)
     * @return Maneuver with type and bitmap icon set
     */
    fun buildManeuver(state: NavigationState, context: Context): Maneuver {
        val type = mapManeuverType(state.maneuverType, state.turnSide)
        val builder = Maneuver.Builder(type)

        getRoundaboutExitNumber(state.maneuverType)?.let {
            builder.setRoundaboutExitNumber(it)
        }

        val iconRes = getIconResource(state.maneuverType, state.turnSide)
        val bitmap = rasterizeIcon(context, iconRes)
        val icon = CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
        builder.setIcon(icon)

        return builder.build()
    }
}
