package com.carlink.navigation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.collection.LruCache
import androidx.core.graphics.createBitmap
import com.carlink.logging.logNavi
import java.io.File
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Programmatic Canvas/Path renderer for navigation maneuver icons.
 *
 * Visual style inspired by Apple Maps: bold rounded strokes, smooth wide bezier curves,
 * elongated chevron arrowheads, and clean teardrop destination pins. Each CPManeuverType
 * (0-53) gets a unique 128x128 white-on-transparent bitmap.
 *
 * Two-tier caching: memory LruCache (64 entries) + disk PNG persistence in app internal
 * storage. Identical icons are never re-rendered across app restarts — disk hits are
 * decoded and promoted to memory. New icons are rendered once, saved to disk, and cached.
 *
 * LHD (turnSide=1) support via canvas mirroring for U-turns and roundabouts.
 */
object ManeuverIconRenderer {

    private const val SIZE = 128
    private const val CENTER = 64f

    // ── Caching ──────────────────────────────────────────────────────────

    private val memCache = LruCache<Int, Bitmap>(64)
    private var diskCacheDir: File? = null

    /**
     * Initialize disk cache directory. Call once at app startup or first use.
     * @param cacheDir Directory for persistent PNG files (e.g. context.filesDir/maneuver_icons)
     */
    fun init(cacheDir: File) {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        diskCacheDir = cacheDir
        logNavi { "[NAVI] ManeuverIconRenderer disk cache: ${cacheDir.absolutePath}" }
    }

    // ── Paints (Apple Maps style) ────────────────────────────────────────

    /** Primary stroke: bold, rounded — matches Apple Maps' confident line weight. */
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7.5f
        color = 0xFFFFFFFF.toInt()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Secondary stroke: dimmed branches, roundabout ring base. */
    private val thinStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        color = 0xFFFFFFFF.toInt()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Solid fill for arrowheads, pins, and filled shapes. */
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }

    // ── Exit angle table (CCW from entry at 6 o'clock) ──────────────────

    private val EXIT_ANGLES = floatArrayOf(
        0f,     // [0] unused
        60f,    // exit 1: first right (~2 o'clock)
        105f,   // exit 2: straight through (~12 o'clock)
        150f,   // exit 3: slight left (~10 o'clock)
        195f,   // exit 4: left (~8 o'clock)
        240f,   // exit 5: sharp left (~7 o'clock)
        285f,   // exit 6: near U-turn (~5 o'clock)
    )

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Render a maneuver icon, with two-tier cache (memory → disk → render).
     *
     * @param cpType CPManeuverType value (0-53)
     * @param turnSide 0=right-hand driving, 1=left-hand driving
     * @return 128x128 ARGB_8888 bitmap with white icon on transparent background
     */
    fun render(cpType: Int, turnSide: Int): Bitmap {
        val key = (cpType shl 4) or turnSide

        // Tier 1: memory
        memCache.get(key)?.let { return it }

        // Tier 2: disk
        val diskFile = diskCacheDir?.let { File(it, "m_${key}.png") }
        if (diskFile != null && diskFile.exists()) {
            val decoded = BitmapFactory.decodeFile(diskFile.absolutePath)
            if (decoded != null) {
                memCache.put(key, decoded)
                return decoded
            }
        }

        // Tier 3: render
        val bitmap = createBitmap(SIZE, SIZE)
        val canvas = Canvas(bitmap)
        val isLHD = turnSide == 1

        when (cpType) {
            0, 3, 5         -> drawStraight(canvas)
            1, 20           -> drawTurn(canvas, -90f)
            2, 21           -> drawTurn(canvas, 90f)
            47              -> drawTurn(canvas, -135f)
            48              -> drawTurn(canvas, 135f)
            49              -> drawTurn(canvas, -30f)
            50              -> drawTurn(canvas, 30f)
            4, 18, 26       -> wrapMirror(canvas, isLHD) { drawUturn(it) }
            6               -> wrapMirror(canvas, isLHD) { drawRoundabout(it, null, entering = true) }
            7               -> wrapMirror(canvas, isLHD) { drawRoundabout(it, null, entering = false) }
            19              -> wrapMirror(canvas, isLHD) { drawRoundabout(it, 6, entering = false) }
            in 28..46       -> wrapMirror(canvas, isLHD) { drawRoundabout(it, cpType - 27, entering = false) }
            8               -> drawOffRamp(canvas, exitLeft = false)
            22              -> drawOffRamp(canvas, exitLeft = true)
            23              -> drawOffRamp(canvas, exitLeft = false)
            9               -> drawFork(canvas, highlightLeft = false)
            13              -> drawFork(canvas, highlightLeft = true)
            14              -> drawFork(canvas, highlightLeft = false)
            51, 53          -> drawFork(canvas, highlightLeft = false)
            52              -> drawFork(canvas, highlightLeft = true)
            10, 12, 27      -> drawDestination(canvas, side = 0)
            24              -> drawDestination(canvas, side = -1)
            25              -> drawDestination(canvas, side = 1)
            11              -> drawDepart(canvas)
            else            -> drawStraight(canvas)
        }

        // Persist to both caches
        memCache.put(key, bitmap)
        if (diskFile != null) {
            try {
                diskFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } catch (_: Exception) {
                // Disk write failure is non-fatal — memory cache still works
            }
        }

        return bitmap
    }

    /** Evict memory cache only. Disk cache persists for reuse across sessions. */
    fun clearMemoryCache() {
        memCache.evictAll()
    }

    /** Evict both memory and disk caches (e.g. app version upgrade). */
    fun clearCache() {
        memCache.evictAll()
        diskCacheDir?.listFiles()?.forEach { it.delete() }
    }

    // ── Drawing functions (Apple Maps inspired) ──────────────────────────

    /**
     * Straight arrow: bold vertical stem with filled chevron arrowhead.
     * Apple style: generous vertical extent, arrowhead meets stem cleanly.
     */
    private fun drawStraight(canvas: Canvas) {
        canvas.drawLine(CENTER, 112f, CENTER, 38f, strokePaint)
        drawArrowhead(canvas, CENTER, 14f, 0f)
    }

    /**
     * Parameterized turn with smooth wide bezier curve.
     * Apple style: gradual entry curve, no sharp bend at the transition point.
     * Uses cubic bezier for smoother curvature than the original quadratic.
     */
    private fun drawTurn(canvas: Canvas, angleDeg: Float) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val absAngle = Math.abs(angleDeg)

        // Stem bottom
        val stemBottom = 112f
        // Bend point varies by sharpness: sharp turns bend lower, slight turns higher
        val bendY = when {
            absAngle >= 120f -> 68f   // sharp: bend lower for room
            absAngle <= 45f  -> 52f   // slight: bend higher, gentle
            else             -> 58f   // normal 90°
        }

        // Curve reach scales with angle magnitude
        val reach = when {
            absAngle >= 120f -> 52f
            absAngle <= 45f  -> 28f
            else             -> 44f
        }

        val sign = if (angleDeg < 0) -1f else 1f
        val endX = CENTER + sign * reach
        val endY = when {
            absAngle >= 120f -> bendY - 16f   // sharp: exits nearly backward
            absAngle <= 45f  -> bendY - 38f   // slight: exits nearly forward
            else             -> bendY - 32f   // normal: exits sideways
        }

        // Cubic bezier for smooth Apple-style curve
        val path = Path()
        path.moveTo(CENTER, stemBottom)
        path.lineTo(CENTER, bendY)

        // Two control points for cubic: smooth entry into curve, smooth exit
        val cp1x = CENTER
        val cp1y = bendY - 16f
        val cp2x = CENTER + sign * reach * 0.65f
        val cp2y = endY + 8f

        path.cubicTo(cp1x, cp1y, cp2x, cp2y, endX, endY)
        canvas.drawPath(path, strokePaint)

        drawArrowhead(canvas, endX, endY, angleDeg)
    }

    /**
     * U-turn: wide smooth 180° arc (RHD default, LHD via wrapMirror).
     * Apple style: generous arc radius, stems well-spaced.
     */
    private fun drawUturn(canvas: Canvas) {
        val rightX = 78f
        val leftX = 50f
        val arcCy = 38f
        val arcRadius = 14f

        // Right stem up
        canvas.drawLine(rightX, 112f, rightX, arcCy, strokePaint)

        // Wide 180° arc
        val arcRect = RectF(leftX, arcCy - arcRadius, rightX, arcCy + arcRadius)
        val arcPath = Path()
        arcPath.addArc(arcRect, 0f, -180f)
        canvas.drawPath(arcPath, strokePaint)

        // Left stem down
        canvas.drawLine(leftX, arcCy, leftX, 76f, strokePaint)

        drawArrowhead(canvas, leftX, 84f, 180f)
    }

    /**
     * Roundabout with optional exit number — the key function solving the 22-icon gap.
     * Apple style: clean circle, bold highlighted arc from entry to exit, clear exit stem.
     */
    private fun drawRoundabout(canvas: Canvas, exitNumber: Int?, entering: Boolean) {
        val cx = CENTER
        val cy = 48f
        val radius = 24f
        val circleRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        // Base circle (thin)
        canvas.drawCircle(cx, cy, radius, thinStrokePaint)

        // Entry stem
        canvas.drawLine(cx, 112f, cx, cy + radius, strokePaint)

        when {
            exitNumber != null -> {
                val exitAngle = getExitAngle(exitNumber)

                // Highlighted arc from entry (6 o'clock = 90° in Android coords) CCW
                val arcPath = Path()
                arcPath.addArc(circleRect, 90f, -exitAngle)
                canvas.drawPath(arcPath, strokePaint)

                // Exit point on circle (convert our CCW angle to canvas coords)
                val canvasRad = Math.toRadians((90.0 - exitAngle))
                val exitX = cx + (radius * cos(canvasRad)).toFloat()
                val exitY = cy + (radius * sin(canvasRad)).toFloat()

                // Exit stem outward
                val stemLen = 26f
                val outX = cx + ((radius + stemLen) * cos(canvasRad)).toFloat()
                val outY = cy + ((radius + stemLen) * sin(canvasRad)).toFloat()

                canvas.drawLine(exitX, exitY, outX, outY, strokePaint)
                drawArrowhead(canvas, outX, outY, -(exitAngle - 90f))
            }
            entering -> {
                // CCW flow indicator: short highlighted arc on right side + small arrow
                val arcPath = Path()
                arcPath.addArc(circleRect, 90f, -45f)
                canvas.drawPath(arcPath, strokePaint)

                // Small chevron at arc end indicating direction
                val chevRad = Math.toRadians(45.0) // 90° - 45° = 45° canvas angle
                val chX = cx + (radius * cos(chevRad)).toFloat()
                val chY = cy + (radius * sin(chevRad)).toFloat()
                drawArrowhead(canvas, chX, chY, -45f, scale = 0.55f)
            }
            else -> {
                // Generic exit: highlighted arc to ~2 o'clock + exit stem pointing right
                val arcPath = Path()
                arcPath.addArc(circleRect, 90f, -75f)
                canvas.drawPath(arcPath, strokePaint)

                val canvasRad = Math.toRadians(15.0)  // 90° - 75°
                val exitX = cx + (radius * cos(canvasRad)).toFloat()
                val exitY = cy + (radius * sin(canvasRad)).toFloat()
                val outX = cx + ((radius + 26f) * cos(canvasRad)).toFloat()
                val outY = cy + ((radius + 26f) * sin(canvasRad)).toFloat()

                canvas.drawLine(exitX, exitY, outX, outY, strokePaint)
                drawArrowhead(canvas, outX, outY, 75f)
            }
        }
    }

    /**
     * Fork/keep: common stem splitting into two branches.
     * Apple style: gradual divergence, clear weight distinction between branches.
     * Highlighted branch has arrowhead, dimmed branch fades out.
     */
    private fun drawFork(canvas: Canvas, highlightLeft: Boolean) {
        // Common stem
        canvas.drawLine(CENTER, 112f, CENTER, 72f, strokePaint)

        // Left branch (cubic for smooth divergence)
        val leftPath = Path()
        leftPath.moveTo(CENTER, 72f)
        leftPath.cubicTo(58f, 56f, 48f, 40f, 36f, 20f)
        canvas.drawPath(leftPath, if (highlightLeft) strokePaint else thinStrokePaint)
        if (highlightLeft) drawArrowhead(canvas, 36f, 20f, -28f)

        // Right branch
        val rightPath = Path()
        rightPath.moveTo(CENTER, 72f)
        rightPath.cubicTo(70f, 56f, 80f, 40f, 92f, 20f)
        canvas.drawPath(rightPath, if (!highlightLeft) strokePaint else thinStrokePaint)
        if (!highlightLeft) drawArrowhead(canvas, 92f, 20f, 28f)
    }

    /**
     * Off-ramp: straight main road with branching exit curve.
     * Apple style: main road clearly dominant, ramp curves away smoothly.
     */
    private fun drawOffRamp(canvas: Canvas, exitLeft: Boolean) {
        val mainX = if (exitLeft) 82f else 46f

        // Main road (full height, bold)
        canvas.drawLine(mainX, 112f, mainX, 12f, strokePaint)

        // Branching ramp with cubic bezier
        val rampPath = Path()
        val rampY = 62f
        rampPath.moveTo(mainX, rampY)

        if (exitLeft) {
            rampPath.cubicTo(mainX - 12f, 46f, mainX - 28f, 34f, mainX - 46f, 24f)
            canvas.drawPath(rampPath, strokePaint)
            drawArrowhead(canvas, mainX - 46f, 24f, -52f)
        } else {
            rampPath.cubicTo(mainX + 12f, 46f, mainX + 28f, 34f, mainX + 46f, 24f)
            canvas.drawPath(rampPath, strokePaint)
            drawArrowhead(canvas, mainX + 46f, 24f, 52f)
        }
    }

    /**
     * Destination: Apple Maps teardrop pin.
     * Smooth teardrop shape with inner circle cutout (drawn as ring).
     */
    private fun drawDestination(canvas: Canvas, side: Int) {
        val offsetX = side * 22f
        val pinX = CENTER + offsetX

        // Teardrop body: circle top + pointed bottom via cubic curves
        val headCy = 40f
        val headR = 14f
        val tipY = 86f

        val path = Path()
        // Start at bottom tip, curve up-right to circle, arc over top, curve down-left to tip
        path.moveTo(pinX, tipY)
        path.cubicTo(pinX + 4f, tipY - 16f, pinX + headR, headCy + 10f, pinX + headR, headCy)

        // Top arc (right to left over the top)
        val arcRect = RectF(pinX - headR, headCy - headR, pinX + headR, headCy + headR)
        path.arcTo(arcRect, 0f, -180f)

        // Left side back down to tip
        path.cubicTo(pinX - headR, headCy + 10f, pinX - 4f, tipY - 16f, pinX, tipY)
        path.close()

        canvas.drawPath(path, fillPaint)

        // Inner circle (punch-out effect — draw smaller filled circle in "clear" isn't
        // possible on transparent bg, so draw a ring stroke to suggest the dot)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = 0xFFFFFFFF.toInt()
        }
        canvas.drawCircle(pinX, headCy, 5f, dotPaint)
    }

    /**
     * Depart: upward arrow from a filled origin dot.
     * Apple style: prominent circle at base, bold arrow pointing up.
     */
    private fun drawDepart(canvas: Canvas) {
        // Origin circle
        canvas.drawCircle(CENTER, 96f, 11f, fillPaint)

        // Stem
        canvas.drawLine(CENTER, 85f, CENTER, 38f, strokePaint)

        // Arrowhead
        drawArrowhead(canvas, CENTER, 14f, 0f)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Get exit angle (degrees CCW from entry) for roundabout exit 1-19. */
    private fun getExitAngle(exitNumber: Int): Float {
        if (exitNumber in 1..6) return EXIT_ANGLES[exitNumber]
        // Exits 7-19: linear interpolation from 300° to 345°
        val t = (exitNumber - 7).toFloat() / 12f
        return 300f + t * 45f
    }

    /**
     * Filled chevron arrowhead at (tipX, tipY), rotated by rotationDeg.
     * Apple style: slightly elongated and narrow (depth > spread).
     * 0° = pointing up, positive = clockwise.
     */
    private fun drawArrowhead(
        canvas: Canvas,
        tipX: Float,
        tipY: Float,
        rotationDeg: Float,
        scale: Float = 1f,
    ) {
        val rad = Math.toRadians(rotationDeg.toDouble())
        val sinR = sin(rad).toFloat()
        val cosR = cos(rad).toFloat()

        // Elongated chevron proportions (depth=20, spread=9) — narrower than equilateral
        val spread = 9f * scale
        val depth = 20f * scale

        val lx = -spread; val ly = depth
        val rx = spread; val ry = depth

        val path = Path()
        path.moveTo(tipX, tipY)
        path.lineTo(tipX + (lx * cosR - ly * sinR), tipY + (lx * sinR + ly * cosR))
        path.lineTo(tipX + (rx * cosR - ry * sinR), tipY + (rx * sinR + ry * cosR))
        path.close()

        canvas.drawPath(path, fillPaint)
    }

    /** Mirror canvas horizontally around center for LHD support. */
    private inline fun wrapMirror(canvas: Canvas, shouldMirror: Boolean, block: (Canvas) -> Unit) {
        if (shouldMirror) {
            canvas.save()
            canvas.scale(-1f, 1f, CENTER, CENTER)
        }
        block(canvas)
        if (shouldMirror) {
            canvas.restore()
        }
    }
}
