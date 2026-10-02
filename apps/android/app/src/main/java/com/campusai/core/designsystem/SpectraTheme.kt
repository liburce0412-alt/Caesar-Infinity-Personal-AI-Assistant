package com.campusai.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.campusai.R
import com.campusai.core.model.SpectraEnvironment
import com.campusai.core.model.ThemeMode

object SpectraColors {
    val Paper = Color(0xFFF8F9FD)
    val Ink = Color(0xFF162033)
    val Night = Color(0xFF0D1422)
    val Cyan = Color(0xFF16C5DC)
    val Violet = Color(0xFF7562F5)
    val Warm = Color(0xFFFF8B43)
    val Rose = Color(0xFFFF79B9)
    val Silver = Color(0xFFDBE3ED)
    val Success = Color(0xFF159763)
    val Warning = Color(0xFFFFB020)
    val Error = Color(0xFFD33F65)
    val Focus = Color(0xFF5A7DFF)
    val Aurora = Color(0xFF187A53)
    val AuroraLight = Color(0xFF72DCA5)
}

val Tomorrow = FontFamily(
    Font(R.font.tomorrow_regular, FontWeight.Normal),
    Font(R.font.tomorrow_semibold, FontWeight.SemiBold),
)

val Plex = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3655C9),
    onPrimary = Color.White,
    secondary = SpectraColors.Violet,
    tertiary = SpectraColors.Cyan,
    background = SpectraColors.Paper,
    onBackground = SpectraColors.Ink,
    surface = Color.White,
    onSurface = SpectraColors.Ink,
    surfaceVariant = Color(0xFFE9EDF5),
    surfaceDim = Color(0xFFDCE2EB),
    surfaceBright = Color(0xFFFAFBFE),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F6FA),
    surfaceContainer = Color(0xFFEEF1F7),
    surfaceContainerHigh = Color(0xFFE7ECF4),
    surfaceContainerHighest = Color(0xFFDFE5EF),
    onSurfaceVariant = Color(0xFF465369),
    primaryContainer = Color(0xFFE3E9FF),
    onPrimaryContainer = Color(0xFF182C70),
    secondaryContainer = Color(0xFFEBE5FA),
    onSecondaryContainer = Color(0xFF392B5F),
    tertiaryContainer = Color(0xFFD8EFEE),
    onTertiaryContainer = Color(0xFF173F40),
    outline = Color(0xFF728096),
    outlineVariant = Color(0xFFCDD5E2),
    error = SpectraColors.Error,
)

private val DarkColors = darkColorScheme(
    primary = Color.White,
    onPrimary = SpectraColors.Night,
    secondary = Color(0xFFAFA4FF),
    tertiary = Color(0xFF72E4EE),
    background = SpectraColors.Night,
    onBackground = Color(0xFFF3F6FC),
    surface = Color(0xFF151E2E),
    onSurface = Color(0xFFF3F6FC),
    surfaceVariant = Color(0xFF253248),
    surfaceDim = SpectraColors.Night,
    surfaceBright = Color(0xFF334158),
    surfaceContainerLowest = Color(0xFF0A101B),
    surfaceContainerLow = Color(0xFF121B2B),
    surfaceContainer = Color(0xFF182335),
    surfaceContainerHigh = Color(0xFF202D40),
    surfaceContainerHighest = Color(0xFF2A384D),
    onSurfaceVariant = Color(0xFFC1CCDC),
    primaryContainer = Color(0xFF2E416C),
    onPrimaryContainer = Color(0xFFDFE7FF),
    secondaryContainer = Color(0xFF423655),
    onSecondaryContainer = Color(0xFFECE2FF),
    tertiaryContainer = Color(0xFF214747),
    onTertiaryContainer = Color(0xFFCEEEEE),
    outline = Color(0xFF91A0B8),
    outlineVariant = Color(0xFF42506A),
    error = Color(0xFFFF7D9D),
)

/**
 * Environment palettes remain restrained Material schemes rather than a second theme system.
 * Existing environments retain the original scheme; 森屿 adds a readable green emphasis and
 * a matching paper/night canvas so the static reduced-motion surface keeps its identity.
 */
internal fun spectraColorScheme(
    dark: Boolean,
    environment: SpectraEnvironment,
): ColorScheme {
    val base = if (dark) DarkColors else LightColors
    if (environment != SpectraEnvironment.AURORA) return base
    return if (dark) {
        base.copy(
            primary = SpectraColors.AuroraLight,
            onPrimary = Color(0xFF082018),
            secondary = Color(0xFFA6DC75),
            tertiary = Color(0xFF55D8C0),
            background = Color(0xFF0B1915),
            onBackground = Color(0xFFEFF9F3),
            surface = Color(0xFF10231D),
            surfaceVariant = Color(0xFF243B2D),
            surfaceDim = Color(0xFF0B1915),
            surfaceBright = Color(0xFF304C3B),
            surfaceContainerLowest = Color(0xFF07110D),
            surfaceContainerLow = Color(0xFF0F1C16),
            surfaceContainer = Color(0xFF15261D),
            surfaceContainerHigh = Color(0xFF1D3127),
            surfaceContainerHighest = Color(0xFF273E31),
            onSurface = Color(0xFFEFF9F3),
            onSurfaceVariant = Color(0xFFC1D6C9),
            outline = Color(0xFF3D6757),
        )
    } else {
        base.copy(
            primary = SpectraColors.Aurora,
            onPrimary = Color.White,
            secondary = Color(0xFF5F8F35),
            tertiary = Color(0xFF198B78),
            background = Color(0xFFF3FAF6),
            onBackground = Color(0xFF13251D),
            surface = Color(0xFFFAFFFC),
            surfaceVariant = Color(0xFFE3EEE7),
            surfaceDim = Color(0xFFD5E3DB),
            surfaceBright = Color(0xFFFBFFFC),
            surfaceContainerLowest = Color(0xFFFAFFFC),
            surfaceContainerLow = Color(0xFFF2F8F4),
            surfaceContainer = Color(0xFFEAF2ED),
            surfaceContainerHigh = Color(0xFFDFEBE3),
            surfaceContainerHighest = Color(0xFFD3E3D9),
            onSurface = Color(0xFF13251D),
            onSurfaceVariant = Color(0xFF415E4C),
            outline = Color(0xFFC6DBD0),
        )
    }
}

private val SpectraTypography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(fontFamily = Plex, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = Plex, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineMedium = TextStyle(fontFamily = Plex, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = Plex, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

@Composable
fun CampusTheme(
    themeMode: ThemeMode,
    environment: SpectraEnvironment = SpectraEnvironment.ORIGINAL,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = spectraColorScheme(dark, environment)
    SpectraSystemBars(dark)
    CompositionLocalProvider(
        LocalSpectraTokens provides DefaultSpectraTokens,
        // Transparent scaffolds and standalone full-screen routes have no Surface to
        // establish foreground color; inherit the active theme instead of default black.
        LocalContentColor provides colors.onBackground,
    ) {
        MaterialTheme(colorScheme = colors, typography = SpectraTypography, content = content)
    }
}

/** Keep every native window aligned with the app theme, including a sheet changed in place. */
@Composable
internal fun SpectraSystemBars(dark: Boolean) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: view.context.themeActivity()?.window
    DisposableEffect(view, window, dark) {
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        }
        onDispose { }
    }
}

private tailrec fun Context.themeActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.themeActivity()
    else -> null
}
