package com.talksync.ui.screens

// bubble geometry wrangler. mostly stops labels from sitting on top of each other.

import android.graphics.RectF
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.talksync.model.SpeechBubbleData
import kotlin.math.max
import kotlin.math.roundToInt

internal data class BubbleRenderModel(
    val bubble: SpeechBubbleData,
    val anchorBounds: RectF?,
    val bubbleSize: IntSize,
    /**
     * Optional normalized [0..1] mouth-center coordinate. When provided, the
     * bubble is anchored to the mouth instead of the face bounding box, which
     * keeps captions tighter to the speaker even as their face turns.
     */
    val mouthAnchor: MouthAnchor? = null,
)

internal data class MouthAnchor(
    val x: Float,
    val y: Float,
)

internal data class ResolvedBubbleLayout(
    val bubble: SpeechBubbleData,
    val offset: IntOffset,
)

internal fun resolveBubbleLayouts(
    models: List<BubbleRenderModel>,
    maxWidthPx: Float,
    maxHeightPx: Float,
    marginPx: Float,
    minWidthPx: Float,
    defaultTopFraction: Float,
    topSpacingPx: Float,
    sideLeadPx: Float = 0f,
    motionOffsetPx: IntOffset = IntOffset.Zero,
    reservedRightPx: Float = 0f,
): List<ResolvedBubbleLayout> {
    if (models.isEmpty()) {
        return emptyList()
    }

    val gapPx = marginPx * 0.75f
    // Keep camera coordinates unchanged; reserve space only when placing the caption rectangles.
    val placementWidthPx = (maxWidthPx - reservedRightPx).coerceAtLeast(minWidthPx + marginPx * 2f)
    val fallbackSlotWidth = ((placementWidthPx - (marginPx * 2f)) / models.size.coerceAtLeast(1)).coerceAtLeast(minWidthPx)
    // first pass: where each bubble wants to go
    val desiredRects = models.mapIndexed { index, model ->
        val bubbleWidth = max(model.bubbleSize.width, minWidthPx.roundToInt()).toFloat()
        val bubbleHeight = model.bubbleSize.height.toFloat().coerceAtLeast(topSpacingPx * 2.6f)
        val containerWidth = bubbleWidth.coerceAtLeast(minWidthPx)
        val containerHeight = bubbleHeight

        val mouth = model.mouthAnchor
        val desiredOffset = when {
            mouth != null -> {
                // Anchor horizontally on the mouth and place the bubble just
                // above it. The face bounding-box top is used as a ceiling so
                // the bubble doesn't drift onto the forehead when the head
                // tilts down.
                val mouthY = mouth.y * maxHeightPx
                val faceTopY = (model.anchorBounds?.top ?: mouth.y) * maxHeightPx
                val ceiling = minOf(mouthY, faceTopY)
                val faceSide = anchorSide(mouth.x)
                IntOffset(
                    x = ((mouth.x * maxWidthPx) - (containerWidth / 2f) + (sideLeadPx * faceSide) + motionOffsetPx.x).roundToInt(),
                    y = (ceiling - bubbleHeight - topSpacingPx + motionOffsetPx.y).roundToInt(),
                )
            }
            model.anchorBounds != null -> {
                val bounds = model.anchorBounds
                val faceSide = anchorSide(bounds.centerX())
                IntOffset(
                    x = ((bounds.centerX() * maxWidthPx) - (containerWidth / 2f) + (sideLeadPx * faceSide) + motionOffsetPx.x).roundToInt(),
                    y = ((bounds.top * maxHeightPx) - bubbleHeight - topSpacingPx + motionOffsetPx.y).roundToInt(),
                )
            }
            else -> IntOffset(
                x = (marginPx + (fallbackSlotWidth * index) + ((fallbackSlotWidth - containerWidth) / 2f)).roundToInt(),
                y = (maxHeightPx * defaultTopFraction - containerHeight).roundToInt(),
            )
        }

        DesiredBubbleRect(
            model = model,
            rect = clampRect(
                left = desiredOffset.x.toFloat(),
                top = desiredOffset.y.toFloat(),
                width = containerWidth,
                height = containerHeight,
                maxWidthPx = placementWidthPx,
                maxHeightPx = maxHeightPx,
                marginPx = marginPx,
            ),
            sortKey = model.mouthAnchor?.x
                ?: model.anchorBounds?.centerX()
                ?: desiredOffset.x.toFloat(),
        )
    }.sortedWith(
        compareBy<DesiredBubbleRect> { it.sortKey }
            .thenBy { it.rect.top },
    )

    val placed = mutableListOf<PlacedBubbleRect>()
    desiredRects.forEach { desired ->
        var currentRect = desired.rect
        repeat(12) {
            // second pass: push overlaps apart
            val collision = placed.firstOrNull { placedRect ->
                rectsOverlap(currentRect, placedRect.rect, gapPx)
            } ?: return@repeat

            val moveDownTop = collision.rect.bottom + gapPx
            val movedDown = clampRect(
                left = currentRect.left,
                top = moveDownTop,
                width = currentRect.width(),
                height = currentRect.height(),
                maxWidthPx = placementWidthPx,
                maxHeightPx = maxHeightPx,
                marginPx = marginPx,
            )
            if (!rectsOverlap(movedDown, collision.rect, gapPx)) {
                currentRect = movedDown
                return@repeat
            }

            val moveUpTop = collision.rect.top - currentRect.height() - gapPx
            val movedUp = clampRect(
                left = currentRect.left,
                top = moveUpTop,
                width = currentRect.width(),
                height = currentRect.height(),
                maxWidthPx = placementWidthPx,
                maxHeightPx = maxHeightPx,
                marginPx = marginPx,
            )
            if (!rectsOverlap(movedUp, collision.rect, gapPx)) {
                currentRect = movedUp
                return@repeat
            }

            val horizontalNudge = if (desired.sortKey <= collision.sortKey) {
                -(gapPx + (currentRect.width() * 0.22f))
            } else {
                gapPx + (currentRect.width() * 0.22f)
            }
            currentRect = clampRect(
                left = currentRect.left + horizontalNudge,
                top = currentRect.top + (gapPx * 0.4f),
                width = currentRect.width(),
                height = currentRect.height(),
                maxWidthPx = placementWidthPx,
                maxHeightPx = maxHeightPx,
                marginPx = marginPx,
            )
        }

        var safety = 0
        while (placed.any { rectsOverlap(currentRect, it.rect, gapPx) } && safety < 16) {
            val blockingRects = placed.filter { rectsOverlap(currentRect, it.rect, gapPx) }
            val lowestCollisionBottom = blockingRects.maxOf { it.rect.bottom }
            currentRect = clampRect(
                left = currentRect.left,
                top = lowestCollisionBottom + gapPx,
                width = currentRect.width(),
                height = currentRect.height(),
                maxWidthPx = placementWidthPx,
                maxHeightPx = maxHeightPx,
                marginPx = marginPx,
            )
            safety += 1
        }

        placed += PlacedBubbleRect(
            bubbleId = desired.model.bubble.id,
            rect = currentRect,
            sortKey = desired.sortKey,
        )
    }

    val resolvedPlaced = resolveRemainingCollisions(
        placed = placed,
        maxWidthPx = placementWidthPx,
        maxHeightPx = maxHeightPx,
        marginPx = marginPx,
        gapPx = gapPx,
    )

    return resolvedPlaced.map { placedRect ->
        val model = models.first { it.bubble.id == placedRect.bubbleId }
        ResolvedBubbleLayout(
            bubble = model.bubble,
            offset = IntOffset(
                x = placedRect.rect.left.roundToInt(),
                y = placedRect.rect.top.roundToInt(),
            ),
        )
    }
}

private fun resolveRemainingCollisions(
    placed: List<PlacedBubbleRect>,
    maxWidthPx: Float,
    maxHeightPx: Float,
    marginPx: Float,
    gapPx: Float,
): List<PlacedBubbleRect> {
    val settled = placed.toMutableList()
    repeat(8) {
        var moved = false
        for (i in settled.indices) {
            for (j in i + 1 until settled.size) {
                val first = settled[i]
                val second = settled[j]
                if (!rectsOverlap(first.rect, second.rect, gapPx)) {
                    continue
                }

                val firstCenterX = first.rect.centerX()
                val secondCenterX = second.rect.centerX()
                val firstCenterY = first.rect.centerY()
                val secondCenterY = second.rect.centerY()
                val overlapX = minOf(first.rect.right + gapPx - second.rect.left, second.rect.right + gapPx - first.rect.left)
                val overlapY = minOf(first.rect.bottom + gapPx - second.rect.top, second.rect.bottom + gapPx - first.rect.top)

                if (overlapX < overlapY) {
                    val direction = if (firstCenterX <= secondCenterX) -1f else 1f
                    val push = (overlapX * 0.5f).coerceAtLeast(gapPx * 0.35f)
                    settled[i] = first.copy(
                        rect = first.rect.shiftedClamped(
                            deltaX = direction * push,
                            deltaY = 0f,
                            maxWidthPx = maxWidthPx,
                            maxHeightPx = maxHeightPx,
                            marginPx = marginPx,
                        ),
                    )
                    settled[j] = second.copy(
                        rect = second.rect.shiftedClamped(
                            deltaX = -direction * push,
                            deltaY = 0f,
                            maxWidthPx = maxWidthPx,
                            maxHeightPx = maxHeightPx,
                            marginPx = marginPx,
                        ),
                    )
                } else {
                    val direction = if (firstCenterY <= secondCenterY) -1f else 1f
                    val push = (overlapY * 0.5f).coerceAtLeast(gapPx * 0.35f)
                    settled[i] = first.copy(
                        rect = first.rect.shiftedClamped(
                            deltaX = 0f,
                            deltaY = direction * push,
                            maxWidthPx = maxWidthPx,
                            maxHeightPx = maxHeightPx,
                            marginPx = marginPx,
                        ),
                    )
                    settled[j] = second.copy(
                        rect = second.rect.shiftedClamped(
                            deltaX = 0f,
                            deltaY = -direction * push,
                            maxWidthPx = maxWidthPx,
                            maxHeightPx = maxHeightPx,
                            marginPx = marginPx,
                        ),
                    )
                }
                moved = true
            }
        }
        if (!moved) {
            return settled
        }
    }
    return settled
}

private fun RectF.shiftedClamped(
    deltaX: Float,
    deltaY: Float,
    maxWidthPx: Float,
    maxHeightPx: Float,
    marginPx: Float,
): RectF {
    return clampRect(
        left = left + deltaX,
        top = top + deltaY,
        width = width(),
        height = height(),
        maxWidthPx = maxWidthPx,
        maxHeightPx = maxHeightPx,
        marginPx = marginPx,
    )
}

private fun anchorSide(normalizedX: Float): Float {
    return when {
        normalizedX < 0.44f -> -1f
        normalizedX > 0.56f -> 1f
        else -> 0f
    }
}

private data class DesiredBubbleRect(
    val model: BubbleRenderModel,
    val rect: RectF,
    val sortKey: Float,
)

private data class PlacedBubbleRect(
    val bubbleId: String,
    val rect: RectF,
    val sortKey: Float,
)

private fun clampRect(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    maxWidthPx: Float,
    maxHeightPx: Float,
    marginPx: Float,
): RectF {
    val availableWidth = (maxWidthPx - (marginPx * 2f)).coerceAtLeast(1f)
    val availableHeight = (maxHeightPx - (marginPx * 2f)).coerceAtLeast(1f)
    val clampedWidth = width.coerceIn(1f, availableWidth)
    val clampedHeight = height.coerceIn(1f, availableHeight)
    val clampedLeft = left.coerceIn(
        minimumValue = marginPx,
        maximumValue = (maxWidthPx - clampedWidth - marginPx).coerceAtLeast(marginPx),
    )
    val clampedTop = top.coerceIn(
        minimumValue = marginPx,
        maximumValue = (maxHeightPx - clampedHeight - marginPx).coerceAtLeast(marginPx),
    )
    return RectF(
        clampedLeft,
        clampedTop,
        clampedLeft + clampedWidth,
        clampedTop + clampedHeight,
    )
}

private fun rectsOverlap(
    first: RectF,
    second: RectF,
    padding: Float,
): Boolean {
    return first.left < second.right + padding &&
        first.right > second.left - padding &&
        first.top < second.bottom + padding &&
        first.bottom > second.top - padding
}
