package com.fieldcapture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobTest {

  private fun active() = JobRecord().apply { on(Ev.START, 1); on(Ev.LIVE, 2) }

  @Test
  fun startPauseResumeStop() {
    val j = JobRecord()
    assertTrue(j.on(Ev.START, 1))
    assertEquals(JobState.CONNECTING, j.state)
    assertTrue(j.on(Ev.LIVE, 2))
    assertTrue(j.on(Ev.SDK_PAUSED, 3))
    assertEquals(JobState.PAUSED, j.state)
    assertTrue(j.on(Ev.LIVE, 4))
    assertTrue(j.on(Ev.STOP, 5))
    assertEquals(JobState.STOPPED, j.state)
    assertEquals(
        listOf("IDLE->CONNECTING", "CONNECTING->ACTIVE", "ACTIVE->PAUSED", "PAUSED->ACTIVE", "ACTIVE->STOPPED"),
        j.events.map { it.type })
  }

  @Test
  fun invalidMovesIgnored() {
    val j = JobRecord()
    assertFalse(j.on(Ev.LIVE, 1))
    assertFalse(j.on(Ev.SDK_PAUSED, 1))
    assertFalse(j.on(Ev.LOST, 1))
    assertFalse(j.on(Ev.STOP, 1))
    assertEquals(JobState.IDLE, j.state)
    val a = active()
    assertFalse(a.on(Ev.START, 3))
    assertFalse(a.on(Ev.LIVE, 3))
    a.on(Ev.LOST, 4)
    assertFalse(a.on(Ev.LOST, 5))
    assertFalse(a.on(Ev.SDK_PAUSED, 5))
    assertEquals(0, a.events.count { it.at == 5L })
  }

  @Test
  fun dropWhileActiveComesBackActiveSameJob() {
    val j = active()
    val id = j.id
    j.captures += Capture("c1", "nameplate", 3, "c1.jpg")
    assertTrue(j.on(Ev.LOST, 4, "session STOPPED"))
    assertEquals(JobState.RECONNECTING, j.state)
    assertTrue(j.on(Ev.LIVE, 5))
    assertEquals(JobState.ACTIVE, j.state)
    assertEquals(id, j.id)
    assertEquals(1, j.captures.size)
  }

  @Test
  fun dropWhilePausedComesBackPaused() {
    val j = active()
    j.on(Ev.SDK_PAUSED, 3)
    j.on(Ev.LOST, 4)
    j.on(Ev.LIVE, 5)
    assertEquals(JobState.PAUSED, j.state)
  }

  @Test
  fun restoreReopensAsReconnecting() {
    val j = active()
    j.restore()
    assertEquals(JobState.RECONNECTING, j.state)
    j.on(Ev.LIVE, 9)
    assertEquals(JobState.ACTIVE, j.state)
    val stopped = active().apply { on(Ev.STOP, 3) }
    stopped.restore()
    assertEquals(JobState.STOPPED, stopped.state)
  }

  @Test
  fun stopFromReconnectingAndNewJobGetsNewId() {
    val j = active()
    val id = j.id
    j.on(Ev.LOST, 3)
    assertTrue(j.on(Ev.STOP, 4))
    assertTrue(j.on(Ev.START, 5))
    assertTrue(id != j.id)
    assertEquals(1, j.events.size)
  }
}
