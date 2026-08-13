package com.fraudguard.monitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val FraudGuardPrimary = Color(0xFF1B3A57)
val FraudGuardCritical = Color(0xFFC62828)
val FraudGuardWarning = Color(0xFFF9A825)

private val LightColors = lightColorScheme(primary = FraudGuardPrimary, error = FraudGuardCritical)
private val DarkColors = darkColorScheme(primary = FraudGuardPrimary, error = FraudGuardCritical)

@Composable
fun FraudGuardMonitorTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
