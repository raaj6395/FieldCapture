package com.fieldcapture

import com.fieldcapture.Command.CaptureDamage
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandParserTest {

  private val cases: List<Pair<String, Command?>> = listOf(
      "capture nameplate" to Command.CaptureNameplate,
      "Capture name plate." to Command.CaptureNameplate,
      "captured nameplate" to Command.CaptureNameplate,
      "Capture nameplate" to Command.CaptureNameplate,
      "capture the nameplate" to Command.CaptureNameplate,
      "capture damage kitchen north wall" to CaptureDamage("kitchen", "north wall", null),
      "capture damage, kitchen, north wall" to CaptureDamage("kitchen", "north wall", null),
      "Capture damage living room ceiling" to CaptureDamage("living room", "ceiling", null),
      "capture damage in the master bathroom on the floor" to CaptureDamage("master bathroom", "floor", null),
      "capture damage ceiling kitchen" to CaptureDamage("kitchen", "ceiling", null),
      "captured damage kitchen sealing" to CaptureDamage("kitchen", "ceiling", null),
      "capture damage" to CaptureDamage(null, null, null),
      "capture damage garage" to CaptureDamage("garage", null, null),
      "capture damage sub floor" to CaptureDamage(null, "subfloor", null),
      "capture damage pantry wall" to CaptureDamage(null, "wall", "pantry"),
      "note: subfloor soft by the cabinet" to Command.Note("subfloor soft by the cabinet"),
      "notes sub floor soft" to Command.Note("subfloor soft"),
      "Note what are heated looking at base" to Command.Note("what are heated looking at base"),
      "start job" to Command.Start,
      "Begin the job." to Command.Start,
      "pause" to Command.Pause,
      "Pause job" to Command.Pause,
      "resume job" to Command.Resume,
      "Resume." to Command.Resume,
      "end job" to Command.Stop,
      "finish the job" to Command.Stop,
      "retake" to Command.Retake,
      "capture" to Command.Unknown("capture"),
      "note" to Command.Unknown("note"),
      "capture moisture reading" to Command.Unknown("capture moisture reading"),
      "hello there" to null,
      "Noted." to null,
      "Paused. Say resume." to null,
      "Nameplate." to null,
      "Damage, kitchen, north wall." to null,
      "the water came from the upstairs bathroom" to null,
      "can you pause the music" to null,
      "" to null,
  )

  @Test
  fun phrases() {
    val wrong = cases.mapNotNull { (text, want) ->
      val got = CommandParser.parse(text)
      if (got != want) "\"$text\": want $want got $got" else null
    }
    assertEquals("${cases.size - wrong.size}/${cases.size} ok\n" + wrong.joinToString("\n"), 0, wrong.size)
  }
}
