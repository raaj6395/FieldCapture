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
      "Captain nameplate" to Command.CaptureNameplate,
      "Capture name please" to Command.CaptureNameplate,
      "Capital damage garage wall" to CaptureDamage("garage", "wall", null),
      "Capture damage basements of floor" to CaptureDamage("basement", "floor", null),
      "Capture damage living" to CaptureDamage(null, null, "living"),
      "Actor nameplate" to Command.CaptureNameplate,
      "capture name hit" to Command.CaptureNameplate,
      "capture name late" to Command.CaptureNameplate,
      "uh capture nameplate please" to Command.CaptureNameplate,
      "okay capture damage kitchen north wall" to CaptureDamage("kitchen", "north wall", null),
      "capture damaged kitchin ceiling" to CaptureDamage("kitchen", "ceiling", null),
      "capture damich bathrom floor" to CaptureDamage("bathroom", "floor", null),
      "captor damage garage wall" to CaptureDamage("garage", "wall", null),
      "rapture damage hallway" to CaptureDamage("hallway", null, null),
      "capture damage garage volume" to CaptureDamage("garage", null, "volume"),
      "No subfloor soft by the cabinet" to Command.Note("subfloor soft by the cabinet"),
      "nope water stain on the ceiling" to Command.Note("water stain on the ceiling"),
      "capture damage kitchen sync" to CaptureDamage("kitchen", "sink", null),
      "capture damage bathroom toilet" to CaptureDamage("bathroom", "toilet", null),
      "capture damage bathroom bathtub" to CaptureDamage("bathroom", "bathtub", null),
      "capture damage living room ceiling fan" to CaptureDamage("living room", "ceiling fan", null),
      "capture damage bedroom hanger" to CaptureDamage("bedroom", "curtain rod", null),
      "capture damage basement water heater" to CaptureDamage("basement", "water heater", null),
      "capture damage attic insulation" to CaptureDamage("attic", "insulation", null),
      "capture damage hallway carpet pad" to CaptureDamage("hallway", "carpet pad", null),
      "capture damage kitchen laminat" to CaptureDamage("kitchen", "laminate", null),
      "capture damage laundry room washing machine" to CaptureDamage("laundry room", "washing machine", null),
      "capture damage bedroom window sill" to CaptureDamage("bedroom", "window sill", null),
      "posse" to null,
      "pause." to Command.Pause,
      "pauze" to Command.Pause,
      "resum" to Command.Resume,
      "and job" to Command.Stop,
      "end jobs" to Command.Stop,
      "Noted: subfloor soft by the cabinet" to null,
      "Resumed." to null,
      "no thanks" to null,
      "there is water damage in the kitchen" to null,
      "this is a picture of my kitchen" to null,
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
  fun ourOwnRepliesNeverParseAsCommands() {
    val replies = listOf(
        "Didn't catch that.", "Glasses back.", "Glasses back. Still paused.", "Glasses battery low.", "Glasses disconnected.",
        "Glasses off. Paused.", "Job already running.", "Job restored.", "Job started.", "No job.", "Nothing to retake.", "Paused.",
        "Photo failed. Say retake.", "Put the glasses on to continue.", "Ready.", "Resumed.", "Say a capture command first.",
        "Blurry. Hold still.", "Cut off. Step back.", "Glare. Tilt a little.", "Nothing in view.", "Too dark. Turn on your light.",
        "Nameplate.", "Damage, kitchen, north wall.", "Damage, living room, ceiling fan.", "Noted: subfloor soft by the cabinet.",
        "Job ended. 3 photos, 1 note.", "Glasses are off. Turn them on first.", "Glasses are folded. Open them first.", "Put the glasses on first.",
    )
    val parsed = replies.mapNotNull { r -> CommandParser.parse(r)?.let { "\"$r\" -> $it" } }
    assertEquals(parsed.joinToString("\n"), 0, parsed.size)
  }

  @Test
  fun phrases() {
    val wrong = cases.mapNotNull { (text, want) ->
      val got = CommandParser.parse(text)
      if (got != want) "\"$text\": want $want got $got" else null
    }
    assertEquals("${cases.size - wrong.size}/${cases.size} ok\n" + wrong.joinToString("\n"), 0, wrong.size)
  }
}
