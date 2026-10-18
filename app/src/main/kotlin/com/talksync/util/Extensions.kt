package com.talksync.util

// small formatting/android helpers that were not worth making whole classes for.

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val historyTimestampFormatter = DateTimeFormatter.ofPattern("HH:mm · MMM d")
private val monthDayFormatter = DateTimeFormatter.ofPattern("MMMM d")

fun Long.toHistoryTimestamp(): String {
    return Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .format(historyTimestampFormatter)
}

fun Long.toHistoryHeader(referenceDate: LocalDate = LocalDate.now()): String {
    val date = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
    return when (date) {
        referenceDate -> "Today"
        referenceDate.minusDays(1) -> "Yesterday"
        else -> date.format(monthDayFormatter)
    }
}

fun String.wordCount(): Int {
    return trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
}

fun Float.roundToTenths(): Float = (this * 10f).roundToInt() / 10f

fun Context.findActivity(): ComponentActivity? {
    var currentContext: Context = this
    while (currentContext is ContextWrapper) {
        if (currentContext is ComponentActivity) {
            return currentContext
        }
        currentContext = currentContext.baseContext
    }
    return null
}
