package com.example.myapplication.views.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CasinoGold,
    secondary = CasinoEmeraldLight,
    background = CasinoDarkFelt,
    surface = CasinoEmerald
)

@Composable
fun HockeyGameTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}

@Composable
fun DominoTheme(
    content: @Composable () -> Unit
) {
    HockeyGameTheme(content = content)
}
