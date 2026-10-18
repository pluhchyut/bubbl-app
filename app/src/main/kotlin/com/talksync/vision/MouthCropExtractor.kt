package com.talksync.vision

// cuts out the mouth area from camera frames for visual speech experiments/debugging.

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Crops the mouth region from a camera bitmap using the lip contour from
 * [LipLandmarkAnalyzer.LipSignal], then resizes and normalizes for the
 * lipreading model.
 *
 * Output: 96x96 grayscale FloatArray normalized to [-1, 1].
 */
object MouthCropExtractor {

    private const val TAG = "MouthCropExtractor"
    const val OUTPUT_SIZE = 96
    private const val PADDING_RATIO = 0.35f

    /**
     * Returns a 96*96 FloatArray (grayscale, [-1, 1]) or null if the contour
     * is empty or the resulting crop would be invalid.
     *
     * The bitmap is NOT recycled; the caller owns it.
     */
    fun extract(
        bitmap: Bitmap,
        lipSignal: LipLandmarkAnalyzer.LipSignal,
    ): FloatArray? {
        if (bitmap.isRecycled) return null
        val contour = lipSignal.contour
        if (contour.isEmpty) return null

        val points = buildList(
            contour.outerUpper.size + contour.outerLower.size +
                contour.innerUpper.size + contour.innerLower.size,
        ) {
            addAll(contour.outerUpper)
            addAll(contour.outerLower)
            addAll(contour.innerUpper)
            addAll(contour.innerLower)
        }
        if (points.isEmpty()) return null

        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        if (w <= 0f || h <= 0f) return null

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for ((nx, ny) in points) {
            val px = nx * w
            val py = ny * h
            if (px < minX) minX = px
            if (px > maxX) maxX = px
            if (py < minY) minY = py
            if (py > maxY) maxY = py
        }

        val rawWidth = maxX - minX
        val rawHeight = maxY - minY
        if (rawWidth <= 1f || rawHeight <= 1f) return null

        val padX = rawWidth * PADDING_RATIO
        val padY = rawHeight * PADDING_RATIO

        val left = (minX - padX).coerceAtLeast(0f).toInt()
        val top = (minY - padY).coerceAtLeast(0f).toInt()
        val right = (maxX + padX).coerceAtMost(w).roundToInt()
        val bottom = (maxY + padY).coerceAtMost(h).roundToInt()

        val cropW = right - left
        val cropH = bottom - top
        if (cropW < 4 || cropH < 4) return null

        val cropped = try {
            Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
        } catch (e: Exception) {
            Log.w(TAG, "createBitmap failed: ${e.message}")
            return null
        }

        val resized = if (cropped.width == OUTPUT_SIZE && cropped.height == OUTPUT_SIZE) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, OUTPUT_SIZE, OUTPUT_SIZE, true)
        }

        val pixels = IntArray(OUTPUT_SIZE * OUTPUT_SIZE)
        resized.getPixels(pixels, 0, OUTPUT_SIZE, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE)
        if (resized !== cropped) resized.recycle()

        val out = FloatArray(OUTPUT_SIZE * OUTPUT_SIZE)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // Rec. 601 luma
            val gray = (0.299f * r + 0.587f * g + 0.114f * b)
            out[i] = (gray / 127.5f) - 1f
        }
        return out
    }

    /**
     * Saves a [-1, 1] grayscale crop to disk as PNG for visual verification.
     */
    fun debugSaveCrop(crop: FloatArray, path: String) {
        if (crop.size != OUTPUT_SIZE * OUTPUT_SIZE) {
            Log.w(TAG, "debugSaveCrop: unexpected size ${crop.size}")
            return
        }
        val pixels = IntArray(crop.size)
        for (i in crop.indices) {
            val v = ((crop[i] + 1f) * 127.5f).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(v, v, v)
        }
        val bmp = Bitmap.createBitmap(OUTPUT_SIZE, OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, OUTPUT_SIZE, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE)
        try {
            FileOutputStream(path).use { fos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
        } catch (e: Exception) {
            Log.w(TAG, "debugSaveCrop failed: ${e.message}")
        } finally {
            bmp.recycle()
        }
    }
}
