package com.talksync.ui.theme

// spacing/radius/motion tokens. boring little numbers that keep screens from looking cursed.

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.unit.dp

object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
    val xxxl = 36.dp
}

object Radii {
    val xs = 8.dp
    val s = 12.dp
    val m = 16.dp
    val l = 20.dp
    val xl = 24.dp
    val pill = 999.dp
}

object MotionDurations {
    const val micro = 120
    const val short = 180
    const val medium = 240
    const val long = 360
}

val EaseStandard = CubicBezierEasing(0.20f, 0.00f, 0.00f, 1.00f)
val EaseEmphasized = CubicBezierEasing(0.30f, 0.00f, 0.05f, 1.00f)
