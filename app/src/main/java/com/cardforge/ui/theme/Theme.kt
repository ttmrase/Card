package com.cardforge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF12101A)
val Surface1 = Color(0xFF1C1930)
val Surface2 = Color(0xFF262140)
val Accent = Color(0xFF9B87F5)
val Gold = Color(0xFFC9A227)
val Danger = Color(0xFFE05C5C)

/** 効果による上昇（攻撃力アップなど）を示す色。 */
val Boost = Color(0xFF5BD6A0)

val MonsterColor = Color(0xFFB08A4A)
val SpellColor = Color(0xFF1F8A70)
val TrapColor = Color(0xFF9B3B7A)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF130E24),
    secondary = Gold,
    onSecondary = Color(0xFF201A05),
    background = Ink,
    onBackground = Color(0xFFEDE9F7),
    surface = Surface1,
    onSurface = Color(0xFFEDE9F7),
    surfaceVariant = Surface2,
    onSurfaceVariant = Color(0xFFC4BCE0),
    error = Danger,
    outline = Color(0xFF544C74)
)

@Composable
fun CardForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
