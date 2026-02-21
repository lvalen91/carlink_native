package com.carlink.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.carlink.logging.Logger
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Shim ContentProvider that claims the orphaned authority
 * `com.google.android.apps.automotive.templates.host.ClusterIconContentProvider`.
 *
 * GM's Templates Host has this provider class but never registers it in its manifest.
 * When Templates Host converts CarIcon maneuver icons into navstate2 protobuf, it calls
 * contentResolver.insert() against this authority. The first failure sets `skipIcons = true`
 * permanently, disabling all icon delivery for the session.
 *
 * This shim implements the 3-method contract Templates Host expects:
 * - insert(): cache PNG bytes keyed by iconId
 * - query(): return contentUri + aspectRatio metadata
 * - openFile(): serve cached PNG via pipe (with optional scaling)
 */
class ClusterIconShimProvider : ContentProvider() {

    companion object {
        private const val TAG = Logger.Tags.ICON_SHIM
        private const val AUTHORITY =
            "com.google.android.apps.automotive.templates.host.ClusterIconContentProvider"
        private const val MAX_CACHE_SIZE = 20
    }

    private val iconCache: MutableMap<String, ByteArray> = Collections.synchronizedMap(
        object : LinkedHashMap<String, ByteArray>(MAX_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
                val shouldRemove = size > MAX_CACHE_SIZE
                if (shouldRemove && eldest != null) {
                    Logger.d("Evicted ${eldest.key} from icon cache (LRU)", tag = TAG)
                }
                return shouldRemove
            }
        }
    )

    override fun onCreate(): Boolean {
        Logger.i("ClusterIconShimProvider registered (authority=$AUTHORITY)", tag = TAG)
        return true
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (values == null) {
            Logger.w("insert() called with null ContentValues", tag = TAG)
            return "content://$AUTHORITY/img/empty".toUri()
        }

        val iconId = values.getAsString("iconId")
        val data = values.getAsByteArray("data")

        if (iconId == null || data == null) {
            Logger.w("insert() missing iconId or data (iconId=$iconId, dataSize=${data?.size})", tag = TAG)
            // Return a non-null URI to prevent skipIcons = true
            return "content://$AUTHORITY/img/unknown".toUri()
        }

        val cacheKey = "cluster_icon_$iconId"
        iconCache[cacheKey] = data
        val resultUri = "content://$AUTHORITY/img/$cacheKey".toUri()
        Logger.d("insert() cached $cacheKey (${data.size} bytes) → $resultUri", tag = TAG)
        return resultUri
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = arrayOf("contentUri", "aspectRatio")
        val cursor = MatrixCursor(columns)

        if (selection == null) {
            Logger.d("query() with null selection — returning empty cursor", tag = TAG)
            return cursor
        }

        val cacheKey = "cluster_icon_$selection"
        val data = iconCache[cacheKey]
        if (data == null) {
            Logger.d("query() cache miss for $cacheKey", tag = TAG)
            return cursor
        }

        // Decode dimensions without allocating the full bitmap
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        val aspectRatio = if (opts.outHeight > 0) {
            opts.outWidth.toDouble() / opts.outHeight.toDouble()
        } else {
            1.0
        }

        val contentUri = "content://$AUTHORITY/img/$cacheKey"
        cursor.addRow(arrayOf<Any>(contentUri, aspectRatio))
        Logger.d(
            "query() hit for $cacheKey (${opts.outWidth}x${opts.outHeight}, ar=${"%.2f".format(aspectRatio)})",
            tag = TAG,
        )
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val cacheKey = uri.lastPathSegment
        if (cacheKey == null) {
            Logger.w("openFile() with null lastPathSegment", tag = TAG)
            return null
        }

        val data = iconCache[cacheKey]
        if (data == null) {
            Logger.w("openFile() cache miss for $cacheKey", tag = TAG)
            return null
        }

        // Check for scaling parameters
        val targetW = uri.getQueryParameter("w")?.toIntOrNull()
        val targetH = uri.getQueryParameter("h")?.toIntOrNull()
        val outputData = if (targetW != null && targetH != null && targetW > 0 && targetH > 0) {
            scaleIcon(data, targetW, targetH)
        } else {
            data
        }

        Logger.d("openFile() serving $cacheKey (${outputData.size} bytes, scale=${targetW}x${targetH})", tag = TAG)

        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { os ->
                    os.write(outputData)
                }
            } catch (e: Exception) {
                Logger.e("openFile() pipe write error for $cacheKey: ${e.message}", tag = TAG)
            }
        }, "IconShim-Pipe").start()

        return readEnd
    }

    private fun scaleIcon(pngData: ByteArray, w: Int, h: Int): ByteArray {
        return try {
            val original = BitmapFactory.decodeByteArray(pngData, 0, pngData.size) ?: return pngData
            val scaled = original.scale(w, h, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (scaled !== original) scaled.recycle()
            original.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            Logger.w("scaleIcon() failed (${w}x${h}): ${e.message} — using original", tag = TAG)
            pngData
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun getType(uri: Uri): String = "image/png"
}
