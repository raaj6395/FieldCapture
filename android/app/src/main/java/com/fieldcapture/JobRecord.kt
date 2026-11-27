package com.fieldcapture

import java.util.UUID

enum class JobState { IDLE, CONNECTING, ACTIVE, PAUSED, RECONNECTING, STOPPED }

enum class Ev { START, LIVE, SDK_PAUSED, LOST, STOP }

data class JobEvent(val type: String, val at: Long, val cause: String = "")

data class Capture(
    val id: String,
    val kind: String,
    val at: Long,
    val file: String,
    val room: String? = null,
    val surface: String? = null,
    val unrecognised: String? = null,
    val transcript: String? = null,
    val asrConfidence: Float? = null,
    val retakeOf: String? = null,
)

data class Note(val id: String, val at: Long, val text: String, val transcript: String, val asrConfidence: Float?)

class JobRecord(
    var id: String = "",
    var state: JobState = JobState.IDLE,
    var pausedBeforeDrop: Boolean = false,
    var startedAt: Long = 0,
    val events: MutableList<JobEvent> = mutableListOf(),
    val captures: MutableList<Capture> = mutableListOf(),
    val notes: MutableList<Note> = mutableListOf(),
) {
  val open get() = state != JobState.IDLE && state != JobState.STOPPED

  fun on(ev: Ev, at: Long, cause: String = ""): Boolean {
    val from = state
    val to = next(ev) ?: return false
    if (ev == Ev.START) {
      id = UUID.randomUUID().toString().take(8).uppercase()
      startedAt = at
      events.clear()
      captures.clear()
      notes.clear()
    }
    if (ev == Ev.LOST) pausedBeforeDrop = from == JobState.PAUSED
    state = to
    events += JobEvent("$from->$to", at, cause.ifEmpty { ev.name })
    return true
  }

  private fun next(ev: Ev): JobState? =
      when (ev) {
        Ev.START -> if (!open) JobState.CONNECTING else null
        Ev.LIVE ->
            when (state) {
              JobState.CONNECTING, JobState.PAUSED -> JobState.ACTIVE
              JobState.RECONNECTING -> if (pausedBeforeDrop) JobState.PAUSED else JobState.ACTIVE
              else -> null
            }
        Ev.SDK_PAUSED -> if (state == JobState.ACTIVE) JobState.PAUSED else null
        Ev.LOST -> if (open && state != JobState.RECONNECTING) JobState.RECONNECTING else null
        Ev.STOP -> if (open) JobState.STOPPED else null
      }

  fun restore() {
    if (!open) return
    pausedBeforeDrop = pausedBeforeDrop || state == JobState.PAUSED
    events += JobEvent("$state->RECONNECTING", System.currentTimeMillis(), "app restarted")
    state = JobState.RECONNECTING
  }

  fun lastRoom() = captures.lastOrNull { it.room != null }?.room
}
