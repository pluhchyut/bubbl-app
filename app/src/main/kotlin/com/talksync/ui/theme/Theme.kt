package com.talksync.ui.theme

// material theme wrapper. screens should come through here instead of inventing random styles.

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val TalkSyncDarkColorScheme = darkColorScheme(
    background = Black,
    surface = SurfaceBlack,
    surfaceVariant = SurfaceVariantBlack,
    primary = PureWhite,
    onPrimary = Black,
    secondary = BrandAccent,
    onSecondary = Black,
    tertiary = BrandAccentSoft,
    onTertiary = Black,
    onBackground = PureWhite,
    onSurface = PureWhite,
    onSurfaceVariant = SecondaryWhite,
    outline = HairlineWhite,
    error = StatusAlertCoral,
    onError = Black,
)

@Composable
fun TalkSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TalkSyncDarkColorScheme,
        typography = TalkSyncTypography,
        content = content,
    )
}
