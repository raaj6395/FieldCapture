package com.fieldcapture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Fc {
  val bg = Color(0xFF141415)
  val card = Color(0xFF19191B)
  val raised = Color(0xFF232325)
  val text = Color(0xFFEDEDED)
  val text2 = Color(0xFFA1A1A6)
  val muted = Color(0xFF85858B)
  val hairline = text.copy(alpha = 0.08f)
  val amber = Color(0xFFF5A524)
  val onAmber = Color(0xFF17130B)
  val good = Color(0xFF4CC38A)
  val bad = Color(0xFFE5484D)

  val title = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, color = text)
  val heading = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = text)
  val body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = text)
  val label = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, color = text2)
  val meta = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace, color = muted)
  val figure = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, color = text, fontFeatureSettings = "tnum")

  val rCard = RoundedCornerShape(16.dp)
  val rControl = RoundedCornerShape(12.dp)
  val rSmall = RoundedCornerShape(8.dp)
}

@Composable
fun FieldTheme(content: @Composable () -> Unit) {
  MaterialTheme(
      colorScheme = darkColorScheme(
          primary = Fc.amber, onPrimary = Fc.onAmber, background = Fc.bg, onBackground = Fc.text,
          surface = Fc.bg, onSurface = Fc.text, surfaceVariant = Fc.card, onSurfaceVariant = Fc.text2, outline = Fc.hairline,
          error = Fc.bad,
      ),
      content = content,
  )
}

private fun lucide(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
      for (d in paths) addPath(PathParser().parsePathString(d).toNodes(), fill = null, stroke = SolidColor(Color.Black),
          strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }.build()

object Lucide {
  val mic = lucide("mic", "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z", "M19 10v2a7 7 0 0 1-14 0v-2", "M12 19v3")
  val micOff = lucide("mic-off", "M2 2l20 20", "M18.89 13.23A7 7 0 0 0 19 12v-2", "M5 10v2a7 7 0 0 0 12 5", "M15 9.34V5a3 3 0 0 0-5.68-1.33", "M9 9v3a3 3 0 0 0 5.12 2.12", "M12 19v3")
  val camera = lucide("camera", "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z", "M9 13a3 3 0 1 0 6 0a3 3 0 1 0-6 0")
  val note = lucide("file-text", "M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z", "M14 2v6h6", "M16 13H8", "M16 17H8", "M10 9H8")
  val upload = lucide("upload-cloud", "M4 14.9A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.24", "M12 12v9", "M16 16l-4-4-4 4")
  val glasses = lucide("glasses", "M2 15a4 4 0 1 0 8 0a4 4 0 1 0-8 0", "M14 15a4 4 0 1 0 8 0a4 4 0 1 0-8 0", "M10 15a2 2 0 0 1 4 0", "M2.5 13L5 7c.7-1.3 1.4-2 3-2", "M21.5 13L19 7c-.7-1.3-1.5-2-3-2")
  val chevronRight = lucide("chevron-right", "M9 18l6-6-6-6")
  val chevronDown = lucide("chevron-down", "M6 9l6 6 6-6")
  val back = lucide("arrow-left", "M12 19l-7-7 7-7", "M19 12H5")
  val alert = lucide("alert-triangle", "M21.73 18l-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3z", "M12 9v4", "M12 17h.01")
  val check = lucide("check", "M20 6L9 17l-5-5")
  val wrench = lucide("wrench", "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z")
}

@Composable
fun FcIcon(v: ImageVector, tint: Color = Fc.muted, size: Dp = 18.dp) = Icon(v, contentDescription = null, tint = tint, modifier = Modifier.size(size))

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    Button(onClick, modifier.heightIn(min = 56.dp), shape = Fc.rControl,
        colors = ButtonDefaults.buttonColors(containerColor = Fc.amber, contentColor = Fc.onAmber)) {
      Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Fc.text) =
    Button(onClick, modifier.heightIn(min = 56.dp).border(1.dp, Fc.hairline, Fc.rControl), shape = Fc.rControl,
        colors = ButtonDefaults.buttonColors(containerColor = Fc.raised, contentColor = color)) {
      Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }

const val CONF_CHECK = 0.5
const val CONF_SURE = 0.8

@Composable
fun ConfidenceMeter(conf: Double) {
  val lit = (conf * 5).let { kotlin.math.ceil(it).toInt() }.coerceIn(0, 5)
  val on = if (conf < CONF_CHECK) Fc.amber else Fc.text2
  Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
    repeat(5) { i -> Box(Modifier.size(width = 4.dp, height = 12.dp).clip(RoundedCornerShape(1.dp)).background(if (i < lit) on else Fc.raised)) }
  }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Box(modifier.fillMaxWidth().clip(Fc.rCard).background(Fc.card).border(1.dp, Fc.hairline, Fc.rCard).padding(16.dp)) { content() }

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) = Text(text, style = Fc.label, modifier = modifier.padding(top = 24.dp, bottom = 8.dp))

@Composable
fun Hairline() = Box(Modifier.fillMaxWidth().height(1.dp).background(Fc.hairline))

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: Color = Fc.text) =
    androidx.compose.foundation.layout.Column(modifier) {
      Text(value, style = Fc.figure.copy(color = color))
      Text(label, style = Fc.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

fun human(v: String): String = v.replace('_', ' ').replaceFirstChar { it.uppercase() }
