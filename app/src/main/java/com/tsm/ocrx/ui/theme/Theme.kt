package com.tsm.ocrx.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// Industrial palette: charcoal steel + safety orange.
val SafetyOrange = Color(0xFFFF7A18)
val Charcoal = Color(0xFF0F1114)
val Panel = Color(0xFF171A1F)
val PanelHigh = Color(0xFF1E222A)
val Steel = Color(0xFF8A96A3)
val SteelDim = Color(0xFF5B646E)
val Hairline = Color(0xFF2C333C)
val OffWhite = Color(0xFFE7EAEE)

private val IndustrialColors = darkColorScheme(
    primary = SafetyOrange,
    onPrimary = Charcoal,
    primaryContainer = SafetyOrange,
    onPrimaryContainer = Charcoal,
    secondary = Steel,
    onSecondary = Charcoal,
    background = Charcoal,
    onBackground = OffWhite,
    surface = Panel,
    onSurface = OffWhite,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = Steel,
    outline = Hairline,
    outlineVariant = Hairline,
    error = Color(0xFFFF5252),
    onError = Charcoal,
    errorContainer = Color(0xFF2A1416),
    onErrorContainer = Color(0xFFFF8A8A)
)

/** Sharp, boxy shapes for an industrial/technical feel. */
val PanelShape = RoundedCornerShape(6.dp)
val ChipShape = RoundedCornerShape(4.dp)

@Composable
fun OcrXTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Charcoal.toArgb()
            window.navigationBarColor = Charcoal.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(colorScheme = IndustrialColors, content = content)
}
