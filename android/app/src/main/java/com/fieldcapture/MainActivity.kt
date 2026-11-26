package com.fieldcapture

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.Wearables

class MainActivity : ComponentActivity() {

  private val vm: ProbeViewModel by viewModels()

  private val perms =
      registerForActivityResult(RequestMultiplePermissions()) { result ->
        vm.log("android perms: ${result.map { "${it.key.substringAfterLast('.')}=${it.value}" }}")
        Wearables.initialize(this)
        vm.log("Wearables initialized")
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { MaterialTheme { Surface { ProbeScreen(vm) } } }
    if (savedInstanceState == null) {
      perms.launch(
          arrayOf(
              Manifest.permission.BLUETOOTH_CONNECT,
              Manifest.permission.CAMERA,
              Manifest.permission.RECORD_AUDIO,
          ),
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProbeScreen(vm: ProbeViewModel) {
  val lines by vm.lines.collectAsStateWithLifecycle()
  val buttons =
      listOf(
          "Mock setup" to { vm.mockSetup(); Unit },
          "Start session" to vm::startSession,
          "Camera" to vm::startCamera,
          "Photo" to { vm.capturePhoto(); Unit },
          "Speech" to vm::startSpeech,
          "Inject speech" to vm::injectSpeech,
          "Inputs" to vm::startInputs,
          "Inject capture" to vm::injectCapture,
          "Stop session" to { vm.stopSession(); Unit },
      ) + listOf("powerOff", "powerOn", "doff", "don", "fold", "unfold", "tap").map { a -> a to { vm.mock(a) } }

  Column(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      buttons.forEach { (label, action) -> Button(onClick = action) { Text(label, fontSize = 12.sp) } }
    }
    LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
      items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
    }
  }
}
