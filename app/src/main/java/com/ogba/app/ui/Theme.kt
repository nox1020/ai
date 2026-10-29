package com.ogba.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Palette from open-grok-bot client/app/globals.css + tailwind.config.js */
object C {
    val background = Color(0xFF09090B)
    val foreground = Color(0xFFF4F4F5)
    val sidebar = Color(0xFF141416)
    val cardActive = Color(0xFF27272A)
    val cardActiveBorder = Color(0xFF34343A)
    val popover = Color(0xFF1C1C20)
    val pillBorder = Color(0xFF2B2B32)
    val bubbleBot = Color(0xFF1E1E22)
    val bubbleUser = Color(0xFF38383E)
    val bubbleError = Color(0xFF241416)
    val bubbleErrorBorder = Color(0xFF3F191B)
    val errorText = Color(0xFFF87171)
    val search = Color(0xFF222225)
    val searchBorder = Color(0xFF2C2C30)
    val divider = Color(0xFF1F1F23)
    val headerBorder = Color(0xFF18181C)
    val zinc300 = Color(0xFFD4D4D8)
    val zinc400 = Color(0xFFA1A1AA)
    val zinc500 = Color(0xFF71717A)
    val slate900 = Color(0xCC0F172A)
    val slate800 = Color(0xFF1E293B)
    val slate400 = Color(0xFF94A3B8)
    val blue = Color(0xFF3B82F6)
    val blue400 = Color(0xFF60A5FA)
    val cyan300 = Color(0xFF67E8F9)
    val cyan400 = Color(0xFF22D3EE)
    val emerald = Color(0xFF10B981)
    val emerald600 = Color(0xFF059669)
    val amber = Color(0xFFF59E0B)
    val amber400 = Color(0xFFFBBF24)
    val rose = Color(0xFFF43F5E)
    val rose400 = Color(0xFFFB7185)
    val codeBg = Color(0xFF2A2A30)
}

val OgbaColors = darkColorScheme(
    primary = C.blue, onPrimary = Color.White, background = C.background, onBackground = C.foreground,
    surface = C.background, onSurface = C.foreground, surfaceVariant = C.popover, onSurfaceVariant = C.zinc400,
    secondary = C.emerald, tertiary = C.cyan400, error = C.rose, outline = C.pillBorder
)

@Composable
fun OgbaTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = OgbaColors, content = content)

/** Port of MascotAvatar.jsx: rounded triangle blob with two eyes; blue / pink / warning. */
@Composable
fun MascotAvatar(type: String = "blue", size: Dp = 40.dp) {
    if (type == "warning") {
        Box(Modifier.size(size).background(Color(0xFF222226), RoundedCornerShape(12.dp)).border(1.dp, Color(0xFF2E2E34), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center) { Text("!", color = C.amber, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) }
        return
    }
    val (c1, c2) = if (type == "pink") Color(0xFFF43F5E) to Color(0xFFBE123C) else Color(0xFF3B82F6) to Color(0xFF1D4ED8)
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 100f
        val p = Path().apply {
            moveTo(50 * k, 12 * k)
            cubicTo(60 * k, 12 * k, 88 * k, 65 * k, 84 * k, 76 * k)
            cubicTo(80 * k, 87 * k, 20 * k, 87 * k, 16 * k, 76 * k)
            cubicTo(12 * k, 65 * k, 40 * k, 12 * k, 50 * k, 12 * k)
            close()
        }
        drawPath(p, Brush.linearGradient(listOf(c1, c2), Offset.Zero, Offset(this.size.width, this.size.height)))
        val ink = Color(0xFF0F172A)
        drawCircle(Color.White, 7 * k, Offset(43 * k, 52 * k)); drawCircle(ink, 3.5f * k, Offset(41 * k, 52 * k)); drawCircle(Color.White, 1.2f * k, Offset(40 * k, 50 * k))
        drawCircle(Color.White, 6 * k, Offset(62 * k, 54 * k)); drawCircle(ink, 3 * k, Offset(60 * k, 54 * k)); drawCircle(Color.White, 1 * k, Offset(59 * k, 53 * k))
    }
}
