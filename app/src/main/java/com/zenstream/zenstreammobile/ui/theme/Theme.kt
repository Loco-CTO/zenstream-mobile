package com.zenstream.zenstreammobile.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ZenBlack = Color(0xFF08090B)
private val ZenSurface = Color(0xFF141318)
private val ZenSurfaceContainer = Color(0xFF1B1920)
private val ZenForeground = Color(0xFFF7F3FB)
private val ZenViolet = Color(0xFFA78BFA)
private val ZenDeepViolet = Color(0xFF6D28D9)
private val ZenOutline = Color(0x33FFFFFF)

private val ZenDarkColors: ColorScheme =
    darkColorScheme(
        primary = ZenViolet,
        onPrimary = Color.Black,
        primaryContainer = ZenDeepViolet,
        onPrimaryContainer = Color.White,
        secondary = Color(0xFFC4B5FD),
        onSecondary = Color.Black,
        background = ZenBlack,
        onBackground = ZenForeground,
        surface = ZenSurface,
        onSurface = ZenForeground,
        surfaceVariant = ZenSurfaceContainer,
        onSurfaceVariant = Color(0xFFC2BACB),
        outline = ZenOutline,
    )

private val ZenTypography = Typography()
private val ZenShapes =
    Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
        small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    )

@Composable
fun ZenStreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ZenDarkColors,
        typography = ZenTypography,
        shapes = ZenShapes,
        content = {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = ZenDarkColors.background,
                contentColor = ZenDarkColors.onBackground,
                content = content,
            )
        },
    )
}
