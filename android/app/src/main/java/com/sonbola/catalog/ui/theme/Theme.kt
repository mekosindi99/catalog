package com.sonbola.catalog.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SonbolaColorScheme = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = BrandLight,
    background = Surface,
    surface = CardWhite,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = Color(0xFFDC2626)
)

@Composable
fun SonbolaCatalogTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SonbolaColorScheme,
        typography = AppTypography,
        content = content
    )
}
