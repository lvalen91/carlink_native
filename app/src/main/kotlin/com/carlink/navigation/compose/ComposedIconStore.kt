package com.carlink.navigation.compose

import android.graphics.Bitmap
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logNavi
import com.carlink.navigation.Iap2ManeuverData
import com.carlink.navigation.Iap2RouteData
import com.carlink.navigation.Iap2RouteParser

/**
 * Caches pre-composed maneuver icon bitmaps for the active route.
 *
 * Lifecycle:
 *   1. Route-calc arrives as `_iap2m` field in a NaviJSON message — [CarlinkManager] calls
 *      [populateFromIap2m]. This parses the burst, then composes ALL maneuver icons up front
 *      via [ManeuverComposer] + [IconBitmapRenderer], storing them in [iconByKey].
 *   2. Per-step NaviJSON events arrive with (NaviManeuverType, NaviRoadName) —
 *      [NavigationStateManager.onNaviJson] calls [lookup] to get the pre-composed bitmap.
 *      Zero compose latency on the hot path.
 *   3. Route ends (NaviStatus=0 flush) or new route-calc arrives — [clear] discards the
 *      whole cache.
 *
 * Architecture justification: the 0x5202 burst is the entire route in one message. Composing
 * up front (when we have spare cycles before driving starts) avoids per-step compose cost
 * during driving (when cluster updates are latency-sensitive). The Carmel route had 19
 * maneuvers; pre-compose takes a few hundred ms total once, vs ~15ms per step in the hot
 * path otherwise.
 *
 * Key design: store keyed by `(cpManeuverType, postManeuverRoadName)` — matches the fields
 * present in per-step NaviJSON events. Falls back to type-only match if road name is empty
 * or absent. The cursor-walk pattern (see [Iap2RouteData.findStepIndex]) handles duplicate
 * (type, road) pairs by remembering the last-matched index and walking forward from there.
 *
 * Thread safety: writes only via [populateFromIap2m] / [clear] from USB read thread.
 * Reads via [lookup] from same thread. `@Volatile` on [routeData] handles the write-then-
 * read across NaviJSON events. The HashMap is replaced wholesale on each populate, never
 * mutated entry-by-entry — no concurrent-modification risk.
 *
 * Feature-flag gated: nothing wires the store into the cluster icon pipeline unless
 * [enabled] is true. While disabled, populate/lookup still happen for debug-log purposes
 * but the cluster keeps using the static-XML / AA bitmap paths.
 */
object ComposedIconStore {
    /**
     * Target bitmap dimensions in pixels. 512 chosen as a high enough resolution that
     * cluster downscales (most clusters render at 88-200dp = ~100-400px) produce clean
     * results. 512×512 ARGB_8888 = 1MB per icon × ~20 maneuvers/route = ~20MB cache —
     * acceptable for a non-CPU-constrained host phone. Bump to 1024 if any cluster's
     * native icon slot exceeds 512px. Truly-scalable delivery would need the ContentURI
     * render-on-demand provider pattern (see ClusterIconShimProvider) instead of
     * pre-rendered bitmaps, but bitmap is the only IPC-serializable format for CarIcon.
     */
    private const val ICON_SIZE_PX = 512

    /**
     * Master switch. When false, [lookup] returns null even if a bitmap exists.
     * Toggleable at runtime via [setEnabled] for A/B testing during validation.
     */
    @Volatile
    var enabled: Boolean = false
        private set

    fun setEnabled(value: Boolean) {
        if (enabled != value) {
            enabled = value
            logInfo("[COMPOSER] enabled=$value", tag = Logger.Tags.NAVI)
        }
    }

    /** Optional sink for writing each composed bitmap to disk as a debug PNG. */
    @Volatile
    var debugSink: ((Iap2ManeuverData, Bitmap) -> Unit)? = null

    @Volatile
    private var routeData: Iap2RouteData? = null

    @Volatile
    private var iconByKey: Map<IconKey, Bitmap> = emptyMap()

    /** Cursor for the cursor-walk matcher — remembers the last matched maneuver index. */
    @Volatile
    private var cursorIndex: Int = 0

    /** Read-only accessor for NavigationStateManager (Tier C route-anchored state derivation). */
    internal fun currentRoute(): Iap2RouteData? = routeData

    private data class IconKey(
        val cpManeuverType: Int,
        val roadName: String,
    )

    /**
     * Parse an `_iap2m` hex string from a NaviJSON message, compose every maneuver's icon,
     * and replace the cache.
     *
     * Called by [com.carlink.CarlinkManager] when a `_iap2m` field is observed in a NaviJSON
     * payload. Best-effort: on parse failure or compose failure, logs and leaves the prior
     * cache in place rather than wiping (so a malformed mid-route message doesn't strand
     * the cluster with no icons).
     *
     * @return number of icons composed, or null if parsing failed
     */
    fun populateFromIap2m(iap2mHex: String): Int? {
        val parsed = Iap2RouteParser.parse(iap2mHex) ?: return null
        if (parsed.maneuvers.isEmpty()) return null

        val newMap = HashMap<IconKey, Bitmap>(parsed.maneuvers.size)
        val sink = debugSink
        var composed = 0
        for (maneuver in parsed.maneuvers) {
            try {
                val composedIcon = ManeuverComposer.compose(maneuver)
                val bmp = IconBitmapRenderer.render(ICON_SIZE_PX, composedIcon)
                val key = IconKey(maneuver.cpManeuverType, maneuver.postManeuverRoadName)
                newMap.putIfAbsent(key, bmp)
                sink?.invoke(maneuver, bmp)
                composed++
            } catch (e: NoSuchElementException) {
                // Compose path has no entry for this (cp, geometry) — typically high-exit-ordinal
                // roundabouts beyond the 148-entry AppleManeuverPaths map. Not an error; lookup
                // will return null at render time and ManeuverMapper falls through to the static
                // AVD via ManeuverIconRenderer.drawableForManeuver as a last-resort safety net.
                logNavi { "[COMPOSER] no path data for idx=${maneuver.index} cpType=${maneuver.cpManeuverType}: ${e.message}" }
            } catch (e: Throwable) {
                Logger.w(Logger.Tags.NAVI, "[COMPOSER] failed for idx=${maneuver.index} cpType=${maneuver.cpManeuverType}: ${e.message}")
            }
        }
        routeData = parsed
        iconByKey = newMap
        cursorIndex = 0
        logInfo(
            "[COMPOSER] Populated ${composed}/${parsed.maneuvers.size} icons; ${newMap.size} unique keys; cursor reset to 0",
            tag = Logger.Tags.NAVI,
        )
        return composed
    }

    /**
     * Look up a pre-composed bitmap for the per-step event's (cpManeuverType, roadName) pair.
     *
     * Returns null when:
     *   - [enabled] is false (feature flag off)
     *   - the cache is empty (no route active or population failed)
     *   - no maneuver in the route matches the given key
     *
     * Uses the cursor-walk pattern: searches from [cursorIndex] forward, advances the
     * cursor on a match. Handles re-routes (cursor reset on next populateFromIap2m) and
     * duplicate (type, road) pairs (cursor walks past them).
     */
    fun lookup(cpManeuverType: Int, roadName: String?): Bitmap? {
        if (!enabled) return null
        val route = routeData ?: return null
        // Find the matching maneuver index using the LOOSE matcher (post-road OR instruction-text).
        // v6.1 Tier C cursor body sets state.roadName from `route.maneuvers[cursor].instructionText`
        // (the long form, e.g. "At the roundabout, take the 3rd exit onto E 116th St"), not the
        // shorter `postManeuverRoadName` ("E 116th St"). The strict matcher would miss this. Cursor
        // walks forward from prior cursor index; wraps to 0 to support backward-jumps from reroutes.
        val foundIdx = route.findStepIndexLoose(cpManeuverType, roadName, searchStartIndex = cursorIndex)
            ?: route.findStepIndexLoose(cpManeuverType, roadName, searchStartIndex = 0)
            ?: return null

        cursorIndex = foundIdx
        val maneuver = route.maneuvers[foundIdx]
        val bmp = iconByKey[IconKey(maneuver.cpManeuverType, maneuver.postManeuverRoadName)]
        logNavi {
            "[COMPOSER] Lookup matched idx=$foundIdx cpType=$cpManeuverType road=\"$roadName\" -> ${if (bmp != null) "bitmap[${bmp.width}x${bmp.height}]" else "no bitmap (compose failed earlier)"}"
        }
        return bmp
    }

    /** Wipe the cache. Called on NaviStatus=0 flush or before re-populating. */
    fun clear() {
        if (iconByKey.isNotEmpty()) {
            // Recycle bitmaps to release native memory immediately rather than waiting for GC.
            iconByKey.values.forEach { if (!it.isRecycled) it.recycle() }
            logInfo("[COMPOSER] Cleared ${iconByKey.size} icons", tag = Logger.Tags.NAVI)
        }
        routeData = null
        iconByKey = emptyMap()
        cursorIndex = 0
    }
}
