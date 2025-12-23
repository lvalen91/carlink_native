package com.carlink.util

import android.content.Context

/**
 * Utility for loading icon assets for adapter initialization.
 *
 * Icons are uploaded to the adapter during initialization for display
 * in CarPlay/Android Auto device pickers.
 */
object IconAssets {
    private const val ICON_120_ASSET = "ICON_120x120.png"
    private const val ICON_180_ASSET = "ICON_180x180.png"
    private const val ICON_256_ASSET = "ICON_256x256.png"

    /**
     * Load icon data from assets folder.
     *
     * @param context Android context for asset access
     * @return Triple of (icon120, icon180, icon256) byte arrays, or nulls if loading fails
     */
    fun loadIcons(context: Context): Triple<ByteArray?, ByteArray?, ByteArray?> {
        val icon120 = loadAsset(context, ICON_120_ASSET)
        val icon180 = loadAsset(context, ICON_180_ASSET)
        val icon256 = loadAsset(context, ICON_256_ASSET)
        return Triple(icon120, icon180, icon256)
    }

    private fun loadAsset(
        context: Context,
        fileName: String,
    ): ByteArray? =
        try {
            context.assets.open(fileName).use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
}
