package com.fieldcapture

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import com.meta.wearable.dat.mockdevice.api.speech.MockSpeechSource
import com.meta.wearable.dat.speech.addSpeech
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProbeViewModel(private val app: Application) : AndroidViewModel(app) {

  val lines = MutableStateFlow<List<String>>(emptyList())

  private val kit = MockDeviceKit.getInstance(app)
  private val selector by lazy { AutoDeviceSelector() }
  private val t0 = SystemClock.elapsedRealtime()
  private var glasses: MockGlasses? = null
  private var session: DeviceSession? = null
  private var stream: Stream? = null
  private val sessionJobs = mutableListOf<Job>()

  fun log(msg: String) {
    val line = "%6.1fs  %s".format((SystemClock.elapsedRealtime() - t0) / 1000.0, msg)
    Log.i("FC", line)
    lines.update { (listOf(line) + it).take(300) }
  }

  fun mockSetup() = viewModelScope.launch {
    if (glasses != null) return@launch log("mock already set up")
    kit.enable()
    kit.permissions.set(Permission.CAMERA, PermissionStatus.Granted)
    kit.permissions.set(Permission.MICROPHONE, PermissionStatus.Granted)
    kit.pairGlasses(GlassesModel.RAYBAN_META).fold(
        onSuccess = { g ->
          glasses = g
          g.powerOn()
          g.unfold()
          g.don()
          g.services.camera.setCameraFeed(CameraFacing.BACK)
          g.services.speech.setTranscriptionSource(MockSpeechSource.LIVE_DEVICE_ASR)
          log("mock: paired, on, unfolded, donned, back camera, live phone ASR")
        },
        onFailure = { e, _ -> log("mock pair FAILED: ${e.description}") },
    )
    launch { Wearables.registrationState.collect { log("registration: $it") } }
    launch { selector.activeDeviceFlow().collect { log("active device: $it") } }
  }

  fun startSession() {
    if (session != null) return log("session exists, stop it first")
    Wearables.createSession(selector)
        .onSuccess { s ->
          session = s
          sessionJobs += viewModelScope.launch {
            s.state.collect {
              log("session: $it")
              if (it.name == "STOPPED") clearSession()
            }
          }
          sessionJobs += viewModelScope.launch { s.errors.collect { log("session ERROR: ${it.description}") } }
          s.start()
        }
        .onFailure { e, _ -> log("createSession FAILED: ${e.description}") }
  }

  fun stopSession() = session?.stop() ?: log("no session")

  private fun clearSession() {
    session = null
    stream = null
    sessionJobs.forEach { it.cancel() }
    sessionJobs.clear()
  }

  fun startCamera() = withSession { s ->
    s.addCamera(StreamConfiguration(videoQuality = VideoQuality.HIGH, frameRate = 15))
        .onSuccess { cam ->
          val st = cam.stream
          stream = st
          sessionJobs += viewModelScope.launch { st.state.collect { log("stream: $it") } }
          sessionJobs += viewModelScope.launch { st.errorStream.collect { log("stream ERROR: ${it.description}") } }
          sessionJobs += viewModelScope.launch {
            var n = 0
            st.videoStream.collect { f ->
              if (n++ % 45 == 0) log("frame #$n ${f.width}x${f.height} compressed=${f.isCompressed}")
            }
          }
          st.start().onFailure { e, _ -> log("stream start FAILED: ${e.description}") }
        }
        .onFailure { e, _ -> log("addCamera FAILED: ${e.description}") }
  }

  fun capturePhoto() = viewModelScope.launch {
    val st = stream ?: return@launch log("no stream")
    val t = SystemClock.elapsedRealtime()
    st.capturePhoto()
        .onSuccess { p ->
          val ms = SystemClock.elapsedRealtime() - t
          val dir = File(app.getExternalFilesDir(null), "probe").apply { mkdirs() }
          when (p) {
            is PhotoData.Bitmap -> {
              File(dir, "photo_$t.jpg").outputStream().use { p.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
              log("photo BITMAP ${p.bitmap.width}x${p.bitmap.height} in ${ms}ms")
            }
            is PhotoData.HEIC -> {
              val buf = p.data.duplicate().apply { rewind() }
              val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
              File(dir, "photo_$t.heic").writeBytes(bytes)
              val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
              BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
              log("photo HEIC ${o.outWidth}x${o.outHeight} ${bytes.size / 1024}KB in ${ms}ms")
            }
          }
        }
        .onFailure { e, _ -> log("capturePhoto FAILED: ${e.description}") }
  }

  fun startSpeech() = withSession { s ->
    s.addSpeech().fold(
        onSuccess = { sp ->
          sessionJobs += viewModelScope.launch {
            sp.transcriptions.collect { r ->
              r ?: return@collect
              log("speech ${if (r.isFinal) "FINAL" else "partial"} \"${r.text}\" conf=${r.confidence}")
            }
          }
          sessionJobs += viewModelScope.launch { sp.state.collect { log("speech state: $it") } }
          sessionJobs += viewModelScope.launch { sp.errors.collect { log("speech ERROR: $it") } }
          sp.start().onFailure { e, _ -> log("speech start FAILED: ${e.description}") }
        },
        onFailure = { e, _ -> log("addSpeech FAILED: ${e.description}") },
    )
  }

  fun injectSpeech() = withGlasses {
    it.services.speech.simulateTranscription("capture damage kitchen ceiling", true, 0.9f)
    log("injected speech text")
  }

  fun startInputs() = withSession { s ->
    s.addInputs(InputsConfiguration(consumeBack = false)).fold(
        onSuccess = { inp ->
          sessionJobs += viewModelScope.launch { inp.events.collect { log("input: $it") } }
          sessionJobs += viewModelScope.launch { inp.state.collect { log("inputs state: $it") } }
          sessionJobs += viewModelScope.launch { inp.errors.collect { log("inputs ERROR: $it") } }
        },
        onFailure = { e, _ -> log("addInputs FAILED: ${e.description}") },
    )
  }

  fun injectCapture() = withGlasses {
    it.services.input.capture(CapturePressType.SHORT_PRESS)
    log("injected capture button")
  }

  fun mock(action: String) = withGlasses { g ->
    log(">> mock $action")
    when (action) {
      "powerOff" -> g.powerOff()
      "powerOn" -> g.powerOn()
      "doff" -> g.doff()
      "don" -> g.don()
      "fold" -> g.fold()
      "unfold" -> g.unfold()
      "tap" -> viewModelScope.launch { g.services.captouch.tap() }
    }
  }

  private fun withSession(block: suspend (DeviceSession) -> Unit) {
    val s = session ?: return log("no session")
    viewModelScope.launch { block(s) }
  }

  private fun withGlasses(block: (MockGlasses) -> Unit) {
    val g = glasses ?: return log("run Mock setup first")
    block(g)
  }
}
