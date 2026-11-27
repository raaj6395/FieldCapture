package com.fieldcapture

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceE2ETest {

  @Test
  fun injectedCommandsCreateCapturesAndNotes() = runBlocking {
    val ins = InstrumentationRegistry.getInstrumentation()
    val pkg = ins.targetContext.packageName
    for (p in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)) {
      ins.uiAutomation.grantRuntimePermission(pkg, p)
    }
    java.io.File(ins.targetContext.getExternalFilesDir(null), "jobs").deleteRecursively()
    val scenario = ActivityScenario.launch(MainActivity::class.java)
    val js = (ins.targetContext.applicationContext as FieldCaptureApp).session
    withContext(Dispatchers.Main) {
      js.boot()
      js.startJob()
    }
    withTimeout(30_000) { js.state.first { it == JobState.ACTIVE } }
    withTimeout(15_000) { js.listening.first { it } }
    val id = js.job.id

    suspend fun say(text: String, done: () -> Boolean) {
      delay(4000)
      withContext(Dispatchers.Main) { js.inject(text) }
      withTimeout(15_000) { while (!done()) delay(100) }
    }

    say("capture nameplate") { js.job.captures.size == 1 }
    say("Capture damage, kitchen, north wall.") { js.job.captures.size == 2 }
    say("note subfloor soft by the cabinet") { js.job.notes.size == 1 }
    say("capture damage ceiling") { js.job.captures.size == 3 }
    say("hello there, nice house") { true }
    delay(3000)

    val c = js.job.captures
    assertEquals(id, js.job.id)
    assertEquals(listOf("nameplate", "damage", "damage"), c.map { it.kind })
    assertEquals("kitchen", c[1].room)
    assertEquals("north wall", c[1].surface)
    assertEquals("kitchen", c[2].room)
    assertEquals("ceiling", c[2].surface)
    assertEquals("subfloor soft by the cabinet", js.job.notes[0].text)
    assertEquals(3, c.size)

    withContext(Dispatchers.Main) { js.stopJob() }
    assertEquals(JobState.STOPPED, js.state.value)
    scenario.close()
  }
}
