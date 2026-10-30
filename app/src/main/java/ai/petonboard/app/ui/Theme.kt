package ai.petonboard.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** 화면 시안(다크 글라스)과 같은 색 */
object C {
    val Bg = Color(0xFF0A111D)
    val BgGlow = Color(0xFF1A2A47)
    val Text = Color(0xFFF3F6FB)
    val Muted = Color(0xFF9AA8BC)
    val Soft = Color(0xFFC8D2E0)
    val Blue = Color(0xFF5B95FF)
    val BlueLight = Color(0xFF8FB6FF)
    val BlueChip = Color(0x245B95FF)
    val Green = Color(0xFF5EE0A6)
    val Orange = Color(0xFFFBA65A)
    val Red = Color(0xFFF87171)
    val GlassTop = Color(0x1CA0AFC8)
    val GlassBottom = Color(0x0DA0AFC8)
    val GlassLine = Color(0x2BA0AFC8)
    val GhostFill = Color(0x1AA0AFC8)
    val GhostLine = Color(0x38A0AFC8)
    val Scrim = Color(0x99050910)

    val primary = Brush.linearGradient(listOf(Color(0xFF6AA0FF), Color(0xFF3A74FF), Color(0xFF2F63F0)))
    val glass = Brush.verticalGradient(listOf(GlassTop, GlassBottom))
    val ring = Brush.sweepGradient(listOf(Color(0xFF67E8F9), Color(0xFF8B9CFF), Color(0xFFC4B5FD), Color(0xFF67E8F9)))
    val bar = Brush.horizontalGradient(listOf(Color(0xFF8B9CFF), Color(0xFF5B95FF)))
    val dialog = Brush.verticalGradient(listOf(Color(0xF51C283E), Color(0xF5141E30)))
}

@Composable
fun PetOnboardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Blue,
            background = C.Bg,
            surface = C.Bg,
            onBackground = C.Text,
            onSurface = C.Text,
        ),
        content = content,
    )
}
