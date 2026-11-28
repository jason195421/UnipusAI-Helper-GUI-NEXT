package io.github.unipus.helper.ui

import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

private val AccentLight = Color(0xFF0078D4)
private val AccentDark = Color(0xFF60CDFF)

/** Maps the app palette onto a miuix [Colors] instance. */
fun miuixPalette(dark: Boolean): Colors = if (dark) darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF00121F),
    primaryVariant = AccentDark,
    onPrimaryVariant = Color(0xFFBFE6FF),
    primaryContainer = Color(0xFF003A5C),
    onPrimaryContainer = Color(0xFFBFE6FF),
    secondaryVariant = Color(0xFF222B38),
    onSecondaryVariant = Color(0xFFF0F5FC),
    secondaryContainer = Color(0xFF2C3646),
    onSecondaryContainer = Color(0xFFDCE6F3),
    background = Color(0xFF181E28),
    onBackground = Color(0xFFF0F5FC),
    onBackgroundVariant = Color(0xFFBFCCDD),
    surface = Color(0xFF181E28),
    onSurface = Color(0xFFF0F5FC),
    surfaceVariant = Color(0xFF222B38),
    onSurfaceSecondary = Color(0xFFBFCCDD),
    onSurfaceVariantSummary = Color(0xFFA8B6C8),
    onSurfaceVariantActions = Color(0xFF8895A8),
    surfaceContainer = Color(0xFF222B38),
    onSurfaceContainer = Color(0xFF8895A8),
    secondaryContainerVariant = Color(0xFF26303E),
    onSurfaceContainerVariant = Color(0xFFBFCCDD),
    surfaceContainerHigh = Color(0xFF2C3646),
    onSurfaceContainerHigh = Color(0xFFBFCCDD),
    surfaceContainerHighest = Color(0xFF3A475B),
    onSurfaceContainerHighest = Color(0xFFF0F5FC),
    outline = Color(0xFF3A475B),
    dividerLine = Color(0xFF3A475B),
) else lightColorScheme(
    primary = AccentLight,
    onPrimary = Color.White,
    primaryVariant = AccentLight,
    onPrimaryVariant = Color(0xFF0A3D66),
    primaryContainer = Color(0xFFD6EAFF),
    onPrimaryContainer = Color(0xFF00294B),
    secondaryVariant = Color(0xFFFFFFFF),
    onSecondaryVariant = Color(0xFF1C2B41),
    secondaryContainer = Color(0xFFEDF2F9),
    onSecondaryContainer = Color(0xFF1C2B41),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF1C2B41),
    onBackgroundVariant = Color(0xFF52637B),
    surface = Color(0xFFF4F7FB),
    onSurface = Color(0xFF1C2B41),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceSecondary = Color(0xFF324359),
    onSurfaceVariantSummary = Color(0xFF52637B),
    onSurfaceVariantActions = Color(0xFF6C7F99),
    surfaceContainer = Color(0xFFFFFFFF),
    onSurfaceContainer = Color(0xFF52637B),
    secondaryContainerVariant = Color(0xFFF8FBFF),
    onSurfaceContainerVariant = Color(0xFF324359),
    surfaceContainerHigh = Color(0xFFEDF2F9),
    onSurfaceContainerHigh = Color(0xFF8494A8),
    surfaceContainerHighest = Color(0xFFDCE4EF),
    onSurfaceContainerHighest = Color(0xFF1C2B41),
    outline = Color(0xFFDCE4EF),
    dividerLine = Color(0xFFDCE4EF),
)
