package com.fieldcapture

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
        result.onSuccess { js.log("DAT permission: $it"); if (it == PermissionStatus.Granted) startJob() }
            .onFailure { e, _ -> js.log("DAT permission FAILED: ${e.description}") }
      }

  private fun startJob() {
    lifecycleScope.launch {
      for (p in listOf(Permission.CAMERA, Permission.MICROPHONE)) {
        val status = Wearables.checkPermissionStatus(p).getOrNull()
        if (status != PermissionStatus.Granted) {
          js.log("DAT $p is $status - requesting")
          return@launch datPerm.launch(p)
        }
      }
      js.startJob()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { MaterialTheme { Surface { JobScreen(js, ::startJob) } } }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JobScreen(js: JobSession, startJob: () -> Unit) {
  val state by js.state.collectAsStateWithLifecycle()
  val rev by js.rev.collectAsStateWithLifecycle()
  val listening by js.listening.collectAsStateWithLifecycle()
  val queue by js.queue.collectAsStateWithLifecycle()
  val lines by js.lines.collectAsStateWithLifecycle()
  val job = js.job
  rev

  Column(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
    Text("Job ${job.id.ifEmpty { "-" }}  $state", fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text("${job.captures.size} photos, ${job.notes.size} notes, mic ${if (listening) "on" else "off"}, queue $queue pending", fontSize = 14.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      Button(onClick = startJob) { Text("Start job") }
      Button(onClick = js::pause) { Text("Pause") }
      Button(onClick = js::resume) { Text("Resume") }
      Button(onClick = js::stopJob) { Text("End job") }
    }
    Text("Mock glasses", fontSize = 12.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      listOf("powerOff", "powerOn", "doff", "don", "fold", "unfold", "tap", "hot", "cool", "battery10", "battery80", "button").forEach { a ->
        OutlinedButton(onClick = { js.mock(a) }) { Text(a, fontSize = 11.sp) }
      }
    }
    Text("Inject speech", fontSize = 12.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      listOf("capture nameplate", "capture damage kitchen north wall", "note subfloor soft by the cabinet", "pause", "resume").forEach { t ->
        OutlinedButton(onClick = { js.inject(t) }) { Text(t, fontSize = 11.sp) }
      }
    }
    LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
      items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
    }
  }
}
