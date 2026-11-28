package com.fieldcapture

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.SystemClock
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DonState
import com.meta.wearable.dat.core.types.HingeState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.types.ThermalLevel
import com.meta.wearable.dat.inputs.Inputs
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.removeInputs
import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import com.meta.wearable.dat.inputs.types.InputsState
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import com.meta.wearable.dat.mockdevice.api.speech.MockSpeechSource
import com.meta.wearable.dat.speech.Speech
import com.meta.wearable.dat.speech.addSpeech
import com.meta.wearable.dat.speech.types.SpeechState
import com.meta.wearable.dat.speech.types.TranscriptionResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

class JobSession(private val app: Application) {

  val job = JobRecord()
  val state = MutableStateFlow(JobState.IDLE)
  val rev = MutableStateFlow(0)
  val listening = MutableStateFlow(false)
  val queue = MutableStateFlow(0)
  val lines = MutableStateFlow<List<String>>(emptyList())
  val preview = MutableStateFlow<Bitmap?>(null)
  val glassesInfo = MutableStateFlow<GlassesInfo?>(null)
  val caption = MutableStateFlow<Caption?>(null)
  val shutter = MutableStateFlow(0)

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val selector by lazy { AutoDeviceSelector() }
  private val tts = Speaker()
  private val captureLock = Mutex()
  private val t0 = SystemClock.elapsedRealtime()

  private var booted = false
  private var glasses: MockGlasses? = null
  private var session: DeviceSession? = null
  private var ss: CoroutineScope? = null
  private var stream: Stream? = null
  private var inputs: Inputs? = null
  private var speech: Speech? = null
  private var capsAdded = false
  private var connectJob: Job? = null
  private var attempt = 0
  private var lastFrameAt = 0L
  private var frames = 0L
  val deviceReady = MutableStateFlow(false)
  val registration = MutableStateFlow(RegistrationState.UNAVAILABLE)
  val registrationError = MutableStateFlow<String?>(null)
  private var device: Device? = null
  private var hot = false
  private var batteryWarned = false
  private var doffPaused = false
  private var lastShot: Shot? = null

  data class GlassesInfo(val battery: Int, val worn: Boolean, val open: Boolean, val hot: Boolean)
  data class Caption(val text: String, val tech: Boolean, val at: Long = SystemClock.elapsedRealtime())

  private data class Shot(val kind: String, val room: String? = null, val surface: String? = null, val unrecognised: String? = null)

  fun log(msg: String) {
    val line = "%6.1fs  %s".format((SystemClock.elapsedRealtime() - t0) / 1000.0, msg)
    Log.i("FC", line)
    lines.update { (listOf(line) + it).take(300) }
  }

  fun boot() {
    if (booted) return
    booted = true
    Wearables.initialize(app)
    if (BuildConfig.DEBUG) {
      mockSetup()
      app.registerReceiver(object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
          val a = i.getStringExtra("do") ?: return
          when {
            a == "start" -> startJob()
            a == "pause" -> pause()
            a == "resume" -> resume()
            a == "stop" -> stopJob()
            a.startsWith("say:") -> inject(a.removePrefix("say:"))
            a.startsWith("ocr:") -> ocrEval(File(a.removePrefix("ocr:")))
            else -> mock(a)
          }
        }
      }, IntentFilter("com.fieldcapture.CMD"), Context.RECEIVER_EXPORTED)
    }
    scope.launch {
      Wearables.registrationState.collect { r ->
        log("registration: $r")
        if (r == RegistrationState.REGISTERED) registrationError.value = null
        if (r == RegistrationState.REGISTERED || registration.value == RegistrationState.UNREGISTERING) prefs.edit().putBoolean("dat_registered", r == RegistrationState.REGISTERED).apply()
        registration.value = r
      }
    }
    scope.launch {
      Wearables.registrationErrorStream.collect { e ->
        log("registration FAILED: $e")
        registrationError.value = when (e.name) {
          "META_AI_NOT_INSTALLED" -> "Install the Meta AI app, then try again."
          "UNAUTHORIZED" -> "Meta AI did not allow FieldCapture. Try again and tap Allow."
          else -> "Couldn't connect to Meta AI. Check the internet and try again."
        }
      }
    }
    scope.launch {
      selector.activeDeviceFlow().collectLatest { id ->
        log("active device: $id")
        if (id == null) {
          device = null
          glassesInfo.value = null
          deviceReady.value = false
          if (job.state == JobState.ACTIVE || job.state == JobState.PAUSED) transition(Ev.LOST, "device gone")
          return@collectLatest
        }
        Wearables.devicesMetadata[id]?.collect(::onDevice) ?: run {
          deviceReady.value = true
          glassesInfo.value = GlassesInfo(-1, worn = true, open = true, hot = false)
        }
      }
    }
    scope.launch {
      while (true) {
        delay(1000)
        if (job.state == JobState.ACTIVE && SystemClock.elapsedRealtime() - lastFrameAt > WATCHDOG_MS) {
          transition(Ev.LOST, "no frames for ${WATCHDOG_MS / 1000} s")
        }
      }
    }
    if (prefs.getBoolean("muted_beeps", false)) quietBeeps(false)
    restore()
    refreshQueue()
    UploadWorker.kick(app, fresh = true)
  }

  fun refreshQueue() {
    queue.value = jobsRoot().listFiles().orEmpty().sumOf { dir ->
      val f = File(dir, "session.json")
      if (!f.exists()) return@sumOf 0
      val o = JSONObject(f.readText())
      val ids = (o.getJSONArray("captures").objs() + o.getJSONArray("notes").objs()).map { it.getString("id") }
      (ids - UploadWorker.uploaded(dir)).size
    }
  }

  private fun queueUpload() {
    refreshQueue()
    UploadWorker.kick(app)
  }

  private fun mockSetup() {
    val kit = MockDeviceKit.getInstance(app)
    kit.enable(MockDeviceKitConfig(initiallyRegistered = prefs.getBoolean("dat_registered", false)))
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
          log("mock glasses ready (back camera, live phone ASR)")
        },
        onFailure = { e, _ -> log("mock pair FAILED: ${e.description}") },
    )
  }

  fun register(activity: android.app.Activity) {
    registrationError.value = null
    log("registration: starting")
    Wearables.startRegistration(activity)
  }

  fun unregister(activity: android.app.Activity) {
    log("registration: removing")
    Wearables.startUnregistration(activity)
  }

  fun glassesProblem(): String? {
    val g = glassesInfo.value
    return when {
      registration.value != RegistrationState.REGISTERED -> "Connect FieldCapture to Meta AI first."
      g == null -> "Glasses are off. Turn them on first."
      !g.open -> "Glasses are folded. Open them first."
      !g.worn -> "Put the glasses on first."
      else -> null
    }
  }

  fun startJob() {
    if (!job.open) glassesProblem()?.let { log("start refused: $it"); return say(it) }
    if (!transition(Ev.START, "start job")) return say("Job already running.")
    attempt = 0
    say("Job started.")
    connect()
  }

  fun stopJob() {
    if (!transition(Ev.STOP, "end job")) return say("No job.")
    say("Job ended. ${plural(job.captures.size, "photo")}, ${plural(job.notes.size, "note")}.")
  }

  fun pause() {
    if (job.state != JobState.ACTIVE) return say(if (job.state == JobState.PAUSED) "Already paused." else "Not connected.")
    val s = session ?: return
    s.pause()
    settle(s, DeviceSessionState.PAUSED) { s.pause() }
  }

  fun resume() {
    if (job.state != JobState.PAUSED) return say(if (job.state == JobState.ACTIVE) "Already running." else "Not connected.")
    doffPaused = false
    val s = session ?: return
    s.resume()
    settle(s, DeviceSessionState.STARTED) { s.resume() }
  }

  private fun settle(s: DeviceSession, want: DeviceSessionState, again: () -> Unit) {
    scope.launch {
      delay(3000)
      if (s === session && s.state.value != want) {
        log("session is ${s.state.value}, wanted $want - re-issuing")
        again()
      }
    }
  }

  private fun transition(ev: Ev, cause: String): Boolean {
    val from = job.state
    if (!job.on(ev, System.currentTimeMillis(), cause)) {
      if (ev != Ev.LIVE) log("ignored $ev in $from ($cause)")
      return false
    }
    val to = job.state
    log("JOB ${job.id}: $from -> $to ($cause)")
    state.value = to
    persist()
    when (to) {
      JobState.CONNECTING -> {
        JobService.start(app)
        quietBeeps(true)
      }
      JobState.ACTIVE -> {
        lastFrameAt = SystemClock.elapsedRealtime()
        attempt = 0
        if (from == JobState.RECONNECTING) say("Glasses back.")
        if (from == JobState.PAUSED) say("Resumed.")
        if (from == JobState.CONNECTING) say("Ready.")
      }
      JobState.PAUSED ->
          when {
            from == JobState.RECONNECTING -> {
              session?.pause()
              say("Glasses back. Still paused.")
            }
            doffPaused -> say("Glasses off. Paused.")
            else -> say("Paused.")
          }
      JobState.RECONNECTING -> {
        release()
        say("Glasses disconnected.")
        connect()
      }
      JobState.STOPPED -> {
        connectJob?.cancel()
        release()
        app.stopService(Intent(app, JobService::class.java))
        scope.launch {
          delay(2000)
          if (!job.open) quietBeeps(false)
        }
        queueUpload()
      }
      JobState.IDLE -> Unit
    }
    return true
  }

  private fun connect() {
    connectJob?.cancel()
    connectJob = scope.launch {
      while (job.open) {
        if (attempt > 0) {
          val wait = BACKOFF_S[minOf(attempt - 1, BACKOFF_S.lastIndex)]
          log("reconnect attempt ${attempt + 1} in ${wait}s")
          delay(wait * 1000L)
        }
        attempt++
        if (!deviceReady.value) {
          log("waiting for glasses (${device?.donState}, ${device?.hingeState})")
          if (device?.donState == DonState.DOFFED) say("Put the glasses on to continue.")
        }
        deviceReady.first { it }
        val s = Wearables.createSession(selector).fold(onSuccess = { it }, onFailure = { e, _ -> log("createSession FAILED: ${e.description}"); null }) ?: continue
        attach(s)
        s.start()
        if (withTimeoutOrNull(20_000) { state.first { it == JobState.ACTIVE || it == JobState.PAUSED } } != null) return@launch
        log("no live stream 20 s after start")
        release()
      }
    }
  }

  private fun attach(s: DeviceSession) {
    session = s
    capsAdded = false
    val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    ss = scope2
    scope2.launch { s.state.collect { onSessionState(s, it) } }
    scope2.launch { s.errors.collect { e -> if (e != null && s === session) event("session error: ${e.description}") } }
  }

  private fun release() {
    val s = session
    session = null
    stream = null
    inputs = null
    speech = null
    capsAdded = false
    doffPaused = false
    listening.value = false
    ss?.cancel()
    ss = null
    if (s != null && s.state.value != DeviceSessionState.STOPPED) runCatching { s.stop() }
  }

  private fun onSessionState(s: DeviceSession, st: DeviceSessionState) {
    if (s !== session) return
    log("sdk session: $st")
    when (st) {
      DeviceSessionState.STARTED -> {
        if (!capsAdded) {
          capsAdded = true
          addCaps(s)
        } else if (inputs?.state?.value == InputsState.INACTIVE && device?.donState == DonState.DONNED) {
          log("re-attaching inputs")
          inputs = null
          s.removeInputs()
          addInputs(s)
        }
        checkLive()
      }
      DeviceSessionState.PAUSED -> transition(Ev.SDK_PAUSED, "sdk paused")
      DeviceSessionState.STOPPED ->
          when {
            !job.open -> Unit
            job.state == JobState.RECONNECTING || job.state == JobState.CONNECTING -> {
              release()
              connect()
            }
            else -> transition(Ev.LOST, "sdk session stopped")
          }
      else -> Unit
    }
  }

  private fun checkLive() {
    if (job.state == JobState.ACTIVE) return
    if (session?.state?.value == DeviceSessionState.STARTED && stream?.state?.value == StreamState.STREAMING) {
      transition(Ev.LIVE, "streaming")
    }
  }

  private fun addCaps(s: DeviceSession) {
    addCamera(s)
    val scope2 = ss ?: return
    addSpeechCap(s)
    addInputs(s)
  }

  private fun addSpeechCap(s: DeviceSession) {
    val scope2 = ss ?: return
    s.addSpeech().fold(
        onSuccess = { sp ->
          speech = sp
          scope2.launch { sp.transcriptions.collect { r -> if (r != null && sp === speech) onTranscript(r) } }
          scope2.launch {
            sp.state.collect {
              log("speech: $it")
              if (sp === speech) listening.value = it == SpeechState.STARTED
            }
          }
          scope2.launch { sp.errors.collect { e -> if (e != null) event("speech error: $e") } }
          sp.start().onFailure { e, _ -> event("speech start FAILED: ${e.description}") }
        },
        onFailure = { e, _ -> event("addSpeech FAILED: ${e.description}") },
    )
  }

  private fun addInputs(s: DeviceSession) {
    val scope2 = ss ?: return
    s.addInputs(InputsConfiguration(consumeBack = false)).fold(
        onSuccess = { inp ->
          inputs = inp
          scope2.launch { inp.events.collect { if (inp === inputs && it is InputEvent.Capture) onCaptureButton() } }
          scope2.launch { inp.state.collect { log("inputs: $it") } }
        },
        onFailure = { e, _ -> event("addInputs FAILED: ${e.description}") },
    )
  }

  private fun addCamera(s: DeviceSession) {
    val scope2 = ss ?: return
    val cfg = if (hot) StreamConfiguration(videoQuality = VideoQuality.LOW, frameRate = 7) else StreamConfiguration(videoQuality = VideoQuality.HIGH, frameRate = 15)
    s.addCamera(cfg).fold(
        onSuccess = { cam ->
          val st = cam.stream
          stream = st
          log("camera added ${cfg.videoQuality} ${cfg.frameRate}fps")
          scope2.launch { st.state.collect { if (st === stream) onStreamState(it) } }
          scope2.launch { st.errorStream.collect { if (st === stream) onStreamError(it) } }
          scope2.launch {
            st.videoStream.collect {
              lastFrameAt = SystemClock.elapsedRealtime()
              if (frames++ % 300 == 0L) log("frame ${it.width}x${it.height} compressed=${it.isCompressed} bytes=${it.buffer.remaining()}")
              if (frames % 3 == 0L && preview.subscriptionCount.value > 0 && !it.isCompressed) {
                val f = it
                val bytes = ByteArray(f.buffer.remaining()).also { b -> f.buffer.duplicate().get(b) }
                scope2.launch(Dispatchers.Default) { runCatching { Frames.i420(bytes, f.width, f.height) }.getOrNull()?.let { b -> preview.value = b } }
              }
            }
          }
          st.start().onFailure { e, _ -> event("stream start FAILED: ${e.description}") }
        },
        onFailure = { e, _ -> event("addCamera FAILED: ${e.description}") },
    )
  }

  private fun onStreamState(st: StreamState) {
    log("stream: $st")
    when (st) {
      StreamState.STREAMING -> checkLive()
      StreamState.CLOSED -> if (job.state != JobState.RECONNECTING) transition(Ev.LOST, "stream closed")
      else -> Unit
    }
  }

  private fun onStreamError(e: StreamError) {
    event("stream error: $e")
    when (e) {
      StreamError.CRITICAL_STREAM_ERROR, StreamError.HINGE_CLOSED -> transition(Ev.LOST, "stream $e")
      StreamError.THERMAL_HOT -> setHot(true)
      StreamError.BATTERY_LOW -> warnBattery()
      else -> Unit
    }
  }

  private fun onDevice(d: Device) {
    val old = device
    device = d
    if (old == null || old.batteryLevel != d.batteryLevel || old.thermalLevel != d.thermalLevel ||
        old.donState != d.donState || old.hingeState != d.hingeState || old.linkState != d.linkState) {
      log("device: link=${d.linkState} battery=${d.batteryLevel} thermal=${d.thermalLevel} don=${d.donState} hinge=${d.hingeState}")
    }
    deviceReady.value = d.hingeState != HingeState.CLOSED && d.donState != DonState.DOFFED
    glassesInfo.value = GlassesInfo(d.batteryLevel, d.donState != DonState.DOFFED, d.hingeState != HingeState.CLOSED, d.thermalLevel >= ThermalLevel.SEVERE)
    if (!hot && d.thermalLevel >= ThermalLevel.SEVERE) setHot(true)
    if (hot && d.thermalLevel <= ThermalLevel.LIGHT) setHot(false)
    if (d.batteryLevel in 0 until 15) warnBattery() else if (d.batteryLevel >= 20) batteryWarned = false
    if (old?.donState != d.donState) onDon(d.donState)
  }

  private fun onDon(don: DonState) {
    val s = session ?: return
    if (don == DonState.DOFFED && job.state == JobState.ACTIVE) {
      event("glasses removed")
      doffPaused = true
      s.pause()
    }
    if (don == DonState.DONNED) {
      if (doffPaused && job.state == JobState.PAUSED) {
        doffPaused = false
        s.resume()
      }
    }
  }

  private fun setHot(on: Boolean) {
    if (hot == on) return
    hot = on
    event(if (on) "thermal high - camera to LOW 7fps" else "thermal ok - camera back to HIGH 15fps")
    say(if (on) "Glasses hot. Lowering video." else "Glasses cooled.")
    val s = session ?: return
    if (!capsAdded) return
    stream = null
    s.removeCamera()
    lastFrameAt = SystemClock.elapsedRealtime()
    addCamera(s)
  }

  private fun warnBattery() {
    if (batteryWarned) return
    batteryWarned = true
    event("glasses battery low")
    say("Glasses battery low.")
  }

  private fun onTranscript(r: TranscriptionResult) {
    if (!r.isFinal) return
    if (tts.echo(r.text)) return log("ignored echo of our reply: \"${r.text}\"")
    val cmd = CommandParser.parse(r.text) ?: return log("chatter: \"${r.text}\"")
    log("voice: \"${r.text}\" conf=${r.confidence} -> $cmd${if (tts.busy()) " (heard during our reply)" else ""}")
    caption.value = Caption(r.text, tech = true)
    handle(cmd, r.text, r.confidence)
  }

  fun handle(cmd: Command, transcript: String? = null, conf: Float? = null) {
    when (cmd) {
      Command.CaptureNameplate -> capture(Shot("nameplate"), transcript, conf)
      is Command.CaptureDamage -> capture(Shot("damage", cmd.room ?: job.lastRoom().takeIf { cmd.unrecognised == null }, cmd.surface, cmd.unrecognised), transcript, conf)
      is Command.Note -> {
        if (!job.open) return say("No job.")
        job.notes += Note("n${job.notes.size + 1}", System.currentTimeMillis(), cmd.text, transcript ?: cmd.text, conf)
        persist()
        queueUpload()
        say("Noted: ${cmd.text}.")
      }
      Command.Start -> startJob()
      Command.Pause -> pause()
      Command.Resume -> resume()
      Command.Stop -> stopJob()
      Command.Retake -> {
        val last = job.captures.lastOrNull() ?: return say("Nothing to retake.")
        capture(Shot(last.kind, last.room, last.surface, last.unrecognised), transcript, conf, last.id)
      }
      is Command.Unknown -> say("Didn't catch that.")
    }
  }

  private fun onCaptureButton() {
    val shot = lastShot ?: job.captures.lastOrNull()?.let { Shot(it.kind, it.room, it.surface, it.unrecognised) } ?: return say("Say a capture command first.")
    log("capture button -> repeat ${shot.kind}")
    capture(shot, "capture button", null)
  }

  private class Attempt(val bytes: ByteArray, val ext: String, val scores: QualityScores, val reasons: List<String>, val ocr: JSONObject? = null)

  private fun capture(shot: Shot, transcript: String?, conf: Float?, retakeOf: String? = null) {
    scope.launch {
      captureLock.withLock {
        if (job.state != JobState.ACTIVE) return@launch say(if (job.state == JobState.PAUSED) "Still paused." else if (job.open) "Glasses not connected." else "No job.")
        val t = SystemClock.elapsedRealtime()
        val timing = linkedMapOf("cmd" to System.currentTimeMillis())
        var best: Attempt? = null
        var attempts = 0
        for (round in 0..MAX_RETAKES) {
          val st = stream ?: break
          for (burst in 0..SILENT_EXTRA_SHOTS) {
            val a = shoot(st, shot.kind) ?: break
            attempts++
            log("attempt $attempts ${a.reasons.ifEmpty { listOf("ok") }} blur=%.1f bright=%.1f dark=%.3f glare=%.3f edges=%.4f border=%.3f motion=%.3f".format(
                a.scores.blur, a.scores.brightness, a.scores.darkFrac, a.scores.glareFrac, a.scores.edgeDensity, a.scores.borderEdgeFrac, a.scores.motionRatio))
            if (best == null || better(a, best)) best = a
            if (a.reasons != listOf("blurry")) break
          }
          val b = best ?: break
          if (b.reasons.isEmpty() || round == MAX_RETAKES) break
          say(Quality.prompt(b.reasons.first()))
          delay(RETAKE_WAIT_MS)
          if (job.state != JobState.ACTIVE) break
        }
        val b = best ?: return@launch say("Photo failed. Say retake.")
        timing["shot"] = System.currentTimeMillis()
        val id = "c%02d".format(job.captures.size + 1)
        val file = File(jobDir(), "$id.${b.ext}").apply { writeBytes(b.bytes) }
        b.ocr?.let { o ->
          File(jobDir(), "$id.ocr.json").writeText(o.toString())
          log("ocr $id: ${o.getJSONArray("lines").length()} lines in ${o.getLong("ms")}ms")
          timing["ocr"] = System.currentTimeMillis()
        }
        val flag = if (b.reasons.isEmpty()) "ok" else "low"
        job.captures += Capture(id, shot.kind, System.currentTimeMillis(), file.name, shot.room, shot.surface, shot.unrecognised, transcript, conf, retakeOf,
            b.scores, b.reasons, attempts, flag, timing)
        lastShot = shot
        persist()
        timing["saved"] = System.currentTimeMillis()
        persist()
        queueUpload()
        log("captured $id ${shot.kind} quality=$flag ${b.reasons} after $attempts shots, ${file.length() / 1024}KB in ${SystemClock.elapsedRealtime() - t}ms")
        say(reply(shot) + if (flag == "low") " Saved, low quality." else "")
      }
    }
  }

  private fun better(a: Attempt, b: Attempt) = a.reasons.size < b.reasons.size || a.reasons.size == b.reasons.size && a.scores.blur > b.scores.blur

  private suspend fun shoot(st: Stream, kind: String): Attempt? {
    shutter.update { it + 1 }
    val p = st.capturePhoto().fold(onSuccess = { it }, onFailure = { e, _ -> event("capture FAILED: ${e.description}"); null }) ?: return null
    return withContext(Dispatchers.Default) {
      val (bytes, ext) = when (p) {
        is PhotoData.Bitmap -> ByteArrayOutputStream().also { p.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray() to "jpg"
        is PhotoData.HEIC -> {
          val buf = p.data.duplicate().apply { rewind() }
          val b = ByteArray(buf.remaining()).also { buf.get(it) }
          b to if (b.size > 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) "jpg" else "heic"
        }
      }
      val bmp = upright(bytes)
      val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
      val scores = Quality.score(px, bmp.width, bmp.height, thresholds)
      val ocr = if (kind == "nameplate") runCatching { Ocr.read(bmp) }.onFailure { log("ocr FAILED: $it") }.getOrNull() else null
      val margin = thresholds.kinds.getValue(kind).textEdgeMargin
      val atEdge = if (ocr != null && margin != null) Quality.textAtEdge(Ocr.lines(ocr), ocr.getInt("w"), ocr.getInt("h"), margin) else 0
      val reasons = Quality.verdict(scores, kind, thresholds).let { if (atEdge > 0 && "cut_off" !in it) it + "cut_off" else it }
      Attempt(bytes, ext, scores, reasons, ocr)
    }
  }

  private fun ocrEval(dir: File) {
    scope.launch(Dispatchers.Default) {
      val out = File(app.getExternalFilesDir(null), "ocr_eval").apply { mkdirs() }
      val files = dir.listFiles().orEmpty().filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }.sortedBy { it.name }
      log("ocr eval: ${files.size} images in $dir")
      for (f in files) {
        val full = upright(f.readBytes())
        val res = JSONObject().put("file", f.name).put("full", Ocr.read(full)).put("640", Ocr.read(Ocr.fit(full, 640)))
        File(out, f.nameWithoutExtension + ".json").writeText(res.toString())
        log("ocr ${f.name}: full ${res.getJSONObject("full").getLong("ms")}ms ${res.getJSONObject("full").getJSONArray("lines").length()} lines, 640 ${res.getJSONObject("640").getLong("ms")}ms ${res.getJSONObject("640").getJSONArray("lines").length()} lines")
      }
      log("ocr eval done")
    }
  }

  private fun upright(bytes: ByteArray): Bitmap {
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val deg = when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
      ExifInterface.ORIENTATION_ROTATE_90 -> 90
      ExifInterface.ORIENTATION_ROTATE_180 -> 180
      ExifInterface.ORIENTATION_ROTATE_270 -> 270
      else -> 0
    }
    return if (deg == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
  }

  private val thresholds by lazy {
    val o = JSONObject(app.assets.open("thresholds.json").bufferedReader().readText())
    fun JSONObject.dbl(k: String) = if (has(k) && !isNull(k)) getDouble(k) else null
    Thresholds(
        edgeE = o.getDouble("edge_e"),
        darkMean = o.getDouble("dark_mean"),
        darkFrac = o.getDouble("dark_frac"),
        kinds = listOf("nameplate", "damage").associateWith { k ->
          val j = o.getJSONObject(k)
          KindThresholds(j.getDouble("blur"), j.dbl("glare_frac"), j.dbl("empty_edge_density"), j.dbl("border_edge_frac"), j.dbl("text_edge_margin"))
        },
        provisional = o.optBoolean("provisional"),
        motionRatio = o.dbl("motion_ratio"),
    ).also { log("quality thresholds loaded${if (it.provisional) " (PROVISIONAL)" else ""}") }
  }

  private fun reply(shot: Shot) =
      if (shot.kind == "nameplate") "Nameplate."
      else listOfNotNull("Damage", shot.room ?: shot.unrecognised, shot.surface).joinToString(", ") + "."

  private fun event(msg: String) {
    log(msg)
    if (!job.open) return
    job.events += JobEvent("device", System.currentTimeMillis(), msg)
    persist()
  }

  fun say(text: String) {
    log("say: $text")
    caption.value = Caption(text, tech = false)
    tts.say(text)
  }

  fun mock(action: String) {
    val g = glasses ?: return log("no mock glasses")
    log(">> mock $action")
    when (action) {
      "powerOff" -> g.powerOff()
      "powerOn" -> g.powerOn()
      "doff" -> g.doff()
      "don" -> g.don()
      "fold" -> g.fold()
      "unfold" -> g.unfold()
      "tap" -> scope.launch { g.services.captouch.tap() }
      "hot" -> g.setThermalLevel(ThermalLevel.SEVERE)
      "cool" -> g.setThermalLevel(ThermalLevel.NONE)
      "battery10" -> g.setBatteryLevel(10)
      "battery80" -> g.setBatteryLevel(80)
      "button" -> g.services.input.capture(CapturePressType.SHORT_PRESS)
      "regfail" -> MockDeviceKit.getInstance(app).setRegistrationResult(false)
      "regok" -> MockDeviceKit.getInstance(app).setRegistrationResult(true)
    }
  }

  fun inject(text: String) {
    val g = glasses ?: return log("no mock glasses")
    log(">> inject \"$text\" (mock listening=${g.services.speech.isListening}, live=${g.services.speech.liveSourceDiagnostic.value})")
    scope.launch {
      g.services.speech.simulateTranscription(text, false, 0.5f)
      delay(150)
      g.services.speech.simulateTranscription(text, true, 0.9f)
    }
  }

  private fun jobsRoot() = UploadWorker.jobsRoot(app)

  private fun jobDir() = File(jobsRoot(), job.id).apply { mkdirs() }

  private fun persist() {
    if (job.id.isEmpty()) return
    val f = File(jobDir(), "session.json")
    val tmp = File(f.path + ".tmp")
    tmp.writeText(toJson().toString(2))
    tmp.renameTo(f)
    rev.update { it + 1 }
  }

  private fun toJson() = JSONObject().apply {
    put("job_id", job.id)
    put("state", job.state.name)
    put("paused_before_drop", job.pausedBeforeDrop)
    put("started_at", job.startedAt)
    put("events", JSONArray(job.events.map { JSONObject().put("type", it.type).put("at", it.at).put("cause", it.cause) }))
    put("captures", JSONArray(job.captures.map { c ->
      JSONObject().put("id", c.id).put("kind", c.kind).put("at", c.at).put("file", c.file).put("room", c.room)
          .put("surface", c.surface).put("unrecognised", c.unrecognised).put("transcript", c.transcript)
          .put("asr_confidence", c.asrConfidence?.toDouble()).put("retake_of", c.retakeOf)
          .put("quality_flag", c.qualityFlag).put("quality_reasons", JSONArray(c.qualityReasons)).put("attempts", c.attempts)
          .put("timing", JSONObject(c.timing as Map<*, *>))
          .put("quality", c.quality?.let { q -> JSONObject().put("blur", q.blur).put("brightness", q.brightness).put("dark_frac", q.darkFrac)
              .put("glare_frac", q.glareFrac).put("edge_density", q.edgeDensity).put("border_edge_frac", q.borderEdgeFrac).put("motion_ratio", q.motionRatio) })
    }))
    put("notes", JSONArray(job.notes.map { n ->
      JSONObject().put("id", n.id).put("at", n.at).put("text", n.text).put("transcript", n.transcript)
          .put("asr_confidence", n.asrConfidence?.toDouble())
    }))
  }

  private val prefs by lazy { app.getSharedPreferences("fc", Context.MODE_PRIVATE) }

  private fun quietBeeps(on: Boolean) {
    runCatching {
      app.getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
      prefs.edit().putBoolean("muted_beeps", on).apply()
      log(if (on) "notification sounds muted (recognizer beeps)" else "notification sounds restored")
    }.onFailure { log("notification mute FAILED: $it") }
  }

  private fun restore() {
    val f = jobsRoot().listFiles()?.map { File(it, "session.json") }?.filter { it.exists() }
        ?.firstOrNull { JSONObject(it.readText()).getString("state") !in setOf("IDLE", "STOPPED") } ?: return
    val o = JSONObject(f.readText())
    fun JSONObject.str(k: String) = if (isNull(k)) null else getString(k)
    fun JSONObject.flt(k: String) = if (isNull(k)) null else getDouble(k).toFloat()
    job.id = o.getString("job_id")
    job.state = JobState.valueOf(o.getString("state"))
    job.pausedBeforeDrop = o.getBoolean("paused_before_drop")
    job.startedAt = o.getLong("started_at")
    job.events += o.getJSONArray("events").objs().map { JobEvent(it.getString("type"), it.getLong("at"), it.getString("cause")) }
    job.captures += o.getJSONArray("captures").objs().map {
      Capture(it.getString("id"), it.getString("kind"), it.getLong("at"), it.getString("file"), it.str("room"), it.str("surface"),
          it.str("unrecognised"), it.str("transcript"), it.flt("asr_confidence"), it.str("retake_of"),
          it.optJSONObject("quality")?.let { q -> QualityScores(q.getDouble("blur"), q.getDouble("brightness"), q.getDouble("dark_frac"),
              q.getDouble("glare_frac"), q.getDouble("edge_density"), q.getDouble("border_edge_frac"), q.optDouble("motion_ratio", 1.0)) },
          it.optJSONArray("quality_reasons")?.let { r -> (0 until r.length()).map(r::getString) } ?: emptyList(),
          it.optInt("attempts", 1), it.optString("quality_flag", "unchecked"),
          it.optJSONObject("timing")?.let { t -> t.keys().asSequence().associateWith { k -> t.getLong(k) } } ?: emptyMap())
    }
    job.notes += o.getJSONArray("notes").objs().map { Note(it.getString("id"), it.getLong("at"), it.getString("text"), it.getString("transcript"), it.flt("asr_confidence")) }
    job.restore()
    state.value = job.state
    persist()
    log("restored job ${job.id} (${job.captures.size} captures, ${job.notes.size} notes) -> RECONNECTING")
    JobService.start(app)
    quietBeeps(true)
    say("Job restored.")
    connect()
  }

  private inner class Speaker : TextToSpeech.OnInitListener {
    private val engine = TextToSpeech(app, this)
    private var ready = false
    @Volatile private var mutedUntil = 0L
    private var lastText = ""
    private var lastAt = 0L

    override fun onInit(status: Int) {
      ready = status == TextToSpeech.SUCCESS
      if (!ready) return log("TTS init failed: $status")
      engine.language = Locale.US
      engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(id: String?) { mutedUntil = Long.MAX_VALUE }
        override fun onDone(id: String?) { mutedUntil = SystemClock.elapsedRealtime() + MUTE_AFTER_TTS_MS }
        @Deprecated("") override fun onError(id: String?) { mutedUntil = SystemClock.elapsedRealtime() + MUTE_AFTER_TTS_MS }
      })
    }

    fun say(text: String) {
      if (!ready) return
      mutedUntil = Long.MAX_VALUE
      lastText = CommandParser.normalize(text)
      lastAt = SystemClock.elapsedRealtime()
      engine.speak(text, TextToSpeech.QUEUE_ADD, null, "u${SystemClock.elapsedRealtime()}")
    }

    fun busy() = SystemClock.elapsedRealtime() < mutedUntil

    fun echo(heard: String) =
        SystemClock.elapsedRealtime() - lastAt < ECHO_WINDOW_MS && CommandParser.normalize(heard).let { it.isNotEmpty() && (lastText.contains(it) || lastText.replace("noted ", "note ").contains(it)) }
  }

  companion object {
    const val WATCHDOG_MS = 5000L
    const val MUTE_AFTER_TTS_MS = 500L
    const val ECHO_WINDOW_MS = 6000L
    val BACKOFF_S = listOf(1, 2, 4, 8, 15, 30)
    const val MAX_RETAKES = 2
    const val SILENT_EXTRA_SHOTS = 2
    const val RETAKE_WAIT_MS = 4000L
  }
}
