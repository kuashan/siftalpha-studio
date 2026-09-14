package com.siftalpha.studio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    background = Color(0xFFF7F8FC),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B24),
    primary = Color(0xFF5843B8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE7E1FF),
    onPrimaryContainer = Color(0xFF241752),
    surfaceVariant = Color(0xFFE8E8F0),
    onSurfaceVariant = Color(0xFF565B69),
    errorContainer = Color(0xFFFDE6E8),
    onErrorContainer = Color(0xFFA02635),
)

private val DarkColors = darkColorScheme(
    background = Color(0xFF111318),
    surface = Color(0xFF1B1E26),
    onSurface = Color(0xFFF1F0F7),
    primary = Color(0xFFB5ABFF),
    onPrimary = Color(0xFF251951),
    primaryContainer = Color(0xFF3B2F73),
    onPrimaryContainer = Color(0xFFE7E1FF),
    surfaceVariant = Color(0xFF2A2D37),
    onSurfaceVariant = Color(0xFFB9BBC8),
    errorContainer = Color(0xFF43252B),
    onErrorContainer = Color(0xFFFFB3BC),
)

@Immutable
data class StudioSpacing(
    val xSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 12.dp,
    val large: Dp = 16.dp,
    val xLarge: Dp = 24.dp,
    val xxLarge: Dp = 32.dp,
)

@Immutable
data class StudioStatusColors(
    val successContainer: Color,
    val onSuccess: Color,
    val warningContainer: Color,
    val onWarning: Color,
)

private val LocalStudioSpacing = staticCompositionLocalOf { StudioSpacing() }
private val LocalStudioStatusColors = staticCompositionLocalOf {
    StudioStatusColors(
        successContainer = Color.Unspecified,
        onSuccess = Color.Unspecified,
        warningContainer = Color.Unspecified,
        onWarning = Color.Unspecified,
    )
}

object StudioThemeTokens {
    val spacing: StudioSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalStudioSpacing.current

    val status: StudioStatusColors
        @Composable
        @ReadOnlyComposable
        get() = LocalStudioStatusColors.current
}

private val StudioTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
)

@Composable
fun StudioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val statusColors = if (darkTheme) {
        StudioStatusColors(
            successContainer = Color(0xFF193C30),
            onSuccess = Color(0xFF9CDFB5),
            warningContainer = Color(0xFF403318),
            onWarning = Color(0xFFF2D185),
        )
    } else {
        StudioStatusColors(
            successContainer = Color(0xFFDEF7E8),
            onSuccess = Color(0xFF12663F),
            warningContainer = Color(0xFFFFF2D3),
            onWarning = Color(0xFF735100),
        )
    }

    CompositionLocalProvider(
        LocalStudioSpacing provides StudioSpacing(),
        LocalStudioStatusColors provides statusColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = StudioTypography,
            content = content,
        )
    }
}
