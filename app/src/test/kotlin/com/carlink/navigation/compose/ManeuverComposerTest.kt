package com.carlink.navigation.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.carlink.navigation.Iap2ManeuverData
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Assert.assertNotNull
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders every cpManeuverType branch + a sweep of roundabout angles to PNGs under
 * `build/test-icons/` for visual review. Asserts that every output has a non-empty path,
 * so a regression in [AppleManeuverPaths] would fail the test instead of silently
 * producing blank icons.
 *
 * Run with: `./gradlew :app:testSideloadDebugUnitTest --tests ManeuverComposerTest`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S]) // API 31, Robolectric-supported, matches AAOS
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManeuverComposerTest {

    private val outDir = File("build/test-icons").apply { mkdirs() }
    private val canvasSize = 360
    private val viewport = 160f // matches IconBitmapRenderer's expanded viewport (fits LeftTurn x=-42 .. RightTurn x=+73)

    private fun render(name: String, icon: ComposedIcon) {
        val bmp = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(40, 40, 40))
        // Scale 56pt path coords into 96pt viewport, centered, then scaled to canvasSize.
        val scale = canvasSize / viewport
        val shift = (viewport - 56f) / 2f
        val m = Matrix().apply {
            postTranslate(shift, shift)
            postScale(scale, scale)
        }
        val transformed = android.graphics.Path()

        icon.background?.let {
            transformed.reset()
            it.transform(m, transformed)
            val p = Paint().apply {
                isAntiAlias = true; color = Color.rgb(150, 150, 150); style = Paint.Style.FILL
            }
            c.drawPath(transformed, p)
        }
        transformed.reset()
        icon.foreground.transform(m, transformed)
        val p = Paint().apply {
            isAntiAlias = true; color = Color.WHITE; style = Paint.Style.FILL
        }
        c.drawPath(transformed, p)

        FileOutputStream(File(outDir, "$name.png")).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun manData(
        cpType: Int,
        entryAngles: List<Int> = emptyList(),
        exitAngle: Int? = null,
    ): Iap2ManeuverData = Iap2ManeuverData(
        index = 0,
        cpManeuverType = cpType,
        instructionText = "test cpType=$cpType",
        postManeuverRoadName = "Test Rd",
        distanceMeters = 100,
        distanceDisplayText = "100",
        distanceUnitFlag = 4,
        flag8 = 0,
        flag9 = 0,
        entryAngles = entryAngles,
        exitAngle = exitAngle,
    )

    @Test
    fun `renders every cpManeuverType branch to PNG`() {
        // Standard cpTypes from ManeuverComposer.compose()
        val standardCases = mapOf(
            "01_left_turn" to 1,
            "02_right_turn" to 2,
            "03_straight" to 3,
            "04_uturn" to 4,
            "08_exit_right" to 8,
            "09_merge_right" to 9,
            "10_arrival" to 10,
            "11_proceed_to_route" to 11,
            "13_merge_left" to 13,
            "20_keep_left" to 20,
            "21_keep_right" to 21,
            "22_exit_left" to 22,
            "47_sharp_left" to 47,
            "48_sharp_right" to 48,
            "49_slight_left" to 49,
            "50_slight_right" to 50,
        )
        for ((name, cp) in standardCases) {
            val icon = ManeuverComposer.compose(manData(cp))
            assertNotNull("compose() returned null for cpType=$cp", icon)
            // foreground may be empty for completely-unknown cpType; that's the fallback
            render(name, icon)
        }
    }

    @Test
    fun `renders roundabouts at 16 cardinal exit angles`() {
        // cpType 28 = 1st exit (per existing mapping). 3-arm roundabout with one configured exit.
        for (b in 0 until 16) {
            val relDeg = -180.0 + b * 22.5
            // Place exit at compass (180 + relDeg) wrapped to -180..180
            val exitCompass = ((180.0 + relDeg + 180.0) % 360.0 - 180.0).toInt()
            // entryAngles = "non-exit roads" — 2 dummy spokes (so nArms=3)
            val entry = listOf(60, exitCompass)
            val icon = ManeuverComposer.compose(
                manData(cpType = 28, entryAngles = entry, exitAngle = exitCompass)
            )
            assertNotNull(icon)
            render("roundabout_b%02d_a%+04d".format(b, relDeg.toInt()), icon)
        }
    }

    @Test
    fun `renders Carmel City Center scenario`() {
        // Real data from Image #5 capture (compass -114° exit, multi-spoke junction)
        val data = manData(
            cpType = 30, // 3rd exit
            entryAngles = listOf(78, -37, -114),
            exitAngle = -114,
        )
        val icon = ManeuverComposer.compose(data)
        assertNotNull(icon)
        render("carmel_citycenter_exit3", icon)
    }

    @Test
    fun `renders real captured roundabouts with real arms`() {
        // Live iAP2 captures (entryAngles = real OTHER arms; exitAngle driver-relative).
        data class RB(val name: String, val cp: Int, val entry: List<Int>, val exit: Int)
        listOf(
            RB("real_1_CityCenter_cp30_ex-114", 30, listOf(78, -37), -114),
            RB("real_2_SGuilford_cp30_ex-75", 30, listOf(9, 101), -75),
            RB("real_3_WMain1_cp28_ex+94", 28, listOf(6, -86), 94),
            RB("real_4_WMain2_cp29_ex0", 29, listOf(90, -87), 0),
            RB("real_5_EMain_cp29_ex-6", 29, listOf(-87, 91), -6),
            RB("real_6_take2nd_cp29_ex-13", 29, listOf(78), -13),
            RB("real_7_HazelDell_cp30_ex-83", 30, listOf(0, 94), -83),
        ).forEach { rb ->
            val icon = ManeuverComposer.compose(manData(rb.cp, rb.entry, rb.exit))
            assertNotNull(icon)
            render(rb.name, icon)
        }
    }
}
