package com.carlink.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.carlink.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

private const val TAG = "CARLINK_ART_CACHE"

/**
 * Cache of downscaled album art JPEGs, exposed to AAOS media controllers via
 * [FileProvider] content:// URIs.
 *
 * Used additively alongside the inline [android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART]
 * Bitmap in [MediaSessionManager.publishMetadata]. URI keys are additive — the
 * inline Bitmap is the primary carrier because a broken URI (write failure,
 * missing file) causes AAOS renderers to suppress the whole metadata bundle
 * and blank the card. Writes are instrumented (CARLINK_ART_CACHE logtag) so
 * any silent failure surfaces in logcat rather than manifesting as an empty UI.
 *
 * Caching strategy:
 * - Key by SHA-1 of raw image bytes → idempotent across re-sends of the same cover.
 * - Two-pass decode: [BitmapFactory.Options.inJustDecodeBounds] then compute
 *   `inSampleSize` to decode directly at a near-target resolution.
 * - Persist as JPEG quality 85 at RGB_565 → small files, imperceptible loss.
 * - Bounded LRU: keep [MAX_FILES] most-recent files, prune older on each write.
 *
 * Decoding is blocking; call [put] / [decodeDisplayIcon] from a background
 * dispatcher. Result URIs are thread-safe to hand to [setMetadata].
 */
class AlbumArtCache(
    private val context: Context,
) {
    private val authority: String = "${context.packageName}.albumart"
    private val dir: File = File(context.cacheDir, DIR_NAME).also { d ->
        val made = d.mkdirs()
        if (BuildConfig.DEBUG) Log.d(TAG, "dir=${d.absolutePath} existed=${!made && d.exists()} created=$made writable=${d.canWrite()}")
    }

    /** Exposed so callers can guard URI publish on disk presence. */
    fun fileForHash(key: String): File = File(dir, "$key.jpg")

    /**
     * Decode, downscale, persist to cache, return a content:// URI.
     *
     * @param bytes raw album art (JPEG or PNG from CarPlay/AA adapter).
     * @param maxPx target maximum dimension; AAOS hosts hint via
     *   `BROWSER_ROOT_HINTS_KEY_MEDIA_ART_SIZE_PIXELS` in onGetRoot. Default 320.
     * @return content:// URI, or null on decode failure.
     */
    fun put(bytes: ByteArray, maxPx: Int = DEFAULT_MAX_PX): Uri? {
        if (bytes.isEmpty()) {
            if (BuildConfig.DEBUG) Log.w(TAG, "put: empty bytes")
            return null
        }
        val key = sha1(bytes)
        val file = File(dir, "$key.jpg")
        if (file.exists() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            if (BuildConfig.DEBUG) Log.d(TAG, "put: cache hit key=${key.take(8)} size=${file.length()}B")
            return FileProvider.getUriForFile(context, authority, file)
        }
        val bmp = decodeDownscaled(bytes, maxPx)
        if (bmp == null) {
            if (BuildConfig.DEBUG) Log.w(TAG, "put: decodeDownscaled returned null (format unsupported?), bytes=${bytes.size}B sig=${bytes.take(4).joinToString(",") { "%02X".format(it) }}")
            return null
        }
        var compressOk = false
        try {
            FileOutputStream(file).use { out ->
                compressOk = bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "put: compress/write failed: ${e.message}")
        } finally {
            bmp.recycle()
        }
        val finalSize = if (file.exists()) file.length() else -1L
        if (!compressOk || finalSize <= 0) {
            Log.w(TAG, "put: FAILED compressOk=$compressOk fileSize=$finalSize; deleting truncated file")
            file.delete()
            return null
        }
        pruneIfOver(MAX_FILES)
        if (BuildConfig.DEBUG) Log.d(TAG, "put: wrote key=${key.take(8)} size=${finalSize}B")
        return FileProvider.getUriForFile(context, authority, file)
    }

    /**
     * Decode a small bitmap for [android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON]
     * fallback. Kept because some AAOS OEM forks do not reliably auto-grant the
     * FileProvider URI permission to media controllers — the inline icon guarantees
     * something shows up while the URI path is exercised.
     */
    fun decodeDisplayIcon(bytes: ByteArray, maxPx: Int = DISPLAY_ICON_MAX_PX): Bitmap? =
        decodeDownscaled(bytes, maxPx)

    private fun decodeDownscaled(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val decode = BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxPx)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode) ?: return null
        if (raw.width <= maxPx && raw.height <= maxPx) return raw
        val ratio = minOf(maxPx.toFloat() / raw.width, maxPx.toFloat() / raw.height)
        val scaled = Bitmap.createScaledBitmap(
            raw,
            (raw.width * ratio).toInt().coerceAtLeast(1),
            (raw.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== raw) raw.recycle()
        return scaled
    }

    private fun computeSampleSize(w: Int, h: Int, target: Int): Int {
        var s = 1
        var cw = w
        var ch = h
        while (cw / 2 >= target && ch / 2 >= target) {
            s *= 2
            cw /= 2
            ch /= 2
        }
        return s
    }

    private fun pruneIfOver(maxFiles: Int) {
        val files = dir.listFiles() ?: return
        if (files.size <= maxFiles) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxFiles)
            .forEach { it.delete() }
    }

    private fun sha1(b: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(b)
        val sb = StringBuilder(digest.size * 2)
        for (byte in digest) sb.append("%02x".format(byte))
        return sb.toString()
    }

    companion object {
        private const val DIR_NAME = "albumart"
        private const val DEFAULT_MAX_PX = 320
        private const val DISPLAY_ICON_MAX_PX = 256
        private const val JPEG_QUALITY = 85
        private const val MAX_FILES = 32
    }
}
