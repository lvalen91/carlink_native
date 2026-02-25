package com.carlink.navigation

import androidx.annotation.DrawableRes
import com.carlink.R

/**
 * Maps CPManeuverType (0-53) to VectorDrawable resource IDs for cluster display.
 *
 * Roundabout exit types (28-46) all use the generic enter-roundabout icon because the
 * adapter does not forward exit angle/junction geometry data from iAP2, making per-exit
 * icons incorrect for roundabouts with varying spoke counts.
 */
object ManeuverIconRenderer {

    /** 1:1 mapping: array index == CPManeuverType value. */
    private val CP_DRAWABLES = intArrayOf(
        R.drawable.cp_maneuver_00_no_turn,                  // 0
        R.drawable.cp_maneuver_01_left_turn,                // 1
        R.drawable.cp_maneuver_02_right_turn,               // 2
        R.drawable.cp_maneuver_03_straight_ahead,           // 3
        R.drawable.cp_maneuver_04_uturn,                    // 4
        R.drawable.cp_maneuver_05_follow_road,              // 5
        R.drawable.cp_maneuver_06_enter_roundabout,         // 6
        R.drawable.cp_maneuver_07_exit_roundabout,          // 7
        R.drawable.cp_maneuver_08_off_ramp,                 // 8
        R.drawable.cp_maneuver_09_on_ramp,                  // 9
        R.drawable.cp_maneuver_10_arrive_end_of_navigation, // 10
        R.drawable.cp_maneuver_11_start_route,              // 11
        R.drawable.cp_maneuver_12_arrive_at_destination,    // 12
        R.drawable.cp_maneuver_13_keep_left,                // 13
        R.drawable.cp_maneuver_14_keep_right,               // 14
        R.drawable.cp_maneuver_15_enter_ferry,              // 15
        R.drawable.cp_maneuver_16_exit_ferry,               // 16
        R.drawable.cp_maneuver_17_change_ferry,             // 17
        R.drawable.cp_maneuver_18_start_route_with_uturn,   // 18
        R.drawable.cp_maneuver_19_uturn_at_roundabout,      // 19
        R.drawable.cp_maneuver_20_left_turn_at_end,         // 20
        R.drawable.cp_maneuver_21_right_turn_at_end,        // 21
        R.drawable.cp_maneuver_22_highway_off_ramp_left,    // 22
        R.drawable.cp_maneuver_23_highway_off_ramp_right,   // 23
        R.drawable.cp_maneuver_24_arrive_at_destination_left,  // 24
        R.drawable.cp_maneuver_25_arrive_at_destination_right, // 25
        R.drawable.cp_maneuver_26_uturn_when_possible,      // 26
        R.drawable.cp_maneuver_27_arrive_end_of_directions, // 27
        // 28-46: Roundabout exits use generic roundabout icon.
        // Per-exit drawables removed; adapter strips junction geometry.
        R.drawable.cp_maneuver_06_enter_roundabout,         // 28
        R.drawable.cp_maneuver_06_enter_roundabout,         // 29
        R.drawable.cp_maneuver_06_enter_roundabout,         // 30
        R.drawable.cp_maneuver_06_enter_roundabout,         // 31
        R.drawable.cp_maneuver_06_enter_roundabout,         // 32
        R.drawable.cp_maneuver_06_enter_roundabout,         // 33
        R.drawable.cp_maneuver_06_enter_roundabout,         // 34
        R.drawable.cp_maneuver_06_enter_roundabout,         // 35
        R.drawable.cp_maneuver_06_enter_roundabout,         // 36
        R.drawable.cp_maneuver_06_enter_roundabout,         // 37
        R.drawable.cp_maneuver_06_enter_roundabout,         // 38
        R.drawable.cp_maneuver_06_enter_roundabout,         // 39
        R.drawable.cp_maneuver_06_enter_roundabout,         // 40
        R.drawable.cp_maneuver_06_enter_roundabout,         // 41
        R.drawable.cp_maneuver_06_enter_roundabout,         // 42
        R.drawable.cp_maneuver_06_enter_roundabout,         // 43
        R.drawable.cp_maneuver_06_enter_roundabout,         // 44
        R.drawable.cp_maneuver_06_enter_roundabout,         // 45
        R.drawable.cp_maneuver_06_enter_roundabout,         // 46
        R.drawable.cp_maneuver_47_sharp_left_turn,          // 47
        R.drawable.cp_maneuver_48_sharp_right_turn,         // 48
        R.drawable.cp_maneuver_49_slight_left_turn,         // 49
        R.drawable.cp_maneuver_50_slight_right_turn,        // 50
        R.drawable.cp_maneuver_51_change_highway,           // 51
        R.drawable.cp_maneuver_52_change_highway_left,      // 52
        R.drawable.cp_maneuver_53_change_highway_right,     // 53
    )

    @DrawableRes
    fun drawableForManeuver(cpType: Int): Int {
        // Roundabout exit types 28-46: use generic roundabout icon.
        // The adapter strips junction geometry (JunctionElementAngle/ExitAngle),
        // so per-exit-number icons show incorrect angular positions.
        if (cpType in 28..46) return CP_DRAWABLES[6] // cp_maneuver_06_enter_roundabout
        return CP_DRAWABLES.getOrElse(cpType) { CP_DRAWABLES[0] }
    }
}
