package com.talksync.vision

// tiny mouth-movement score for fast checks before heavier landmark stuff runs.

/**
 * REPLACED by LipLandmarkAnalyzer.kt
 *
 * LipActivityAnalyzer used pixel-delta analysis on mouth region bitmaps.
 * LipLandmarkAnalyzer uses MediaPipe Face Landmarker for precise geometric
 * lip landmark coordinates, giving a much stronger and more reliable signal.
 *
 * This file is kept as an empty stub to avoid breaking any import that
 * may not have been updated. All logic has moved to LipLandmarkAnalyzer.
 */
@Deprecated("Use LipLandmarkAnalyzer instead", replaceWith = ReplaceWith("LipLandmarkAnalyzer"))
class LipActivityAnalyzer
