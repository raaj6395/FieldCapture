package com.fieldcapture

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  private val js by lazy { (application as FieldCaptureApp).session }

  private val perms =
      registerForActivityResult(RequestMultiplePermissions()) { result ->
        js.log("android perms: ${result.map { "${it.key.substringAfterLast('.')}=${it.value}" }}")
        js.boot()
      }

  private val datPerm =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        result.onSuccess { js.log("DAT permission: $it"); if (it == PermissionStatus.Granted) startJob(asked = true) }
            .onFailure { e, _ -> js.log("DAT permission FAILED: ${e.description}") }
      }

  private fun startJob(asked: Boolean = false) {
    lifecycleScope.launch {
      for (p in listOf(Permission.CAMERA, Permission.MICROPHONE)) {
        val status = Wearables.checkPermissionStatus(p).getOrNull()
        if (status != PermissionStatus.Granted && !asked) {
          js.log("DAT $p is $status - requesting")
          return@launch datPerm.launch(p)
        }
      }
      js.startJob()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge(SystemBarStyle.dark(Fc.bg.hashCode()), SystemBarStyle.dark(Fc.bg.hashCode()))
    super.onCreate(savedInstanceState)
    setContent {
      FieldTheme {
        Box(Modifier.fillMaxSize().background(Fc.bg)) {
          var review by rememberSaveable { mutableStateOf<String?>(null) }
          review?.let { id -> BackHandler { review = null }; ReviewScreen(id) { review = null } } ?: JobScreen(js, { startJob() }) { review = it }
        }
      }
    }
    perms.launch(
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        ),
    )
  }
}

private fun stateTitle(s: JobState) = when (s) {
  JobState.IDLE -> "No job running"
  JobState.CONNECTING -> "Connecting to glasses"
  JobState.ACTIVE -> "Recording"
  JobState.PAUSED -> "Paused"
  JobState.RECONNECTING -> "Reconnecting to glasses"
  JobState.STOPPED -> "Job ended"
}

private fun stateDot(s: JobState) = when (s) {
  JobState.ACTIVE -> Fc.good
  JobState.RECONNECTING -> Fc.bad
  JobState.CONNECTING, JobState.PAUSED -> Fc.text2
  else -> Fc.muted
}

private val SAY = listOf("capture nameplate", "capture damage kitchen north wall", "note subfloor soft by the cabinet", "pause", "resume")

@Composable
private fun Dot(color: Color) = Box(Modifier.size(8.dp).clip(CircleShape).background(color))

@Composable
private fun StatusChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, on: Boolean) =
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FcIcon(icon, if (on) Fc.text2 else Fc.muted, 16.dp)
      Text(text, style = Fc.label.copy(color = if (on) Fc.text2 else Fc.muted))
    }

@Composable
private fun SmallAction(text: String, onClick: () -> Unit) =
    Box(Modifier.heightIn(min = 40.dp).clip(Fc.rSmall).background(Fc.raised).border(1.dp, Fc.hairline, Fc.rSmall).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
      Text(text, fontSize = 13.sp, color = Fc.text)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JobScreen(js: JobSession, startJob: () -> Unit, openReview: (String) -> Unit) {
  val state by js.state.collectAsStateWithLifecycle()
  val rev by js.rev.collectAsStateWithLifecycle()
  val listening by js.listening.collectAsStateWithLifecycle()
  val glasses by js.deviceReady.collectAsStateWithLifecycle()
  val queue by js.queue.collectAsStateWithLifecycle()
  val lines by js.lines.collectAsStateWithLifecycle()
  val glassesInfo by js.glassesInfo.collectAsStateWithLifecycle()
  val blocked = remember(glassesInfo) { js.glassesProblem() }
  val job = js.job
  val ctx = LocalContext.current
  val past = remember(rev, state) { jobSummaries(ctx).filter { it.id != job.id || state == JobState.STOPPED }.take(5) }
  var dev by rememberSaveable { mutableStateOf(false) }

  LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
    item {
      Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("FieldCapture", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Fc.text, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
          StatusChip(Lucide.glasses, if (glasses) "Glasses connected" else "Glasses not connected", glasses)
          StatusChip(if (listening) Lucide.mic else Lucide.micOff, if (listening) "Listening" else "Mic off", listening)
        }
      }
    }
    item {
      GlassesView(js, state)
      Spacer(Modifier.size(12.dp))
    }
    item {
      Card {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Dot(stateDot(state))
              Text(if (job.id.isEmpty()) "Current job" else "Job ${job.id}", style = Fc.meta)
            }
            Text(stateTitle(state), style = Fc.title)
          }
          if (job.open || state == JobState.STOPPED) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
              Stat("Photos", "${job.captures.size}", Modifier.weight(1f))
              Stat("Notes", "${job.notes.size}", Modifier.weight(1f))
              Stat("To upload", "$queue", Modifier.weight(1f), if (queue > 0) Fc.amber else Fc.text)
            }
          }
          if (state == JobState.ACTIVE) {
            Hairline()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("Say", style = Fc.label)
              listOf("capture nameplate", "capture damage [room] [surface]", "note [what you see]").forEach { Text("“$it”", style = Fc.body.copy(color = Fc.text2)) }
            }
          }
          when (state) {
            JobState.IDLE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              PrimaryButton("Start job", startJob, Modifier.fillMaxWidth(), enabled = blocked == null)
              blocked?.let { Text(it, style = Fc.label) }
            }
            JobState.STOPPED -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              PrimaryButton("Review job ${job.id}", { openReview(job.id) }, Modifier.fillMaxWidth())
              SecondaryButton("Start new job", startJob, Modifier.fillMaxWidth(), enabled = blocked == null)
              blocked?.let { Text(it, style = Fc.label) }
            }
            JobState.ACTIVE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              SecondaryButton("Pause", js::pause, Modifier.weight(1f))
              SecondaryButton("End job", js::stopJob, Modifier.weight(1f), Fc.bad)
            }
            JobState.PAUSED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              PrimaryButton("Resume", js::resume, Modifier.weight(1f))
              SecondaryButton("End job", js::stopJob, Modifier.weight(1f), Fc.bad)
            }
            else -> SecondaryButton("End job", js::stopJob, Modifier.fillMaxWidth(), Fc.bad)
          }
        }
      }
    }
    if (past.isNotEmpty()) {
      item { SectionLabel("Recent jobs") }
      item {
        Column(Modifier.clip(Fc.rCard).background(Fc.card).border(1.dp, Fc.hairline, Fc.rCard)) {
          past.forEachIndexed { i, j ->
            if (i > 0) Box(Modifier.padding(start = 16.dp)) { Hairline() }
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { openReview(j.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(j.started, style = Fc.body)
                Text("${plural(j.photos, "photo")} · ${plural(j.notes, "note")} · ${j.status}", style = Fc.label.copy(color = if (j.waiting) Fc.amber else Fc.text2))
              }
              Text(j.id, style = Fc.meta, modifier = Modifier.padding(end = 8.dp))
              FcIcon(Lucide.chevronRight)
            }
          }
        }
      }
    }
    if (BuildConfig.DEBUG) {
      item {
        Row(Modifier.fillMaxWidth().padding(top = 24.dp).heightIn(min = 48.dp).clip(Fc.rSmall).clickable { dev = !dev }.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FcIcon(Lucide.wrench, Fc.muted, 16.dp)
          Text("Developer tools", style = Fc.label, modifier = Modifier.weight(1f))
          FcIcon(if (dev) Lucide.chevronDown else Lucide.chevronRight, Fc.muted, 16.dp)
        }
      }
      if (dev) {
        item {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Glasses simulator", style = Fc.meta)
            GlassesSimulator(js, state)
            Spacer(Modifier.size(8.dp))
            Text("Inject speech", style = Fc.meta)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              SAY.forEach { t -> SmallAction("“$t”") { js.inject(t) } }
            }
            Spacer(Modifier.size(8.dp))
            Text("Log", style = Fc.meta)
          }
        }
        items(lines.take(60)) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, color = Fc.muted) }
      }
    }
    item { Spacer(Modifier.size(32.dp)) }
  }
}
