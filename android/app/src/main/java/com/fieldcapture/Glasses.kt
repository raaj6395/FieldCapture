package com.fieldcapture

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private const val CAPTION_MS = 5000L

@Composable
fun GlassesView(js: JobSession, state: JobState) {
  val frame by js.preview.collectAsStateWithLifecycle()
  val info by js.glassesInfo.collectAsStateWithLifecycle()
  val caption by js.caption.collectAsStateWithLifecycle()
  val shutter by js.shutter.collectAsStateWithLifecycle()
  val reg by js.registration.collectAsStateWithLifecycle()
  val linked = reg == com.meta.wearable.dat.core.types.RegistrationState.REGISTERED
  val flash = remember { Animatable(0f) }
  LaunchedEffect(shutter) {
    if (shutter == 0) return@LaunchedEffect
    flash.snapTo(0.85f)
    flash.animateTo(0f, tween(300))
  }
  var now by remember { mutableLongStateOf(0L) }
  LaunchedEffect(caption) {
    now = android.os.SystemClock.elapsedRealtime()
    delay(CAPTION_MS)
    now = android.os.SystemClock.elapsedRealtime()
  }
  val live = state == JobState.ACTIVE
  val g = info

  Column(Modifier.fillMaxWidth().clip(Fc.rCard).background(Fc.card).border(1.dp, Fc.hairline, Fc.rCard)) {
    Box(Modifier.fillMaxWidth().height(if (state == JobState.IDLE || state == JobState.STOPPED) 180.dp else 340.dp).background(Color.Black)) {
      frame?.let {
        Image(it.asImageBitmap(), null, Modifier.fillMaxSize().alpha(if (live) 1f else 0.3f), contentScale = ContentScale.Crop)
      }
      if (!live) {
        Text(
            when {
              !linked -> "Not connected to Meta AI"
              g == null -> "Glasses off"
              !g.open -> "Glasses folded"
              !g.worn -> "Glasses not on face"
              state == JobState.PAUSED -> "Paused"
              state == JobState.IDLE || state == JobState.STOPPED -> "Camera starts with the job"
              else -> "Connecting"
            },
            style = Fc.body.copy(color = Fc.text2), modifier = Modifier.align(Alignment.Center))
      }
      Row(Modifier.align(Alignment.TopStart).padding(12.dp).clip(Fc.rSmall).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (live) Fc.bad else Fc.muted))
        Text(if (live) "Recording" else "Glasses camera", style = Fc.label.copy(color = Fc.text))
      }
      if (g != null && g.battery >= 0) {
        Text("${g.battery}%", style = Fc.meta.copy(color = if (g.battery < 15) Fc.amber else Fc.text),
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clip(Fc.rSmall).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 6.dp))
      }
      val c = caption
      if (c != null && now - c.at < CAPTION_MS) {
        Row(Modifier.align(Alignment.BottomStart).padding(12.dp).clip(Fc.rSmall).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FcIcon(if (c.tech) Lucide.mic else Lucide.glasses, if (c.tech) Fc.text2 else Fc.amber, 16.dp)
          Text(if (c.tech) "“${c.text}”" else c.text, style = Fc.body.copy(color = if (c.tech) Fc.text2 else Fc.text))
        }
      }
      Box(Modifier.fillMaxSize().alpha(flash.value).background(Color.White))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
      Fact("Power", if (!linked) "–" else if (g == null) "Off" else "On")
      Fact("Wearing", if (g == null) "–" else if (g.worn) "On face" else "Off face")
      Fact("Hinge", if (g == null) "–" else if (g.open) "Open" else "Folded")
      Fact("Heat", if (g == null) "–" else if (g.hot) "Hot" else "Normal", g?.hot == true)
    }
  }
}

@Composable
private fun Fact(label: String, value: String, warn: Boolean = false) = Column {
  Text(value, style = Fc.body.copy(color = if (warn) Fc.amber else Fc.text))
  Text(label, style = Fc.meta)
}

@Composable
fun GlassesSimulator(js: JobSession, state: JobState) {
  val info by js.glassesInfo.collectAsStateWithLifecycle()
  val g = info
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Box(Modifier.fillMaxWidth().clip(Fc.rCard).background(Fc.card).border(1.dp, Fc.hairline, Fc.rCard).padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
      GlassesDrawing(on = g != null, open = g?.open != false, recording = state == JobState.ACTIVE)
    }
    Column(Modifier.clip(Fc.rCard).background(Fc.card).border(1.dp, Fc.hairline, Fc.rCard)) {
      Toggle("Power", g != null) { js.mock(if (it) "powerOn" else "powerOff") }
      Box(Modifier.padding(start = 16.dp)) { Hairline() }
      Toggle("On face", g?.worn == true) { js.mock(if (it) "don" else "doff") }
      Box(Modifier.padding(start = 16.dp)) { Hairline() }
      Toggle("Hinge open", g?.open == true) { js.mock(if (it) "unfold" else "fold") }
      Box(Modifier.padding(start = 16.dp)) { Hairline() }
      Toggle("Overheated", g?.hot == true) { js.mock(if (it) "hot" else "cool") }
      Box(Modifier.padding(start = 16.dp)) { Hairline() }
      Toggle("Battery low", g != null && g.battery in 0 until 15) { js.mock(if (it) "battery10" else "battery80") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SecondaryButton("Tap touchpad", { js.mock("tap") }, Modifier.weight(1f))
      SecondaryButton("Press capture", { js.mock("button") }, Modifier.weight(1f))
    }
  }
}

@Composable
private fun Toggle(label: String, on: Boolean, set: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { set(!on) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(label, style = Fc.body, modifier = Modifier.weight(1f))
      Switch(on, set, colors = SwitchDefaults.colors(checkedThumbColor = Fc.onAmber, checkedTrackColor = Fc.amber,
          uncheckedThumbColor = Fc.muted, uncheckedTrackColor = Fc.raised, uncheckedBorderColor = Fc.hairline))
    }

@Composable
private fun GlassesDrawing(on: Boolean, open: Boolean, recording: Boolean) {
  val frame = if (on) Fc.text2 else Fc.muted
  val lens = if (on) Fc.raised else Fc.bg
  Canvas(Modifier.size(width = 280.dp, height = 96.dp)) {
    val w = size.width
    val h = size.height
    val lw = w * 0.36f
    val lh = h * 0.78f
    val top = h * 0.12f
    val stroke = Stroke(width = 3.dp.toPx())
    val r = CornerRadius(lh * 0.35f)
    val left = Offset(w * 0.11f, top)
    val right = Offset(w - w * 0.11f - lw, top)
    if (open) {
      drawLine(frame, Offset(left.x, top + lh * 0.2f), Offset(0f, top + lh * 0.1f), strokeWidth = 3.dp.toPx())
      drawLine(frame, Offset(right.x + lw, top + lh * 0.2f), Offset(w, top + lh * 0.1f), strokeWidth = 3.dp.toPx())
    }
    for (o in listOf(left, right)) {
      drawRoundRect(lens, o, Size(lw, lh), r)
      drawRoundRect(frame, o, Size(lw, lh), r, style = stroke)
    }
    drawLine(frame, Offset(left.x + lw, top + lh * 0.3f), Offset(right.x, top + lh * 0.3f), strokeWidth = 3.dp.toPx())
    val corner = 6.dp.toPx()
    drawCircle(if (on) Fc.text2 else Fc.muted, 2.5.dp.toPx(), Offset(left.x + corner * 1.6f, top + corner * 1.6f))
    if (recording) drawCircle(Color.White, 7.dp.toPx(), Offset(right.x + lw - corner * 1.6f, top + corner * 1.6f), alpha = 0.25f)
    drawCircle(if (recording) Color.White else if (on) Fc.raised else Fc.bg, 3.dp.toPx(), Offset(right.x + lw - corner * 1.6f, top + corner * 1.6f))
  }
}
