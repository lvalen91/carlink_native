package com.carlink.navigation

import android.content.Context
import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.Maneuver
import androidx.core.graphics.drawable.IconCompat
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logNavi
import com.carlink.logging.logWarn

/**
 * Maps CarPlay CPManeuverType values (0-53) to AAOS Car App Library Maneuver.TYPE_* constants
 * and provides resource-based maneuver icons for cluster display.
 *
 * Derived from live RE of the CPC200-CCPA NaviJSON wire format, cross-referenced
 * against iAP2 spec "Table 15-16" and GM's RouteStateMachine.mapManeuverType()
 * (decompiled from DelayedWKSApp). Neither artefact is checked into this repo —
 * the mapping table here is the only in-tree source of truth.
 *
 * Icon source is protocol-dependent:
 *   AA      → adapter-forwarded NAVI_IMAGE bitmap (Google Maps pre-rendered PNG)
 *   CarPlay → static VectorDrawable resource icons (createWithResource, which marshals
 *             a stable resource ID that Templates Host caches by name — avoids the
 *             per-bitmap-identity thrashing you get from createWithBitmap).
 *
 * The ClusterIconShimProvider (see its KDoc) claims the orphaned GM cluster-icon
 * ContentProvider authority so Templates Host's CarIcon→PNG conversion succeeds; that
 * shim is what makes AA bitmap icons actually reach the instrument cluster.
 *
 * Keep in sync with [ManeuverIconRenderer]: both consume the same cpType and both
 * contribute to the same Maneuver. If one learns a new code without the other, the
 * rendered icon and the Maneuver.TYPE_* semantic will disagree.
 *
 * NaviTurnSide controls U-turn direction (LEFT vs RIGHT) and roundabout rotation
 * (CW vs CCW). 0 = right-hand traffic (default), 1 = left-hand traffic.
 *
 * Thread safety: maneuverCache is a plain HashMap. Callers must serialize
 * buildManeuver/buildManeuverForType/clearCache for a given process — today
 * satisfied because NavigationStateManager drives writes (clearCache) from the USB
 * read thread and session/trip consumers read from the Templates-Host dispatch or
 * session coroutine scope in a non-overlapping fashion. Convert to ConcurrentHashMap
 * if that invariant ever becomes hard to audit.
 */
object ManeuverMapper {
    /** Cache built Maneuver objects to avoid icon thrashing.
     *  Key space is implicitly bounded: cpType (0-53) × turnSide (0-1) × hasAaIcon (0-1)
     *  → at most ~216 entries. No explicit eviction beyond [clearCache]. */
    private val maneuverCache = HashMap<Int, Maneuver>()

    /** Identity hash of the AA icon Bitmap used in the last cached entry.
     *  Uses System.identityHashCode (object identity), NOT a content hash — so the
     *  same pixels delivered as a new Bitmap instance will invalidate the cache.
     *  Acceptable: an extra rebuild is cheaper than a content-hashing pass. */
    private var lastCachedAaIconHash = 0

    /**
     * Map a CPManeuverType + turnSide to a Maneuver.TYPE_* constant.
     *
     * @param cpType CPManeuverType value (0-53)
     * @param turnSide 0=right-hand driving (default), 1=left-hand driving
     * @return Maneuver type constant
     */
    fun mapManeuverType(
        cpType: Int,
        turnSide: Int = 0,
    ): Int {
        val isLeftDrive = turnSide == 1
        val mapped =
            when (cpType) {
                // cpType 0 (noTurn): Car App Library has no "no maneuver" type,
                // so TYPE_STRAIGHT is the semantically nearest renderable choice.
                0 -> {
                    Maneuver.TYPE_STRAIGHT
                }

                // noTurn
                1 -> {
                    Maneuver.TYPE_TURN_NORMAL_LEFT
                }

                // left
                2 -> {
                    Maneuver.TYPE_TURN_NORMAL_RIGHT
                }

                // right
                3 -> {
                    Maneuver.TYPE_STRAIGHT
                }

                // straight
                4 -> {
                    if (isLeftDrive) Maneuver.TYPE_U_TURN_RIGHT else Maneuver.TYPE_U_TURN_LEFT
                }

                // uTurn
                5 -> {
                    Maneuver.TYPE_STRAIGHT
                }

                // followRoad
                6 -> {
                    if (isLeftDrive) {
                        Maneuver.TYPE_ROUNDABOUT_ENTER_CW // enterRoundabout
                    } else {
                        Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
                    }
                }

                7 -> {
                    if (isLeftDrive) {
                        Maneuver.TYPE_ROUNDABOUT_EXIT_CW // exitRoundabout
                    } else {
                        Maneuver.TYPE_ROUNDABOUT_EXIT_CCW
                    }
                }

                8 -> {
                    if (isLeftDrive) Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT else Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
                }

                // rampOff (highway exit)
                9 -> {
                    if (isLeftDrive) Maneuver.TYPE_ON_RAMP_NORMAL_LEFT else Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
                }

                // rampOn (merge onto highway)
                10 -> {
                    Maneuver.TYPE_DESTINATION
                }

                // endOfNavigation
                11 -> {
                    Maneuver.TYPE_DEPART
                }

                // proceedToRoute
                12 -> {
                    Maneuver.TYPE_DESTINATION
                }

                // arrived
                13 -> {
                    Maneuver.TYPE_KEEP_LEFT
                }

                // keepLeft
                14 -> {
                    Maneuver.TYPE_KEEP_RIGHT
                }

                // keepRight
                15 -> Maneuver.TYPE_FERRY_BOAT // enterFerry
                16 -> Maneuver.TYPE_FERRY_BOAT // exitFerry
                17 -> Maneuver.TYPE_FERRY_BOAT // changeFerry

                18 -> {
                    if (isLeftDrive) {
                        Maneuver.TYPE_U_TURN_RIGHT // uTurnToRoute
                    } else {
                        Maneuver.TYPE_U_TURN_LEFT
                    }
                }

                19 -> {
                    if (isLeftDrive) {
                        Maneuver.TYPE_ROUNDABOUT_ENTER_CW // roundaboutUTurn
                    } else {
                        Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
                    }
                }

                20 -> {
                    Maneuver.TYPE_TURN_NORMAL_LEFT
                }

                // endOfRoadLeft
                21 -> {
                    Maneuver.TYPE_TURN_NORMAL_RIGHT
                }

                // endOfRoadRight
                22 -> {
                    Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
                }

                // rampOffLeft
                23 -> {
                    Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
                }

                // rampOffRight
                24 -> {
                    Maneuver.TYPE_DESTINATION_LEFT
                }

                // arrivedLeft
                25 -> {
                    Maneuver.TYPE_DESTINATION_RIGHT
                }

                // arrivedRight
                26 -> {
                    if (isLeftDrive) Maneuver.TYPE_U_TURN_RIGHT else Maneuver.TYPE_U_TURN_LEFT
                }

                // uTurnWhenPossible
                27 -> {
                    Maneuver.TYPE_DESTINATION
                }

                // endOfDirections
                in 28..46 -> {
                    // Roundabout exit 1-19 (type - 27 = exit number)
                    if (isLeftDrive) {
                        Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
                    } else {
                        Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
                    }
                }

                47 -> {
                    Maneuver.TYPE_TURN_SHARP_LEFT
                }

                // sharpLeft
                48 -> {
                    Maneuver.TYPE_TURN_SHARP_RIGHT
                }

                // sharpRight
                49 -> {
                    Maneuver.TYPE_TURN_SLIGHT_LEFT
                }

                // slightLeft
                50 -> {
                    Maneuver.TYPE_TURN_SLIGHT_RIGHT
                }

                // slightRight
                51 -> {
                    Maneuver.TYPE_KEEP_RIGHT
                }

                // changeHighway (fork)
                52 -> {
                    Maneuver.TYPE_KEEP_LEFT
                }

                // changeHighwayLeft
                53 -> {
                    Maneuver.TYPE_KEEP_RIGHT
                }

                // changeHighwayRight
                else -> {
                    // Tag choice: uses NAVI (preset-filterable) rather than PROTO_UNKNOWN
                    // (bypass-all-filters). Unknown cpType IS a protocol anomaly — switch
                    // to Logger.Tags.PROTO_UNKNOWN if forensic capture under SILENT matters.
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

    /** Evict maneuver cache on disconnect or icon change. */
    fun clearCache() {
        maneuverCache.clear()
        lastCachedAaIconHash = 0
    }

    /**
     * Build a Maneuver with a resource icon for GM cluster display.
     *
     * @param state Current navigation state
     * @param context Context for loading VectorDrawable resources
     * @return Maneuver with type and resource icon set
     */
    fun buildManeuver(
        state: NavigationState,
        context: Context,
    ): Maneuver {
        // Composer path (D16): when ComposedIconStore.enabled and a route is loaded, look
        // up a pre-composed bitmap keyed by (cpType, roadName) for the current step. If
        // present, use it as a one-shot AA-style override and fall through to the existing
        // builder. Composer is OFF by default — flip via ComposedIconStore.setEnabled(true).
        val composed = com.carlink.navigation.compose.ComposedIconStore
            .lookup(state.maneuverType, state.roadName)
        if (composed != null) {
            return buildManeuverForType(
                cpType = state.maneuverType,
                turnSide = state.turnSide,
                context = context,
                composedIcon = composed,
            )
        }
        return buildManeuverForType(state.maneuverType, state.turnSide, context)
    }

    /**
     * Build a Maneuver from explicit CPManeuverType and turnSide values.
     *
     * Used by [TripBuilder] for the next-step maneuver where the fields come from
     * [NavigationState.nextManeuverType] rather than the current state.
     */
    fun buildManeuverForType(
        cpType: Int,
        turnSide: Int,
        context: Context,
        composedIcon: android.graphics.Bitmap? = null,
    ): Maneuver {
        // Priority: composed (D16, CarPlay+composer enabled) > AA pre-rendered (Android Auto)
        //         > static VectorDrawable (everything else).
        val aaIcon =
            composedIcon
                ?: NavigationStateManager.currentManeuverIcon
                    ?.takeIf { NavigationStateManager.canUseAaManeuverIcon() }
        val aaIconHash = aaIcon?.let { System.identityHashCode(it) } ?: 0
        // Composed bitmaps participate in the same cacheKey scheme so the maneuver cache
        // doesn't return a stale Maneuver built from the wrong icon source.
        val composedFlag = if (composedIcon != null) 2 else 0
        val cacheKey = (cpType shl 16) or (turnSide shl 8) or (if (aaIcon != null) 1 else 0) or composedFlag

        maneuverCache[cacheKey]?.let { cached ->
            if (aaIcon == null || aaIconHash == lastCachedAaIconHash) {
                return cached
            }
        }

        // Always resolve the real maneuver type. The active GM cluster path
        // (ClusterMainSession -> NavigationManager.updateTrip()) consumes the Trip
        // as pure data — the real type feeds GM's OnStarTurnByTurnManager for
        // classification, fallback rendering, and roundabout exit counting.
        //
        // Icon source depends on protocol (see class KDoc for the full rationale):
        //   AA      -> adapter-forwarded NAVI_IMAGE bitmap (Google Maps pre-rendered)
        //   CarPlay -> static VectorDrawable resource icons (stable IPC hashing)
        val type = mapManeuverType(cpType, turnSide)
        val builder = Maneuver.Builder(type)

        getRoundaboutExitNumber(cpType)?.let {
            builder.setRoundaboutExitNumber(it)
        }

        val icon: CarIcon
        if (aaIcon != null) {
            icon = CarIcon.Builder(IconCompat.createWithBitmap(aaIcon)).build()
            lastCachedAaIconHash = aaIconHash
            logInfo(
                "[MANEUVER] AA bitmap (${aaIcon.width}x${aaIcon.height}), type=$type " +
                    "(cpType=$cpType, turnSide=$turnSide)",
                tag = Logger.Tags.NAVI,
            )
        } else {
            val resId = ManeuverIconRenderer.drawableForManeuver(cpType)
            icon = CarIcon.Builder(IconCompat.createWithResource(context, resId)).build()
        }
        builder.setIcon(icon)

        return builder.build().also { maneuverCache[cacheKey] = it }
    }
}
